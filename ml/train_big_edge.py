#!/usr/bin/env python3
"""Train a phone-compatible 128-tree BTC/ETH/SOL research model.

Requires authentic settled-market rows. The exported model can be manually
imported, but its research manifest is intentionally ineligible for the app's
automatic release activation. Minute-close asks are a replay proxy, not fills.

    python3 ml/train_big_edge.py --csv history.csv
    python3 ml/train_big_edge.py --collect --days 30 --max-per-day 16

CSV columns: market_ticker,decision_ts,y_yes,yes_ask,no_ask and the exact
10 columns of train_edge.FEATURE_NAMES. decision_ts is UTC epoch seconds.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING
from pathlib import Path

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss

import train_edge as te

ROOT = Path(__file__).resolve().parents[1]
MIN_MARKETS = 300
MIN_DAYS = 12
FEATURES = te.FEATURE_NAMES


@dataclass(frozen=True)
class Row:
    ticker: str
    ts: int
    yes: int
    yes_ask: float
    no_ask: float
    x: tuple[float, ...]


def read_csv(path: Path) -> list[Row]:
    rows: list[Row] = []
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        required = {"market_ticker", "decision_ts", "y_yes", "yes_ask", "no_ask", *FEATURES}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"CSV missing: {', '.join(sorted(required - set(reader.fieldnames or [])))}")
        for line, rec in enumerate(reader, 2):
            try:
                ticker = rec["market_ticker"].strip()
                if not ticker.startswith(("KXBTC15M-", "KXETH15M-", "KXSOL15M-")):
                    raise ValueError("unknown series")
                ts = int(rec["decision_ts"])
                y = int(rec["y_yes"])
                ya, na = float(rec["yes_ask"]), float(rec["no_ask"])
                x = tuple(float(rec[k]) for k in FEATURES)
                if y not in (0, 1) or not all(math.isfinite(v) for v in (ya, na, *x)):
                    raise ValueError("invalid label or non-finite feature")
                if not (0.001 <= ya <= 0.999 and 0.001 <= na <= 0.999):
                    raise ValueError("unusable buy ask")
                if not 0.0 <= x[FEATURES.index("market_mid")] <= 1.0:
                    raise ValueError("invalid market midpoint")
                rows.append(Row(ticker, ts, y, ya, na, x))
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"invalid CSV row {line}: {error}") from error
    if len(rows) != len({(r.ticker, r.ts) for r in rows}):
        raise ValueError("duplicate market/decision timestamp")
    labels: dict[str, int] = {}
    for row in rows:
        if row.ticker in labels and labels[row.ticker] != row.yes:
            raise ValueError(f"conflicting outcomes for {row.ticker}")
        labels[row.ticker] = row.yes
    return sorted(rows, key=lambda r: (r.ts, r.ticker))


def split_days(rows: list[Row]) -> tuple[list[Row], list[Row], list[Row]]:
    """Assign entire markets to chronological train/calibration/test blocks."""
    first = {ticker: min(r.ts for r in group) for ticker, group in group_markets(rows).items()}
    days = sorted({datetime.fromtimestamp(ts, timezone.utc).date() for ts in first.values()})
    if len(days) < MIN_DAYS or len(first) < MIN_MARKETS:
        raise ValueError(f"need >= {MIN_DAYS} UTC days and >= {MIN_MARKETS} independent markets; got {len(days)} days, {len(first)} markets")
    train_end = max(1, int(len(days) * 0.6))
    cal_end = max(train_end + 1, int(len(days) * 0.8))
    train_days, cal_days, test_days = set(days[:train_end]), set(days[train_end:cal_end]), set(days[cal_end:])
    by_day = (train_days, cal_days, test_days)
    parts = [[r for r in rows if datetime.fromtimestamp(first[r.ticker], timezone.utc).date() in dates]
             for dates in by_day]
    for name, part in zip(("train", "calibration", "test"), parts):
        if len({r.ticker for r in part}) < 30 or len({r.yes for r in part}) != 2:
            raise ValueError(f"{name} block has too few independent markets or only one outcome")
    if max(r.ts for r in parts[0]) >= min(r.ts for r in parts[1]) or max(r.ts for r in parts[1]) >= min(r.ts for r in parts[2]):
        raise ValueError("market observations cross split boundaries")
    return parts[0], parts[1], parts[2]


def group_markets(rows: list[Row]) -> dict[str, list[Row]]:
    result: dict[str, list[Row]] = defaultdict(list)
    for r in rows:
        result[r.ticker].append(r)
    return result


def price_and_fee(ask: float, cap: Decimal = Decimal("5.00")) -> tuple[int, Decimal]:
    """Non-direct-member equivalent order fee, with cent rounding."""
    p = Decimal(str(ask))
    for n in range(int(cap / p), 0, -1):
        position = p * n
        raw_fee = (Decimal("0.07") * n * p * (1 - p)).quantize(Decimal("0.000001"), rounding=ROUND_CEILING)
        cost = (position + raw_fee).quantize(Decimal("0.01"), rounding=ROUND_CEILING)
        if cost <= cap:
            return n, cost
    return 0, Decimal("0")


def paper_replay(rows: list[Row], probabilities: np.ndarray) -> dict[str, float]:
    decisions: dict[str, tuple[float, float, int]] = {}
    # One entry at the first qualifying decision per market. Absent markets
    # contribute zero when bootstrapping across the independent market set.
    for row, prob in zip(rows, probabilities):
        if row.ticker in decisions:
            continue
        candidates = []
        for side, chance, ask in ((1, float(prob), row.yes_ask), (0, 1 - float(prob), row.no_ask)):
            n, cost = price_and_fee(ask)
            if n:
                ev = chance - float(cost) / n
                if ev > 0.03:
                    pnl = n * int(row.yes == side) - float(cost)
                    candidates.append((ev, pnl))
        if candidates:
            _, pnl = max(candidates)
            decisions[row.ticker] = (pnl, float(prob), row.yes)
    tickers = list(group_markets(rows))
    per_market = np.array([decisions[t][0] if t in decisions else 0.0 for t in tickers], dtype=float)
    rng = np.random.default_rng(47)
    means = rng.choice(per_market, (1000, len(per_market)), replace=True).mean(axis=1)
    lo, hi = np.quantile(means, [0.025, 0.975])
    return {"paper_trades": len(decisions), "paper_pnl_usd": float(per_market.sum()),
            "paper_usd_per_market": float(per_market.mean()),
            "paper_ci95_usd_per_market_low": float(lo), "paper_ci95_usd_per_market_high": float(hi)}


def export_trees(model: GradientBoostingClassifier) -> tuple[float, list[dict]]:
    prior = float(model.init_.class_prior_[1])
    base = math.log(prior / (1 - prior))
    trees = []
    for estimator in model.estimators_[:, 0]:
        tree = estimator.tree_
        nodes = []
        for i in range(tree.node_count):
            if tree.children_left[i] < 0:
                nodes.append({"value": float(tree.value[i, 0, 0])})
            else:
                nodes.append({"feature": int(tree.feature[i]), "threshold": float(tree.threshold[i]),
                              "left": int(tree.children_left[i]), "right": int(tree.children_right[i])})
        trees.append({"nodes": nodes})
    return base, trees


def exported_logit(export: dict, features: tuple[float, ...]) -> float:
    """Pure Python implementation of the Android flat-tree contract."""
    z = float(export["base_score"])
    for tree in export["trees"]:
        nodes, at = tree["nodes"], 0
        for _ in range(len(nodes)):
            node = nodes[at]
            if "value" in node:
                z += export["learning_rate"] * node["value"]
                break
            at = node["left"] if features[node["feature"]] <= node["threshold"] else node["right"]
        else:
            raise ValueError("invalid exported tree")
    return z


def train(rows: list[Row]) -> tuple[dict, dict]:
    tr, cal, test = split_days(rows)
    x = lambda part: np.asarray([r.x for r in part], dtype=np.float32)
    y = lambda part: np.asarray([r.yes for r in part], dtype=int)
    model = GradientBoostingClassifier(n_estimators=128, learning_rate=0.05, max_depth=3,
                                       min_samples_leaf=20, subsample=0.8, random_state=47)
    model.fit(x(tr), y(tr))
    calibration = LogisticRegression(C=1.0, random_state=47)
    calibration.fit(model.decision_function(x(cal)).reshape(-1, 1), y(cal))
    a, b = float(calibration.coef_[0, 0]), float(calibration.intercept_[0])
    base, trees = export_trees(model)
    payload = {"version": 2, "kind": "gbdt", "feature_names": FEATURES,
               "weights": [0.0] * len(FEATURES), "bias": 0.0,
               "mean": [0.0] * len(FEATURES), "std": [1.0] * len(FEATURES),
               "base_score": base, "learning_rate": 0.05, "trees": trees,
               "platt_a": a, "platt_b": b, "blend_weight": 0.35,
               "fee_margin": 0.07, "confidence_margin": 0.03}
    # Check every held-out row against scikit-learn before writing a model.
    raw = model.decision_function(x(test))
    for row, expected in zip(test, raw):
        got = exported_logit(payload, row.x)
        if abs(got - float(expected)) > 1e-4:
            raise ValueError(f"tree export parity failed for {row.ticker}: {got} vs {expected}")
    p = calibration.predict_proba(raw.reshape(-1, 1))[:, 1].clip(0.02, 0.98)
    mid = np.array([r.x[FEATURES.index("market_mid")] for r in test]).clip(0.02, 0.98)
    metrics = {"train_markets": len(group_markets(tr)), "calibration_markets": len(group_markets(cal)),
               "test_markets": len(group_markets(test)), "test_rows": len(test),
               "model_brier": float(brier_score_loss(y(test), p)),
               "market_brier": float(brier_score_loss(y(test), mid)),
               "model_logloss": float(log_loss(y(test), p)),
               "market_logloss": float(log_loss(y(test), mid)),
               **paper_replay(test, p)}
    payload["metrics"] = {k: float(v) for k, v in metrics.items()}
    return payload, metrics


def collect(days: int, max_per_day: int) -> list[Row]:
    rows: list[Row] = []
    for series in te.SERIES:
        markets = te.fetch_settled(series, days, limit=max(3000, days * 110))
        by_day: dict[str, list[dict]] = defaultdict(list)
        for m in markets:
            close = te.parse_iso(m.get("close_time"))
            if close:
                by_day[close.astimezone(timezone.utc).date().isoformat()].append(m)
        for day, group in sorted(by_day.items()):
            group.sort(key=lambda m: m["close_time"])
            # Sample throughout each day, never selecting by its outcome.
            step = max(1, len(group) // max_per_day)
            for m in group[::step][:max_per_day]:
                close = te.parse_iso(m.get("close_time"))
                opened = te.parse_iso(m.get("open_time"))
                if not close or m.get("result") not in ("yes", "no"):
                    continue
                end = int(close.timestamp())
                start = int(opened.timestamp()) if opened else end - 900
                try:
                    candles = te.fetch_candles(series, m["ticker"], start, end)
                    spot = te.fetch_spot(te.PRODUCT[series], start - 900, end)
                except Exception as exc:
                    print(f"skip {m['ticker']}: {exc}", file=sys.stderr)
                    continue
                if not spot or len(candles) < 3:
                    continue
                # One minute at mid-window and one late; no final settlement bar.
                for idx in (max(1, len(candles) // 2), max(1, len(candles) - 2)):
                    candle = candles[idx]
                    ts = int(candle.get("end_period_ts") or 0)
                    if ts <= start or ts >= end:
                        continue
                    features = te.features_for(m, candles, spot, idx)
                    bid = te._f((candle.get("yes_bid") or {}).get("close_dollars"))
                    ask = te._f((candle.get("yes_ask") or {}).get("close_dollars"))
                    if features is None or bid is None or ask is None:
                        continue
                    no_ask = 1 - bid
                    if 0.001 <= ask <= 0.999 and 0.001 <= no_ask <= 0.999:
                        rows.append(Row(m["ticker"], ts, int(m["result"] == "yes"), ask, no_ask, tuple(features)))
                time.sleep(0.06)
        print(f"{series}: {len(markets)} markets found, {len(rows)} rows collected so far", flush=True)
    return rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    source = ap.add_mutually_exclusive_group(required=True)
    source.add_argument("--csv", type=Path)
    source.add_argument("--collect", action="store_true")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--max-per-day", type=int, default=16)
    ap.add_argument("--out", type=Path, default=ROOT / "ml" / "big_edge_model.json")
    ap.add_argument("--manifest", type=Path, default=ROOT / "ml" / "big_edge_manifest.json")
    args = ap.parse_args()
    try:
        if args.days < MIN_DAYS or args.max_per_day < 1:
            raise ValueError("insufficient day range or daily market sample")
        rows = collect(args.days, args.max_per_day) if args.collect else read_csv(args.csv)
        model, metrics = train(rows)
        manifest = {"version": "2", "trained_at": datetime.now(timezone.utc).isoformat(),
                    "n_samples": metrics["test_rows"], "n_holdout": metrics["test_rows"],
                    "model_brier": metrics["model_brier"], "market_brier": metrics["market_brier"],
                    "model_logloss": metrics["model_logloss"], "market_logloss": metrics["market_logloss"],
                    "sim_trades": metrics["paper_trades"], "sim_pnl": metrics["paper_pnl_usd"],
                    "model_asset": args.out.name, "data_source": "offline_gbdt_research",
                    "beats_market": False, "note": "Manual import only; minute-close asks are not verified fills."}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(model, separators=(",", ":")), encoding="utf-8")
        args.manifest.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print(json.dumps(metrics, indent=2))
        print(f"research model: {args.out}; auto-activation disabled")
        return 0
    except (OSError, ValueError) as exc:
        print(f"no model published: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
