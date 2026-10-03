#!/usr/bin/env python3
"""Favorite-longshot check on Kalshi 15m crypto: does the expensive side pay?

Prompted by the news-window study (2026-10-03): buying the underdog (ask
10-40c) at minute 5 lost -$0.65 per $5 bet over 16,853 control windows, i.e.
the cheap side looks overpriced even after the fee. That observation used the
same 91 days, so this is a robustness check, not a fresh test: the rule is
fixed below and reported by month, coin and decision minute. A rule that is
positive in every month and every coin is worth forward-testing; anything
else is noise.

Rule: at the given minute, buy the favorite (the side with ask in
[0.60, 0.90]), $5 all-in, exact taker fee, hold to settlement. Underdog
(ask 0.10-0.40) shown alongside. Candle-close fill; stress = worse of
close/high.
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

from edge_search import build_rows, day_block_ci, place, summary  # noqa: E402

MINUTES = (3, 5, 8, 11)
FAV = (0.60, 0.90)
DOG = (0.10, 0.40)


def pick(ya, na, band):
    c = [(a, s) for a, s in ((ya, "YES"), (na, "NO")) if a is not None and band[0] <= a <= band[1]]
    if not c:
        return None
    return (max(c) if band is FAV else min(c))[1]


def line(name, bets):
    s = summary(bets)
    if not s["n"]:
        return f"| {name} | 0 | | | | | |"
    lo, hi = day_block_ci(bets, 0.95)
    st = f"${s['stress_pnl']:+.0f}" if s.get("stress_pnl") is not None else "n/a"
    return (f"| {name} | {s['n']} | {s['win'] * 100:.1f}% | {s['ask'] * 100:.1f}¢ | ${s['pnl']:+.0f} | "
            f"{s['per']:+.3f} [{lo:+.3f}, {hi:+.3f}] | {st} |")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cache", default=str(HERE.parent / "backtest" / "cache"))
    a = ap.parse_args(argv)
    rows = build_rows(Path(a.cache))
    days = sorted({r.day for r in rows})
    out = ["# Favorite-longshot robustness check", "",
           f"Days {days[0]} → {days[-1]} ({len(days)}). $5 all-in, exact taker fee, one bet per market per minute "
           "rule. $/bet with 95% day-block CI.", ""]
    for minute in MINUTES:
        at = [r for r in rows if r.elapsed == minute]
        fav, dog = defaultdict(list), defaultdict(list)
        for r in at:
            for band, store in ((FAV, fav), (DOG, dog)):
                side = pick(r.ya, r.na, band)
                b = place(r, side) if side else None
                if b:
                    store["all"].append(b)
                    store["coin " + r.coin].append(b)
                    store["month " + r.day[:7]].append(b)
        out += [f"## Minute {minute}", "| slice | bets | win | avg ask | P&L | $/bet [95% CI] | stress P&L |",
                "|---|---|---|---|---|---|---|"]
        for key in ["all"] + sorted(k for k in fav if k != "all"):
            out.append(line(f"favorite · {key}", fav[key]))
        out.append(line("underdog · all", dog["all"]))
        out.append("")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
