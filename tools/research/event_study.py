#!/usr/bin/env python3
"""News-window study: is Kalshi's 15m crypto price too sure of itself right after US releases?

US data drops at 8:30 ET, stocks open at 9:30 ET and more data drops at
10:00 ET. Kalshi's 15m windows start on the quarter hour, so on weekdays a
window *opens* at each of those times and the whole window carries the extra
volatility. If the market prices those windows like any other, the side far
from even (the underdog) is too cheap there.

Pre-registered, no parameter search (fixed before looking at results):
  * Event windows: weekday markets whose open time is 08:30, 09:30 or 10:00
    America/New_York. Control: every other market.
  * Decision: the 5th minute of the window (elapsed == 5), candle-close ask.
  * Rule: buy the underdog -- the side whose ask is in [0.10, 0.40] -- $5
    all-in with the exact Kalshi taker fee, hold to settlement.
  * Report P&L per family and pooled, event vs control, with day-block
    bootstrap 95% / 99% CIs. Only a pooled 99% CI above 0 *and* above the
    control counts as a finding.
  * Sanity check: realized |ln(spot_close / spot_decision)| in event windows
    vs control (if it is not larger, the premise fails).

Runs on the backtest cache (tools/backtest/fetch.py). Stdlib only.
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

from edge_search import Bet, build_rows, day_block_ci, place, summary  # noqa: E402
from simulate import _spot_at, load_cache  # noqa: E402

NY = ZoneInfo("America/New_York")
FAMILIES = {(8, 30): "8:30 ET data", (9, 30): "9:30 ET stock open", (10, 0): "10:00 ET data"}
DECISION_ELAPSED = 5
DOG_LO, DOG_HI = 0.10, 0.40


def family_of(open_ms: int) -> str | None:
    t = datetime.fromtimestamp(open_ms / 1000, tz=timezone.utc).astimezone(NY)
    if t.weekday() >= 5:
        return None
    return FAMILIES.get((t.hour, t.minute))


def dog_side(ya: float | None, na: float | None) -> str | None:
    """The cheaper side, if its ask is in the underdog band."""
    c = [(a, side) for a, side in ((ya, "YES"), (na, "NO")) if a is not None and DOG_LO <= a <= DOG_HI]
    return min(c)[1] if c else None


def fmt(name: str, bets: list[Bet]) -> str:
    s = summary(bets)
    if s["n"] == 0:
        return f"| {name} | 0 | | | | | | |"
    lo95, hi95 = day_block_ci(bets, 0.95)
    lo99, hi99 = day_block_ci(bets, 0.99)
    stress = f"${s['stress_pnl']:+.2f}" if s.get("stress_pnl") is not None else "n/a"
    return (f"| {name} | {s['n']} | {s['win'] * 100:.1f}% | {s['ask'] * 100:.1f}¢ | ${s['pnl']:+.2f} | "
            f"{s['per']:+.4f} | [{lo95:+.3f}, {hi95:+.3f}] / [{lo99:+.3f}, {hi99:+.3f}] | {stress} |")


def run(cache: Path) -> str:
    rows = build_rows(cache)
    markets, _candles, spots = load_cache(cache)
    open_of = {m["ticker"]: int(m["open_ms"]) for m in markets if m.get("open_ms")}
    by_family: dict[str, list[Bet]] = defaultdict(list)
    control: list[Bet] = []
    moves: dict[str, list[float]] = defaultdict(list)
    n_windows: dict[str, int] = defaultdict(int)
    for r in rows:
        if r.elapsed != DECISION_ELAPSED or r.ticker not in open_of:
            continue
        fam = family_of(open_of[r.ticker])
        key = fam or "control"
        n_windows[key] += 1
        close_px = _spot_at(spots.get(r.coin, {}), r.close_ms // 1000)
        if r.spot and close_px:
            moves[key].append(abs(math.log(close_px / r.spot)))
        side = dog_side(r.ya, r.na)
        if side is None:
            continue
        b = place(r, side)
        if b is None:
            continue
        (by_family[fam] if fam else control).append(b)

    days = sorted({r.day for r in rows})
    pooled = [b for bs in by_family.values() for b in bs]
    L = [
        "# News-window study (pre-registered, no parameter search)",
        "",
        f"Days {days[0] if days else '-'} → {days[-1] if days else '-'} ({len(days)}). Decision at minute "
        f"{DECISION_ELAPSED}; buy the underdog (ask {DOG_LO * 100:.0f}–{DOG_HI * 100:.0f}¢), $5 all-in, exact taker fee, "
        "hold to settlement. Fill = candle-close ask; stress = worse of close/high.",
        "",
        "## Premise check: is BTC/ETH/SOL actually jumpier in these windows?",
        "| windows | markets | median abs move, minute 5 → close (bp) | mean (bp) |",
        "|---|---|---|---|",
    ]
    for key in list(FAMILIES.values()) + ["control"]:
        xs = sorted(moves.get(key, []))
        if not xs:
            L.append(f"| {key} | {n_windows.get(key, 0)} | | |")
            continue
        L.append(f"| {key} | {n_windows[key]} | {xs[len(xs) // 2] * 1e4:.1f} | {sum(xs) / len(xs) * 1e4:.1f} |")
    L += [
        "",
        "## Underdog bets",
        "| windows | bets | win | avg ask | P&L | $/bet | 95% / 99% CI $/bet | stress P&L |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for fam in FAMILIES.values():
        L.append(fmt(fam, by_family.get(fam, [])))
    L.append(fmt("**all news windows**", pooled))
    L.append(fmt("control (all other windows)", control))
    L += ["", "Counts as a finding only if the pooled 99% CI is above 0 and above the control's $/bet."]
    return "\n".join(L)


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cache", default=str(HERE.parent / "backtest" / "cache"))
    a = ap.parse_args(argv)
    print(run(Path(a.cache)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
