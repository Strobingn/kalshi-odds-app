#!/usr/bin/env python3
"""No-network unit tests for the favourite-side maker test in tape_study.py.

Run: python3 tools/research/test_tape_study.py   (needs numpy and pandas)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tape_study as ts  # noqa: E402


def mk(ticker: str, open_iso: str) -> dict:
    return {"ticker": ticker, "open": ts._ts(open_iso), "close": ts._ts(open_iso) + 900}


def test_pull_subset_skips_days_then_shards() -> None:
    ms = [mk(f"T{i}", f"2026-10-0{1 + i % 6}T12:00:00Z") for i in range(12)]
    assert ts.pull_subset(ms) == ms
    kept = ts.pull_subset(ms, "0/1", "2026-10-02:2026-10-04")
    assert {ts.market_day(m) for m in kept} == {"2026-10-01", "2026-10-05", "2026-10-06"}
    parts = [ts.pull_subset(ms, f"{i}/3", "2026-10-02:2026-10-04") for i in range(3)]
    assert sorted(m["ticker"] for p in parts for m in p) == sorted(m["ticker"] for m in kept)
    assert all(len(p) == 2 for p in parts)
    try:
        ts.pull_subset(ms, "3/3")
    except ValueError:
        pass
    else:
        raise AssertionError("shard 3/3 must be rejected")


def frame(rows) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=["tk", "day", "tau", "Q", "cost", "pnl"])


def test_masks_split_by_our_price_and_time() -> None:
    df = frame([
        ("A", "2026-10-05", 60, 0, 0.72, 0.28),    # favourite side, early
        ("A", "2026-10-05", 600, 0, 0.50, -0.50),  # favourite side (edge of the band), late
        ("A", "2026-10-05", 60, 0, 0.28, -0.28),   # underdog side, early
        ("A", "2026-10-05", 60, 0, 0.93, 0.07),    # above the band: neither
        ("A", "2026-10-05", 60, 0, 0.07, -0.07),   # below the band: neither
    ])
    m = ts.fav_masks(df)
    assert list(m["favourite side (50-90c)"]) == [True, True, False, False, False]
    assert list(m["favourite side, first 5 min"]) == [True, False, False, False, False]
    assert list(m["underdog side (10-50c)"]) == [False, False, True, False, False]
    assert m["all orders"].all()


def test_cell_is_cents_per_fill_with_day_blocks() -> None:
    rng = np.random.default_rng(1)
    rows = [("A", "2026-10-05", 60, 0, 0.60, 0.40), ("B", "2026-10-05", 60, 0, 0.60, -0.60),
            ("C", "2026-10-06", 60, 0, 0.60, 0.40), ("D", "2026-10-06", 60, 0, 0.60, np.nan)]
    c = ts.fav_cell(frame(rows), rng, 500)
    assert (c["orders"], c["fills"], c["days"], c["pos_days"]) == (4, 3, 2, 1)
    assert abs(c["cents"] - 100 * (0.40 - 0.60 + 0.40) / 3) < 1e-9
    assert c["ci95"][0] <= c["cents"] <= c["ci95"][1]
    assert ts.fav_cell(frame([("A", "2026-10-05", 60, 0, 0.6, np.nan)]), rng, 10) == {"orders": 1, "fills": 0}
    # one day only: no day-block CI
    one = ts.fav_cell(frame(rows[:2]), rng, 100)
    assert np.isnan(one["ci99"][0]) and ts._ci_text(one["ci99"]) == "n/a"


def test_verdicts() -> None:
    assert ts.fav_verdict({"days": 3, "ci99": (0.5, 1.0)}).startswith("NOT EVALUABLE")
    assert ts.fav_verdict({"days": 9, "ci99": (0.2, 1.0)}).startswith("PASS")
    assert ts.fav_verdict({"days": 9, "ci99": (-1.0, -0.2)}).startswith("FAIL")
    assert ts.fav_verdict({"days": 9, "ci99": (-0.4, 0.9)}).startswith("NO EDGE SHOWN")


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
