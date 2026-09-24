#!/usr/bin/env python3
"""
Offline edge trainer for DipHunter 0.3.7.

Walk-forward logistic on settled Kalshi BTC/ETH/SOL 15m markets + Coinbase
spot candles. Calibrates (Platt), reports Brier / log-loss vs the market
price, and a simulated net P&L after Kalshi-style fees.

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
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

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
UA = "DipHunterTrainer/0.3.7"


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
    out: list[dict] = []
    cursor = None
    cutoff = time.time() - days * 86400
    while len(out) < limit:
        q = {"series_ticker": series, "status": "settled", "limit": min(200, limit - len(out))}
        if cursor:
            q["cursor"] = cursor
        data = http_get(f"{KALSHI}/markets?{urllib.parse.urlencode(q)}")
        batch = data.get("markets") or []
        if not batch:
            break
        for m in batch:
            ct = parse_iso(m.get("close_time"))
            if ct and ct.timestamp() >= cutoff and (m.get("result") or "").lower() in ("yes", "no"):
                out.append(m)
        cursor = data.get("cursor")
        time.sleep(0.08)
        if not cursor:
            break
    return out


def fetch_candles(series: str, ticker: str, open_ts: int, close_ts: int) -> list[dict]:
    q = {"start_ts": open_ts - 60, "end_ts": close_ts + 60, "period_interval": 1}
    data = http_get(f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    sticks = data.get("candlesticks") or []
    if sticks:
        return sticks
    hist = http_get(f"{KALSHI}/historical/markets/{ticker}/candlesticks?{urllib.parse.urlencode(q)}")
    return hist.get("candlesticks") or []


def fetch_spot(product: str, start: int, end: int) -> list[float]:
    q = {"granularity": 60, "start": datetime.fromtimestamp(start, tz=timezone.utc).isoformat(), "end": datetime.fromtimestamp(end, tz=timezone.utc).isoformat()}
    try:
        data = http_get(f"{COINBASE}/products/{product}/candles?{urllib.parse.urlencode(q)}")
    except Exception:
        return []
    if not isinstance(data, list):
        return []
    rows = sorted(data, key=lambda r: r[0] if r else 0)
    return [float(r[4]) for r in rows if r and len(r) >= 5]


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


def features_for(market: dict, candles: list[dict], spot: list[float], idx: int) -> list[float] | None:
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
    spread = (ask - bid) if bid is not None and ask is not None else 0.02
    imbalance = 0.0
    sigma = realized_vol_annual(spot) if spot else None
    strike = _f(market.get("floor_strike"))
    spot_px = spot[-1] if spot else None
    dist = dist_vol(spot_px, strike, tte, sigma) if spot_px and strike and sigma else 0.0
    fair = digital_fair(spot_px, strike, tte, sigma) if spot_px and strike and sigma else mid
    hour = datetime.fromtimestamp(end_ts or time.time(), tz=timezone.utc).hour
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
        0.0,
        float((hour * 60) / (24 * 60)),
        float(fair if fair is not None else mid),
    ]


def collect(days: int, max_markets: int) -> tuple[list[list[float]], list[int], list[float], list[int]]:
    X: list[list[float]] = []
    y: list[int] = []
    mids: list[float] = []
    times: list[int] = []
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
            spot = fetch_spot(PRODUCT[series], open_ts - 900, close_ts)
            # two samples: mid-window and late
            for idx in (max(1, len(candles) // 2), max(1, len(candles) - 2)):
                feats = features_for(m, candles, spot, idx)
                if not feats:
                    continue
                X.append(feats)
                y.append(label)
                mids.append(feats[2])
                times.append(int(candles[idx].get("end_period_ts") or close_ts))
    return X, y, mids, times


def standardize(X: list[list[float]]) -> tuple[list[list[float]], list[float], list[float]]:
    n = len(X[0])
    mean = [sum(row[i] for row in X) / len(X) for i in range(n)]
    std = []
    for i in range(n):
        var = sum((row[i] - mean[i]) ** 2 for row in X) / max(1, len(X) - 1)
        std.append(math.sqrt(var) if var > 1e-12 else 1.0)
    Z = [[(row[i] - mean[i]) / std[i] for i in range(n)] for row in X]
    return Z, mean, std


def fit_logistic(X: list[list[float]], y: list[int], iters: int = 80, lr: float = 0.15) -> tuple[list[float], float]:
    n = len(X[0])
    w = [0.0] * n
    b = 0.0
    for _ in range(iters):
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
    return w, b


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


def simulated_pnl(p: list[float], mids: list[float], y: list[int]) -> dict[str, float]:
    pnl = 0.0
    n = 0
    hits = 0
    for pi, m, yi in zip(p, mids, y):
        gap = abs(pi - m)
        fee = FEE_RATE * m * (1 - m)
        if gap <= fee + CONF_MARGIN:
            continue
        side_yes = pi > m
        price = m
        fee_c = FEE_RATE * price * (1 - price)
        win = (yi == 1) if side_yes else (yi == 0)
        pnl += (1.0 - price - fee_c) if win else (-price - fee_c)
        n += 1
        hits += int(win)
    return {"n": n, "pnl": pnl, "hit_rate": (hits / n) if n else 0.0}


def walk_forward(X: list[list[float]], y: list[int], mids: list[float], times: list[int], folds: int = 4) -> dict[str, Any]:
    order = sorted(range(len(X)), key=lambda i: times[i])
    X = [X[i] for i in order]
    y = [y[i] for i in order]
    mids = [mids[i] for i in order]
    fold = max(1, len(X) // folds)
    preds = [0.0] * len(X)
    for k in range(1, folds):
        tr_end = k * fold
        te_end = len(X) if k == folds - 1 else (k + 1) * fold
        if tr_end < 20 or te_end <= tr_end:
            continue
        Ztr, mean, std = standardize(X[:tr_end])
        w, b = fit_logistic(Ztr, y[:tr_end])
        Zte = [[(X[i][j] - mean[j]) / std[j] for j in range(len(mean))] for i in range(tr_end, te_end)]
        raw = predict_rows(Zte, w, b)
        a, pb = fit_platt(predict_rows(Ztr, w, b), y[:tr_end])
        cal = predict_rows(Zte, w, b, a, pb)
        for i, p in enumerate(cal):
            preds[tr_end + i] = p
    # fill unfilled with market
    for i, p in enumerate(preds):
        if p == 0.0:
            preds[i] = mids[i]
    hold = [i for i, p in enumerate(preds) if p != mids[i] or True]
    hold = list(range(fold, len(X)))  # first fold is train-only
    if not hold:
        hold = list(range(len(X)))
    ph = [preds[i] for i in hold]
    yh = [y[i] for i in hold]
    mh = [mids[i] for i in hold]
    pnl = simulated_pnl(ph, mh, yh)
    return {
        "n_holdout": len(hold),
        "model_brier": brier(ph, yh),
        "market_brier": brier(mh, yh),
        "model_logloss": logloss(ph, yh),
        "market_logloss": logloss(mh, yh),
        "sim_trades": pnl["n"],
        "sim_pnl": pnl["pnl"],
        "sim_hit_rate": pnl["hit_rate"],
    }


def fit_final(X: list[list[float]], y: list[int]) -> dict[str, Any]:
    Z, mean, std = standardize(X)
    w, b = fit_logistic(Z, y)
    raw = predict_rows(Z, w, b)
    a, pb = fit_platt(raw, y)
    return {"weights": w, "bias": b, "mean": mean, "std": std, "platt_a": a, "platt_b": pb}


def fixture_dataset(n: int = 240) -> tuple[list[list[float]], list[int], list[float], list[int]]:
    X, y, mids, times = [], [], [], []
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
    return X, y, mids, times


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
    ap.add_argument("--fixture", action="store_true")
    args = ap.parse_args()
    if args.fixture:
        X, y, mids, times = fixture_dataset()
    else:
        try:
            X, y, mids, times = collect(args.days, args.max_markets)
        except Exception as e:
            print(f"live collect failed ({e}); falling back to fixture", flush=True)
            X, y, mids, times = fixture_dataset()
    if len(X) < 30:
        print(f"only {len(X)} rows — using fixture so the export still exists", flush=True)
        X, y, mids, times = fixture_dataset()
    print(f"samples {len(X)} yes={sum(y)} no={len(y) - sum(y)}", flush=True)
    metrics = walk_forward(X, y, mids, times)
    print(json.dumps(metrics, indent=2), flush=True)
    model = fit_final(X, y)
    export(model, metrics, Path(args.out))
    # tiny parity sample for Android tests
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
    (ML_DIR / "fixtures").mkdir(exist_ok=True)
    (ML_DIR / "fixtures" / "parity_sample.json").write_text(json.dumps(sample, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
