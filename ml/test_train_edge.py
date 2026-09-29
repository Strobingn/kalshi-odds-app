#!/usr/bin/env python3
"""No-look-ahead + publish-safety checks for ml/train_edge.py (stdlib only).

    python3 ml/test_train_edge.py
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import publish_gates as gates  # noqa: E402
import train_edge as te  # noqa: E402

OPEN = 1_790_000_000
CLOSE = OPEN + 900
STRIKE = 100.0
GOLDEN = Path(__file__).resolve().parent / "fixtures" / "edge_features_golden.json"


def _candle(end_ts: int, mid: float) -> dict:
    return {
        "end_period_ts": end_ts,
        "price": {"close_dollars": mid},
        "yes_bid": {"close_dollars": mid - 0.01},
        "yes_ask": {"close_dollars": mid + 0.01},
    }


def _market() -> dict:
    return {"close_time": "2026-09-20T00:15:00Z", "floor_strike": STRIKE}


def _spot_rows(final_px: float) -> list[tuple[int, float]]:
    rows = [(OPEN - 900 + 60 * i, 99.9 + 0.001 * (i % 3)) for i in range(29)]
    rows.append((CLOSE - 60, final_px))
    return rows


class NoLookAheadTest(unittest.TestCase):
    def setUp(self) -> None:
        te_close = te.parse_iso(_market()["close_time"])
        assert te_close is not None
        self.close_ts = int(te_close.timestamp())
        self.candles = [_candle(self.close_ts - 900 + 60 * (i + 1), 0.45) for i in range(15)]

    def test_mid_window_features_ignore_settlement_spot(self) -> None:
        idx = 7
        up = _spot_rows(final_px=110.0)
        down = _spot_rows(final_px=90.0)
        shift = self.close_ts - CLOSE
        up = [(ts + shift, c) for ts, c in up]
        down = [(ts + shift, c) for ts, c in down]
        f_up = te.features_for(_market(), self.candles, up, idx)
        f_down = te.features_for(_market(), self.candles, down, idx)
        self.assertIsNotNone(f_up)
        self.assertEqual(f_up, f_down, "features must not depend on spot after the decision minute")
        self.assertLess(f_up[te.FEATURE_NAMES.index("digital_fair")], 0.5)

    def test_spot_known_at_excludes_unfinished_bar(self) -> None:
        rows = [(0, 1.0), (60, 2.0), (120, 3.0)]
        self.assertEqual(te.spot_known_at(rows, 120), [1.0, 2.0])
        self.assertEqual(te.spot_known_at(rows, 179), [1.0, 2.0])
        self.assertEqual(te.spot_known_at(rows, 180), [1.0, 2.0, 3.0])

    def test_cross_asset_is_five_minute_return(self) -> None:
        closes = [100.0, 101.0, 102.0, 103.0, 104.0, 110.0]
        self.assertAlmostEqual(te.spot_return(closes, 5), 0.10)
        self.assertEqual(te.spot_return(closes[:3], 5), 0.0)


class PublishSafetyTest(unittest.TestCase):
    def test_failing_fetch_exits_nonzero_and_writes_nothing(self) -> None:
        """Item 1: a dead Kalshi/Coinbase fetch must not publish a fake model."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "edge_model.json"
            man = Path(tmp) / "edge_model_manifest.json"
            with mock.patch.object(te, "http_get", side_effect=RuntimeError("simulated fetch fail")):
                rc = te.main(["--days", "1", "--max-markets", "8", "--out", str(out), "--manifest", str(man)])
            self.assertNotEqual(rc, 0)
            self.assertFalse(out.exists(), "must not write a model after a failed fetch")
            self.assertFalse(man.exists(), "must not write a manifest after a failed fetch")

    def test_short_live_sample_exits_nonzero_and_writes_nothing(self) -> None:
        empty = ([], [], [], [], [])
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "edge_model.json"
            man = Path(tmp) / "edge_model_manifest.json"
            with mock.patch.object(te, "collect", return_value=empty):
                rc = te.main(["--days", "1", "--out", str(out), "--manifest", str(man)])
            self.assertNotEqual(rc, 0)
            self.assertFalse(out.exists())
            self.assertFalse(man.exists())

    def test_fixture_is_not_publishable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "edge_model.json"
            man = Path(tmp) / "edge_model_manifest.json"
            rc = te.main(["--fixture", "--out", str(out), "--manifest", str(man)])
            self.assertEqual(rc, 0)
            payload = json.loads(man.read_text())
            self.assertTrue(payload["synthetic"])
            self.assertEqual(payload["data_source"], "synthetic_fixture")
            self.assertFalse(payload["beats_market"])
            self.assertFalse(payload["publishable"])
            self.assertGreaterEqual(payload["n_markets"], 1)

    def test_synthetic_never_beats_market(self) -> None:
        d = gates.publish_decision(
            {
                "synthetic": True,
                "n_markets": 5000,
                "n_rows": 5000,
                "n_holdout": 1000,
                "model_brier": 0.02,
                "market_brier": 0.18,
                "model_logloss": 0.10,
                "market_logloss": 0.50,
            }
        )
        self.assertFalse(d["beats_market"])
        self.assertFalse(d["publishable"])

    def test_tiny_margin_does_not_beat_market(self) -> None:
        d = gates.publish_decision(
            {
                "synthetic": False,
                "n_markets": 5000,
                "n_rows": 8000,
                "n_holdout": 800,
                "model_brier": 0.185,
                "market_brier": 0.186,
                "model_logloss": 0.549,
                "market_logloss": 0.550,
            }
        )
        self.assertFalse(d["beats_market"])

    def test_missing_counts_fail(self) -> None:
        d = gates.publish_decision(
            {
                "model_brier": 0.10,
                "market_brier": 0.20,
                "model_logloss": 0.30,
                "market_logloss": 0.50,
            }
        )
        self.assertFalse(d["beats_market"])

    def test_honest_pass(self) -> None:
        d = gates.publish_decision(
            {
                "synthetic": False,
                "n_markets": 2500,
                "n_rows": 12000,
                "n_holdout": 800,
                "model_brier": 0.160,
                "market_brier": 0.186,
                "model_logloss": 0.480,
                "market_logloss": 0.520,
            }
        )
        self.assertTrue(d["beats_market"])
        self.assertTrue(d["publishable"])


class GoldenFeatureTest(unittest.TestCase):
    def test_shared_golden_vector(self) -> None:
        raw = json.loads(GOLDEN.read_text())
        got = te.features_from_raw(
            spot_px=raw["spot"],
            strike=raw["strike"],
            tte=raw["tte_seconds"],
            sigma=raw["sigma_annual"],
            mid=raw["market_mid"],
            imbalance=raw["imbalance"],
            spread=raw["spread"],
            coinbase_closes=raw["coinbase_closes"],
            cross=raw["cross_asset"],
            end_ts=raw["end_ts"],
        )
        expected = raw["expected"]
        self.assertEqual(len(got), len(expected))
        for i, (g, e) in enumerate(zip(got, expected)):
            self.assertAlmostEqual(g, e, places=6, msg=f"feature {te.FEATURE_NAMES[i]}")

    def test_utc_hour_not_new_york(self) -> None:
        # 2026-09-20T18:00:00Z is 14:00 America/New_York (EDT). Trainer must use 18/24.
        ts = int(datetime_utc(2026, 9, 20, 18, 0))
        self.assertAlmostEqual(te.utc_hour_frac(ts), 18.0 / 24.0, places=6)


def datetime_utc(y: int, m: int, d: int, hh: int, mm: int) -> float:
    from datetime import datetime, timezone
    return datetime(y, m, d, hh, mm, tzinfo=timezone.utc).timestamp()


if __name__ == "__main__":
    unittest.main()
