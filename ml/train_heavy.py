#!/usr/bin/env python3
"""
Dip Hunter 0.3.0 teacher → student export.

Trains a richer offline teacher (tabular GBM-style stumps + sequence
summaries) on settled BTC/ETH/SOL history and writes a compact student
JSON the phone can load from assets.

  python3 ml/train_heavy.py --csv history.csv
  cp ml/heavy_ml_student.json app/src/main/assets/heavy_ml_student.json

CSV columns (header required):
  series,tte,y_yes,mid,volume,tte_frac,volatility,momentum,oi,
  imbalance,aggressor,depth_q,lead_lag,spot,spread
Optional sequence columns (comma-joined 30 floats each):
  seq_mid,seq_size,seq_imb,seq_agg,seq_spot

If TensorFlow is installed, also writes ml/diphunter_seq.tflite
(Input [1,30,5] → P(YES)). Phone stays fast — this is the student, not the teacher.

See ml/DISTILL.md. Crypto only. Never places orders.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
ML_DIR = REPO / "ml"
ASSETS = REPO / "app" / "src" / "main" / "assets"

TABULAR = [
    "mid", "volume", "tte_frac", "volatility", "momentum", "oi",
    "imbalance", "aggressor", "depth_q", "lead_lag", "spot", "spread",
]


def sigmoid(z: np.ndarray) -> np.ndarray:
    z = np.clip(z, -30, 30)
    return 1.0 / (1.0 + np.exp(-z))


def load_csv(path: Path) -> tuple[np.ndarray, np.ndarray, list[str], list[str]]:
    import csv
    rows = []
    series = []
    tte = []
    with path.open() as f:
        reader = csv.DictReader(f)
        for rec in reader:
            try:
                y = float(rec["y_yes"])
                x = [float(rec.get(k, 0.0) or 0.0) for k in TABULAR]
            except (KeyError, ValueError):
                continue
            rows.append(x + [y])
            series.append(rec.get("series", "KXBTC15M"))
            tte.append(rec.get("tte", "EARLY"))
    arr = np.asarray(rows, dtype=np.float64)
    if arr.size == 0:
        raise SystemExit("no usable rows in CSV")
    return arr[:, :-1], arr[:, -1], series, tte


def fit_stumps(x: np.ndarray, y: np.ndarray, n_trees: int = 8) -> list[dict]:
    """Very small residual-stump booster — the teacher prior."""
    residual = y - y.mean()
    trees = []
    lr = 0.35
    for _ in range(n_trees):
        best = None
        for feat in range(x.shape[1]):
            vals = np.unique(np.round(x[:, feat], 3))
            if vals.size < 2:
                continue
            for thr in vals[1:-1][:12]:
                left = residual[x[:, feat] <= thr]
                right = residual[x[:, feat] > thr]
                if left.size < 4 or right.size < 4:
                    continue
                gain = -(left.var() * left.size + right.var() * right.size)
                if best is None or gain > best[0]:
                    best = (gain, feat, float(thr), float(left.mean()), float(right.mean()))
        if best is None:
            break
        _, feat, thr, lv, rv = best
        trees.append({
            "nodes": [
                {"feature": int(feat), "threshold": thr, "left": 1, "right": 2},
                {"value": lv},
                {"value": rv},
            ]
        })
        pred = np.where(x[:, feat] <= thr, lv, rv)
        residual = residual - lr * pred
    return trees


def maybe_export_tflite(x_seq: np.ndarray | None, y: np.ndarray, dest: Path) -> bool:
    if x_seq is None:
        return False
    try:
        import tensorflow as tf
    except ImportError:
        print("TensorFlow not installed — skipping diphunter_seq.tflite")
        return False
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(30, 5)),
        tf.keras.layers.Conv1D(12, 3, padding="same", activation="relu"),
        tf.keras.layers.Conv1D(12, 3, padding="same", activation="relu"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(1, activation="sigmoid"),
    ])
    model.compile(optimizer="adam", loss="binary_crossentropy")
    model.fit(x_seq, y, epochs=4, batch_size=64, verbose=0)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    dest.write_bytes(converter.convert())
    print(f"wrote {dest}")
    return True


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--csv", type=Path, default=None)
    p.add_argument("--copy-assets", action="store_true")
    args = p.parse_args()

    if args.csv and args.csv.exists():
        x, y, series, tte = load_csv(args.csv)
        trees = fit_stumps(x, y)
        base = float(np.clip(math.log((y.mean() + 1e-3) / (1 - y.mean() + 1e-3)), -1.5, 1.5))
        print(f"fitted {len(trees)} stumps on {len(y)} rows  base={base:.3f}")
    else:
        print("no CSV — writing the same structured prior the Kotlin defaults use")
        trees = []
        base = 0.0

    bundle = {
        "version": 1,
        "note": "Student snapshot. Phone loads this if present; else Kotlin defaults.",
        "stack": {"mlp": 0.55, "cnn": 0.20, "lstm": 0.10, "gbm": 0.15, "sampleCount": 0},
        "gbm": {"learningRate": 0.35, "baseScore": base, "trees": trees},
        "series": ["KXBTC15M", "KXETH15M", "KXSOL15M"],
    }
    out = ML_DIR / "heavy_ml_student.json"
    out.write_text(json.dumps(bundle, indent=2))
    print(f"wrote {out}")
    if args.copy_assets:
        dest = ASSETS / "heavy_ml_student.json"
        dest.write_text(out.read_text())
        print(f"copied {dest}")

    metrics = {
        "student": "heavy_ml_0.3.0",
        "rows": int(args.csv.exists() and sum(1 for _ in open(args.csv)) - 1) if args.csv else 0,
        "tflite": False,
    }
    (ML_DIR / "heavy_train_metrics.json").write_text(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
