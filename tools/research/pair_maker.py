#!/usr/bin/env python3
"""Two-sided resting bids ("pair maker") on Kalshi 15-minute BTC markets.

Idea under test
---------------
YES and NO on one market always pay exactly $1 between them. Kalshi charges no
maker fee on KXBTC15M (docs/kalshi-fees-and-incentives.md, fee probe
2026-10-07). So a resting YES bid at Py and a resting NO bid at Pn with
Py + Pn < $1 lock in (1 − Py − Pn) per pair if BOTH fill, with no forecast at
all. The risk is that only one side fills, and it fills because the price is
about to move against it (adverse selection). This tool measures whether the
locked pairs pay for the one-sided fills, on recorded data.

Input: the 1 s recordings (same format and loader as maker_sim.py /
recordings.py): spot_, book_, trades_, settle_ files per UTC day.

Pre-registered grid (fixed before any data was seen):

  rule      join     both bids at the best bid on their side (queue = displayed qty)
            improve  both bids 1¢ above the best bid (queue 0), each strictly under its ask
  lock      0.01 / 0.02 / 0.03   post only if 1 − Py − Pn ≥ lock
  cancel T  30 / 60 / 120 s after posting, or 60 s before close, whichever first
  unwind    hold      an unmatched leg is held to settlement (directional risk)
            complete  at cancel time, buy the missing contracts of the other
                      side at its ask (taker fee 0.07), so every fill ends as a
                      pair; if there is no ask, hold

Size: 10 contracts per leg. Decision times every 30 s from 1:00 to 13:00
elapsed. One episode (a pair of resting bids) at a time per market; the next
episode may start at the first decision time after the previous one ended.
Prices 5–95¢ per leg.

Fill model: maker_sim.conservative_fill only (recorded trades on our side at
or through our price, after the post time and up to the cancel time; the
displayed quantity at our price must trade first; queue never moves up from
cancels; trades with no taker_side never fill). There is no optimistic model
here on purpose: the whole question is whether we get filled on both sides.

Maker fee defaults to 0 (verified for KXBTC15M); every run also shows 0.0175
as a stress line. The completing buy always pays the 0.07 taker fee.

IS/OOS and decision rule (same protocol as maker_sim.py):
  ≥ 6 UTC days: the last third (at least 2) is OOS. The config is picked on IS
  P&L per episode (≥ 20 IS episodes with a fill) at the main maker fee, then
  evaluated on OOS. Consider paper trading only if the OOS 99% day-block CI
  of $ per posted episode excludes 0 with ≥ 5 OOS days. < 6 days: EXPLORATORY,
  full grid shown, no config picked, no claims.

Python 3 stdlib only. Prints (and optionally writes) a Markdown report.
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import maker_sim as ms  # noqa: E402

TAKER_FEE = 0.07
STRESS_MAKER_FEE = 0.0175
DEFAULT_MAKER_FEE = 0.0  # fee_probe.py 2026-10-07: KXBTC15M fee_type=quadratic, no maker fee

# --- pre-registered grid ------------------------------------------------------
RULES = ("join", "improve")
LOCKS = (0.01, 0.02, 0.03)
CANCEL_SECS = (30, 60, 120)
UNWINDS = ("hold", "complete")
CONTRACTS = 10
DECISION_ELAPSED_S = tuple(range(60, 781, 30))
CANCEL_BEFORE_CLOSE_MS = ms.CANCEL_BEFORE_CLOSE_MS
TICK = ms.TICK
MIN_PRICE = ms.MIN_PRICE
MAX_PRICE = ms.MAX_PRICE
MIN_IS_EPISODES = 20
MIN_DAYS_FOR_OOS = ms.MIN_DAYS_FOR_OOS
MIN_OOS_DAYS_FOR_RULE = ms.MIN_OOS_DAYS_FOR_RULE
EPS = ms.EPS


@dataclass(frozen=True)
class Config:
    rule: str
    lock: float
    cancel_s: int
    unwind: str

    def label(self) -> str:
        return f"{self.rule}/L{self.lock:.2f}/T{self.cancel_s}/{self.unwind}"


GRID = [Config(r, lk, t, u) for r in RULES for lk in LOCKS for t in CANCEL_SECS for u in UNWINDS]


def _bid(snap: ms.Snap, side: str) -> tuple[float | None, float | None]:
    """(best bid, displayed qty) on `side`; a missing NO/YES bid mirrors the other side's ask."""
    if side == "YES":
        if snap.yes_bid is not None:
            return snap.yes_bid, snap.yes_bid_qty
        return (1.0 - snap.no_ask, snap.no_ask_qty) if snap.no_ask is not None else (None, None)
    if snap.no_bid is not None:
        return snap.no_bid, snap.no_bid_qty
    return (1.0 - snap.yes_ask, snap.yes_ask_qty) if snap.yes_ask is not None else (None, None)


def pair_quote(snap: ms.Snap, rule: str, lock: float) -> tuple[float, float, float, float] | None:
    """(yes price, yes queue, no price, no queue) for a two-sided post, or None."""
    legs = []
    for side in ("YES", "NO"):
        bid, qty = _bid(snap, side)
        _, _, ask, _ = snap.side(side)
        if bid is None or ask is None:
            return None
        bid, ask = round(bid, 4), round(ask, 4)
        if rule == "join":
            p, q = bid, (qty or 0.0)
        elif rule == "improve":
            p, q = round(bid + TICK, 4), 0.0
        else:
            raise ValueError(rule)
        if p >= ask - EPS or p < MIN_PRICE - EPS or p > MAX_PRICE + EPS:
            return None
        legs.append((p, q))
    (py, qy), (pn, qn) = legs
    if 1.0 - py - pn < lock - EPS:
        return None
    return py, qy, pn, qn


@dataclass
class Episode:
    ticker: str
    day: str
    post_ms: int
    end_ms: int
    py: float
    pn: float
    fy: int
    fn: int
    pnl: float
    cost: float
    pairs: int
    completed: int          # contracts bought at the ask to complete the pair
    d_mid: float | None     # one-sided fill: filled side's mid at end − its mid at post


@dataclass
class Result:
    episodes: list[Episode] = field(default_factory=list)

    def filled(self) -> list[Episode]:
        return [e for e in self.episodes if e.fy or e.fn]


def settle_payout(fy: int, fn: int, result: str) -> float:
    return float(fy if result == "yes" else fn if result == "no" else 0)


def episode_pnl(fy: int, py: float, fn: int, pn: float, result: str, maker_fee: float,
                unwind: str, ask_at_end: dict[str, float | None]) -> tuple[float, float, int]:
    """(pnl, total cost, contracts bought to complete) for one episode held to settlement."""
    cost = ms.fill_cost(fy, py, maker_fee) + ms.fill_cost(fn, pn, maker_fee)
    completed = 0
    if unwind == "complete" and fy != fn:
        short = "NO" if fy > fn else "YES"
        ask = ask_at_end.get(short)
        if ask is not None and MIN_PRICE - EPS <= ask <= 1.0 - EPS:
            completed = abs(fy - fn)
            cost += ms.fill_cost(completed, ask, TAKER_FEE)
            if short == "NO":
                fn += completed
            else:
                fy += completed
    return settle_payout(fy, fn, result) - cost, cost, completed


def simulate_market(m: ms.Market, cfg: Config, maker_fee: float, res: Result) -> None:
    trade_ts = m._trade_ts
    stop = m.close_ms - CANCEL_BEFORE_CLOSE_MS
    busy_until = -1
    for e in DECISION_ELAPSED_S:
        t = m.open_ms + e * 1000
        if t >= stop or t < busy_until:
            continue
        snap = m.snap_at(t)
        if snap is None:
            continue
        q = pair_quote(snap, cfg.rule, cfg.lock)
        if q is None:
            continue
        py, qy, pn, qn = q
        end = ms.cancel_time(t, cfg.cancel_s, m.close_ms)
        fy, _ = ms.conservative_fill("YES", py, CONTRACTS, qy, m.trades, t, end, trade_ts)
        fn, _ = ms.conservative_fill("NO", pn, CONTRACTS, qn, m.trades, t, end, trade_ts)
        end_snap = m.snap_at(end)
        asks = {s: (end_snap.side(s)[2] if end_snap is not None else None) for s in ("YES", "NO")}
        pnl, cost, completed = episode_pnl(fy, py, fn, pn, m.result, maker_fee, cfg.unwind, asks)
        d_mid = None
        if (fy > 0) != (fn > 0) and end_snap is not None:
            side = "YES" if fy else "NO"
            a, b = snap.mid(side), end_snap.mid(side)
            d_mid = (b - a) if a is not None and b is not None else None
        res.episodes.append(Episode(m.ticker, m.day, t, end, py, pn, fy, fn, pnl, cost,
                                    min(fy, fn), completed, d_mid))
        busy_until = end


def run(d: Path, maker_fee: float, series: str = "BTC", grid: list[Config] | None = None) -> tuple[dict, dict]:
    """Returns (results[(cfg, rate)] -> Result, stats)."""
    grid = grid or GRID
    rates = sorted({maker_fee, STRESS_MAKER_FEE})
    stats = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0, "days_loaded": set()}
    results = {(c, r): Result() for c in grid for r in rates}
    for m in ms.build_markets(d, series, stats):
        for (c, r), res in results.items():
            simulate_market(m, c, r, res)
    return results, stats


# --- stats ----------------------------------------------------------------------

def summarize(res: Result, days: set[str] | None, iters: int, seed: int) -> dict:
    eps = [e for e in res.episodes if days is None or e.day in days]
    n = len(eps)
    s: dict = {"episodes": n}
    if not n:
        return s
    filled = [e for e in eps if e.fy or e.fn]
    both = [e for e in eps if e.fy and e.fn]
    one = [e for e in eps if (e.fy > 0) != (e.fn > 0)]
    by_day: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    for e in eps:
        by_day[e.day][0] += e.pnl
        by_day[e.day][1] += 1
    pd = {k: tuple(v) for k, v in by_day.items()}
    pnl = sum(e.pnl for e in eps)
    locked = sum(e.pairs * (1.0 - e.py - e.pn) for e in eps)
    s.update(
        filled=len(filled), both=len(both), one=len(one),
        pnl=pnl, per=pnl / n, locked=locked, rest=pnl - locked,
        completed=sum(e.completed for e in eps), days=len(pd),
        ci95=ms.day_block_ci(pd, 0.95, iters, seed), ci99=ms.day_block_ci(pd, 0.99, iters, seed),
        d_mid=ms._mean([e.d_mid for e in one]),
    )
    return s


def pick_is(results: dict, rate: float, is_days: set[str], iters: int, seed: int) -> tuple[Config | None, dict]:
    best, best_s = None, {}
    for (c, r), res in results.items():
        if r != rate:
            continue
        s = summarize(res, is_days, iters, seed)
        if s.get("filled", 0) < MIN_IS_EPISODES:
            continue
        if best is None or s["per"] > best_s["per"] + EPS:
            best, best_s = c, s
    return best, best_s


# --- report ---------------------------------------------------------------------

def _c(x, fmt="{:+.3f}"):
    return ms._c(x, fmt)


def row(label: str, s: dict) -> str:
    if not s.get("episodes"):
        return f"| {label} | 0 | — | — | — | — | — | — | — | — |"
    n = s["episodes"]
    return (f"| {label} | {n} | {s['filled'] / n:.0%} | {s['both'] / n:.0%} | {s['one'] / n:.0%} "
            f"| {_c(s['per'])} | {_c(s['locked'], '{:+.2f}')} | {_c(s['rest'], '{:+.2f}')} "
            f"| {ms._ci(s['ci99'])} | {_c(s['d_mid'])} |")


HEAD = ("| config | episodes | any fill | both legs | one leg | $/episode | locked $ | other $ "
        "| 99% day CI $/ep | one-leg Δmid |\n|---|---|---|---|---|---|---|---|---|---|")


def report(results: dict, stats: dict, maker_fee: float, iters: int = 2000, seed: int = 7) -> str:
    days = sorted({e.day for res in results.values() for e in res.episodes})
    out = ["# Pair maker (two-sided resting bids) — KXBTC15M", ""]
    out.append(f"Markets: {stats['markets']} · recording days: {len(stats.get('recording_days', []))} "
               f"· days with episodes: {len(days)} · trades without taker_side: {stats['trades_no_side']} "
               f"of {stats['trade_rows']}")
    out.append(f"Maker fee {maker_fee:g} (main), {STRESS_MAKER_FEE:g} (stress). Completing buys pay {TAKER_FEE:g}. "
               f"{CONTRACTS} contracts per leg. Conservative fills only.")
    out.append("")
    out.append("*locked $* = pairs × (1 − Py − Pn), the riskless part. *other $* = everything else "
               "(one-leg fills held or completed at the ask). *one-leg Δmid* < 0 means one-leg fills "
               "were adversely selected.")
    out.append("")
    if len(days) < MIN_DAYS_FOR_OOS:
        out.append(f"**EXPLORATORY** — {len(days)} day(s) < {MIN_DAYS_FOR_OOS}: full grid, no config picked, no claims.")
        out.append("")
        for rate in sorted({r for _, r in results}):
            out.append(f"## Maker fee {rate:g}")
            out.append("")
            out.append(HEAD)
            for (c, r), res in results.items():
                if r == rate:
                    out.append(row(c.label(), summarize(res, None, iters, seed)))
            out.append("")
        out.append("Decision: NOT EVALUABLE (too few days).")
        return "\n".join(out)
    n_oos = max(2, len(days) // 3)
    is_days, oos_days = set(days[:-n_oos]), set(days[-n_oos:])
    out.append(f"IS days: {len(is_days)} ({min(is_days)} → {max(is_days)}) · "
               f"OOS days: {len(oos_days)} ({min(oos_days)} → {max(oos_days)})")
    out.append("")
    out.append(f"## IS grid at maker fee {maker_fee:g}")
    out.append("")
    out.append(HEAD)
    for (c, r), res in results.items():
        if r == maker_fee:
            out.append(row(c.label(), summarize(res, is_days, iters, seed)))
    out.append("")
    cfg, _ = pick_is(results, maker_fee, is_days, iters, seed)
    if cfg is None:
        out.append(f"No config reached {MIN_IS_EPISODES} IS episodes with a fill. Decision: NOT EVALUABLE.")
        return "\n".join(out)
    out.append(f"## Picked on IS: `{cfg.label()}` — OOS")
    out.append("")
    out.append(HEAD)
    oos = {}
    for rate in sorted({r for _, r in results}):
        oos[rate] = summarize(results[(cfg, rate)], oos_days, iters, seed)
        out.append(row(f"{cfg.label()} @ maker {rate:g}", oos[rate]))
    out.append("")
    s = oos[maker_fee]
    lo, hi = s.get("ci99", (float("nan"), float("nan")))
    if len(oos_days) < MIN_OOS_DAYS_FOR_RULE or s.get("filled", 0) == 0 or math.isnan(lo):
        verdict = f"NOT EVALUABLE ({len(oos_days)} OOS day(s); need ≥ {MIN_OOS_DAYS_FOR_RULE})."
    elif lo > 0:
        verdict = "PASS — OOS 99% CI of $/episode is above 0. Paper trade it next; it is not proven live."
    elif hi < 0:
        verdict = "FAIL — OOS 99% CI of $/episode is below 0."
    else:
        verdict = "NO EDGE SHOWN — OOS 99% CI of $/episode includes 0."
    out.append(f"Decision: {verdict}")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dir", required=True, help="directory with spot_/book_/trades_/settle_ recordings")
    ap.add_argument("--maker-fee", type=float, default=DEFAULT_MAKER_FEE,
                    help=f"maker fee rate (default {DEFAULT_MAKER_FEE}, verified for KXBTC15M)")
    ap.add_argument("--out", default=None, help="write the Markdown report here")
    ap.add_argument("--series", default="BTC", help="ticker/product substring filter (default BTC)")
    ap.add_argument("--iters", type=int, default=2000, help="bootstrap iterations")
    args = ap.parse_args(argv)
    if not (0.0 <= args.maker_fee <= 0.25):
        ap.error("--maker-fee must be in [0, 0.25]")
    d = Path(args.dir)
    if not d.is_dir():
        ap.error(f"--dir {d} is not a directory")
    results, stats = run(d, args.maker_fee, args.series)
    text = report(results, stats, args.maker_fee, iters=args.iters)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
