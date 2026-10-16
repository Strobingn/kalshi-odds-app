#!/usr/bin/env python3
"""Grouped time-ordered split must not leak markets into validation."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import train_diphunter as td  # noqa: E402


class GroupedSplitTest(unittest.TestCase):
    def test_no_market_in_both_sides(self) -> None:
        # 10 markets × 4 rows, times increasing. Random row split would leak.
        market_ids = [f"M{i // 4}" for i in range(40)]
        times = [1_700_000_000 + (i // 4) * 900 for i in range(40)]
        tr, va = td.grouped_time_split(market_ids, times, 40, val_frac=0.20)
        train_m = {market_ids[int(i)] for i in tr}
        val_m = {market_ids[int(i)] for i in va}
        self.assertEqual(train_m & val_m, set())
        self.assertTrue(val_m)
        self.assertTrue(train_m)
        # Latest markets land in val.
        self.assertIn("M9", val_m)
        self.assertIn("M0", train_m)

    def test_random_split_would_leak(self) -> None:
        """Documents the old bug: 4 rows/market + random split leaks."""
        import numpy as np
        market_ids = [f"M{i // 4}" for i in range(40)]
        rng = np.random.default_rng(42)
        idx = rng.permutation(40)
        tr, va = idx[:34], idx[34:]
        train_m = {market_ids[int(i)] for i in tr}
        val_m = {market_ids[int(i)] for i in va}
        self.assertTrue(train_m & val_m, "old random split leaks markets (sanity)")


if __name__ == "__main__":
    unittest.main()
