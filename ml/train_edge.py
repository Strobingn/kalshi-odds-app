#!/usr/bin/env python3
"""
Offline edge trainer for DipHunter (Kashi).

Walk-forward logistic on settled Kalshi BTC/ETH/SOL 15m markets
(KXBTC15M, KXETH15M, KXSOL15M) plus public Coinbase 1-minute candles.
Calibrates (Platt) on a held-out fold, then scores a later time-ordered
holdout on Brier / log-loss and fee-aware P&L versus the market.

A failing Kalshi/Coinbase fetch or a short sample is a hard error.
Synthetic data is written only with --fixture (explicit test mode) and
is never marked beats_market / publishable.

    python3 ml/train_edge.py
    python3 ml/train_edge.py --days 30 --out ml/edge_model.json
    python3 ml/train_edge.py --fixture   # no network; synthetic; not publishable
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import publish_gates as gates

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
COINBASE = "https://api.exchange.coinbase.com"
# Public market data only. Never a Kalshi account key.
DEFAULT_SERIES = ["KXBTC15M", "KXETH15M", "KXSOL15M"]
ALL_SERIES = ["KXBTC15M", "KXETH15M", "KXSOL15M"]
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
UA = "DipHunterTrainer/0.3.34"
# App: ExternalMarketFeatures.realizedVol(closes.takeLast(16)).
SPOT_LOOKBACK_BARS = 16
# Edge momentum / realized_vol: last 8 one-minute Coinbase closes.
FEATURE_CANDLE_BARS = 8
# Kalshi historical markets page size (docs: max 1000).
HIST_PAGE = 1000
# Live settled markets page size (docs: typically 200).
LIVE_PAGE = 200
# Coinbase candles: max 300 per request (Exchange REST).
CB_CANDLE_MAX = 300


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


def fetch_cutoff_ts() -> int | None:
    """GET /historical/cutoff — official Kalshi historical partition."""
    try:
        data = http_get(f"{KALSHI}/historical/cutoff")
    except Exception as e:
        print(f"cutoff fetch failed: {e}", flush=True)
        return None
    raw = data.get("market_settled_ts") or data.get("cutoff") or data.get("market_settled_ts_iso")
    if isinstance(raw, (int, float)) and raw > 1_000_000_000:
        return int(raw)
    if isinstance(raw, str):
        dt = parse_iso(raw)
        if dt:
            return int(dt.timestamp())
    return None


def _paginate_markets(path: str, series: str, extra: dict[str, Any], page: int, hard_cap: int) -> list[dict]:
    out: list[dict] = []
    cursor = None
    while len(out) < hard_cap:
        q: dict[str, Any] = {"series_ticker": series, "limit": min(page, hard_cap - len(out))}
        q.update(extra)
        if cursor:
            q["cursor"] = cursor
        data = http_get(f"{KALSHI}{path}?{urllib.parse.urlencode(q)}")
        batch = data.get("markets") or []
        if not batch:
            break
        out.extend(batch)
        cursor = data.get("cursor")
        time.sleep(0.12)
        if not cursor:
            break
    return out


def fetch_settled(series: str, days: int, limit: int = 50_000) -> list[dict]:
    """Live settled + historical settled, cursor-paginated.

    Official docs:
      GET /markets?status=settled (live tier, recent)
      GET /historical/markets (older than GET /historical/cutoff)
    Filters on historical are mutually exclusive; series_ticker is one filter.
    """
    seen: set[str] = set()
    out: list[dict] = []
    cutoff = time.time() - days * 86400 if days > 0 else 0.0

    def keep(m: dict) -> bool:
        t = m.get("ticker")
        if not t or t in seen:
            return False
        if (m.get("result") or "").lower() not in ("yes", "no"):
            return False
        ct = parse_iso(m.get("close_time") or m.get("settlement_ts"))
        if cutoff and ct and ct.timestamp() < cutoff:
            return False
        seen.add(t)
        return True

    live = _paginate_markets("/markets", series, {"status": "settled"}, LIVE_PAGE, limit)
    for m in live:
        if keep(m):
            out.append(m)
    hist_limit = max(0, limit - len(out))
    if hist_limit > 0:
        hist = _paginate_markets("/historical/markets", series, {}, HIST_PAGE, hist_limit)
        for m in hist:
            if keep(m):
                out.append(m)
    return out


def fetch_candles(series: str, ticker: str, open_ts: int, close_ts: int) -> list[dict]:
    q = {"start_ts": open_ts - 60, "end_ts": close_ts + 60, "period_interval": 1}
    data = http_get(f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    sticks = data.get("candlesticks") or []
    if sticks:
        return sticks
    hist = http_get(f"{KALSHI}/historical/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    return hist.get("candlesticks") or []


def fetch_spot(product: str, start: int, end: int) -> list[tuple[int, float]]:
    """1-minute Coinbase closes as (bucket_start_ts, close), oldest first.

    Exchange REST: GET /products/{id}/candles — max 300 candles / request,
    granularity=60. Paginate when the window is longer.
    """
    rows: list[tuple[int, float]] = []
    cursor = start
    while cursor < end:
        chunk_end = min(end, cursor + CB_CANDLE_MAX * 60)
        q = {
            "granularity": 60,
            "start": datetime.fromtimestamp(cursor, tz=timezone.utc).isoformat(),
            "end": datetime.fromtimestamp(chunk_end, tz=timezone.utc).isoformat(),
        }
        try:
            data = http_get(f"{COINBASE}/products/{product}/candles?{urllib.parse.urlencode(q)}")
        except Exception:
            break
        if not isinstance(data, list):
            break
        for r in data:
            if r and len(r) >= 5:
                rows.append((int(r[0]), float(r[4])))
        time.sleep(0.12)
        if chunk_end >= end:
            break
        cursor = chunk_end
    rows.sort(key=lambda x: x[0])
    dedup: list[tuple[int, float]] = []
    seen: set[int] = set()
    for ts, px in rows:
        if ts in seen:
            continue
        seen.add(ts)
        dedup.append((ts, px))
    return dedup


def spot_known_at(spot: list[tuple[int, float]], decision_ts: int, keep: int = SPOT_LOOKBACK_BARS) -> list[float]:
    """Closes whose 1m bar has finished by [decision_ts] — no look-ahead."""
    known = [c for ts, c in spot if ts + 60 <= decision_ts]
    return known[-keep:]


def realized_vol_annual(closes: list[float]) -> float | None:
    if len(closes) < 5:
        return None
    rets = [math.log(b / a) for a, b in zip(closes, closes[1:]) if a > 0 and b > 0]
    if len(rets) < 4:
        return None
    mean = sum(rets) / len(rets)
    var = sum((r - mean) ** 2 for r in rets) / (len(rets) - 1)
    std = math.sqrt(max(var, 0.0))
    if std <= 0:
        return None
    return min(5.0, max(0.01, std * math.sqrt(SECONDS_PER_YEAR / 60.0)))


def spot_return(closes: list[float], bars: int) -> float:
    if len(closes) <= bars or closes[-1 - bars] <= 0:
        return 0.0
    return (closes[-1] - closes[-1 - bars]) / closes[-1 - bars]


def coinbase_window_features(closes: list[float]) -> tuple[float, float]:
    """Momentum and realized_vol from 8 one-minute Coinbase closes.

    Shared with Kotlin EdgeFeatures.candleWindow — keep the formula identical.
    momentum = (last − first) / first, clipped [-1, 1]
    realized_vol = population std / mean, clipped [0, 1]
    """
    if len(closes) < 2 or closes[0] <= 0:
        return 0.0, 0.0
    mom = (closes[-1] - closes[0]) / closes[0]
    rvol = 0.0
    if len(closes) >= 3:
        mu = sum(closes) / len(closes)
        if mu > 0:
            rvol = math.sqrt(sum((c - mu) ** 2 for c in closes) / len(closes)) / mu
    return max(-1.0, min(1.0, mom)), min(1.0, max(0.0, rvol))


def utc_hour_frac(end_ts: int) -> float:
    """UTC hour / 24 — matches Kotlin EdgeFeatures.timeOfDayFrac (UTC)."""
    hour = datetime.fromtimestamp(end_ts or time.time(), tz=timezone.utc).hour
    return float(hour / 24.0)


def features_from_raw(
    *,
    spot_px: float | None,
    strike: float | None,
    tte: float,
    sigma: float | None,
    mid: float,
    imbalance: float,
    spread: float,
    coinbase_closes: list[float],
    cross: float,
    end_ts: int,
) -> list[float]:
    """Shared feature builder — golden-tested against Kotlin EdgeFeatures."""
    dist = dist_vol(spot_px, strike, tte, sigma) if spot_px and strike and sigma else 0.0
    fair = digital_fair(spot_px, strike, tte, sigma) if spot_px and strike and sigma else mid
    mom, rvol = coinbase_window_features(coinbase_closes[-FEATURE_CANDLE_BARS:])
    return [
        float(dist or 0.0),
        float(min(2.0, max(0.0, tte / 900.0))),
        float(min(1.0, max(0.0, mid))),
        float(imbalance),
        float(min(1.0, max(0.0, spread))),
        float(mom),
        float(rvol),
        float(max(-0.2, min(0.2, cross))),
        float(utc_hour_frac(end_ts)),
        float(fair if fair is not None else mid),
    ]


def features_for(market: dict, candles: list[dict], spot_rows: list[tuple[int, float]], idx: int) -> list[float] | None:
    mid = candle_mid(candles[idx])
    if mid is None:
        return None
    close_dt = parse_iso(market.get("close_time"))
    end_ts = int(candles[idx].get("end_period_ts") or 0)
    tte = max(0.0, (close_dt.timestamp() if close_dt else end_ts) - end_ts)
    yb = candles[idx].get("yes_bid") or {}
    ya = candles[idx].get("yes_ask") or {}
    bid = _f(yb.get("close_dollars") if "close_dollars" in yb else yb.get("close"))
    ask = _f(ya.get("close_dollars") if "close_dollars" in ya else ya.get("close"))
    spread = (ask - bid) if bid is not None and ask is not None else 0.02
    imbalance = 0.0
    spot = spot_known_at(spot_rows, end_ts)
    sigma = realized_vol_annual(spot) if spot else None
    strike = _f(market.get("floor_strike"))
    spot_px = spot[-1] if spot else None
    cb8 = spot[-FEATURE_CANDLE_BARS:] if spot else []
    return features_from_raw(
        spot_px=spot_px,
        strike=strike,
        tte=tte,
        sigma=sigma,
        mid=mid,
        imbalance=imbalance,
        spread=spread,
        coinbase_closes=cb8,
        cross=spot_return(spot, 5) if spot else 0.0,
        end_ts=end_ts,
    )


def collect(days: int, max_markets: int, series_list: list[str] | None = None) -> tuple[list[list[float]], list[int], list[float], list[int], list[str]]:
    X: list[list[float]] = []
    y: list[int] = []
    mids: list[float] = []
    times: list[int] = []
    market_ids: list[str] = []
    series_list = series_list or DEFAULT_SERIES
    per = max(8, max_markets // max(1, len(series_list)))
    for series in series_list:
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
            spot = fetch_spot(PRODUCT[series], open_ts - 900, close_ts)
            # One row per completed decision minute (skip first warmup + last print).
            usable = list(range(1, max(2, len(candles) - 1)))
            for idx in usable:
                feats = features_for(m, candles, spot, idx)
                if not feats:
                    continue
                X.append(feats)
                y.append(label)
                mids.append(feats[2])
                times.append(int(candles[idx].get("end_period_ts") or close_ts))
                market_ids.append(str(m.get("ticker") or ""))
    return X, y, mids, times, market_ids


def standardize(X: list[list[float]]) -> tuple[list[list[float]], list[float], list[float]]:
    n = len(X[0])
    mean = [sum(row[i] for row in X) / len(X) for i in range(n)]
    std = []
    for i in range(n):
        var = sum((row[i] - mean[i]) ** 2 for row in X) / max(1, len(X) - 1)
        std.append(math.sqrt(var) if var > 1e-12 else 1.0)
    Z = [[(row[i] - mean[i]) / std[i] for i in range(n)] for row in X]
    return Z, mean, std


def _nll(X: list[list[float]], y: list[int], w: list[float], b: float) -> float:
    s = 0.0
    for row, yi in zip(X, y):
        p = sigmoid(b + sum(wj * xj for wj, xj in zip(w, row)))
        q = min(1 - 1e-9, max(1e-9, p))
        s += -(yi * math.log(q) + (1 - yi) * math.log(1 - q))
    return s / max(1, len(X))


def fit_logistic(
    X: list[list[float]],
    y: list[int],
    iters: int = 80,
    lr: float = 0.15,
    X_val: list[list[float]] | None = None,
    y_val: list[int] | None = None,
    max_iters: int | None = None,
    patience: int = 25,
) -> tuple[list[float], float]:
    """Gradient descent. When a val fold is given, train to convergence
    with early stopping. `iters` is the cap when no val fold is supplied
    (kept for Platt's 1-feature fit and older tests)."""
    n = len(X[0])
    w = [0.0] * n
    b = 0.0
    cap = max_iters if max_iters is not None else (2000 if X_val is not None else iters)
    best_w, best_b = w[:], b
    best_val = float("inf")
    stale = 0
    for _ in range(cap):
        gw = [0.0] * n
        gb = 0.0
        for row, yi in zip(X, y):
            z = b + sum(wj * xj for wj, xj in zip(w, row))
            p = sigmoid(z)
            err = p - yi
            for i in range(n):
                gw[i] += err * row[i]
            gb += err
        scale = 1.0 / len(X)
        for i in range(n):
            w[i] -= lr * (gw[i] * scale + 1e-4 * w[i])
        b -= lr * gb * scale
        if X_val is not None and y_val is not None and y_val:
            val = _nll(X_val, y_val, w, b)
            if val < best_val - 1e-6:
                best_val = val
                best_w, best_b = w[:], b
                stale = 0
            else:
                stale += 1
                if stale >= patience:
                    return best_w, best_b
        else:
            best_w, best_b = w[:], b
    return best_w, best_b


def predict_rows(X: list[list[float]], w: list[float], b: float, a: float = 1.0, pb: float = 0.0) -> list[float]:
    out = []
    for row in X:
        p = sigmoid(b + sum(wj * xj for wj, xj in zip(w, row)))
        if a != 1.0 or pb != 0.0:
            q = min(1 - 1e-6, max(1e-6, p))
            lp = math.log(q / (1 - q))
            p = sigmoid(a * lp + pb)
        out.append(min(0.98, max(0.02, p)))
    return out


def fit_platt(p: list[float], y: list[int]) -> tuple[float, float]:
    xs = []
    for pi in p:
        q = min(1 - 1e-6, max(1e-6, pi))
        xs.append([math.log(q / (1 - q))])
    w, b = fit_logistic(xs, y, iters=80, lr=0.2)
    return w[0], b


def brier(p: list[float], y: list[int]) -> float:
    return sum((pi - yi) ** 2 for pi, yi in zip(p, y)) / len(y)


def logloss(p: list[float], y: list[int]) -> float:
    s = 0.0
    for pi, yi in zip(p, y):
        q = min(1 - 1e-9, max(1e-9, pi))
        s += -(yi * math.log(q) + (1 - yi) * math.log(1 - q))
    return s / len(y)


def side_pnl(side_yes: bool, mid: float, yi: int) -> float:
    """One-contract taker P&L after the quadratic fee.

    YES pays ``mid``. NO pays ``1 - mid``. Fee is ``feeRate × P × (1 − P)``
    on the price of the side that is bought (not rounded to the cent).
    """
    raw = mid if side_yes else (1.0 - mid)
    price = min(0.99, max(0.01, raw))
    fee = FEE_RATE * price * (1.0 - price)
    win = (yi == 1) if side_yes else (yi == 0)
    return (1.0 - price - fee) if win else (-price - fee)


def simulated_pnl(p: list[float], mids: list[float], y: list[int]) -> dict[str, float]:
    """Selective model policy: trade only when |model − mid| clears fee + margin."""
    pnl = 0.0
    n = 0
    hits = 0
    for pi, m, yi in zip(p, mids, y):
        gap = abs(pi - m)
        fee = FEE_RATE * m * (1.0 - m)
        if gap <= fee + CONF_MARGIN:
            continue
        side_yes = pi > m
        pnl += side_pnl(side_yes, m, yi)
        n += 1
        hits += int((yi == 1) if side_yes else (yi == 0))
    return {"n": n, "pnl": pnl, "hit_rate": (hits / n) if n else 0.0}


def market_follow_pnl(mids: list[float], y: list[int]) -> float:
    """Buy the market favourite on every row, same fee model. Baseline to beat."""
    return sum(side_pnl(m >= 0.5, m, yi) for m, yi in zip(mids, y))


def _order(X, y, mids, times):
    order = sorted(range(len(X)), key=lambda i: times[i])
    return (
        [X[i] for i in order],
        [y[i] for i in order],
        [mids[i] for i in order],
        [times[i] for i in order],
    )


def time_split(n: int) -> tuple[int, int, int]:
    """Train / val / cal / holdout cuts on a time-ordered array.

    50% train, 15% val (early stopping), 15% Platt, 20% holdout.
    """
    tr = max(20, int(n * 0.50))
    va = max(tr + 10, int(n * 0.65))
    cal = max(va + 10, int(n * 0.80))
    return tr, va, cal


def evaluate_holdout(X: list[list[float]], y: list[int], mids: list[float], times: list[int]) -> dict[str, Any]:
    X, y, mids, times = _order(X, y, mids, times)
    n = len(X)
    tr, va, cal = time_split(n)
    if cal >= n - 5:
        # Too small for a real holdout — report identity (does not beat market).
        return {
            "n_holdout": 0,
            "model_brier": 1.0,
            "market_brier": brier(mids, y) if y else 1.0,
            "model_logloss": 1.0,
            "market_logloss": logloss(mids, y) if y else 1.0,
            "sim_trades": 0,
            "sim_pnl": 0.0,
            "sim_hit_rate": 0.0,
            "market_pnl": market_follow_pnl(mids, y) if y else 0.0,
            "split": {"train": tr, "val": va, "cal": cal, "n": n},
        }
    Ztr, mean, std = standardize(X[:tr])
    Zva = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(tr, va)]
    w, b = fit_logistic(Ztr, y[:tr], X_val=Zva, y_val=y[tr:va], max_iters=2000, patience=25, lr=0.12)
    Zcal = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(va, cal)]
    raw_cal = predict_rows(Zcal, w, b)
    a, pb = fit_platt(raw_cal, y[va:cal])
    Zho = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(cal, n)]
    ph = predict_rows(Zho, w, b, a, pb)
    yh = y[cal:]
    mh = mids[cal:]
    pnl = simulated_pnl(ph, mh, yh)
    return {
        "n_holdout": len(ph),
        "model_brier": brier(ph, yh),
        "market_brier": brier(mh, yh),
        "model_logloss": logloss(ph, yh),
        "market_logloss": logloss(mh, yh),
        "sim_trades": pnl["n"],
        "sim_pnl": pnl["pnl"],
        "sim_hit_rate": pnl["hit_rate"],
        "market_pnl": market_follow_pnl(mh, yh),
        "split": {"train": tr, "val": va - tr, "cal": cal - va, "holdout": n - cal, "n": n},
        "platt_fit_on": "calibration_fold",
    }


def walk_forward(X: list[list[float]], y: list[int], mids: list[float], times: list[int], folds: int = 4) -> dict[str, Any]:
    """Kept for older callers; new training uses evaluate_holdout."""
    return evaluate_holdout(X, y, mids, times)


def fit_final(X: list[list[float]], y: list[int], times: list[int] | None = None) -> dict[str, Any]:
    """Fit on train+val (time-ordered), Platt on the calibration fold.

    Does not peek at the holdout used for beats_market.
    """
    if times and len(times) == len(X):
        order = sorted(range(len(X)), key=lambda i: times[i])
        X = [X[i] for i in order]
        y = [y[i] for i in order]
        tr, va, cal = time_split(len(X))
        fit_end = va  # train + val
        Zfit, mean, std = standardize(X[:fit_end])
        Zva = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(tr, va)] if va > tr else None
        yva = y[tr:va] if va > tr else None
        w, b = fit_logistic(Zfit[:tr] if va > tr else Zfit, y[:tr] if va > tr else y, X_val=Zva, y_val=yva, max_iters=2000, patience=25, lr=0.12)
        if cal > va:
            Zcal = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(va, cal)]
            raw = predict_rows(Zcal, w, b)
            a, pb = fit_platt(raw, y[va:cal])
        else:
            a, pb = 1.0, 0.0
        return {"weights": w, "bias": b, "mean": mean, "std": std, "platt_a": a, "platt_b": pb}
    Z, mean, std = standardize(X)
    w, b = fit_logistic(Z, y, max_iters=2000, patience=25, lr=0.12)
    return {"weights": w, "bias": b, "mean": mean, "std": std, "platt_a": 1.0, "platt_b": 0.0}


def fixture_dataset(n: int = 240) -> tuple[list[list[float]], list[int], list[float], list[int], list[str]]:
    X, y, mids, times, ids = [], [], [], [], []
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
        times.append(t0 + i * 900)
        ids.append(f"FIX-{i // 4}")
    return X, y, mids, times, ids


def model_tag(trained_at: str) -> str:
    day = trained_at[:10].replace("-", "")
    return f"model-{day}"


def write_manifest(
    metrics: dict[str, Any],
    path: Path,
    *,
    trained_at: str | None = None,
    sha256: str = "",
    series: list[str] | None = None,
) -> None:
    gate = gates.publish_decision(metrics)
    stamp = trained_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = {
        "version": "2",
        "trained_at": stamp,
        "package": gates.PACKAGE_ID,
        "sha256": sha256,
        "series": series or list(DEFAULT_SERIES),
        "n_samples": int(metrics.get("n_rows") or metrics.get("n_samples") or 0),
        "n_rows": int(metrics.get("n_rows") or 0),
        "n_markets": int(metrics.get("n_markets") or 0),
        "n_holdout": int(metrics.get("n_holdout") or 0),
        "model_brier": float(metrics.get("model_brier", 1.0)),
        "market_brier": float(metrics.get("market_brier", 1.0)),
        "model_logloss": float(metrics.get("model_logloss", 1.0)),
        "market_logloss": float(metrics.get("market_logloss", 1.0)),
        "brier_margin": gate["brier_margin"],
        "logloss_margin": gate["logloss_margin"],
        "sim_trades": metrics.get("sim_trades"),
        "sim_pnl": metrics.get("sim_pnl"),
        "market_pnl": metrics.get("market_pnl"),
        "sim_hit_rate": metrics.get("sim_hit_rate"),
        "fee_rate": FEE_RATE,
        "model_asset": "edge_model.json",
        "tag": model_tag(stamp),
        "synthetic": bool(metrics.get("synthetic")),
        "data_source": "synthetic_fixture" if metrics.get("synthetic") else "kalshi_settled_coinbase_spot_v1",
        "beat_market": bool(gate["beat_market"]),
        "beats_market": bool(gate["beats_market"]),
        "publishable": bool(gate["publishable"]),
        "gate_reasons": gate["reasons"],
        "min_markets": gates.MIN_PUBLISH_MARKETS,
        "min_rows": gates.MIN_PUBLISH_ROWS,
        "min_holdout": gates.MIN_HOLDOUT_ROWS,
        "min_sim_trades": gates.MIN_SIM_TRADES,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path}", flush=True)


def export(model: dict[str, Any], metrics: dict[str, Any], path: Path) -> None:
    gate = gates.publish_decision(metrics)
    payload = {
        "version": 2,
        "kind": "logistic",
        "feature_names": FEATURE_NAMES,
        "weights": model["weights"],
        "bias": model["bias"],
        "mean": model["mean"],
        "std": model["std"],
        "platt_a": model["platt_a"],
        "platt_b": model["platt_b"],
        "blend_weight": 0.35 if gate["beats_market"] else 0.0,
        "fee_margin": FEE_RATE,
        "confidence_margin": CONF_MARGIN,
        "metrics": {**metrics, **{k: gate[k] for k in ("beats_market", "publishable", "brier_margin", "logloss_margin", "synthetic")}},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path}", flush=True)


def _unlink_outputs(out: Path, manifest: Path) -> None:
    for p in (out, manifest):
        try:
            if p.is_file():
                p.unlink()
        except OSError:
            pass


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=3650, help="Lookback; 0 = no time cutoff (full history)")
    ap.add_argument("--max-markets", type=int, default=50_000)
    ap.add_argument(
        "--series",
        default=",".join(DEFAULT_SERIES),
        help="Comma-separated series (default BTC, ETH, and SOL 15m)",
    )
    ap.add_argument("--out", default=str(ML_DIR / "edge_model.json"))
    ap.add_argument("--manifest", default=str(ML_DIR / "edge_model_manifest.json"))
    ap.add_argument("--fixture", action="store_true", help="Explicit test mode: synthetic data, never publishable")
    args = ap.parse_args(argv)
    out_path = Path(args.out)
    man_path = Path(args.manifest)
    synthetic = bool(args.fixture)
    series_list = [s.strip() for s in args.series.split(",") if s.strip()]
    for s in series_list:
        if s not in PRODUCT:
            print(f"unknown series {s}", flush=True)
            _unlink_outputs(out_path, man_path)
            return 2

    if args.fixture:
        X, y, mids, times, market_ids = fixture_dataset()
    else:
        try:
            X, y, mids, times, market_ids = collect(args.days, args.max_markets, series_list)
        except Exception as e:
            print(f"live collect failed: {e}", flush=True)
            _unlink_outputs(out_path, man_path)
            return 2

    n_markets = len({m for m in market_ids if m})
    print(f"samples {len(X)} markets={n_markets} yes={sum(y)} no={len(y) - sum(y)}", flush=True)

    if not args.fixture and (len(X) < 30 or n_markets < 1):
        print(f"insufficient live data (rows={len(X)} markets={n_markets}) — refusing to train or write", flush=True)
        _unlink_outputs(out_path, man_path)
        return 2

    metrics = evaluate_holdout(X, y, mids, times)
    metrics["n_rows"] = len(X)
    metrics["n_samples"] = len(X)
    metrics["n_markets"] = n_markets
    metrics["synthetic"] = synthetic
    print(json.dumps({k: metrics[k] for k in metrics if k != "split"}, indent=2), flush=True)
    gate = gates.publish_decision(metrics)
    metrics["beats_market"] = gate["beats_market"]
    metrics["publishable"] = gate["publishable"]
    print("gate:", json.dumps(gate, indent=2), flush=True)

    model = fit_final(X, y, times)
    export(model, metrics, out_path)
    digest = hashlib.sha256(out_path.read_bytes()).hexdigest()
    trained_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    write_manifest(
        metrics,
        man_path,
        trained_at=trained_at,
        sha256=digest,
        series=series_list,
    )

    if not args.fixture:
        sample = {
            "x": X[0],
            "p": predict_rows(
                [[(X[0][j] - model["mean"][j]) / model["std"][j] for j in range(len(FEATURE_NAMES))]],
                model["weights"],
                model["bias"],
                model["platt_a"],
                model["platt_b"],
            )[0],
        }
        out_dir = out_path.resolve().parent
        (out_dir / "parity_sample.last.json").write_text(json.dumps(sample, indent=2), encoding="utf-8")

    if args.fixture:
        print("fixture mode: files written for tests; not publishable", flush=True)
        return 0
    if not gate["publishable"]:
        print("publish gates failed — exiting non-zero so CI does not publish", flush=True)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
