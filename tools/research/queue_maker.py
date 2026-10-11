#!/usr/bin/env python3
"""Queue-aware resting bids on Kalshi 15-minute BTC markets, with the REAL queue.

Two ideas under test (docs/queue-maker-2026-10-08.md)
----------------------------------------------------
docs/tape-study-2026-10-04.md found that a resting bid earns about +0.3c per
contract at the front of the queue and loses 0.9-1.5c behind the 2,000-10,000
contracts that normally sit at the best bid. That study ASSUMED the queue.
The cloud recorder now stores the displayed size at the best bid every second,
so this tool uses the real one.

  1. Thin queue.   Rest a bid only when few contracts are ahead of it.
  2. Favourite side. The same tape showed takers who buy the cheap side
     (under 50c) lose more than the fee, and takers who buy the dear side lose
     less than the fee. The maker opposite a cheap-side buyer holds the dear
     side. So: rest bids only on the side priced 50c or more.

Design, fixed on 2026-10-08 before any recording was scored
-----------------------------------------------------------
  decision times   every 30 s from 0:30 to 13:00 elapsed
  order            join the best bid on YES and on NO (two independent orders),
                   10 contracts, price 5-95c, only while bid < ask
  cancel           30 s after posting or 60 s before close, whichever is first
                   (60 s is shown as a second table)
  one at a time    per market and side: the next order waits for the cancel
  held to          settlement, maker fee 0 (verified for KXBTC15M); 0.0175 is
                   shown as a stress line
  fill model       maker_sim.conservative_fill: recorded trades on our side at
                   or through our price, after the post and up to the cancel;
                   the displayed size at our price must trade first; the queue
                   never shrinks from cancels ahead of us

Pre-registered cells (each one is a single cell, not picked after the fact):

  H1 thin queue        displayed size at our price <= 250 contracts, any price
  H2 favourite side    our price in [0.50, 0.90], any queue
  H3 favourite, early  H2 and posted in the first 5 minutes
  H4 favourite, thin   H2 and displayed size <= 2,000 contracts

Everything else in the report (queue buckets, price buckets, phases) is
context. Metric: cents per filled contract, with a day-block bootstrap CI
(whole UTC days resampled).

Decision rule (same bar as maker_sim.py / pair_maker.py): paper trade a cell
only if its 99% day-block CI is above 0 with at least 5 recorded days. Four
cells are tested, so one passing at 99% is still weak evidence on its own.

Python 3 stdlib only. Prints (and optionally writes) a Markdown report.
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import maker_sim as ms  # noqa: E402
from pair_maker import _bid  # noqa: E402

DEFAULT_MAKER_FEE = 0.0
STRESS_MAKER_FEE = 0.0175
CONTRACTS = 10
CANCEL_SECS = (30, 60)
DECISION_ELAPSED_S = tuple(range(30, 781, 30))
MIN_DAYS_FOR_RULE = 5
EPS = ms.EPS

QUEUE_EDGES = (100, 250, 500, 2000, 5000)
QUEUE_LABELS = ("0-100", "100-250", "250-500", "500-2,000", "2,000-5,000", "over 5,000")
PRICE_EDGES = (0.20, 0.35, 0.50, 0.65, 0.80)
PRICE_LABELS = ("5-20c", "20-35c", "35-50c", "50-65c", "65-80c", "80-95c")
PHASE_EDGES = (300, 600)
PHASE_LABELS = ("first 5 min", "minutes 5-10", "minutes 10-13")

FAV_LO, FAV_HI = 0.50, 0.90
THIN_QUEUE = 250.0
FAV_THIN_QUEUE = 2000.0
EARLY_S = 300


@dataclass
class Order:
    day: str
    ticker: str
    side: str
    price: float
    queue: float       # displayed contracts at our price when we posted
    elapsed_s: int
    cancel_s: int
    filled: int
    won: bool
    d_mid: float | None  # our side's mid at cancel − at post (negative = adverse)

    def pnl(self, maker_fee: float) -> float:
        if not self.filled:
            return 0.0
        return (self.filled if self.won else 0.0) - ms.fill_cost(self.filled, self.price, maker_fee)


def bucket(x: float, edges: tuple) -> int:
    for i, e in enumerate(edges):
        if x < e - EPS:
            return i
    return len(edges)


def queue_bucket(q: float) -> int:
    for i, e in enumerate(QUEUE_EDGES):
        if q <= e + EPS:
            return i
    return len(QUEUE_EDGES)


CELLS = {
    "H1 thin queue (≤ 250 ahead)": lambda o: o.queue <= THIN_QUEUE + EPS,
    "H2 favourite side (50–90¢)": lambda o: FAV_LO - EPS <= o.price <= FAV_HI + EPS,
    "H3 favourite, first 5 min": lambda o: FAV_LO - EPS <= o.price <= FAV_HI + EPS and o.elapsed_s <= EARLY_S,
    "H4 favourite, ≤ 2,000 ahead": lambda o: FAV_LO - EPS <= o.price <= FAV_HI + EPS and o.queue <= FAV_THIN_QUEUE + EPS,
}


def quote(snap: ms.Snap, side: str) -> tuple[float, float] | None:
    """(price, displayed queue) for joining the best bid on `side`, or None."""
    bid, qty = _bid(snap, side)
    _, _, ask, _ = snap.side(side)
    if bid is None or ask is None:
        return None
    bid, ask = round(bid, 4), round(ask, 4)
    if bid >= ask - EPS or bid < ms.MIN_PRICE - EPS or bid > ms.MAX_PRICE + EPS:
        return None
    return bid, float(qty or 0.0)


def simulate_market(m: ms.Market, cancel_s: int, out: list[Order]) -> None:
    stop = m.close_ms - ms.CANCEL_BEFORE_CLOSE_MS
    busy = {"YES": -1, "NO": -1}
    for e in DECISION_ELAPSED_S:
        t = m.open_ms + e * 1000
        if t >= stop:
            break
        snap = m.snap_at(t)
        if snap is None:
            continue
        for side in ("YES", "NO"):
            if t < busy[side]:
                continue
            q = quote(snap, side)
            if q is None:
                continue
            price, queue = q
            end = ms.cancel_time(t, cancel_s, m.close_ms)
            filled, _ = ms.conservative_fill(side, price, CONTRACTS, queue, m.trades, t, end, m._trade_ts)
            d_mid = None
            if filled:
                end_snap = m.snap_at(end)
                a, b = snap.mid(side), end_snap.mid(side) if end_snap is not None else None
                d_mid = (b - a) if a is not None and b is not None else None
            won = (m.result == "yes") == (side == "YES")
            out.append(Order(m.day, m.ticker, side, price, queue, e, cancel_s, filled, won, d_mid))
            busy[side] = end


def run(d: Path, series: str = "BTC") -> tuple[dict[int, list[Order]], dict]:
    stats = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0, "days_loaded": set()}
    orders: dict[int, list[Order]] = {t: [] for t in CANCEL_SECS}
    for m in ms.build_markets(d, series, stats):
        for t in CANCEL_SECS:
            simulate_market(m, t, orders[t])
    return orders, stats


def summarize(orders: list[Order], maker_fee: float, iters: int, seed: int) -> dict:
    n = len(orders)
    s: dict = {"orders": n}
    if not n:
        return s
    fills = [o for o in orders if o.filled]
    contracts = sum(o.filled for o in fills)
    s.update(fills=len(fills), contracts=contracts, queue=sorted(o.queue for o in orders)[n // 2])
    if not contracts:
        return s
    by_day: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    for o in fills:
        by_day[o.day][0] += o.pnl(maker_fee)
        by_day[o.day][1] += o.filled
    bd = {k: (v[0], v[1]) for k, v in by_day.items()}
    pnl = sum(v[0] for v in bd.values())
    s.update(
        pnl=pnl, cents=100.0 * pnl / contracts, days=len(bd),
        win=sum(o.filled for o in fills if o.won) / contracts,
        price=sum(o.price * o.filled for o in fills) / contracts,
        ci95=tuple(100.0 * x for x in ms.day_block_ci(bd, 0.95, iters, seed)),
        ci99=tuple(100.0 * x for x in ms.day_block_ci(bd, 0.99, iters, seed)),
        d_mid=ms._mean([o.d_mid for o in fills]),
        pos_days=sum(1 for v in bd.values() if v[0] > 0),
    )
    return s


HEAD = ("| cell | orders | fill % | contracts | avg price | win % | ¢ / contract | 95% day CI | 99% day CI "
        "| days + | Δmid ¢ |\n|---|---:|---:|---:|---:|---:|---:|---|---|---:|---:|")


def _ci(ci) -> str:
    if not ci or any(isinstance(x, float) and math.isnan(x) for x in ci):
        return "—"
    return f"[{ci[0]:+.2f}, {ci[1]:+.2f}]"


def row(label: str, s: dict) -> str:
    n = s.get("orders", 0)
    if not n or not s.get("contracts"):
        return f"| {label} | {n} | {'0' if n else '—'} | 0 | — | — | — | — | — | — | — |"
    d_mid = "—" if s["d_mid"] is None else f"{100.0 * s['d_mid']:+.2f}"
    return (f"| {label} | {n} | {100.0 * s['fills'] / n:.0f} | {s['contracts']} | {100.0 * s['price']:.0f}¢ "
            f"| {100.0 * s['win']:.1f} | {s['cents']:+.2f} | {_ci(s['ci95'])} | {_ci(s['ci99'])} "
            f"| {s['pos_days']}/{s['days']} | {d_mid} |")


def verdict(s: dict, days: int) -> str:
    if days < MIN_DAYS_FOR_RULE or not s.get("contracts"):
        return f"NOT EVALUABLE ({days} day(s); need ≥ {MIN_DAYS_FOR_RULE})"
    lo, hi = s["ci99"]
    if math.isnan(lo):
        return "NOT EVALUABLE (no CI)"
    if lo > 0:
        return "PASS — 99% CI above 0. Paper trade it next; it is not proven live"
    if hi < 0:
        return "FAIL — 99% CI below 0"
    return "NO EDGE SHOWN — 99% CI includes 0"


def report(orders: dict[int, list[Order]], stats: dict, maker_fee: float, iters: int = 2000, seed: int = 7) -> str:
    main = orders[CANCEL_SECS[0]]
    days = sorted({o.day for o in main})
    out = ["# Queue-aware resting bids — KXBTC15M, real displayed queue", ""]
    out.append(f"Markets: {stats['markets']} · days: {len(days)}"
               + (f" ({days[0]} → {days[-1]})" if days else "")
               + f" · trades without taker_side: {stats['trades_no_side']} of {stats['trade_rows']}")
    out.append(f"Join the best bid on YES and on NO every 30 s, {CONTRACTS} contracts, hold to settlement, "
               f"maker fee {maker_fee:g}. Conservative fills: the displayed size at our price trades first.")
    out.append("")
    out.append("*¢ / contract* is per filled contract. *days +* = days with positive P&L. "
               "*Δmid* = our side's mid at cancel minus at post, for filled orders (negative = picked off).")
    out.append("")
    for t in CANCEL_SECS:
        os_ = orders[t]
        tag = "" if t == CANCEL_SECS[0] else " (second look, not the pre-registered cancel)"
        out.append(f"## Pre-registered cells — cancel after {t} s{tag}")
        out.append("")
        out.append(HEAD)
        out.append(row("all orders", summarize(os_, maker_fee, iters, seed)))
        for label, pred in CELLS.items():
            out.append(row(label, summarize([o for o in os_ if pred(o)], maker_fee, iters, seed)))
        out.append("")
    out.append("## Decision (cancel 30 s)")
    out.append("")
    for label, pred in CELLS.items():
        s = summarize([o for o in main if pred(o)], maker_fee, iters, seed)
        out.append(f"- {label}: {verdict(s, len(days))}")
    out.append("")
    out.append(f"## Stress: maker fee {STRESS_MAKER_FEE:g} (cancel 30 s)")
    out.append("")
    out.append(HEAD)
    for label, pred in CELLS.items():
        out.append(row(label, summarize([o for o in main if pred(o)], STRESS_MAKER_FEE, iters, seed)))
    out.append("")
    out.append("## Context: by real queue ahead (cancel 30 s)")
    out.append("")
    out.append(HEAD)
    for i, lab in enumerate(QUEUE_LABELS):
        out.append(row(lab, summarize([o for o in main if queue_bucket(o.queue) == i], maker_fee, iters, seed)))
    out.append("")
    out.append("## Context: by our price (cancel 30 s)")
    out.append("")
    out.append(HEAD)
    for i, lab in enumerate(PRICE_LABELS):
        out.append(row(lab, summarize([o for o in main if bucket(o.price, PRICE_EDGES) == i], maker_fee, iters, seed)))
    out.append("")
    out.append("## Context: by time in the window (cancel 30 s)")
    out.append("")
    out.append(HEAD)
    for i, lab in enumerate(PHASE_LABELS):
        sel = [o for o in main if bucket(o.elapsed_s, PHASE_EDGES) == i]
        out.append(row(lab, summarize(sel, maker_fee, iters, seed)))
        fav = [o for o in sel if FAV_LO - EPS <= o.price <= FAV_HI + EPS]
        out.append(row(f"{lab}, favourite side", summarize(fav, maker_fee, iters, seed)))
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dir", required=True, help="directory with spot_/book_/trades_/settle_ recordings")
    ap.add_argument("--maker-fee", type=float, default=DEFAULT_MAKER_FEE)
    ap.add_argument("--out", default=None, help="write the Markdown report here")
    ap.add_argument("--series", default="BTC")
    ap.add_argument("--iters", type=int, default=2000, help="bootstrap iterations")
    args = ap.parse_args(argv)
    d = Path(args.dir)
    if not d.is_dir():
        ap.error(f"--dir {d} is not a directory")
    orders, stats = run(d, args.series)
    text = report(orders, stats, args.maker_fee, iters=args.iters)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
