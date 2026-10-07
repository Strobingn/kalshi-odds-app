#!/usr/bin/env python3
"""No-look-ahead checks for ml/train_edge.py (stdlib only).

    python3 ml/test_train_edge.py
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import train_edge as te  # noqa: E402

OPEN = 1_790_000_000
CLOSE = OPEN + 900
STRIKE = 100.0


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
    # Flat-ish tape at 99.9 for the whole window, then a last-minute print.
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
        # Rebase spot rows onto this market's clock.
        shift = self.close_ts - CLOSE
        up = [(ts + shift, c) for ts, c in up]
        down = [(ts + shift, c) for ts, c in down]
        f_up = te.features_for(_market(), self.candles, up, idx)
        f_down = te.features_for(_market(), self.candles, down, idx)
        self.assertIsNotNone(f_up)
        self.assertEqual(f_up, f_down, "features must not depend on spot after the decision minute")
        # Spot below strike at decision time → digital fair below 0.5.
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

    def test_no_profit_uses_no_ask_not_the_midpoint(self) -> None:
        pnl = te.simulated_pnl([0.10], [0.30], [0])
        self.assertEqual(pnl["n"], 1)
        price = 0.70 + te.SIM_HALF_SPREAD
        self.assertAlmostEqual(pnl["pnl"], 1.0 - price - 0.07 * price * (1.0 - price))

    def test_fixture_manifest_cannot_claim_edge(self) -> None:
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            te.write_manifest(
                {"n_holdout": 180, "model_brier": 0.02, "market_brier": 0.18,
                 "model_logloss": 0.1, "market_logloss": 0.5},
                path,
                fixture=True,
            )
            import json
            manifest = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["data_source"], "synthetic_fixture")
            self.assertFalse(manifest["beats_market"])

    def test_time_of_day_matches_new_york_minutes(self) -> None:
        market = _market()
        end_ts = self.close_ts - 420
        feats = te.features_for(market, [_candle(end_ts, 0.45)], [], 0)
        self.assertIsNotNone(feats)
        local = te.datetime.fromtimestamp(end_ts, te.ZoneInfo("America/New_York"))
        expected = (local.hour * 60 + local.minute) / 1440
        self.assertAlmostEqual(feats[te.FEATURE_NAMES.index("time_of_day")], expected)


if __name__ == "__main__":
    unittest.main()
