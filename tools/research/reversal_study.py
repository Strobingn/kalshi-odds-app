#!/usr/bin/env python3
"""Window-to-window reversal study for Kalshi KXBTC15M.

Claim under test
----------------
A 2026 cross-market study (arXiv 2608.21888) reports that at 15-minute
horizons crypto returns reverse in SIGN: betting against the previous
candle's direction wins slightly more than half the time, and the flip rate
rises with the size of the previous move (about 50.2% in the smallest-move
decile to 53.0% in the largest). KXBTC15M windows are back to back: the next
window's strike IS the previous window's 60 s settlement average. So the
claim maps exactly onto "does window n+1 settle the other way from window n?"

That alone is not money. The question that matters is whether Kalshi's own
price at the start of window n+1 already prices the reversal. This study
measures both:

  1. flip rate by size of the previous window's move (strike → settlement
     average, in basis points), with 95% Wilson intervals;
  2. the market-implied flip probability one minute into window n+1 (mid of
     the side against the previous result), and the EV of actually buying
     that side at the ask with the taker fee, or resting 1¢ above the bid
     with no fee (UPPER BOUND: assumes the bid fills, ignores adverse
     selection and queue).

Pre-registered (fixed before any data was seen):

  move buckets  |move| in bps: [0,5) [5,10) [10,20) [20,40) [40,∞)
  entry         the first 1-minute candle that closes ≥ 60 s after the open
  rule          bet against the previous result when |move| ≥ 20 bps
  decision      OOS = last third of UTC days (≥ 2); the rule's OOS 99%
                day-block CI of $ per contract at the ask (taker fee
                included) must exclude 0 with ≥ 5 OOS days. Maker numbers
                are context only and never count.

Data: Kalshi's public API (no key). `pull` fetches settled markets and
their 1-minute candles into a JSON cache; `report` reads the cache.

  python3 tools/research/reversal_study.py pull --days 90 --cache rev.json
  python3 tools/research/reversal_study.py report --cache rev.json --out rev.md
  python3 tools/research/test_reversal_study.py

Research only: it places no orders.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "ml"))
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import fee_per_contract  # noqa: E402

SERIES = "KXBTC15M"
TAKER_FEE = 0.07
STAKE_USD = 5.0
BUCKETS_BPS = (0.0, 5.0, 10.0, 20.0, 40.0, math.inf)
RULE_MIN_BPS = 20.0
ENTRY_AFTER_S = 60
WINDOW_S = 900
CONTIGUOUS_TOL_S = 5
MIN_DAYS_FOR_OOS = 6
MIN_OOS_DAYS_FOR_RULE = 5
TICK = 0.01
EPS = 1e-9


@dataclass
class Window:
    ticker: str
    open_ts: int
    close_ts: int
    strike: float
    settle: float | None     # 60 s settlement average (expiration_value), if Kalshi reports it
    result: str              # "yes" | "no"
    yes_bid: float | None    # quote one minute in
    yes_ask: float | None

    @property
    def day(self) -> str:
        return datetime.fromtimestamp(self.close_ts, tz=timezone.utc).strftime("%Y-%m-%d")


@dataclass
class Pair:
    prev: Window
    nxt: Window
    move_bps: float          # previous window: settlement average vs its strike
    flip: bool               # next window settled the other way
    against: str             # "YES" | "NO": the side that bets on a flip


# --- data ---------------------------------------------------------------------

def _num(x) -> float | None:
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def window_from_market(m: dict, candles: list[dict]) -> Window | None:
    import train_edge as te  # noqa: E402  (network helpers; imported lazily for tests)
    ot, ct = te.parse_iso(m.get("open_time")), te.parse_iso(m.get("close_time"))
    strike = _num(m.get("floor_strike"))
    res = (m.get("result") or "").lower()
    if not ot or not ct or strike is None or res not in ("yes", "no"):
        return None
    open_ts, close_ts = int(ot.timestamp()), int(ct.timestamp())
    bid = ask = None
    for c in sorted(candles, key=lambda c: c.get("end_period_ts") or 0):
        if (c.get("end_period_ts") or 0) >= open_ts + ENTRY_AFTER_S:
            b, a = te.candle_quote(c)
            bid, ask = te.usable(b), te.usable(a)
            break
    return Window(m["ticker"], open_ts, close_ts, strike, _num(m.get("expiration_value")), res, bid, ask)


def pull(days: int, workers: int = 3) -> list[Window]:
    import train_edge as te  # noqa: E402
    markets = te.fetch_settled(SERIES, days)
    print(f"{len(markets)} settled {SERIES} markets in {days} days", file=sys.stderr)

    def one(m: dict) -> Window | None:
        ot, ct = te.parse_iso(m.get("open_time")), te.parse_iso(m.get("close_time"))
        if not ot or not ct:
            return None
        try:
            candles = te.fetch_candles(SERIES, m["ticker"], int(ot.timestamp()), int(ot.timestamp()) + 180)
        except Exception:
            candles = []
        return window_from_market(m, candles)

    with ThreadPoolExecutor(max_workers=workers) as ex:
        out = [w for w in ex.map(one, markets) if w is not None]
    return sorted(out, key=lambda w: w.close_ts)


def save(windows: list[Window], path: Path) -> None:
    path.write_text(json.dumps([w.__dict__ for w in windows]))


def load(path: Path) -> list[Window]:
    return [Window(**d) for d in json.loads(path.read_text())]


# --- core (unit-tested) ---------------------------------------------------------

def pairs(windows: list[Window]) -> list[Pair]:
    """Back-to-back windows. The previous settlement average is expiration_value,
    else the next window's strike (by rule, the strike is the previous window's
    60 s average). Ties settle YES."""
    ws = sorted(windows, key=lambda w: w.close_ts)
    out = []
    for a, b in zip(ws, ws[1:]):
        if abs(b.open_ts - a.close_ts) > CONTIGUOUS_TOL_S:
            continue
        settle = a.settle if a.settle is not None else b.strike
        if not a.strike:
            continue
        move = (settle - a.strike) / a.strike * 1e4
        out.append(Pair(a, b, move, a.result != b.result, "NO" if a.result == "yes" else "YES"))
    return out


def bucket(move_bps: float) -> int:
    m = abs(move_bps)
    for i in range(len(BUCKETS_BPS) - 1):
        if BUCKETS_BPS[i] - EPS <= m < BUCKETS_BPS[i + 1]:
            return i
    return len(BUCKETS_BPS) - 2


def bucket_label(i: int) -> str:
    lo, hi = BUCKETS_BPS[i], BUCKETS_BPS[i + 1]
    return f"[{lo:g},{hi:g}) bps" if math.isfinite(hi) else f"≥{lo:g} bps"


def side_quote(w: Window, side: str) -> tuple[float | None, float | None]:
    """(bid, ask) for buying `side` one minute in."""
    if side == "YES":
        return w.yes_bid, w.yes_ask
    nb = 1.0 - w.yes_ask if w.yes_ask is not None else None
    na = 1.0 - w.yes_bid if w.yes_bid is not None else None
    return nb, na


def taker_pnl(p: Pair) -> float | None:
    """$ per contract buying the flip side at the ask, taker fee included."""
    _, ask = side_quote(p.nxt, p.against)
    if ask is None or not (0.0 < ask < 1.0):
        return None
    return (1.0 if p.flip else 0.0) - ask - fee_per_contract(ask, TAKER_FEE, STAKE_USD)


def maker_pnl(p: Pair) -> float | None:
    """UPPER BOUND $ per contract resting 1¢ above the bid on the flip side, no fee, assumed filled."""
    bid, ask = side_quote(p.nxt, p.against)
    if bid is None or ask is None:
        return None
    px = round(bid + TICK, 4)
    if px >= ask - EPS or px <= 0.0:
        return None
    return (1.0 if p.flip else 0.0) - px


def implied_flip(p: Pair) -> float | None:
    bid, ask = side_quote(p.nxt, p.against)
    return (bid + ask) / 2.0 if bid is not None and ask is not None else None


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n <= 0:
        return float("nan"), float("nan")
    ph = k / n
    den = 1 + z * z / n
    c = (ph + z * z / (2 * n)) / den
    h = z * math.sqrt(ph * (1 - ph) / n + z * z / (4 * n * n)) / den
    return c - h, c + h


def day_block_ci(by_day: dict[str, tuple[float, int]], level: float, iters: int, seed: int) -> tuple[float, float]:
    days = [d for d, (_, n) in by_day.items() if n > 0]
    if len(days) < 2 or iters <= 0:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    vals = []
    for _ in range(iters):
        s = n = 0.0
        for _ in days:
            a, b = by_day[days[rng.randrange(len(days))]]
            s += a
            n += b
        if n:
            vals.append(s / n)
    vals.sort()
    a = (1 - level) / 2
    return vals[int(a * (len(vals) - 1))], vals[int((1 - a) * (len(vals) - 1))]


def ev_stats(ps: list[Pair], fn, iters: int, seed: int) -> dict:
    by_day: dict[str, list[float]] = defaultdict(lambda: [0.0, 0])
    vals = []
    for p in ps:
        v = fn(p)
        if v is None:
            continue
        vals.append(v)
        by_day[p.nxt.day][0] += v
        by_day[p.nxt.day][1] += 1
    bd = {d: (s, n) for d, (s, n) in by_day.items()}
    if not vals:
        return {"n": 0}
    return {"n": len(vals), "mean": sum(vals) / len(vals), "days": len(bd),
            "ci95": day_block_ci(bd, 0.95, iters, seed), "ci99": day_block_ci(bd, 0.99, iters, seed)}


# --- report -----------------------------------------------------------------------

def _f(x, fmt="{:+.4f}"):
    return "—" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def _ci(ci):
    return "—" if ci is None or any(math.isnan(v) for v in ci) else f"[{ci[0]:+.4f}, {ci[1]:+.4f}]"


def bucket_table(ps: list[Pair], iters: int, seed: int) -> list[str]:
    rows = ["| prev move | pairs | flip rate | 95% CI | implied flip @1m | taker $/ct | 95% day CI | maker UB $/ct |",
            "|---|---|---|---|---|---|---|---|"]
    by: dict[int, list[Pair]] = defaultdict(list)
    for p in ps:
        by[bucket(p.move_bps)].append(p)
    for i in range(len(BUCKETS_BPS) - 1):
        g = by.get(i, [])
        if not g:
            rows.append(f"| {bucket_label(i)} | 0 | — | — | — | — | — | — |")
            continue
        k = sum(p.flip for p in g)
        lo, hi = wilson(k, len(g))
        imp = [v for p in g if (v := implied_flip(p)) is not None]
        imp_s = f"{sum(imp) / len(imp):.1%}" if imp else "—"
        t = ev_stats(g, taker_pnl, iters, seed)
        m = ev_stats(g, maker_pnl, iters, seed)
        rows.append(f"| {bucket_label(i)} | {len(g)} | {k / len(g):.1%} | [{lo:.1%}, {hi:.1%}] | {imp_s} "
                    f"| {_f(t.get('mean'))} | {_ci(t.get('ci95'))} | {_f(m.get('mean'))} |")
    return rows


def report(windows: list[Window], iters: int = 2000, seed: int = 11) -> str:
    ps = pairs(windows)
    days = sorted({p.nxt.day for p in ps})
    out = ["# Window-to-window reversal — KXBTC15M", ""]
    with_settle = sum(1 for w in windows if w.settle is not None)
    quoted = sum(1 for p in ps if implied_flip(p) is not None)
    out.append(f"Windows: {len(windows)} ({with_settle} with Kalshi's settlement value; the rest use the next "
               f"window's strike) · back-to-back pairs: {len(ps)} · pairs with a 1-minute quote: {quoted} "
               f"· days: {len(days)}")
    out.append("")
    out.append("*flip* = the next window settled the other way. *implied flip @1m* = the mid of that side one "
               "minute into the next window: if it matches the flip rate, Kalshi already prices the reversal. "
               f"*taker $/ct* buys that side at the ask with the {TAKER_FEE:g} fee. *maker UB* rests 1¢ above "
               "the bid with no fee and assumes a fill: an upper bound, never evidence.")
    out.append("")
    if not ps:
        out.append("No back-to-back pairs. Decision: NOT EVALUABLE.")
        return "\n".join(out)
    k = sum(p.flip for p in ps)
    lo, hi = wilson(k, len(ps))
    out.append(f"All pairs: flip rate {k / len(ps):.1%} (95% CI [{lo:.1%}, {hi:.1%}]).")
    out.append("")
    out.append("## Flip rate by size of the previous move (all days)")
    out.append("")
    out += bucket_table(ps, iters, seed)
    out.append("")
    rule = [p for p in ps if abs(p.move_bps) >= RULE_MIN_BPS - EPS]
    out.append(f"## Rule: bet against the previous result when |move| ≥ {RULE_MIN_BPS:g} bps")
    out.append("")
    if len(days) < MIN_DAYS_FOR_OOS:
        out.append(f"**EXPLORATORY** — {len(days)} day(s) < {MIN_DAYS_FOR_OOS}. Decision: NOT EVALUABLE.")
        return "\n".join(out)
    n_oos = max(2, len(days) // 3)
    oos_days = set(days[-n_oos:])
    is_rule = [p for p in rule if p.nxt.day not in oos_days]
    oos_rule = [p for p in rule if p.nxt.day in oos_days]
    out.append("| sample | bets | flip rate | taker $/ct | 99% day CI | maker UB $/ct |")
    out.append("|---|---|---|---|---|---|")
    for name, g in (("IS", is_rule), ("OOS", oos_rule)):
        t = ev_stats(g, taker_pnl, iters, seed)
        m = ev_stats(g, maker_pnl, iters, seed)
        fr = f"{sum(p.flip for p in g) / len(g):.1%}" if g else "—"
        out.append(f"| {name} | {t.get('n', 0)} | {fr} | {_f(t.get('mean'))} | {_ci(t.get('ci99'))} | {_f(m.get('mean'))} |")
    out.append("")
    t = ev_stats(oos_rule, taker_pnl, iters, seed)
    lo, hi = t.get("ci99", (float("nan"), float("nan")))
    if n_oos < MIN_OOS_DAYS_FOR_RULE or not t.get("n") or math.isnan(lo):
        verdict = f"NOT EVALUABLE ({n_oos} OOS day(s); need ≥ {MIN_OOS_DAYS_FOR_RULE})."
    elif lo > 0:
        verdict = "PASS — OOS 99% CI at the ask is above 0. Paper trade it next; it is not proven live."
    elif hi < 0:
        verdict = "FAIL — OOS 99% CI at the ask is below 0: the reversal (if any) does not pay the taker fee."
    else:
        verdict = "NO EDGE SHOWN — OOS 99% CI at the ask includes 0."
    out.append(f"Decision: {verdict}")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("pull", help="fetch settled markets + 1-minute quotes into a JSON cache")
    p1.add_argument("--days", type=int, default=90)
    p1.add_argument("--cache", required=True)
    p1.add_argument("--workers", type=int, default=3)
    p2 = sub.add_parser("report", help="write the Markdown report from a cache")
    p2.add_argument("--cache", required=True)
    p2.add_argument("--out", default=None)
    p2.add_argument("--iters", type=int, default=2000)
    args = ap.parse_args(argv)
    if args.cmd == "pull":
        ws = pull(args.days, args.workers)
        save(ws, Path(args.cache))
        print(f"saved {len(ws)} windows to {args.cache}", file=sys.stderr)
        return 0
    text = report(load(Path(args.cache)), iters=args.iters)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
