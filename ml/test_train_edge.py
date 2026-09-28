#!/usr/bin/env python3
"""Checks for ml/train_edge.py (stdlib only).

    python3 ml/test_train_edge.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import train_edge as te  # noqa: E402
import pipeline as pl  # noqa: E402  (train_edge puts tools/backtest on sys.path)

OPEN_MS = 1_790_000_100_000 - (1_790_000_100_000 % 900_000)
CLOSE_MS = OPEN_MS + 900_000
STRIKE = 100.0


def _bars(mid: float = 0.45) -> list[dict]:
    return [
        {"end_ts": OPEN_MS // 1000 + 60 * (i + 1), "yes_bid": {"close": mid - 0.01}, "yes_ask": {"close": mid + 0.01}}
        for i in range(15)
    ]


def _spot(final_px: float) -> dict[int, float]:
    """1m Coinbase closes keyed by bar start; flat ~99.9 then a last-minute print."""
    start = OPEN_MS // 1000 - 90 * 60
    idx = {start + 60 * i: 99.9 + 0.001 * (i % 3) for i in range(90 + 14)}
    idx[CLOSE_MS // 1000 - 60] = final_px
    return idx


def _market() -> dict:
    return {"ticker": "KXBTC15M-TEST", "open_ms": OPEN_MS, "close_ms": CLOSE_MS, "result": "yes", "floor_strike": STRIKE}


class NoLookAheadTest(unittest.TestCase):
    def test_rows_ignore_spot_after_the_decision_minute(self) -> None:
        up = te.market_rows(_market(), _bars(), _spot(110.0))
        down = te.market_rows(_market(), _bars(), _spot(90.0))
        self.assertEqual(len(up), 13)
        # Minutes 1…13 are all decided before the settlement-minute bar closes.
        self.assertEqual([r["x"] for r in up], [r["x"] for r in down])
        # Spot ~99.9 below strike 100 → the digital sits below 50%.
        gap = up[6]["x"][pl.EDGE_FEATURES.index("digital_gap")]
        self.assertLess(gap + pl.logit(0.45), 0.0)


class OffsetModelTest(unittest.TestCase):
    def test_zero_model_is_the_market(self) -> None:
        n = len(pl.EDGE_FEATURES)
        zero = {"weights": [0.0] * n, "bias": 0.0, "mean": [0.0] * n, "std": [1.0] * n}
        x = pl.edge_features(0.37, 0.02, 101.0, 100.0, 300.0, 0.6, 0.001, 0.002, 1_790_000_000_000)
        self.assertAlmostEqual(pl.edge_predict(zero, x, 0.37), 0.37, places=9)

    def test_fit_recovers_planted_edge(self) -> None:
        model = te.fit_model(te.fixture_rows(n_markets=300))
        w = dict(zip(te.FEATURE_NAMES, model["weights"]))
        self.assertGreater(w["digital_gap"], 0.1)


class SafetyTest(unittest.TestCase):
    def _run(self, *args: str) -> int:
        return subprocess.run([sys.executable, str(HERE / "train_edge.py"), *args], capture_output=True, text=True).returncode

    def test_fixture_never_beats_market(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            out, man = Path(d) / "m.json", Path(d) / "man.json"
            self.assertEqual(self._run("--fixture", "--out", str(out), "--manifest", str(man)), 0)
            manifest = json.loads(man.read_text())
            model = json.loads(out.read_text())
            self.assertTrue(manifest["fixture"])
            self.assertFalse(manifest["beats_market"])
            self.assertTrue(model["fixture"])
            self.assertEqual(model["schema"], 2)

    def test_missing_data_fails_instead_of_faking(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "m.json"
            rc = self._run("--cache", str(Path(d) / "empty"), "--out", str(out), "--manifest", str(Path(d) / "man.json"))
            self.assertNotEqual(rc, 0)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
