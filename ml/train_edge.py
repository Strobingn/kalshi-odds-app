#!/usr/bin/env python3
"""
Offline edge trainer for DipHunter 0.3.8.

Walk-forward logistic on settled Kalshi BTC/ETH/SOL 15m markets from both
the live and historical Kalshi API tiers, plus Coinbase spot candles.
Calibrates with monotone PAV, reports Brier / log-loss vs the market price,
and a simulated net P&L after Kalshi-style fees.

Exports a compact JSON the Android app can import (Data → Import model).

    python3 ml/train_edge.py
    python3 ml/train_edge.py --days 30 --out ml/edge_model.json
    python3 ml/train_edge.py --fixture   # no network; writes a tiny fixture model
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
COINBASE = "https://api.exchange.coinbase.com"
SERIES = ["KXBTC15M", "KXETH15M", "KXSOL15M"]
PRODUCT = {"KXBTC15M": "BTC-USD", "KXETH15M": "ETH-USD", "KXSOL15M": "SOL-USD"}
FEATURE_NAMES = [
    "dist_to_strike_vol",
    "tte_frac",
    "market_mid",
    "imbalance",
    "spread",
    "momentum",
    "realized_vol",
    "cross_asset",
    "time_of_day",
    "digital_fair",
]
SECONDS_PER_YEAR = 365.25 * 24 * 3600
FEE_RATE = 0.07
CONF_MARGIN = 0.03
SIM_HALF_SPREAD = 0.01
UA = "DipHunterTrainer/0.3.8"
# App: ExternalMarketFeatures.realizedVol(closes.takeLast(16)); both use the
# same recent-weighted EWMA so a train/inference feature cannot drift.
SPOT_LOOKBACK_BARS = 16
EWMA_DECAY = 0.90
MIN_PROMOTION_TRADES = 300
MIN_PROMOTION_DAYS = 14
MIN_BRIER_ADVANTAGE = 0.0025
BOOTSTRAP_REPS = 1_000


def http_get(url: str, retries: int = 5) -> Any:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": UA})
            with urllib.request.urlopen(req, timeout=45) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (429, 502, 503):
                time.sleep(min(2 ** attempt, 20))
                continue
            if e.code == 404:
                return {}
            raise
        except urllib.error.URLError as e:
            last = e
            time.sleep(min(2 ** attempt, 12))
    raise RuntimeError(f"GET failed {url}: {last}")


def parse_iso(ts: str | None) -> datetime | None:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except Exception:
        return None


def erf(x: float) -> float:
    sign = -1.0 if x < 0 else 1.0
    ax = abs(x)
    t = 1.0 / (1.0 + 0.3275911 * ax)
    y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * math.exp(-ax * ax)
    return sign * y


def norm_cdf(x: float) -> float:
    return 0.5 * (1.0 + erf(x / math.sqrt(2.0)))


def digital_fair(spot: float, strike: float, tte_s: float, sigma: float) -> float | None:
    if spot <= 0 or strike <= 0 or sigma <= 1e-8:
        return None
    t = max(tte_s, 1.0) / SECONDS_PER_YEAR
    vol = sigma * math.sqrt(t)
    if vol <= 1e-12:
        return 1.0 if spot > strike else 0.0
    d2 = (math.log(spot / strike) - 0.5 * sigma * sigma * t) / vol
    return min(1.0, max(0.0, norm_cdf(d2)))


def dist_vol(spot: float, strike: float, tte_s: float, sigma: float) -> float | None:
    if spot <= 0 or strike <= 0 or sigma <= 1e-8:
        return None
    t = max(tte_s, 1.0) / SECONDS_PER_YEAR
    vol = sigma * math.sqrt(t)
    if vol <= 1e-12:
        return None
    return math.log(spot / strike) / vol


def sigmoid(z: float) -> float:
    z = max(-30.0, min(30.0, z))
    return 1.0 / (1.0 + math.exp(-z))


def candle_mid(c: dict) -> float | None:
    price = c.get("price") or {}
    close = _f(price.get("close_dollars") if "close_dollars" in price else price.get("close"))
    yb = c.get("yes_bid") or {}
    ya = c.get("yes_ask") or {}
    bid = _f(yb.get("close_dollars") if "close_dollars" in yb else yb.get("close"))
    ask = _f(ya.get("close_dollars") if "close_dollars" in ya else ya.get("close"))
    if bid is not None and ask is not None:
        return (bid + ask) / 2.0
    return close


def _f(x: Any) -> float | None:
    try:
        return float(x) if x is not None else None
    except (TypeError, ValueError):
        return None


def fetch_settled(series: str, days: int, limit: int = 200) -> list[dict]:
    """Read both Kalshi market tiers so archive migration cannot shrink training data.

    Kalshi publishes the live-to-historical handoff at
    ``/historical/cutoff``. The two market endpoints have the same cursor
    semantics; deduplicate by ticker because an overlap can exist at handoff.
    """
    horizon = time.time() - days * 86400
    try:
        archived_before = parse_iso(http_get(f"{KALSHI}/historical/cutoff").get("market_settled_ts"))
    except Exception as exc:
        print(f"  historical cutoff unavailable ({exc}); using live tier only", flush=True)
        archived_before = None

    out: list[dict] = []
    seen: set[str] = set()
    endpoints = [f"{KALSHI}/markets"]
    if archived_before and horizon < archived_before.timestamp():
        endpoints.append(f"{KALSHI}/historical/markets")
    for endpoint in endpoints:
        cursor: str | None = None
        while len(out) < limit:
            q = {"series_ticker": series, "status": "settled", "limit": min(200, limit - len(out))}
            if cursor:
                q["cursor"] = cursor
            data = http_get(f"{endpoint}?{urllib.parse.urlencode(q)}")
            batch = data.get("markets") or []
            if not batch:
                break
            for market in batch:
                ticker = str(market.get("ticker") or "")
                close = parse_iso(market.get("close_time"))
                if (
                    ticker and ticker not in seen and close and close.timestamp() >= horizon and
                    (market.get("result") or "").lower() in ("yes", "no")
                ):
                    out.append(market)
                    seen.add(ticker)
            cursor = data.get("cursor")
            time.sleep(0.08)
            if not cursor:
                break
    return sorted(out, key=lambda market: market.get("close_time") or "", reverse=True)


def fetch_candles(series: str, ticker: str, open_ts: int, close_ts: int) -> list[dict]:
    q = {"start_ts": open_ts - 60, "end_ts": close_ts + 60, "period_interval": 1}
    data = http_get(f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    sticks = data.get("candlesticks") or []
    if sticks:
        return sticks
    hist = http_get(f"{KALSHI}/historical/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    return hist.get("candlesticks") or []


def fetch_spot(product: str, start: int, end: int) -> list[tuple[int, float]]:
    """1-minute Coinbase closes as (bucket_start_ts, close), oldest first."""
    q = {"granularity": 60, "start": datetime.fromtimestamp(start, tz=timezone.utc).isoformat(), "end": datetime.fromtimestamp(end, tz=timezone.utc).isoformat()}
    try:
        data = http_get(f"{COINBASE}/products/{product}/candles?{urllib.parse.urlencode(q)}")
    except Exception:
        return []
    if not isinstance(data, list):
        return []
    rows = sorted(data, key=lambda r: r[0] if r else 0)
    return [(int(r[0]), float(r[4])) for r in rows if r and len(r) >= 5]


def spot_known_at(spot: list[tuple[int, float]], decision_ts: int, keep: int = SPOT_LOOKBACK_BARS) -> list[float]:
    """Closes whose 1m bar has finished by [decision_ts] — no look-ahead.

    A Coinbase bucket starting at t closes at t + 60. The market's own
    settlement spot must never leak into a mid-window feature row.
    """
    known = [c for ts, c in spot if ts + 60 <= decision_ts]
    return known[-keep:]


def realized_vol_annual(closes: list[float]) -> float | None:
    if len(closes) < 5:
        return None
    rets = [math.log(b / a) for a, b in zip(closes, closes[1:]) if a > 0 and b > 0]
    if len(rets) < 4:
        return None
    weight = 1.0
    weighted_squares = 0.0
    weight_sum = 0.0
    for ret in reversed(rets):
        weighted_squares += weight * ret * ret
        weight_sum += weight
        weight *= EWMA_DECAY
    var = weighted_squares / max(weight_sum, 1e-12)
    std = math.sqrt(max(var, 0.0))
    if std <= 0:
        return None
    return min(5.0, max(0.01, std * math.sqrt(SECONDS_PER_YEAR / 60.0)))


def spot_return(closes: list[float], bars: int) -> float:
    """Simple return over the last [bars] 1m closes (app: spotReturn5m)."""
    if len(closes) <= bars or closes[-1 - bars] <= 0:
        return 0.0
    return (closes[-1] - closes[-1 - bars]) / closes[-1 - bars]


def features_for(market: dict, candles: list[dict], spot_rows: list[tuple[int, float]], idx: int) -> list[float] | None:
    mid = candle_mid(candles[idx])
    if mid is None:
        return None
    close_dt = parse_iso(market.get("close_time"))
    end_ts = int(candles[idx].get("end_period_ts") or 0)
    tte = max(0.0, (close_dt.timestamp() if close_dt else end_ts) - end_ts)
    window = [candle_mid(c) for c in candles[max(0, idx - 7) : idx + 1]]
    mids = [m for m in window if m is not None]
    momentum = (mids[-1] - mids[0]) if len(mids) >= 2 else 0.0
    yb = candles[idx].get("yes_bid") or {}
    ya = candles[idx].get("yes_ask") or {}
    bid = _f(yb.get("close_dollars") if "close_dollars" in yb else yb.get("close"))
    ask = _f(ya.get("close_dollars") if "close_dollars" in ya else ya.get("close"))
    spread = (ask - bid) if bid is not None and ask is not None else 0.0
    imbalance = 0.0
    spot = spot_known_at(spot_rows, end_ts)
    sigma = realized_vol_annual(spot) if spot else None
    strike = _f(market.get("floor_strike"))
    spot_px = spot[-1] if spot else None
    dist = dist_vol(spot_px, strike, tte, sigma) if spot_px and strike and sigma else 0.0
    fair = digital_fair(spot_px, strike, tte, sigma) if spot_px and strike and sigma else mid
    local = datetime.fromtimestamp(end_ts, tz=ZoneInfo("America/New_York"))
    rvol = 0.0
    if len(mids) >= 3:
        mu = sum(mids) / len(mids)
        rvol = math.sqrt(sum((m - mu) ** 2 for m in mids) / len(mids))
    return [
        float(dist or 0.0),
        float(min(2.0, max(0.0, tte / 900.0))),
        float(min(1.0, max(0.0, mid))),
        float(imbalance),
        float(min(1.0, max(0.0, spread))),
        float(max(-1.0, min(1.0, momentum))),
        float(min(1.0, max(0.0, rvol))),
        float(max(-0.2, min(0.2, spot_return(spot, 5)))),
        float((local.hour * 60 + local.minute) / (24 * 60)),
        float(fair if fair is not None else mid),
    ]


def load_settlement_index(path: str | None) -> dict[str, list[tuple[int, float]]]:
    """Read the app's `diphunter-settlement-index` CSV export, oldest first."""
    if not path:
        return {}
    rows: dict[str, list[tuple[int, float]]] = {}
    with Path(path).open(encoding="utf-8") as f:
        header = next(f, "").strip().split(",")
        cols = {name: i for i, name in enumerate(header)}
        required = {"index_id", "source_ts_ms", "value_usd"}
        if not required.issubset(cols):
            raise ValueError("settlement-index CSV is missing required columns")
        for line in f:
            values = line.rstrip("\n").split(",")
            try:
                index_id = values[cols["index_id"]].strip().upper()
                ts = int(values[cols["source_ts_ms"]])
                value = float(values[cols["value_usd"]])
            except (IndexError, ValueError):
                continue
            if index_id and ts > 0 and value > 0:
                rows.setdefault(index_id, []).append((ts // 1_000, value))
    return {key: sorted(value) for key, value in rows.items()}


def settlement_spot_known_at(rows: list[tuple[int, float]], decision_ts: int) -> list[float]:
    """Minute buckets complete before the decision; mirrors Coinbase no-look-ahead."""
    latest_by_minute: dict[int, float] = {}
    for ts, value in rows:
        minute = ts - (ts % 60)
        if minute + 60 <= decision_ts:
            latest_by_minute[minute] = value
    return [latest_by_minute[t] for t in sorted(latest_by_minute)[-SPOT_LOOKBACK_BARS:]]


def collect(days: int, max_markets: int, settlement_index: dict[str, list[tuple[int, float]]] | None = None) -> tuple[list[list[float]], list[int], list[float], list[int], list[int], float]:
    X: list[list[float]] = []
    y: list[int] = []
    mids: list[float] = []
    times: list[int] = []
    closes: list[int] = []
    settlement_index = settlement_index or {}
    cf_rows = 0
    total_rows = 0
    per = max(8, max_markets // len(SERIES))
    for series in SERIES:
        print(f"=== {series}", flush=True)
        markets = fetch_settled(series, days, per)
        print(f"  settled {len(markets)}", flush=True)
        for m in markets:
            result = (m.get("result") or "").lower()
            label = 1 if result == "yes" else 0
            close_dt = parse_iso(m.get("close_time"))
            open_dt = parse_iso(m.get("open_time"))
            if not close_dt:
                continue
            close_ts = int(close_dt.timestamp())
            open_ts = int(open_dt.timestamp()) if open_dt else close_ts - 900
            try:
                candles = fetch_candles(series, m["ticker"], open_ts, close_ts)
            except Exception as e:
                print(f"  skip candles {m.get('ticker')}: {e}", flush=True)
                continue
            time.sleep(0.06)
            if len(candles) < 3:
                continue
            coinbase_spot = fetch_spot(PRODUCT[series], open_ts - 900, close_ts)
            index_id = {"KXBTC15M": "BRTI", "KXETH15M": "ETHUSD_RTI", "KXSOL15M": "SOLUSD_RTI"}[series]
            # two samples: mid-window and late
            for idx in (max(1, len(candles) // 2), max(1, len(candles) - 2)):
                end_ts = int(candles[idx].get("end_period_ts") or close_ts)
                settlement_spot = settlement_spot_known_at(settlement_index.get(index_id, []), end_ts)
                # Never silently substitute Coinbase when a settlement archive
                # was requested: coverage is measured and promotion will fail
                # until the 2–4 week CF collection is complete.
                spot = settlement_spot if len(settlement_spot) >= 5 else coinbase_spot
                feats = features_for(m, candles, spot, idx)
                if not feats:
                    continue
                X.append(feats)
                y.append(label)
                mids.append(feats[2])
                times.append(end_ts)
                closes.append(close_ts)
                total_rows += 1
                cf_rows += int(len(settlement_spot) >= 5)
    return X, y, mids, times, closes, (cf_rows / total_rows if total_rows else 0.0)


def standardize(X: list[list[float]]) -> tuple[list[list[float]], list[float], list[float]]:
    n = len(X[0])
    mean = [sum(row[i] for row in X) / len(X) for i in range(n)]
    std = []
    for i in range(n):
        var = sum((row[i] - mean[i]) ** 2 for row in X) / max(1, len(X) - 1)
        std.append(math.sqrt(var) if var > 1e-12 else 1.0)
    Z = [[(row[i] - mean[i]) / std[i] for i in range(n)] for row in X]
    return Z, mean, std


def fit_logistic(
    X: list[list[float]], y: list[int], iters: int = 80, lr: float = 0.15,
    offsets: list[float] | None = None
) -> tuple[list[float], float]:
    n = len(X[0])
    w = [0.0] * n
    b = 0.0
    offsets = offsets or [0.0] * len(X)
    for _ in range(iters):
        gw = [0.0] * n
        gb = 0.0
        for row, yi, offset in zip(X, y, offsets):
            z = b + offset + sum(wj * xj for wj, xj in zip(w, row))
            p = sigmoid(z)
            err = p - yi
            for i in range(n):
                gw[i] += err * row[i]
            gb += err
        scale = 1.0 / len(X)
        for i in range(n):
            w[i] -= lr * (gw[i] * scale + 1e-4 * w[i])
        b -= lr * gb * scale
    return w, b


def predict_rows(
    X: list[list[float]], w: list[float], b: float, a: float = 1.0, pb: float = 0.0,
    offsets: list[float] | None = None, isotonic_x: list[float] | None = None,
    isotonic_y: list[float] | None = None
) -> list[float]:
    out = []
    offsets = offsets or [0.0] * len(X)
    for row, offset in zip(X, offsets):
        p = sigmoid(b + offset + sum(wj * xj for wj, xj in zip(w, row)))
        if a != 1.0 or pb != 0.0:
            q = min(1 - 1e-6, max(1e-6, p))
            lp = math.log(q / (1 - q))
            p = sigmoid(a * lp + pb)
        if isotonic_x and isotonic_y:
            p = apply_isotonic(p, isotonic_x, isotonic_y)
        out.append(min(1.0 - 1e-4, max(1e-4, p)))
    return out


def market_offsets(mids: list[float]) -> list[float]:
    return [math.log(min(1 - 1e-6, max(1e-6, p)) / (1 - min(1 - 1e-6, max(1e-6, p)))) for p in mids]


def fit_isotonic(p: list[float], y: list[int]) -> tuple[list[float], list[float]]:
    """Pool-adjacent-violators calibration, fitted only on past/fit rows."""
    blocks: list[dict[str, float]] = []
    for prob, label in sorted(zip(p, y), key=lambda item: item[0]):
        blocks.append({"lo": prob, "hi": prob, "sum": float(label), "n": 1.0})
        while len(blocks) >= 2 and blocks[-2]["sum"] / blocks[-2]["n"] > blocks[-1]["sum"] / blocks[-1]["n"]:
            right, left = blocks.pop(), blocks.pop()
            blocks.append({"lo": left["lo"], "hi": right["hi"], "sum": left["sum"] + right["sum"], "n": left["n"] + right["n"]})
    return ([(b["lo"] + b["hi"]) / 2.0 for b in blocks], [b["sum"] / b["n"] for b in blocks])


def apply_isotonic(p: float, xs: list[float], ys: list[float]) -> float:
    if len(xs) < 2 or len(xs) != len(ys):
        return p
    if p <= xs[0]:
        return ys[0]
    if p >= xs[-1]:
        return ys[-1]
    for i in range(1, len(xs)):
        if p <= xs[i]:
            width = xs[i] - xs[i - 1]
            if width <= 1e-12:
                return ys[i]
            t = (p - xs[i - 1]) / width
            return ys[i - 1] + t * (ys[i] - ys[i - 1])
    return ys[-1]


def fit_platt(p: list[float], y: list[int]) -> tuple[float, float]:
    # one-feature logistic on logit(p)
    xs = []
    for pi in p:
        q = min(1 - 1e-6, max(1e-6, pi))
        xs.append([math.log(q / (1 - q))])
    w, b = fit_logistic(xs, y, iters = 60, lr=0.2)
    return w[0], b


def brier(p: list[float], y: list[int]) -> float:
    return sum((pi - yi) ** 2 for pi, yi in zip(p, y)) / len(y)


def logloss(p: list[float], y: list[int]) -> float:
    s = 0.0
    for pi, yi in zip(p, y):
        q = min(1 - 1e-9, max(1e-9, pi))
        s += -(yi * math.log(q) + (1 - yi) * math.log(1 - q))
    return s / len(y)


def calibration_error(p: list[float], y: list[int], bins: int = 10) -> float:
    """Expected calibration error; report it alongside proper scores, never alone."""
    if not p:
        return 1.0
    total = 0.0
    for bucket in range(bins):
        rows = [(pi, yi) for pi, yi in zip(p, y) if min(bins - 1, int(pi * bins)) == bucket]
        if rows:
            total += len(rows) / len(p) * abs(sum(pi for pi, _ in rows) / len(rows) - sum(yi for _, yi in rows) / len(rows))
    return total


def simulated_pnl(p: list[float], mids: list[float], y: list[int]) -> dict[str, float]:
    """Taker fill at the ask, not the midpoint. A midpoint fill is the
    2026-09-25 bug that made every strategy look better than a real order."""
    pnl = 0.0
    n = 0
    hits = 0
    for pi, m, yi in zip(p, mids, y):
        gap = abs(pi - m)
        fee = FEE_RATE * m * (1 - m)
        if gap <= fee + CONF_MARGIN:
            continue
        side_yes = pi > m
        price = min(0.999, m + SIM_HALF_SPREAD) if side_yes else min(0.999, (1.0 - m) + SIM_HALF_SPREAD)
        fee_c = FEE_RATE * price * (1 - price)
        win = (yi == 1) if side_yes else (yi == 0)
        pnl += (1.0 - price - fee_c) if win else (-price - fee_c)
        n += 1
        hits += int(win)
    return {"n": n, "pnl": pnl, "hit_rate": (hits / n) if n else 0.0}


def bootstrap_by_day(p: list[float], mids: list[float], y: list[int], times: list[int]) -> dict[str, float]:
    """Blocked bootstrap: resample whole UTC days, never correlated rows."""
    by_day: dict[str, list[int]] = {}
    for i, ts in enumerate(times):
        day = datetime.fromtimestamp(ts, tz=timezone.utc).date().isoformat()
        by_day.setdefault(day, []).append(i)
    days = sorted(by_day)
    if not days:
        return {"days": 0.0, "brier_advantage_lower_95": 0.0, "sim_pnl_lower_95": float("-inf")}
    rng = random.Random(0xD1A10)
    brier_delta: list[float] = []
    pnl_samples: list[float] = []
    for _ in range(BOOTSTRAP_REPS):
        indexes = [i for _ in days for i in by_day[rng.choice(days)]]
        pp = [p[i] for i in indexes]
        mm = [mids[i] for i in indexes]
        yy = [y[i] for i in indexes]
        brier_delta.append(brier(mm, yy) - brier(pp, yy))
        pnl_samples.append(simulated_pnl(pp, mm, yy)["pnl"])
    brier_delta.sort()
    pnl_samples.sort()
    q = int((BOOTSTRAP_REPS - 1) * 0.05)
    return {
        "days": float(len(days)),
        "brier_advantage_lower_95": brier_delta[q],
        "sim_pnl_lower_95": pnl_samples[q],
    }


def walk_forward(X: list[list[float]], y: list[int], mids: list[float], times: list[int], folds: int = 4, closes: list[int] | None = None) -> dict[str, Any]:
    """Folds are whole markets so a late row cannot train on its own settlement."""
    groups = list(closes) if closes is not None else list(times)
    order = sorted(range(len(X)), key=lambda i: (groups[i], times[i]))
    X = [X[i] for i in order]
    y = [y[i] for i in order]
    mids = [mids[i] for i in order]
    groups = [groups[i] for i in order]
    starts = [0]
    for i in range(1, len(groups)):
        if groups[i] != groups[i - 1]:
            starts.append(i)
    starts.append(len(X))
    n_markets = max(1, len(starts) - 1)
    fold = max(1, n_markets // folds)
    preds = [0.0] * len(X)
    for k in range(1, folds):
        tr_m = min(n_markets, k * fold)
        te_m = n_markets if k == folds - 1 else min(n_markets, (k + 1) * fold)
        tr_end = starts[tr_m]
        te_end = starts[te_m]
        if tr_end < 20 or te_end <= tr_end:
            continue
        Ztr, mean, std = standardize(X[:tr_end])
        offset_tr = market_offsets(mids[:tr_end])
        offset_te = market_offsets(mids[tr_end:te_end])
        w, b = fit_logistic(Ztr, y[:tr_end], offsets=offset_tr)
        Zte = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(tr_end, te_end)]
        raw_train = predict_rows(Ztr, w, b, offsets=offset_tr)
        iso_x, iso_y = fit_isotonic(raw_train, y[:tr_end])
        cal = predict_rows(Zte, w, b, offsets=offset_te, isotonic_x=iso_x, isotonic_y=iso_y)
        for i, p in enumerate(cal):
            preds[tr_end + i] = p
    # fill unfilled with market
    for i, p in enumerate(preds):
        if p == 0.0:
            preds[i] = mids[i]
    hold_start = starts[min(fold, n_markets)]
    hold = list(range(hold_start, len(X)))  # first fold is train-only
    if not hold:
        hold = list(range(len(X)))
    ph = [preds[i] for i in hold]
    yh = [y[i] for i in hold]
    mh = [mids[i] for i in hold]
    pnl = simulated_pnl(ph, mh, yh)
    final = [i for i in hold if X[i][1] <= (2.0 / 15.0)]
    final_p = [preds[i] for i in final]
    final_y = [y[i] for i in final]
    final_m = [mids[i] for i in final]
    evidence = bootstrap_by_day(ph, mh, yh, [times[i] for i in hold])
    return {
        "n_holdout": len(hold),
        "model_brier": brier(ph, yh),
        "market_brier": brier(mh, yh),
        "model_logloss": logloss(ph, yh),
        "market_logloss": logloss(mh, yh),
        "sim_trades": pnl["n"],
        "sim_pnl": pnl["pnl"],
        "sim_hit_rate": pnl["hit_rate"],
        "calibration_error": calibration_error(ph, yh),
        "market_calibration_error": calibration_error(mh, yh),
        "final_window_samples": len(final),
        "final_window_model_brier": brier(final_p, final_y) if final else 1.0,
        "final_window_market_brier": brier(final_m, final_y) if final else 1.0,
        "distinct_days": evidence["days"],
        "brier_advantage_lower_95": evidence["brier_advantage_lower_95"],
        "sim_pnl_lower_95": evidence["sim_pnl_lower_95"],
    }


def fit_final(X: list[list[float]], y: list[int], mids: list[float]) -> dict[str, Any]:
    Z, mean, std = standardize(X)
    w, b = fit_logistic(Z, y, offsets=market_offsets(mids))
    raw = predict_rows(Z, w, b, offsets=market_offsets(mids))
    iso_x, iso_y = fit_isotonic(raw, y)
    return {
        "weights": w, "bias": b, "mean": mean, "std": std,
        "platt_a": 1.0, "platt_b": 0.0, "isotonic_x": iso_x, "isotonic_y": iso_y,
    }


def fixture_dataset(n: int = 240) -> tuple[list[list[float]], list[int], list[float], list[int], list[int]]:
    X, y, mids, times, closes = [], [], [], [], []
    t0 = 1_700_000_000
    for i in range(n):
        mid = 0.35 + 0.3 * ((i % 40) / 40.0)
        dist = (mid - 0.5) * 2
        fair = min(0.95, max(0.05, mid + 0.08 * math.sin(i / 7.0)))
        row = [dist, 0.5, mid, 0.0, 0.02, 0.01, 0.04, 0.0, (i % 24) / 24.0, fair]
        label = 1 if fair + 0.02 * math.sin(i) > 0.5 else 0
        X.append(row)
        y.append(label)
        mids.append(mid)
        times.append(t0 + i * 60)
        closes.append(t0 + (i // 2) * 900)
    return X, y, mids, times, closes


def write_manifest(metrics: dict[str, Any], path: Path, trained_at: str | None = None, fixture: bool = False, cf_coverage: float = 0.0) -> None:
    n = int(metrics.get("n_holdout") or metrics.get("n_samples") or 0)
    model_brier = float(metrics.get("model_brier", 1.0))
    market_brier = float(metrics.get("market_brier", 1.0))
    model_ll = float(metrics.get("model_logloss", 1.0))
    market_ll = float(metrics.get("market_logloss", 1.0))
    payload = {
        "version": "1",
        "trained_at": trained_at or datetime.now(timezone.utc).isoformat(),
        "n_samples": n,
        "n_holdout": n,
        "model_brier": model_brier,
        "market_brier": market_brier,
        "model_logloss": model_ll,
        "market_logloss": market_ll,
        "sim_trades": metrics.get("sim_trades"),
        "sim_pnl": metrics.get("sim_pnl"),
        "sim_hit_rate": metrics.get("sim_hit_rate"),
        "model_asset": "edge_model.json",
        "tag": "edge-model-chat-GTP",
        "data_source": "synthetic_fixture" if fixture else "kalshi_live_historical_cf_settlement_index_v3",
        "beats_market": not fixture and n > 0 and model_brier < market_brier and model_ll < market_ll,
        "final_window_samples": int(metrics.get("final_window_samples", 0)),
        "final_window_model_brier": metrics.get("final_window_model_brier"),
        "final_window_market_brier": metrics.get("final_window_market_brier"),
        "calibration_error": metrics.get("calibration_error"),
        "market_calibration_error": metrics.get("market_calibration_error"),
        "distinct_days": int(metrics.get("distinct_days", 0)),
        "brier_advantage_lower_95": metrics.get("brier_advantage_lower_95"),
        "sim_pnl_lower_95": metrics.get("sim_pnl_lower_95"),
        "settlement_index_coverage": cf_coverage,
    }
    payload["promotion_eligible"] = bool(
        payload["beats_market"] and
        n >= MIN_PROMOTION_TRADES and
        payload["sim_trades"] >= MIN_PROMOTION_TRADES and
        payload["distinct_days"] >= MIN_PROMOTION_DAYS and
        cf_coverage >= 0.95 and
        float(payload["brier_advantage_lower_95"] or 0.0) >= MIN_BRIER_ADVANTAGE and
        float(payload["sim_pnl_lower_95"] or float("-inf")) > 0.0 and
        payload["final_window_samples"] >= 60 and
        float(payload["final_window_model_brier"] or 1.0) < float(payload["final_window_market_brier"] or 1.0) and
        float(payload["calibration_error"] or 1.0) <= float(payload["market_calibration_error"] or 1.0)
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def export(model: dict[str, Any], metrics: dict[str, Any], path: Path) -> None:
    payload = {
        "version": 1,
        "kind": "logistic",
        "feature_names": FEATURE_NAMES,
        "weights": model["weights"],
        "bias": model["bias"],
        "mean": model["mean"],
        "std": model["std"],
        "platt_a": model["platt_a"],
        "platt_b": model["platt_b"],
        "blend_weight": 0.35,
        "fee_margin": FEE_RATE,
        "confidence_margin": CONF_MARGIN,
        "market_prior": True,
        "isotonic_x": model.get("isotonic_x", []),
        "isotonic_y": model.get("isotonic_y", []),
        "metrics": metrics,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"wrote {path}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--max-markets", type=int, default=180)
    ap.add_argument("--out", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--settlement-index-csv", help="App export after 2–4 weeks of CF collection")
    ap.add_argument("--fixture", action="store_true")
    args = ap.parse_args()
    if args.fixture:
        X, y, mids, times, closes = fixture_dataset()
        cf_coverage = 1.0
    else:
        try:
            index = load_settlement_index(args.settlement_index_csv)
            X, y, mids, times, closes, cf_coverage = collect(args.days, args.max_markets, index)
        except Exception as e:
            print(f"live collect failed ({e}); refusing to publish a fixture model", file=sys.stderr, flush=True)
            return 1
    if len(X) < 30:
        print(f"only {len(X)} rows — refusing to publish an unvalidated model", file=sys.stderr, flush=True)
        return 1
    print(f"samples {len(X)} yes={sum(y)} no={len(y) - sum(y)}", flush=True)
    metrics = walk_forward(X, y, mids, times, closes=closes)
    print(json.dumps(metrics, indent=2), flush=True)
    model = fit_final(X, y, mids)
    export(model, metrics, Path(args.out))
    metrics["settlement_index_coverage"] = cf_coverage
    write_manifest(metrics, Path(args.manifest), fixture=args.fixture, cf_coverage=cf_coverage)
    # Do not overwrite the hand-checked Android/Python parity fixture.
    # Write a sample next to the exported model for debugging only.
    if not args.fixture:
        sample = {
            "x": X[0],
            "p": predict_rows(
                [[(X[0][j] - model["mean"][j]) / model["std"][j] for j in range(len(FEATURE_NAMES))]],
                model["weights"],
                model["bias"],
                model["platt_a"],
                model["platt_b"],
                offsets=market_offsets([mids[0]]),
                isotonic_x=model["isotonic_x"],
                isotonic_y=model["isotonic_y"],
            )[0],
        }
        out_dir = Path(args.out).resolve().parent
        (out_dir / "parity_sample.last.json").write_text(json.dumps(sample, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
