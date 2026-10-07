#!/usr/bin/env python3
"""No-look-ahead and offset-model checks for ml/train_edge.py (stdlib only).

    python3 ml/test_train_edge.py
"""
from __future__ import annotations

import json
import math
import random
import sys
import tempfile
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

    def test_every_decision_minute_is_sampled_without_look_ahead(self) -> None:
        # One candle per minute from open (elapsed 0) through close (elapsed 15).
        market = dict(_market(), result="yes", ticker="KXBTC15M-T", open_time=None)
        candles = [_candle(self.close_ts - 900 + 60 * i, 0.45) for i in range(16)]
        shift = self.close_ts - CLOSE
        up = [(ts + shift, c) for ts, c in _spot_rows(final_px=110.0)]
        down = [(ts + shift, c) for ts, c in _spot_rows(final_px=90.0)]
        s_up = te.market_samples(market, candles, up)
        s_down = te.market_samples(market, candles, down)
        elapsed = [(s.ts - (self.close_ts - 900)) // 60 for s in s_up]
        self.assertEqual(elapsed, list(range(1, 14)), "elapsed minutes 1..13, not 2 per market")
        self.assertEqual([s.x for s in s_up], [s.x for s in s_down])
        self.assertTrue(all(s.y == 1 and s.close_ts == self.close_ts for s in s_up))
        # Candle-close fills: YES = yes_ask, NO = 1 − yes_bid.
        self.assertAlmostEqual(s_up[0].yes_ask, 0.46)
        self.assertAlmostEqual(s_up[0].no_ask, 0.56)

    def test_spot_known_at_drops_stale_bars_after_a_gap(self) -> None:
        rows = [(0, 1.0), (60, 2.0)]
        self.assertEqual(te.spot_known_at(rows, 120), [1.0, 2.0])
        self.assertEqual(te.spot_known_at(rows, 120 + te.SPOT_MAX_AGE_S), [])


def _samples(n_markets: int, seed: int = 3, lag: float = 0.0) -> list[te.Sample]:
    """Synthetic minutes. lag = 0: the mid is the true probability."""
    rng = random.Random(seed)
    out = []
    for m in range(n_markets):
        close = OPEN + 900 * (m // 3 + 1)
        truth = rng.gauss(0.0, 1.5)
        y = 1 if rng.random() < te.sigmoid(truth) else 0
        for k in range(1, 14):
            spot_logit = truth + rng.gauss(0.0, 0.5)
            mid = te.sigmoid((1.0 - lag) * truth + (rng.gauss(0.0, 0.3) if lag else 0.0))
            x = [spot_logit / 1.6, (900 - 60 * k) / 900, mid, 0.0, 0.02, 0.0, 0.05, 0.0, 0.5, te.sigmoid(spot_logit)]
            out.append(
                te.Sample(
                    x=x,
                    y=y,
                    mid=mid,
                    ts=close - 900 + 60 * k,
                    close_ts=close,
                    ticker=f"M{m}",
                    yes_ask=te.usable(round(mid + 0.01, 3)),
                    no_ask=te.usable(round(1.0 - mid + 0.01, 3)),
                )
            )
    return out


class OffsetModelTest(unittest.TestCase):
    def _zero_model(self, mean_mid: float = 0.0, std_mid: float = 1.0) -> dict:
        mean = [0.0] * 10
        std = [1.0] * 10
        mean[te.MID_INDEX] = mean_mid
        std[te.MID_INDEX] = std_mid
        return {
            "kind": te.KIND_OFFSET,
            "weights": [0.0] * 10,
            "bias": 0.0,
            "mean": mean,
            "std": std,
            "platt_a": 1.0,
            "platt_b": 0.0,
            "mid_clip": te.MID_CLIP,
        }

    def test_zero_weights_reproduce_the_mid(self) -> None:
        # A scaler on market_mid must not touch the offset: it is logit(mid), not a feature.
        for model in (self._zero_model(), self._zero_model(mean_mid=0.5, std_mid=0.2)):
            for mid in (0.001, 0.03, 0.31, 0.5, 0.77, 0.999):
                x = [0.0] * 10
                x[te.MID_INDEX] = mid
                self.assertAlmostEqual(te.model_predict(model, x, mid), mid, places=9)
        clipped = te.model_predict(self._zero_model(), [0.0] * 10, 0.0002)
        self.assertAlmostEqual(clipped, te.MID_CLIP, places=9)

    def test_newton_fit_converges(self) -> None:
        rng = random.Random(11)
        X = [[rng.gauss(0, 1), rng.gauss(0, 1)] for _ in range(2000)]
        off = [rng.gauss(0, 1) for _ in X]
        y = [1 if rng.random() < te.sigmoid(o + 0.7 * a - 0.4 * b) else 0 for o, (a, b) in zip(off, X)]
        l2 = 0.01
        w, b = te.fit_logistic(X, y, offset=off, l2=l2)
        # Gradient of mean log-loss + ½·l2·‖θ‖² is zero at the optimum.
        g = [0.0, 0.0, 0.0]
        for o, x, yi in zip(off, X, y):
            r = te.sigmoid(o + b + w[0] * x[0] + w[1] * x[1]) - yi
            g[0] += r
            g[1] += r * x[0]
            g[2] += r * x[1]
        g = [gi / len(X) + l2 * t for gi, t in zip(g, [b, w[0], w[1]])]
        self.assertLess(max(abs(v) for v in g), 1e-7)
        self.assertAlmostEqual(w[0], 0.7, delta=0.15)
        self.assertAlmostEqual(w[1], -0.4, delta=0.15)

    def test_calibrated_market_gets_near_zero_weights(self) -> None:
        samples = _samples(1200)
        model = te.fit_model(samples)
        design = {te.FEATURE_NAMES.index(n) for n in te.OFFSET_FEATURES}
        for i, w in enumerate(model["weights"]):
            if i not in design:
                self.assertEqual(w, 0.0, f"{te.FEATURE_NAMES[i]} is outside the design")
        self.assertLess(max(abs(w) for w in model["weights"]), 0.2)
        self.assertLess(abs(model["bias"]), 0.1)
        metrics = te.walk_forward(samples)
        self.assertAlmostEqual(metrics["model_brier"], metrics["market_brier"], delta=0.003)

    def test_lagging_market_is_beaten_and_traded_at_the_ask(self) -> None:
        metrics = te.walk_forward(_samples(2400, seed=7, lag=0.3))
        self.assertLess(metrics["model_brier"], metrics["market_brier"])
        self.assertLess(metrics["model_logloss"], metrics["market_logloss"])
        self.assertGreater(metrics["sim_trades"], 0)
        # The mid as its own forecast never clears ask + fee.
        self.assertEqual(metrics["market_sim_trades"], 0)

    def test_folds_train_only_on_settled_markets(self) -> None:
        splits = te.fold_splits(_samples(120), folds=4)
        self.assertEqual(len(splits), 3)
        seen = set()
        for train, test in splits:
            self.assertLessEqual(max(s.close_ts for s in train), min(s.ts for s in test))
            tickers = {s.ticker for s in test}
            self.assertFalse(tickers & {s.ticker for s in train}, "a market never straddles a fold")
            self.assertFalse(tickers & seen)
            seen |= tickers

    def test_ev_side_rule(self) -> None:
        # Fair == mid: both sides lose the spread + fee → skip.
        self.assertIsNone(te.ev_side(0.50, 0.51, 0.51)[0])
        # Clear edge on NO even though YES is the favorite.
        side, ev_yes, ev_no = te.ev_side(0.55, 0.70, 0.31)
        self.assertEqual(side, "NO")
        self.assertAlmostEqual(ev_no, 0.45 - 0.31 - 0.07 * 0.31 * 0.69)
        self.assertLess(ev_yes, 0)
        # Clear edge on YES.
        self.assertEqual(te.ev_side(0.80, 0.70, 0.31)[0], "YES")
        # Missing / unusable asks: only the quoted side is evaluated.
        self.assertEqual(te.ev_side(0.80, 0.70, None)[0], "YES")
        self.assertEqual(te.ev_side(0.80, None, None), (None, None, None))
        self.assertIsNone(te.ev_side(0.80, 1.0, 0.0)[0])
        # Margin: 3¢ after fees (1.3¢ here).
        self.assertIsNone(te.ev_side(0.56, 0.53, 0.48)[0])

    def test_calibration_error_small_for_calibrated_forecast(self) -> None:
        # 100 rows at p=0.7 with 70 YES: that bin's ECE is 0.
        p = [0.7] * 100
        y = [1] * 70 + [0] * 30
        self.assertAlmostEqual(te.calibration_error(p, y), 0.0)
        # Same forecast, wrong outcomes: large ECE.
        self.assertGreater(te.calibration_error(p, [1] * 30 + [0] * 70), 0.3)
        self.assertEqual(te.calibration_error([], []), 1.0)

    def test_bootstrap_ci_brackets_true_pnl_and_is_deterministic(self) -> None:
        samples = _samples(240, seed=5, lag=0.4)
        rows = [(s, te.sigmoid(2.0 * s.x[0])) for s in samples]
        base = te.ev_pnl(rows)
        a = te.bootstrap_pnl_ci(rows, n_boot=200)
        b = te.bootstrap_pnl_ci(rows, n_boot=200)
        self.assertEqual(a, b, "fixed seed → deterministic CI")
        self.assertLessEqual(a["ci_low"], base["pnl_per_bet"] + 0.05)
        self.assertGreaterEqual(a["ci_high"], base["pnl_per_bet"] - 0.05)
        self.assertGreaterEqual(a["ci_low"], 0.0 if base["pnl_per_bet"] > 0.10 else -1.0)
        self.assertTrue(0.0 <= a["p_low"] <= 1.0)
        self.assertEqual(a["n_boot_markets"], 240.0, "one block per market")

    def test_promotion_requires_bootstrap_ci_excluding_zero(self) -> None:
        # Beats the market on scores but the CI includes zero → not eligible.
        metrics = {
            "n_holdout": 100.0,
            "model_brier": 0.10,
            "market_brier": 0.15,
            "model_logloss": 0.30,
            "market_logloss": 0.40,
            "sim_trades": 40.0,
            "boot_ci_low": -0.01,
            "boot_ci_high": 0.05,
        }
        with tempfile.TemporaryDirectory() as tmp:
            man = Path(tmp) / "manifest.json"
            te.write_manifest(metrics, man, synthetic=False)
            payload = json.loads(man.read_text())
        self.assertTrue(payload["beats_market"])
        self.assertFalse(payload["promotion_eligible"])
        metrics["boot_ci_low"] = 0.01
        with tempfile.TemporaryDirectory() as tmp:
            man = Path(tmp) / "manifest.json"
            te.write_manifest(metrics, man, synthetic=False)
            payload = json.loads(man.read_text())
        self.assertTrue(payload["promotion_eligible"])
        # Too few simulated trades can never promote.
        metrics["sim_trades"] = 5.0
        metrics["boot_ci_low"] = 0.05
        with tempfile.TemporaryDirectory() as tmp:
            man = Path(tmp) / "manifest.json"
            te.write_manifest(metrics, man, synthetic=False)
            payload = json.loads(man.read_text())
        self.assertFalse(payload["promotion_eligible"])

    def test_recency_metrics_flag_a_stale_fit(self) -> None:
        hold = [(s, s.mid) for s in _samples(240, seed=3)]
        r = te.recency_metrics(hold)
        self.assertEqual(r["recent_n"], 780.0)  # 240 markets x 13 min x 25%
        # market vs itself: perfect parity on the recent slice
        self.assertAlmostEqual(r["recent_model_brier"], r["recent_market_brier"], places=9)

    def test_sweep_l2_prefers_some_regularization(self) -> None:
        # A lagging market: the truth moves and the mid underreacts. Any
        # nonzero L2 should be selected; the sweep must return a candidate.
        best, per = te.sweep_l2(_samples(360, seed=11, lag=0.4), folds=3, candidates=(0.5, 0.05, 0.005))
        self.assertIn(best, (0.5, 0.05, 0.005))
        self.assertEqual(len(per), 3)
        for cand, m in per.items():
            self.assertIn("logloss", m)
            self.assertGreater(m["logloss"], 0.0)

    def test_margin_curve_monotone_fewer_bets(self) -> None:
        hold = [(s, te.sigmoid(2.0 * s.x[0])) for s in _samples(240, seed=5, lag=0.4)]
        curve = te.margin_curve(hold)
        self.assertIn("0.03", curve)
        ns = [curve[k]["n"] for k in sorted(curve)]
        margins = sorted(float(k) for k in curve)
        counts = [curve[f"{m:.2f}"]["n"] for m in margins]
        self.assertEqual(counts, sorted(counts, reverse=True), "higher margin → fewer (or equal) bets")

    def test_walk_forward_reports_calibration_and_bootstrap(self) -> None:
        metrics = te.walk_forward(_samples(360, seed=9, lag=0.3))
        for key in ("model_calibration_error", "market_calibration_error", "boot_ci_low", "boot_ci_high", "boot_p_of_loss"):
            self.assertIn(key, metrics)
            self.assertTrue(math.isfinite(metrics[key]), key)
        self.assertLessEqual(metrics["model_calibration_error"], metrics["market_calibration_error"] + 0.05)

    def test_export_is_offset_logistic_with_ten_features(self) -> None:
        samples = _samples(90)
        model = te.fit_model(samples)
        metrics = te.walk_forward(samples)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "edge_model.json"
            man = Path(tmp) / "manifest.json"
            te.export(model, metrics, out)
            te.write_manifest(metrics, man, synthetic=True)
            payload = json.loads(out.read_text())
            manifest = json.loads(man.read_text())
        self.assertEqual(payload["kind"], "offset_logistic")
        self.assertEqual(payload["feature_names"], te.FEATURE_NAMES)
        self.assertEqual(len(payload["weights"]), 10)
        self.assertEqual(payload["mid_clip"], te.MID_CLIP)
        self.assertEqual((payload["platt_a"], payload["platt_b"]), (1.0, 0.0))
        self.assertTrue(all(isinstance(v, float) for v in payload["metrics"].values()))
        self.assertEqual(manifest["tag"], "mis-bitcoin-edge-model")
        self.assertFalse(manifest["beats_market"], "synthetic data never activates")


if __name__ == "__main__":
    unittest.main()
