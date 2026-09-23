#!/usr/bin/env python3
"""
Train a small MLP Dip Hunter model on Kalshi KXBTC15M / KXWTI15M settled markets.

Exports:
  - app/src/main/assets/diphunter.tflite
  - app/src/main/assets/feature_scaler.json  (also ml/feature_scaler.json)
  - ml/train_metrics.json
  - ml/fallback_weights.json  (dense layer weights for Kotlin sigmoid fallback)
  - ml/FEATURES.md
"""
from __future__ import annotations

import json
import math
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import numpy as np

# Lazy TF import after data prep so fetch failures are clear
REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
ASSETS = REPO / "app" / "src" / "main" / "assets"
BASE = "https://api.elections.kalshi.com/trade-api/v2"
SERIES = [("KXBTC15M", 0.0), ("KXWTI15M", 1.0)]
HIST_CUTOFF = datetime(2026, 7, 25, tzinfo=timezone.utc)
FEATURE_NAMES = [
    "mid_price",
    "volume_norm",
    "tte_frac",
    "volatility",
    "momentum",
    "mean_reversion",
    "series_id",
    "oi_norm",
]
# Softmax output order: [P(NO), P(YES)]
OUTPUT_ORDER = ["P_NO", "P_YES"]
SAMPLES_PER_MARKET = 4  # sample multiple minutes before close
MAX_MARKETS_PER_SERIES_LIVE = 100
MAX_MARKETS_PER_SERIES_HIST = 80
REQUEST_PAUSE_S = 0.08


def http_get(path: str, params: dict[str, Any] | None = None, retries: int = 5) -> dict:
    qs = f"?{urlencode(params)}" if params else ""
    url = f"{BASE}{path}{qs}"
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = Request(url, headers={"Accept": "application/json", "User-Agent": "DipHunterTrainer/0.1"})
            with urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except HTTPError as e:
            last_err = e
            if e.code in (429, 503, 502):
                time.sleep(min(2 ** attempt, 30))
                continue
            if e.code == 404:
                return {}
            raise
        except URLError as e:
            last_err = e
            time.sleep(min(2 ** attempt, 20))
    raise RuntimeError(f"GET failed {url}: {last_err}")


def parse_iso(ts: str | None) -> datetime | None:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except Exception:
        return None


def fnum(x: Any) -> float | None:
    if x is None:
        return None
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def candle_mid(c: dict) -> float | None:
    price = c.get("price") or {}
    # live: close_dollars; hist: close
    close = fnum(price.get("close_dollars") if "close_dollars" in price else price.get("close"))
    yb = c.get("yes_bid") or {}
    ya = c.get("yes_ask") or {}
    bid = fnum(yb.get("close_dollars") if "close_dollars" in yb else yb.get("close"))
    ask = fnum(ya.get("close_dollars") if "close_dollars" in ya else ya.get("close"))
    if bid is not None and ask is not None and 0 <= bid <= 1 and 0 <= ask <= 1:
        return (bid + ask) / 2.0
    if close is not None and 0 <= close <= 1:
        return close
    return None


def candle_high_low(c: dict) -> tuple[float | None, float | None]:
    price = c.get("price") or {}
    hi = fnum(price.get("high_dollars") if "high_dollars" in price else price.get("high"))
    lo = fnum(price.get("low_dollars") if "low_dollars" in price else price.get("low"))
    return hi, lo


def candle_volume(c: dict) -> float:
    v = fnum(c.get("volume_fp") if "volume_fp" in c else c.get("volume"))
    return v if v is not None else 0.0


def candle_oi(c: dict) -> float:
    v = fnum(c.get("open_interest_fp") if "open_interest_fp" in c else c.get("open_interest"))
    return v if v is not None else 0.0


def fetch_markets_paginated(
    series: str,
    *,
    historical: bool,
    limit_total: int,
    page_size: int = 200,
) -> list[dict]:
    out: list[dict] = []
    cursor: str | None = None
    path = "/historical/markets" if historical else "/markets"
    while len(out) < limit_total:
        params: dict[str, Any] = {
            "series_ticker": series,
            "limit": min(page_size, limit_total - len(out)),
        }
        if not historical:
            params["status"] = "settled"
        if cursor:
            params["cursor"] = cursor
        data = http_get(path, params)
        batch = data.get("markets") or []
        if not batch:
            break
        out.extend(batch)
        cursor = data.get("cursor")
        time.sleep(REQUEST_PAUSE_S)
        if not cursor:
            break
    return out[:limit_total]


def fetch_candles(series: str, ticker: str, start_ts: int, end_ts: int, historical: bool) -> list[dict]:
    params = {"start_ts": start_ts, "end_ts": end_ts, "period_interval": 1}
    if historical:
        data = http_get(f"/historical/markets/{ticker}/candlesticks", params)
    else:
        data = http_get(f"/series/{series}/markets/{ticker}/candlesticks", params)
    if not data.get("candlesticks"):
        # Fallback: try the other endpoint
        alt = http_get(f"/series/{series}/markets/{ticker}/candlesticks", params)
        return alt.get("candlesticks") or []
    return data.get("candlesticks") or []


def build_features_from_window(
    candles: list[dict],
    idx: int,
    close_ts: int,
    series_id: float,
) -> np.ndarray | None:
    """Build 8-feature vector at candle index idx using history up to idx inclusive."""
    if idx < 0 or idx >= len(candles):
        return None
    window = candles[max(0, idx - 7) : idx + 1]
    mids = [candle_mid(c) for c in window]
    mids = [m for m in mids if m is not None]
    if not mids:
        return None
    mid = mids[-1]
    # cumulative volume through window
    vol = sum(candle_volume(c) for c in window)
    end_ts = int(candles[idx].get("end_period_ts") or close_ts)
    secs_to_expiry = max(0.0, float(close_ts - end_ts))
    tte_frac = min(1.0, max(0.0, secs_to_expiry / 900.0))

    hi, lo = candle_high_low(candles[idx])
    if hi is not None and lo is not None:
        volatility = max(0.0, hi - lo)
    elif len(mids) >= 2:
        volatility = float(np.std(mids))
    else:
        volatility = 0.05

    if len(mids) >= 2:
        momentum = mids[-1] - mids[0]
    else:
        momentum = 0.0

    mean_reversion = 0.5 - mid
    volume_norm = math.log1p(vol) / math.log1p(1e6)
    oi = candle_oi(candles[idx])
    oi_norm = math.log1p(oi) / math.log1p(1e6)

    feats = np.array(
        [
            float(mid),
            float(volume_norm),
            float(tte_frac),
            float(min(volatility, 1.0)),
            float(np.clip(momentum, -1.0, 1.0)),
            float(mean_reversion),
            float(series_id),
            float(oi_norm),
        ],
        dtype=np.float32,
    )
    return feats


def sample_market(market: dict, series: str, series_id: float, historical: bool) -> list[tuple[np.ndarray, int]]:
    result = (market.get("result") or "").lower()
    if result not in ("yes", "no"):
        return []
    label = 1 if result == "yes" else 0
    close_dt = parse_iso(market.get("close_time"))
    open_dt = parse_iso(market.get("open_time"))
    if close_dt is None:
        return []
    close_ts = int(close_dt.timestamp())
    open_ts = int(open_dt.timestamp()) if open_dt else close_ts - 900
    ticker = market["ticker"]
    try:
        candles = fetch_candles(series, ticker, open_ts - 60, close_ts + 60, historical=historical)
    except Exception as e:
        print(f"  skip candles {ticker}: {e}", flush=True)
        return []
    time.sleep(REQUEST_PAUSE_S)
    if len(candles) < 2:
        return []
    # Prefer last N candles before close; sample evenly
    n = len(candles)
    # skip very first candle (warmup) and last (often settled print)
    usable = list(range(1, max(2, n - 1)))
    if not usable:
        return []
    if len(usable) <= SAMPLES_PER_MARKET:
        idxs = usable
    else:
        step = len(usable) / SAMPLES_PER_MARKET
        idxs = [usable[int(i * step)] for i in range(SAMPLES_PER_MARKET)]
    rows: list[tuple[np.ndarray, int]] = []
    for idx in idxs:
        feats = build_features_from_window(candles, idx, close_ts, series_id)
        if feats is not None:
            rows.append((feats, label))
    return rows


def collect_dataset() -> tuple[np.ndarray, np.ndarray, dict]:
    X_list: list[np.ndarray] = []
    y_list: list[int] = []
    stats = {"markets_live": 0, "markets_hist": 0, "samples_by_series": {}, "yes": 0, "no": 0}

    for series, series_id in SERIES:
        print(f"=== Fetching settled live markets for {series}", flush=True)
        live = fetch_markets_paginated(series, historical=False, limit_total=MAX_MARKETS_PER_SERIES_LIVE)
        print(f"  live settled: {len(live)}", flush=True)
        print(f"=== Fetching historical markets for {series}", flush=True)
        hist = fetch_markets_paginated(series, historical=True, limit_total=MAX_MARKETS_PER_SERIES_HIST)
        print(f"  historical: {len(hist)}", flush=True)

        # Deduplicate by ticker (prefer live)
        seen: set[str] = set()
        combined: list[tuple[dict, bool]] = []
        for m in live:
            t = m.get("ticker")
            if t and t not in seen:
                seen.add(t)
                combined.append((m, False))
        for m in hist:
            t = m.get("ticker")
            if t and t not in seen:
                # Only use hist if before cutoff or missing from live
                ct = parse_iso(m.get("close_time"))
                if ct and ct >= HIST_CUTOFF and any(x.get("ticker") == t for x in live):
                    continue
                seen.add(t)
                combined.append((m, True))

        stats["samples_by_series"][series] = 0
        for i, (m, is_hist) in enumerate(combined):
            if i % 25 == 0:
                print(f"  [{series}] market {i+1}/{len(combined)} ticker={m.get('ticker')} hist={is_hist}", flush=True)
            rows = sample_market(m, series, series_id, historical=is_hist)
            if is_hist:
                stats["markets_hist"] += 1
            else:
                stats["markets_live"] += 1
            for feats, label in rows:
                X_list.append(feats)
                y_list.append(label)
                stats["samples_by_series"][series] += 1
                if label == 1:
                    stats["yes"] += 1
                else:
                    stats["no"] += 1

    if not X_list:
        raise RuntimeError("No training samples collected from Kalshi API")
    X = np.stack(X_list).astype(np.float32)
    y = np.array(y_list, dtype=np.int32)
    return X, y, stats


def train_and_export(X: np.ndarray, y: np.ndarray, data_stats: dict) -> dict:
    import tensorflow as tf
    from tensorflow import keras

    # Standardize
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)
    Xz = (X - mean) / std

    # One-hot for [P(NO), P(YES)]
    y_oh = np.zeros((len(y), 2), dtype=np.float32)
    y_oh[np.arange(len(y)), y] = 1.0  # index 0 = NO (label0), index 1 = YES (label1)
    # Wait: label 1 = yes → should set index 1. label 0 = no → index 0.
    # y_oh[i, y[i]] = 1 means label 0 → col0 (NO), label 1 → col1 (YES). Correct.

    n = len(X)
    rng = np.random.default_rng(42)
    idx = rng.permutation(n)
    split = max(1, int(n * 0.85)) if n >= 20 else max(1, n - max(1, n // 5))
    if n < 10:
        train_idx, val_idx = idx, idx[: max(1, n // 5)]
    else:
        train_idx, val_idx = idx[:split], idx[split:]
        if len(val_idx) == 0:
            val_idx = idx[-max(1, n // 10) :]

    Xtr, ytr = Xz[train_idx], y_oh[train_idx]
    Xva, yva = Xz[val_idx], y_oh[val_idx]

    model = keras.Sequential(
        [
            keras.layers.Input(shape=(8,), name="features"),
            keras.layers.Dense(32, activation="relu", name="dense1"),
            keras.layers.Dense(16, activation="relu", name="dense2"),
            keras.layers.Dense(2, activation="softmax", name="probs"),
        ]
    )
    model.compile(optimizer=keras.optimizers.Adam(1e-3), loss="categorical_crossentropy", metrics=["accuracy"])

    callbacks = []
    if len(val_idx) >= 5:
        callbacks.append(
            keras.callbacks.EarlyStopping(monitor="val_loss", patience=8, restore_best_weights=True)
        )

    history = model.fit(
        Xtr,
        ytr,
        validation_data=(Xva, yva) if len(val_idx) >= 2 else None,
        epochs=80 if n >= 50 else 40,
        batch_size=min(64, max(8, n // 4)),
        verbose=1,
        callbacks=callbacks,
    )

    # Metrics on full set + val
    pred = model.predict(Xz, verbose=0)
    pred_yes = pred[:, 1]
    pred_cls = (pred_yes >= 0.5).astype(np.int32)
    acc = float((pred_cls == y).mean())
    # AUC if both classes present
    auc = None
    try:
        from sklearn.metrics import roc_auc_score

        if len(np.unique(y)) == 2:
            auc = float(roc_auc_score(y, pred_yes))
    except Exception:
        # Manual AUC via ranks if sklearn missing
        if len(np.unique(y)) == 2:
            pos = pred_yes[y == 1]
            neg = pred_yes[y == 0]
            if len(pos) and len(neg):
                # Mann–Whitney
                correct = 0.0
                for p in pos:
                    correct += np.sum(p > neg) + 0.5 * np.sum(p == neg)
                auc = float(correct / (len(pos) * len(neg)))

    val_acc = None
    if len(val_idx) >= 2:
        pva = model.predict(Xva, verbose=0)
        val_acc = float(((pva[:, 1] >= 0.5).astype(np.int32) == y[val_idx]).mean())

    ML_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)

    scaler = {
        "feature_names": FEATURE_NAMES,
        "mean": mean.tolist(),
        "std": std.tolist(),
        "output_order": OUTPUT_ORDER,
        "note": "Apply (x - mean) / std before TFLite. Softmax outputs [P(NO), P(YES)].",
    }
    (ML_DIR / "feature_scaler.json").write_text(json.dumps(scaler, indent=2))
    (ASSETS / "feature_scaler.json").write_text(json.dumps(scaler, indent=2))

    # TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = []
    tflite_model = converter.convert()
    tflite_path = ASSETS / "diphunter.tflite"
    tflite_path.write_bytes(tflite_model)
    (ML_DIR / "diphunter.tflite").write_bytes(tflite_model)

    # Fallback weights for Kotlin (flatten dense layers)
    weights_export = {"layers": [], "output_order": OUTPUT_ORDER, "scaler_mean": mean.tolist(), "scaler_std": std.tolist()}
    for layer in model.layers:
        if not isinstance(layer, keras.layers.Dense):
            continue
        w, b = layer.get_weights()
        weights_export["layers"].append(
            {
                "name": layer.name,
                "activation": layer.activation.__name__ if hasattr(layer.activation, "__name__") else str(layer.activation),
                "kernel": w.tolist(),  # shape [in, out]
                "bias": b.tolist(),
            }
        )
    (ML_DIR / "fallback_weights.json").write_text(json.dumps(weights_export))
    (ASSETS / "fallback_weights.json").write_text(json.dumps(weights_export))

    metrics = {
        "samples": int(n),
        "train_samples": int(len(train_idx)),
        "val_samples": int(len(val_idx)),
        "accuracy": acc,
        "val_accuracy": val_acc,
        "auc": auc,
        "yes_count": int(data_stats.get("yes", 0)),
        "no_count": int(data_stats.get("no", 0)),
        "markets_live": int(data_stats.get("markets_live", 0)),
        "markets_hist": int(data_stats.get("markets_hist", 0)),
        "samples_by_series": data_stats.get("samples_by_series", {}),
        "architecture": "Input(8)->Dense(32,relu)->Dense(16,relu)->Dense(2,softmax)",
        "output_order": OUTPUT_ORDER,
        "feature_names": FEATURE_NAMES,
        "epochs_ran": len(history.history.get("loss", [])),
        "final_loss": float(history.history["loss"][-1]) if history.history.get("loss") else None,
        "trained_at": datetime.now(timezone.utc).isoformat(),
    }
    (ML_DIR / "train_metrics.json").write_text(json.dumps(metrics, indent=2))

    features_md = f"""# Dip Hunter TFLite Features

Fixed float32 input order (length 8). Android and training must match.

| Idx | Name | Definition |
|-----|------|------------|
| 0 | mid_price | YES mid in [0,1] from candle bid/ask mid or close |
| 1 | volume_norm | log1p(volume) / log1p(1e6) over recent candle window |
| 2 | tte_frac | seconds_to_expiry / 900, clipped to [0,1] |
| 3 | volatility | candle (high-low) or std of recent mids |
| 4 | momentum | mid_now - mid_at_window_start |
| 5 | mean_reversion | 0.5 - mid_price |
| 6 | series_id | 0 = KXBTC15M, 1 = KXWTI15M |
| 7 | oi_norm | log1p(open_interest) / log1p(1e6) |

Standardization: `(x - mean) / std` using `feature_scaler.json`.

## Model

- Framework: TensorFlow Lite
- Architecture: `{metrics["architecture"]}`
- Output softmax order: **[P(NO), P(YES)]** (index 0 = NO, index 1 = YES)
- Trained on Kalshi settled markets + 1-minute candlesticks (live + historical)

## Labels

- `1` if market `result == yes`, else `0`
"""
    (ML_DIR / "FEATURES.md").write_text(features_md)
    print(json.dumps(metrics, indent=2), flush=True)
    return metrics


def main() -> int:
    print("Collecting Kalshi training data...", flush=True)
    X, y, stats = collect_dataset()
    print(f"Collected X={X.shape} yes={stats['yes']} no={stats['no']}", flush=True)
    metrics = train_and_export(X, y, stats)
    print(f"Done. samples={metrics['samples']} accuracy={metrics['accuracy']}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
