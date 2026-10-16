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
import os
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
# 0.3.37: daily 5 PM ET above/below series alongside the 15-minute series.
DAILY_SERIES = ["KXBTCD", "KXETHD", "KXSOLD"]
ALL_SERIES = DEFAULT_SERIES + DAILY_SERIES
PRODUCT = {
    "KXBTC15M": "BTC-USD", "KXETH15M": "ETH-USD", "KXSOL15M": "SOL-USD",
    "KXBTCD": "BTC-USD", "KXETHD": "ETH-USD", "KXSOLD": "SOL-USD",
}
# Held-out evaluation window: the most recent 4 days, never trained on.
HOLDOUT_DAYS = 4
# Recency weighting half-life for training rows (full history is kept).
HALF_LIFE_DAYS = 30.0
EVAL_REPORT_DIR = ML_DIR / "reports"
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
UA = "DipHunterTrainer/0.3.37"
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


def scrub_kalshi_env() -> None:
    for key in list(os.environ):
        if key.upper().startswith("KALSHI"):
            os.environ.pop(key, None)


# Paced, 429-aware public fetch (ported from kashi-run/run_fresh.py).
# Never authenticated: no Kalshi key is read, and KALSHI* env vars are scrubbed.
_PACE: dict[str, float] = {
    "interval": float(os.environ.get("KASHI_MIN_INTERVAL", "0.4")),
    "n429": 0.0,
    "calls": 0.0,
    "skips": 0.0,
}
_PACE["base"] = _PACE["interval"]
_LAST: dict[str, float] = {"kalshi": 0.0, "other": 0.0}
MAX_INTERVAL = 3.0


def _retry_after(e: urllib.error.HTTPError) -> float:
    try:
        raw = e.headers.get("Retry-After") if e.headers else None
        return float(raw) if raw else 0.0
    except (TypeError, ValueError):
        return 0.0


def http_get(url: str, retries: int = 6) -> Any:
    """GET JSON with per-host pacing and adaptive 429 backoff.

    Kalshi: start at KASHI_MIN_INTERVAL (0.4 s); each 429 widens the gap
    ×1.1 (max 3 s); each success relaxes it ×0.98 toward the base. Honors
    Retry-After; caps any single sleep so a run never stalls for minutes.
    Candlestick calls give up after 3 tries (a skipped market, not a crash).
    """
    scrub_kalshi_env()
    host = "kalshi" if "kalshi.com" in url else "other"
    is_candle = "candlestick" in url
    tries = 3 if is_candle else retries
    last: Exception | None = None
    for attempt in range(tries):
        gap = _PACE["interval"] if host == "kalshi" else 0.08
        wait = _LAST[host] + gap - time.time()
        if wait > 0:
            time.sleep(wait)
        _LAST[host] = time.time()
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": UA})
            with urllib.request.urlopen(req, timeout=45) as resp:
                _PACE["calls"] += 1
                if host == "kalshi":
                    _PACE["interval"] = max(_PACE["base"], _PACE["interval"] * 0.98)
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                return {}
            if e.code in (429, 500, 502, 503, 504):
                if e.code == 429:
                    _PACE["n429"] += 1
                    if host == "kalshi":
                        _PACE["interval"] = min(MAX_INTERVAL, max(_PACE["interval"] * 1.1, 0.5))
                if is_candle and attempt >= tries - 1:
                    _PACE["skips"] += 1
                    raise
                time.sleep(max(min(_retry_after(e), 30.0), min(2 ** attempt, 12)))
                continue
            raise
        except urllib.error.URLError as e:
            last = e
            time.sleep(min(2 ** attempt, 8))
    raise RuntimeError(f"GET failed {url}: {last}")


def pace_stats() -> dict[str, float]:
    return dict(_PACE)


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


def is_daily(series: str) -> bool:
    return series.upper() in DAILY_SERIES


def fetch_candles(series: str, ticker: str, open_ts: int, close_ts: int) -> list[dict]:
    # 15m markets: 1-minute candles. Daily markets: hourly candles (one decision row per hour).
    period = 60 if is_daily(series) else 1
    q = {"start_ts": open_ts - 60 * period, "end_ts": close_ts + 60 * period, "period_interval": period}
    data = http_get(f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    sticks = data.get("candlesticks") or []
    if sticks:
        return sticks
    hist = http_get(f"{KALSHI}/historical/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    return hist.get("candlesticks") or []


_SPOT_CACHE: dict[tuple[str, int, int], list[tuple[int, float]]] = {}


def fetch_spot(product: str, start: int, end: int) -> list[tuple[int, float]]:
    """Cached: daily strikes of one event share the same spot window."""
    key = (product, int(start), int(end))
    if key not in _SPOT_CACHE:
        if len(_SPOT_CACHE) > 256:
            _SPOT_CACHE.clear()
        _SPOT_CACHE[key] = _fetch_spot_uncached(product, start, end)
    return _SPOT_CACHE[key]


def _fetch_spot_uncached(product: str, start: int, end: int) -> list[tuple[int, float]]:
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
            if is_daily(series):
                # One event = many strikes; keep only near-the-money strikes to bound the row count.
                mids0 = [candle_mid(c) for c in candles]
                mids0 = [x for x in mids0 if x is not None]
                if not mids0 or all(x < 0.03 or x > 0.97 for x in mids0):
                    continue
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
    sw: list[float] | None = None,
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
    weights = sw if sw is not None and len(sw) == len(X) else [1.0] * len(X)
    total_w = sum(weights) or float(len(X))
    for _ in range(cap):
        gw = [0.0] * n
        gb = 0.0
        for row, yi, si in zip(X, y, weights):
            z = b + sum(wj * xj for wj, xj in zip(w, row))
            p = sigmoid(z)
            err = (p - yi) * si
            for i in range(n):
                gw[i] += err * row[i]
            gb += err
        scale = 1.0 / total_w
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


def kalshi_fee(price: float, contracts: int = 1, fee_rate: float = FEE_RATE) -> float:
    """Kalshi taker fee: ceil to the cent of fee_rate × C × P × (1 − P)."""
    raw = fee_rate * contracts * price * (1.0 - price)
    return math.ceil(round(raw * 100.0, 6)) / 100.0


def side_pnl(side_yes: bool, mid: float, yi: int) -> float:
    """One-contract taker P&L after the 7% fee rounded UP to the cent.

    YES pays ``mid``. NO pays ``1 - mid``. Fee is ``ceil_cent(0.07 × P × (1 − P))``
    on the price of the side that is bought.
    """
    raw = mid if side_yes else (1.0 - mid)
    price = min(0.99, max(0.01, raw))
    fee = kalshi_fee(price)
    win = (yi == 1) if side_yes else (yi == 0)
    return (1.0 - price - fee) if win else (-price - fee)


def simulated_pnl(p: list[float], mids: list[float], y: list[int]) -> dict[str, float]:
    """Selective model policy: trade only when |model − mid| clears fee + margin."""
    pnl = 0.0
    n = 0
    hits = 0
    for pi, m, yi in zip(p, mids, y):
        gap = abs(pi - m)
        fee = kalshi_fee(min(0.99, max(0.01, m if pi > m else 1.0 - m)))
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


def recency_weights(times: list[int], ref_ts: int, half_life_days: float = HALF_LIFE_DAYS) -> list[float]:
    """exp(-ln2 × age / half-life). Full history is kept; older rows just count less."""
    lam = math.log(2.0) / (max(half_life_days, 1e-6) * 86400.0)
    return [math.exp(-lam * max(0, ref_ts - t)) for t in times]


def holdout_start(times_sorted: list[int], holdout_days: float = HOLDOUT_DAYS) -> tuple[int, int]:
    """First index of the last ``holdout_days`` (times sorted ascending) and the cutoff ts."""
    if not times_sorted:
        return 0, 0
    cutoff = int(times_sorted[-1] - holdout_days * 86400)
    for i, t in enumerate(times_sorted):
        if t >= cutoff:
            return i, cutoff
    return len(times_sorted), cutoff


def _zrows(X: list[list[float]], mean: list[float], std: list[float]) -> list[list[float]]:
    return [[(row[j] - mean[j]) / std[j] for j in range(len(mean))] for row in X]


def fit_on(X: list[list[float]], y: list[int], times: list[int], half_life_days: float = HALF_LIFE_DAYS) -> dict[str, Any]:
    """Recency-weighted logistic on time-ordered pre-holdout rows.

    70% train (weighted), 15% validation (early stopping), 15% Platt.
    """
    n = len(X)
    tr = max(20, int(n * 0.70))
    va = max(tr + 5, int(n * 0.85))
    va = min(va, n - 5) if n - 5 > tr else n
    ref = times[-1] if times else 0
    Ztr, mean, std = standardize(X[:tr])
    sw = recency_weights(times[:tr], ref, half_life_days)
    Zva = _zrows(X[tr:va], mean, std) if va > tr else None
    yva = y[tr:va] if va > tr else None
    w, b = fit_logistic(Ztr, y[:tr], X_val=Zva, y_val=yva, max_iters=2000, patience=25, lr=0.12, sw=sw)
    if n > va:
        raw = predict_rows(_zrows(X[va:], mean, std), w, b)
        a, pb = fit_platt(raw, y[va:])
    else:
        a, pb = 1.0, 0.0
    return {"weights": w, "bias": b, "mean": mean, "std": std, "platt_a": a, "platt_b": pb}


def predict_model(model: dict[str, Any], X: list[list[float]]) -> list[float]:
    return predict_rows(
        _zrows(X, model["mean"], model["std"]),
        model["weights"],
        model["bias"],
        float(model.get("platt_a", 1.0)),
        float(model.get("platt_b", 0.0)),
    )


def trade_pnls(p: list[float], mids: list[float], y: list[int], ids: list[str]) -> list[tuple[str, float]]:
    """Per-trade fee-aware P&L (same selective policy as simulated_pnl) with its market id."""
    out: list[tuple[str, float]] = []
    for pi, m, yi, mid_id in zip(p, mids, y, ids):
        side_yes = pi > m
        fee = kalshi_fee(min(0.99, max(0.01, m if side_yes else 1.0 - m)))
        if abs(pi - m) <= fee + CONF_MARGIN:
            continue
        out.append((mid_id, side_pnl(side_yes, m, yi)))
    return out


def clustered_bootstrap_ci(pairs: list[tuple[str, float]], resamples: int = 2000, seed: int = 0) -> dict[str, float] | None:
    """Market-clustered bootstrap of mean P&L per trade (resample whole markets)."""
    import random

    if not pairs:
        return None
    groups: dict[str, list[float]] = {}
    for k, v in pairs:
        groups.setdefault(k, []).append(v)
    clusters = [(sum(v), len(v)) for v in groups.values()]
    mean = sum(v for _, v in pairs) / len(pairs)
    if len(clusters) < 2:
        return {"mean": mean, "lo": mean, "hi": mean, "clusters": len(clusters)}
    rnd = random.Random(seed)
    stats = []
    for _ in range(resamples):
        s = 0.0
        n = 0
        for _ in range(len(clusters)):
            cs, cn = clusters[rnd.randrange(len(clusters))]
            s += cs
            n += cn
        stats.append(s / n if n else 0.0)
    stats.sort()
    lo = stats[int(0.025 * resamples)]
    hi = stats[min(resamples - 1, int(0.975 * resamples))]
    return {"mean": mean, "lo": lo, "hi": hi, "clusters": len(clusters)}


def sanity_reasons(metrics: dict[str, Any]) -> list[str]:
    """Backtest sanity gates: catch leakage and broken splits before any publish."""
    out: list[str] = []
    tmax = metrics.get("train_max_ts")
    hmin = metrics.get("holdout_min_ts")
    if tmax is not None and hmin is not None and tmax >= hmin:
        out.append("train rows overlap the holdout window (look-ahead)")
    mb = float(metrics.get("model_brier", 1.0))
    kb = float(metrics.get("market_brier", 1.0))
    if kb > 0.26:
        out.append(f"market Brier {kb:.3f} > 0.26 — holdout labels or mids look broken")
    if kb > 0 and mb < 0.5 * kb:
        out.append(f"model Brier {mb:.3f} < half the market's {kb:.3f} — suspiciously good, check for leakage")
    if int(metrics.get("sim_trades") or 0) > int(metrics.get("n_holdout") or 0):
        out.append("more trades than holdout rows")
    return out


def evaluate_and_fit(
    X: list[list[float]],
    y: list[int],
    mids: list[float],
    times: list[int],
    market_ids: list[str] | None = None,
    holdout_days: float = HOLDOUT_DAYS,
    half_life_days: float = HALF_LIFE_DAYS,
    champion: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """Fit on full pre-holdout history (recency weighted), score the last 4 days.

    The returned model is exactly the model that was scored — the holdout is
    never trained on, so the eval report describes what ships.
    """
    ids = market_ids if market_ids and len(market_ids) == len(X) else [str(i) for i in range(len(X))]
    order = sorted(range(len(X)), key=lambda i: times[i])
    X = [X[i] for i in order]
    y = [y[i] for i in order]
    mids = [mids[i] for i in order]
    times = [times[i] for i in order]
    ids = [ids[i] for i in order]
    n = len(X)
    first, cutoff = holdout_start(times, holdout_days)
    mode = f"last_{holdout_days:g}_days"
    if first < 40 or n - first < 5:
        _, _, cal = time_split(n)
        first, cutoff, mode = cal, (times[cal] if cal < n else 0), "proportional_fallback_last_20pct"
    base = {
        "holdout_mode": mode,
        "holdout_days": holdout_days,
        "half_life_days": half_life_days,
        "fee_rule": "7% taker, ceil to the cent per order",
        "split": {"pre_holdout": first, "holdout": n - first, "n": n},
    }
    if first >= n - 5 or first < 25:
        return {
            **base,
            "n_holdout": 0,
            "model_brier": 1.0,
            "market_brier": brier(mids, y) if y else 1.0,
            "model_logloss": 1.0,
            "market_logloss": logloss(mids, y) if y else 1.0,
            "sim_trades": 0,
            "sim_pnl": 0.0,
            "sim_hit_rate": 0.0,
            "market_pnl": market_follow_pnl(mids, y) if y else 0.0,
        }, None
    model = fit_on(X[:first], y[:first], times[:first], half_life_days)
    ph = predict_model(model, X[first:])
    yh, mh, idh = y[first:], mids[first:], ids[first:]
    pnl = simulated_pnl(ph, mh, yh)
    metrics: dict[str, Any] = {
        **base,
        "n_holdout": len(ph),
        "holdout_start_ts": cutoff,
        "train_max_ts": times[first - 1],
        "holdout_min_ts": times[first],
        "model_brier": brier(ph, yh),
        "market_brier": brier(mh, yh),
        "model_logloss": logloss(ph, yh),
        "market_logloss": logloss(mh, yh),
        "sim_trades": pnl["n"],
        "sim_pnl": pnl["pnl"],
        "sim_hit_rate": pnl["hit_rate"],
        "market_pnl": market_follow_pnl(mh, yh),
        "sim_pnl_per_trade_ci": clustered_bootstrap_ci(trade_pnls(ph, mh, yh, idh)),
        "holdout_markets": len(set(idh)),
        "platt_fit_on": "calibration_fold_pre_holdout",
    }
    if champion is not None:
        try:
            pc = predict_model(champion, X[first:])
            cp = simulated_pnl(pc, mh, yh)
            metrics["champion"] = {
                "tag": champion.get("_tag", ""),
                "model_brier": brier(pc, yh),
                "model_logloss": logloss(pc, yh),
                "sim_pnl": cp["pnl"],
                "sim_trades": cp["n"],
            }
        except Exception as e:  # incompatible champion → treated as absent, but recorded
            metrics["champion_error"] = str(e)
    metrics["sanity_reasons"] = sanity_reasons(metrics)
    return metrics, model


def champion_challenger(metrics: dict[str, Any]) -> dict[str, Any]:
    """The challenger must beat the champion on Brier, log loss, and fee-aware P&L (OOS)."""
    champ = metrics.get("champion")
    if not champ:
        return {"beats_champion": True, "reasons": ["no champion — market gate only"]}
    reasons: list[str] = []
    if float(metrics["model_brier"]) >= float(champ["model_brier"]):
        reasons.append(f"Brier {metrics['model_brier']:.4f} not below champion {champ['model_brier']:.4f}")
    if float(metrics["model_logloss"]) >= float(champ["model_logloss"]):
        reasons.append(f"log loss {metrics['model_logloss']:.4f} not below champion {champ['model_logloss']:.4f}")
    if float(metrics.get("sim_pnl") or 0.0) <= float(champ.get("sim_pnl") or 0.0):
        reasons.append(f"fee-aware P&L {metrics.get('sim_pnl')} not above champion {champ.get('sim_pnl')}")
    return {"beats_champion": not reasons, "reasons": reasons}


def load_champion(model_path: Path | None, manifest_path: Path | None) -> dict[str, Any] | None:
    """Current published champion; None when missing, synthetic, or feature-incompatible."""
    if model_path is None or not model_path.is_file():
        return None
    try:
        model = json.loads(model_path.read_text(encoding="utf-8"))
        man = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path and manifest_path.is_file() else {}
    except (OSError, ValueError):
        return None
    if man.get("synthetic") or man.get("data_source") == "synthetic_fixture":
        return None
    if list(model.get("feature_names") or []) != FEATURE_NAMES:
        return None
    if len(model.get("weights") or []) != len(FEATURE_NAMES):
        return None
    model["_tag"] = str(man.get("tag") or "")
    return model


def default_champion_paths() -> tuple[Path | None, Path | None]:
    latest = ML_DIR / "published" / "latest.json"
    if not latest.is_file():
        return None, None
    try:
        data = json.loads(latest.read_text(encoding="utf-8"))
    except ValueError:
        return None, None
    mp = data.get("model_path")
    fp = data.get("manifest_path")
    return (REPO / mp if mp else None), (REPO / fp if fp else None)


def write_eval_report(report_dir: Path, payload: dict[str, Any], stamp: str) -> tuple[Path, str]:
    """Immutable eval report: content-addressed name, created exclusively, then read-only."""
    body = json.dumps(payload, indent=2, sort_keys=True, default=str) + "\n"
    digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
    report_dir.mkdir(parents=True, exist_ok=True)
    compact = stamp.replace("-", "").replace(":", "")
    path = report_dir / f"eval-{compact}-{digest[:12]}.json"
    with open(path, "x", encoding="utf-8") as fh:  # never overwrite an existing report
        fh.write(body)
    try:
        os.chmod(path, 0o444)
    except OSError:
        pass
    print(f"wrote immutable eval report {path}", flush=True)
    return path, digest


def evaluate_holdout(X: list[list[float]], y: list[int], mids: list[float], times: list[int]) -> dict[str, Any]:
    """Back-compat wrapper: metrics only."""
    return evaluate_and_fit(X, y, mids, times)[0]


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
        "beats_champion": metrics.get("beats_champion"),
        "champion": (metrics.get("champion") or {}).get("tag") if metrics.get("champion") else None,
        "holdout_mode": metrics.get("holdout_mode"),
        "holdout_days": metrics.get("holdout_days"),
        "half_life_days": metrics.get("half_life_days"),
        "fee_rounding": "ceil_cent",
        "eval_report": metrics.get("eval_report"),
        "eval_report_sha256": metrics.get("eval_report_sha256"),
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
        "metrics": {
            **{k: v for k, v in metrics.items() if k not in ("champion", "sim_pnl_per_trade_ci", "sanity_reasons", "champion_reasons", "split")},
            **{k: gate[k] for k in ("beats_market", "publishable", "brier_margin", "logloss_margin", "synthetic")},
        },
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
    scrub_kalshi_env()
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
    ap.add_argument("--holdout-days", type=float, default=HOLDOUT_DAYS)
    ap.add_argument("--half-life-days", type=float, default=HALF_LIFE_DAYS)
    ap.add_argument("--champion-model", default=None, help="Champion edge_model.json (default: ml/published/latest.json)")
    ap.add_argument("--champion-manifest", default=None)
    ap.add_argument("--report-dir", default=None, help="Immutable eval reports (default: <out dir>/reports)")
    args = ap.parse_args(argv)
    out_path = Path(args.out)
    man_path = Path(args.manifest)
    report_dir = Path(args.report_dir) if args.report_dir else out_path.resolve().parent / "reports"
    synthetic = bool(args.fixture)
    series_list = [s.strip() for s in args.series.split(",") if s.strip()]
    for s in series_list:
        if s not in PRODUCT:
            print(f"unknown series {s}", flush=True)
            _unlink_outputs(out_path, man_path)
            return 2

    # Champion is read BEFORE anything is written (the default out path may be the champion's file).
    if args.champion_model:
        champ_paths = (Path(args.champion_model), Path(args.champion_manifest) if args.champion_manifest else None)
    elif args.fixture:
        champ_paths = (None, None)
    else:
        champ_paths = default_champion_paths()
    champion = load_champion(*champ_paths)
    print(f"champion: {champion.get('_tag') if champion else 'none'}", flush=True)

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

    metrics, model = evaluate_and_fit(
        X, y, mids, times, market_ids,
        holdout_days=args.holdout_days,
        half_life_days=args.half_life_days,
        champion=champion,
    )
    metrics["n_rows"] = len(X)
    metrics["n_samples"] = len(X)
    metrics["n_markets"] = n_markets
    metrics["synthetic"] = synthetic
    cc = champion_challenger(metrics)
    metrics["beats_champion"] = cc["beats_champion"]
    metrics["champion_reasons"] = cc["reasons"]
    print(json.dumps({k: metrics[k] for k in metrics if k != "split"}, indent=2, default=str), flush=True)
    gate = gates.publish_decision(metrics)
    metrics["beats_market"] = gate["beats_market"]
    metrics["publishable"] = gate["publishable"]
    print("gate:", json.dumps(gate, indent=2), flush=True)

    if model is None:
        model = fit_final(X, y, times)
    export(model, metrics, out_path)
    digest = hashlib.sha256(out_path.read_bytes()).hexdigest()
    trained_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    report_path, report_sha = write_eval_report(
        report_dir,
        {
            "kind": "kashi-edge-eval",
            "schema_version": 1,
            "trained_at": trained_at,
            "tag": model_tag(trained_at),
            "series": series_list,
            "synthetic": synthetic,
            "model_sha256": digest,
            "metrics": metrics,
            "gate": gate,
            "champion_challenger": cc,
            "config": {
                "holdout_days": args.holdout_days,
                "half_life_days": args.half_life_days,
                "fee_rate": FEE_RATE,
                "fee_rounding": "ceil_cent",
                "conf_margin": CONF_MARGIN,
                "days": args.days,
                "max_markets": args.max_markets,
            },
            "fetch": {"authenticated": False, **pace_stats()},
        },
        trained_at,
    )
    metrics["eval_report"] = report_path.name
    metrics["eval_report_sha256"] = report_sha
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
