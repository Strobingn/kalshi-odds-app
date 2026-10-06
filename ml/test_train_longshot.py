#!/usr/bin/env python3
"""Tests for the longshot residual exporter (schema 1, fee-aware, clustered CI)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_longshot as tl  # noqa: E402


def candle(end, bid, ask):
    return {"end_period_ts": end, "yes_bid": {"close_dollars": f"{bid:.4f}"}, "yes_ask": {"close_dollars": f"{ask:.4f}"}}


class LongshotTest(unittest.TestCase):
    def test_entry_uses_last_candle_with_five_minutes_left_only(self) -> None:
        close = 10_000
        cs = [candle(close - 600, 0.40, 0.42), candle(close - 300, 0.10, 0.12), candle(close - 60, 0.98, 0.99)]
        self.assertEqual(tl.entry_quotes(cs, close), (0.12, 0.90))
        self.assertIsNone(tl.entry_quotes([candle(close - 60, 0.5, 0.52)], close))
        self.assertIsNone(tl.entry_quotes([candle(close - 600, 0.0, 0.05)], close))
        self.assertIsNone(tl.entry_quotes([candle(close - 600, 0.95, 1.0)], close))

    def test_net_includes_rounded_up_fee_on_ten_dollar_clip(self) -> None:
        c = tl.clip_contracts(0.05)
        self.assertGreater(c, 0)
        fee = tl.te.kalshi_fee(0.05, c)
        self.assertLessEqual(c * 0.05 + fee, 10.0 + 1e-9)
        self.assertAlmostEqual(tl.net_per_contract(0.05, False), -0.05 - fee / c)
        self.assertLess(tl.net_per_contract(0.05, True), 0.95)

    def test_buckets_validate_only_with_enough_data_and_positive_ci(self) -> None:
        rows = [{"ticker": f"T{i}", "cluster": str(i), "price": 0.92, "won": 1, "net": 0.05} for i in range(400)]
        rows += [{"ticker": f"L{i}", "cluster": str(i), "price": 0.04, "won": 0, "net": -0.05} for i in range(400)]
        rows += [{"ticker": f"S{i}", "cluster": str(i), "price": 0.30, "won": 1, "net": 0.6} for i in range(20)]
        b = {(x["lo"], x["hi"]): x for x in tl.bucketize(rows)}
        self.assertTrue(b[(0.90, 0.95)]["validated"])
        self.assertFalse(b[(0.0, 0.05)]["validated"])
        self.assertFalse(b[(0.25, 0.50)]["validated"])  # n=20 < 300
        self.assertEqual(b[(0.05, 0.10)]["n"], 0)

    def test_fixture_export_is_schema_1_and_synthetic(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "l.json"
            self.assertEqual(tl.main(["--fixture", "--out", str(out)]), 0)
            p = json.loads(out.read_text())
            self.assertEqual(p["schema_version"], 1)
            self.assertTrue(p["synthetic"])
            self.assertEqual(p["fee"]["rounding"], "ceil_cent_per_clip")
            self.assertEqual(len(p["buckets"]), len(tl.EDGES) - 1)

    def test_cache_mode_builds_rows_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            (cache / "KXBTC15M-X.json").write_text(json.dumps([candle(1_000 - 400, 0.90, 0.92)]))
            m = {"ticker": "KXBTC15M-X", "result": "yes", "close_time": "1970-01-01T00:16:40Z"}
            rows = tl.build_rows([m], lambda mk, ct: json.loads((cache / f"{mk['ticker']}.json").read_text()))
            sides = {r["side"]: r for r in rows}
            self.assertEqual(sides["yes"]["price"], 0.92)
            self.assertEqual(sides["yes"]["won"], 1)
            self.assertAlmostEqual(sides["no"]["price"], 0.10)


if __name__ == "__main__":
    unittest.main()
