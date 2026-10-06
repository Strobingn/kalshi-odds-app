#!/usr/bin/env python3
"""Favourite-longshot residual export for the app (schema_version 1).

For every settled 15m market we take ONE entry snapshot: the last 1m candle
that closed with >= ENTRY_MIN_LEFT_S (300 s) left, i.e. the earliest point the
app's fav15 / longshot policies may act. Both sides are scored at the price a
taker would actually pay:

    YES paid = yes_ask close        NO paid = 1 - yes_bid close

Empty books (ask >= 1 or bid <= 0) are skipped. Net P&L per contract uses a
$10 clip and the 7% taker fee rounded UP to the cent on the whole clip
(train_edge.kalshi_fee). Buckets on price paid; sub-10c are their own buckets.
Each bucket gets a seeded window-clustered bootstrap (all coins settling in
the same 15m window form one cluster; seed 0, 2000 resamples) 95% CI.

The app only treats a longshot bucket as validated if synthetic is false,
n >= min_n and net_ci_lo > 0 — otherwise "NO BET — longshot not validated".

Data: unauthenticated, paced public endpoints only (train_edge.http_get),
or a local candle cache (--candle-cache DIR with <ticker>.json files).
Never reads a Kalshi key. Candles carry no depth, so the CI is optimistic
about fills; the app's fill model still caps at visible depth at runtime.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_edge as te  # noqa: E402

SCHEMA_VERSION = 1
MIN_N = 300
ENTRY_MIN_LEFT_S = 300
CLIP_USD = 10.0
EDGES = [0.0, 0.05, 0.10, 0.15, 0.25, 0.50, 0.75, 0.85, 0.90, 0.95, 1.0]
DEFAULT_OUT = te.REPO / "app" / "src" / "main" / "assets" / "longshot_residual.json"


def _close(d: Any) -> float | None:
    if not isinstance(d, dict):
        return None
    return te._f(d.get("close_dollars") if "close_dollars" in d else d.get("close"))


def clip_contracts(price: float, clip: float = CLIP_USD) -> int:
    """Largest C with C*price + fee(C) <= clip (fee rounded up)."""
    if not (0.0 < price < 1.0):
        return 0
    c = int(clip / price)
    while c > 0 and c * price + te.kalshi_fee(price, c) > clip + 1e-9:
        c -= 1
    return c


def net_per_contract(price: float, won: bool) -> float | None:
    c = clip_contracts(price)
    if c <= 0:
        return None
    fee = te.kalshi_fee(price, c)
    return ((1.0 if won else 0.0) - price) - fee / c


def entry_quotes(candles: list[dict], close_ts: int, min_left_s: int = ENTRY_MIN_LEFT_S) -> tuple[float, float] | None:
    """(yes_paid, no_paid) at the last candle closed with >= min_left_s left; no look-ahead."""
    best = None
    for c in candles:
        end = int(c.get("end_period_ts") or 0)
        if end and close_ts - end >= min_left_s:
            if best is None or end > int(best.get("end_period_ts") or 0):
                best = c
    if best is None:
        return None
    bid = _close(best.get("yes_bid"))
    ask = _close(best.get("yes_ask"))
    if bid is None or ask is None or bid <= 0.0 or ask >= 1.0 or ask <= bid - 1e-9:
        return None
    return ask, 1.0 - bid


def build_rows(markets: list[dict], candles_for) -> list[dict]:
    rows = []
    for m in markets:
        result = (m.get("result") or "").lower()
        if result not in ("yes", "no"):
            continue
        close_dt = te.parse_iso(m.get("close_time"))
        if not close_dt:
            continue
        close_ts = int(close_dt.timestamp())
        candles = candles_for(m, close_ts)
        q = entry_quotes(candles or [], close_ts)
        if q is None:
            continue
        yes_paid, no_paid = q
        for side, paid, won in (("yes", yes_paid, result == "yes"), ("no", no_paid, result == "no")):
            net = net_per_contract(paid, won)
            if net is None:
                continue
            rows.append({"ticker": m["ticker"], "cluster": str(close_ts), "side": side,
                         "price": paid, "won": 1 if won else 0, "net": net})
    return rows


def bucketize(rows: list[dict], edges: list[float] = EDGES, min_n: int = MIN_N) -> list[dict]:
    out = []
    for lo, hi in zip(edges, edges[1:]):
        last = hi >= 1.0
        sel = [r for r in rows if r["price"] >= lo and (r["price"] < hi or (last and r["price"] <= hi))]
        n = len(sel)
        if n == 0:
            out.append({"lo": lo, "hi": hi, "n": 0, "price_mean": None, "win_rate": None, "residual": 0.0,
                        "net_per_contract": None, "net_ci_lo": None, "net_ci_hi": None, "clusters": 0,
                        "validated": False})
            continue
        pm = sum(r["price"] for r in sel) / n
        wr = sum(r["won"] for r in sel) / n
        ci = te.clustered_bootstrap_ci([(r["cluster"], r["net"]) for r in sel])
        out.append({
            "lo": lo, "hi": hi, "n": n,
            "price_mean": round(pm, 6), "win_rate": round(wr, 6), "residual": round(wr - pm, 6),
            "net_per_contract": round(ci["mean"], 6), "net_ci_lo": round(ci["lo"], 6), "net_ci_hi": round(ci["hi"], 6),
            "clusters": ci["clusters"],
            "validated": bool(n >= min_n and ci["lo"] > 0.0),
        })
    return out


def export(rows: list[dict], out: Path, *, synthetic: bool, source: str, series: list[str]) -> dict:
    trained_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = {
        "schema_version": SCHEMA_VERSION,
        "version": f"longshot-flb-v1-{trained_at[:10].replace('-', '')}",
        "trained_at": trained_at,
        "synthetic": synthetic,
        "source": source,
        "series": series,
        "min_n": MIN_N,
        "entry_min_left_s": ENTRY_MIN_LEFT_S,
        "clip_usd": CLIP_USD,
        "fee": {"rate": te.FEE_RATE, "rounding": "ceil_cent_per_clip"},
        "bootstrap": {"cluster": "settlement_window", "resamples": 2000, "seed": 0, "level": 0.95},
        "n_rows": len(rows),
        "n_markets": len({r["ticker"] for r in rows}),
        "buckets": bucketize(rows),
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    return payload


def main(argv: list[str] | None = None) -> int:
    te.scrub_kalshi_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--series", default=",".join(te.DEFAULT_SERIES))
    ap.add_argument("--days", type=int, default=14)
    ap.add_argument("--max-markets", type=int, default=3000, help="per series")
    ap.add_argument("--candle-cache", default=None)
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--fixture", action="store_true", help="Synthetic rows; exported synthetic:true (never validated)")
    args = ap.parse_args(argv)
    out = Path(args.out)
    series = [s.strip() for s in args.series.split(",") if s.strip()]
    if args.fixture:
        import random

        rnd = random.Random(0)
        rows = []
        for i in range(900):
            p = 0.02 + 0.96 * rnd.random()
            won = rnd.random() < p
            rows.append({"ticker": f"F{i}", "cluster": str(i // 3), "side": "yes", "price": p,
                         "won": int(won), "net": net_per_contract(p, won) or 0.0})
        payload = export(rows, out, synthetic=True, source="fixture", series=series)
        print(json.dumps({k: payload[k] for k in ("version", "n_rows", "synthetic")}), flush=True)
        return 0

    cache = Path(args.candle_cache) if args.candle_cache else None

    def candles_for(m: dict, close_ts: int) -> list[dict]:
        if cache is not None:
            f = cache / f"{m['ticker']}.json"
            if f.is_file():
                try:
                    return json.loads(f.read_text())
                except ValueError:
                    return []
            return []
        open_dt = te.parse_iso(m.get("open_time"))
        open_ts = int(open_dt.timestamp()) if open_dt else close_ts - 900
        try:
            return te.fetch_candles(m.get("series_ticker") or m["ticker"].split("-")[0], m["ticker"], open_ts, close_ts)
        except Exception as e:  # noqa: BLE001
            print(f"  skip candles {m.get('ticker')}: {e}", flush=True)
            return []

    markets: list[dict] = []
    for s in series:
        got = te.fetch_settled(s, args.days, args.max_markets)
        print(f"{s}: settled {len(got)}", flush=True)
        markets.extend(got)
    rows = build_rows(markets, candles_for)
    if len(rows) < 100:
        print(f"only {len(rows)} rows — refusing to export a real model", flush=True)
        return 2
    payload = export(rows, out, synthetic=False,
                     source="kalshi_public_settled_candles" + ("+local_cache" if cache else ""), series=series)
    for b in payload["buckets"]:
        print(f"  [{b['lo']:.2f},{b['hi']:.2f}) n={b['n']} res={b['residual']} net={b['net_per_contract']} "
              f"ci_lo={b['net_ci_lo']} validated={b['validated']}", flush=True)
    print("fetch:", json.dumps({"authenticated": False, **te.pace_stats()}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
