#!/usr/bin/env python3
"""0.3.37 ML pipeline tests: 4-day holdout, recency weighting, champion/challenger,
fee rounding, immutable eval report, paced 429-aware fetch, look-ahead invariance.

Stdlib only. No network. Never reads a Kalshi key.
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import publish_gates as gates  # noqa: E402
import train_edge as te  # noqa: E402


def dataset(days: float = 12.0, per_hour: int = 6, seed: int = 3):
    """Deterministic rows spanning ``days`` with an informative feature."""
    import random

    rnd = random.Random(seed)
    X, y, mids, times, ids = [], [], [], [], []
    t0 = 1_760_000_000
    n = int(days * 24 * per_hour)
    for i in range(n):
        mid = 0.2 + 0.6 * rnd.random()
        signal = rnd.gauss(0, 1)
        p = min(0.97, max(0.03, mid + 0.12 * signal))
        label = 1 if rnd.random() < p else 0
        row = [signal, 0.5, mid, 0.0, 0.02, 0.0, 0.01, 0.0, (i % 24) / 24.0, mid]
        X.append(row)
        y.append(label)
        mids.append(mid)
        times.append(t0 + int(i * 3600 / per_hour))
        ids.append(f"M{i // 3}")
    return X, y, mids, times, ids


class HoldoutAndWeightsTest(unittest.TestCase):
    def test_holdout_is_exactly_the_last_four_days(self) -> None:
        X, y, mids, times, ids = dataset()
        m, model = te.evaluate_and_fit(X, y, mids, times, ids)
        self.assertEqual(m["holdout_mode"], "last_4_days")
        self.assertIsNotNone(model)
        cutoff = max(times) - 4 * 86400
        self.assertEqual(m["n_holdout"], sum(1 for t in times if t >= cutoff))
        self.assertLess(m["train_max_ts"], m["holdout_min_ts"])
        self.assertEqual(m["sanity_reasons"], [])

    def test_recency_weights_prefer_newer_rows(self) -> None:
        w = te.recency_weights([0, 86400 * 30, 86400 * 60], ref_ts=86400 * 60, half_life_days=30)
        self.assertAlmostEqual(w[2], 1.0)
        self.assertAlmostEqual(w[1], 0.5, places=6)
        self.assertAlmostEqual(w[0], 0.25, places=6)

    def test_weighted_fit_differs_from_unweighted(self) -> None:
        X = [[1.0], [1.0], [-1.0], [-1.0]]
        y = [1, 0, 0, 0]
        a = te.fit_logistic(X, y, iters=200)
        b = te.fit_logistic(X, y, iters=200, sw=[10.0, 0.1, 1.0, 1.0])
        self.assertNotAlmostEqual(a[0][0], b[0][0], places=3)


class LookAheadInvarianceTest(unittest.TestCase):
    def test_holdout_labels_never_move_the_model(self) -> None:
        X, y, mids, times, ids = dataset()
        _, m1 = te.evaluate_and_fit(X, y, mids, times, ids)
        cutoff = max(times) - 4 * 86400
        flipped = [1 - v if t >= cutoff else v for v, t in zip(y, times)]
        _, m2 = te.evaluate_and_fit(X, flipped, mids, times, ids)
        self.assertEqual(m1, m2)

    def test_features_ignore_future_spot_and_candles(self) -> None:
        market = {"close_time": "2026-10-06T16:15:00Z", "floor_strike": 100.0}
        base = 1_791_303_300  # some ts
        candles = [
            {"end_period_ts": base + 60 * i, "yes_bid": {"close_dollars": "0.40"}, "yes_ask": {"close_dollars": "0.42"},
             "price": {"close_dollars": "0.41"}}
            for i in range(6)
        ]
        spot = [(base - 60 * k, 100.0 + 0.01 * k) for k in range(30, 0, -1)]
        a = te.features_for(market, candles, spot, 2)
        decision_ts = base + 120  # end_period_ts of candles[2]
        future_spot = spot + [(decision_ts - 59 + 60 * k, 500.0) for k in range(0, 20)]
        future_candles = candles[:3] + [dict(c, yes_bid={"close_dollars": "0.99"}) for c in candles[3:]]
        b = te.features_for(market, future_candles, future_spot, 2)
        self.assertEqual(a, b)


class FeeAndChampionTest(unittest.TestCase):
    def test_fee_rounds_up_to_the_cent(self) -> None:
        self.assertEqual(te.kalshi_fee(0.50), 0.02)  # 0.0175 → 0.02
        self.assertEqual(te.kalshi_fee(0.50, 10), 0.18)  # 0.175 → 0.18
        self.assertEqual(te.kalshi_fee(0.95), 0.01)  # 0.003325 → 0.01
        self.assertAlmostEqual(te.side_pnl(True, 0.50, 1), 0.48)
        self.assertAlmostEqual(te.side_pnl(True, 0.50, 0), -0.52)

    def test_challenger_must_beat_champion_and_market(self) -> None:
        base = {
            "synthetic": False, "n_markets": 5000, "n_rows": 12000, "n_holdout": 800,
            "model_brier": 0.160, "market_brier": 0.186, "model_logloss": 0.48, "market_logloss": 0.52,
            "sim_pnl": 12.0, "sim_trades": 80, "market_pnl": 2.0,
        }
        self.assertTrue(gates.publish_decision({**base, "beats_champion": True})["publishable"])
        better_champ = {**base, "champion": {"model_brier": 0.150, "model_logloss": 0.47, "sim_pnl": 15.0}}
        cc = te.champion_challenger(better_champ)
        self.assertFalse(cc["beats_champion"])
        d = gates.publish_decision({**base, "beats_champion": cc["beats_champion"]})
        self.assertFalse(d["publishable"])
        self.assertFalse(d["beat_market"])
        worse_champ = {**base, "champion": {"model_brier": 0.170, "model_logloss": 0.50, "sim_pnl": 3.0}}
        self.assertTrue(te.champion_challenger(worse_champ)["beats_champion"])
        # Beats the champion but not the market → still no publish.
        lose_mkt = {**base, "sim_pnl": 1.0, "market_pnl": 2.0, "champion": {"model_brier": 0.2, "model_logloss": 0.6, "sim_pnl": 0.5}}
        self.assertFalse(gates.publish_decision({**lose_mkt, "beats_champion": te.champion_challenger(lose_mkt)["beats_champion"]})["publishable"])

    def test_sanity_gate_blocks_leakage(self) -> None:
        r = te.sanity_reasons({"train_max_ts": 10, "holdout_min_ts": 5, "model_brier": 0.05, "market_brier": 0.2})
        self.assertTrue(any("look-ahead" in x for x in r))
        self.assertTrue(any("suspiciously" in x for x in r))
        self.assertFalse(gates.publish_decision({"sanity_reasons": r})["publishable"])

    def test_clustered_bootstrap_is_seeded(self) -> None:
        pairs = [(f"m{i // 2}", 0.1 if i % 3 else -0.2) for i in range(60)]
        a = te.clustered_bootstrap_ci(pairs)
        self.assertEqual(a, te.clustered_bootstrap_ci(pairs))
        self.assertEqual(a["clusters"], 30)
        self.assertLessEqual(a["lo"], a["mean"])

    def test_champion_loaded_only_if_real_and_compatible(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            mp = Path(tmp) / "m.json"
            fp = Path(tmp) / "f.json"
            mp.write_text(json.dumps({"feature_names": te.FEATURE_NAMES, "weights": [0.0] * len(te.FEATURE_NAMES),
                                      "bias": 0, "mean": [0.0] * 10, "std": [1.0] * 10}))
            fp.write_text(json.dumps({"tag": "model-20261001", "synthetic": False, "data_source": "kalshi_settled_coinbase_spot_v1"}))
            self.assertEqual(te.load_champion(mp, fp)["_tag"], "model-20261001")
            fp.write_text(json.dumps({"tag": "model-20261001", "synthetic": True}))
            self.assertIsNone(te.load_champion(mp, fp))
            self.assertIsNone(te.load_champion(Path(tmp) / "missing.json", None))


class ReportAndRunTest(unittest.TestCase):
    def test_fixture_run_writes_immutable_report_and_manifest_links_it(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "edge_model.json"
            man = Path(tmp) / "edge_model_manifest.json"
            rc = te.main(["--fixture", "--out", str(out), "--manifest", str(man)])
            self.assertEqual(rc, 0)
            payload = json.loads(man.read_text())
            name = payload["eval_report"]
            self.assertTrue(name.startswith("eval-"))
            rp = Path(tmp) / "reports" / name
            self.assertTrue(rp.is_file())
            report = json.loads(rp.read_text())
            self.assertFalse(report["fetch"]["authenticated"])
            self.assertEqual(report["config"]["fee_rounding"], "ceil_cent")
            self.assertFalse(payload["publishable"])
            with self.assertRaises(FileExistsError):
                open(rp, "x")

    def test_eval_report_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p1, _ = te.write_eval_report(Path(tmp), {"a": 1}, "2026-10-06T00:00:00Z")
            with self.assertRaises(FileExistsError):
                te.write_eval_report(Path(tmp), {"a": 1}, "2026-10-06T00:00:00Z")
            p2, _ = te.write_eval_report(Path(tmp), {"a": 2}, "2026-10-06T00:00:00Z")
            self.assertNotEqual(p1, p2)

    def test_daily_series_are_supported(self) -> None:
        for s in ("KXBTCD", "KXETHD", "KXSOLD"):
            self.assertIn(s, te.PRODUCT)
            self.assertTrue(te.is_daily(s))
        self.assertFalse(te.is_daily("KXBTC15M"))


class PacedFetchTest(unittest.TestCase):
    def setUp(self) -> None:
        te._PACE["interval"] = te._PACE["base"] = 0.0
        te._LAST["kalshi"] = te._LAST["other"] = 0.0

    def test_429_backs_off_and_widens_interval_without_auth(self) -> None:
        seen_headers = []
        calls = {"n": 0}

        def fake_open(req, timeout=0):
            seen_headers.append(dict(req.header_items()))
            calls["n"] += 1
            if calls["n"] == 1:
                raise urllib.error.HTTPError(req.full_url, 429, "Too Many", {"Retry-After": "0"}, io.BytesIO(b""))
            return io.BytesIO(b'{"ok": true}')

        os.environ["KALSHI_API_KEY"] = "must-not-be-used"
        with mock.patch.object(te.urllib.request, "urlopen", side_effect=fake_open), mock.patch.object(te.time, "sleep"):
            out = te.http_get("https://api.elections.kalshi.com/trade-api/v2/markets")
        self.assertEqual(out, {"ok": True})
        self.assertNotIn("KALSHI_API_KEY", os.environ)
        self.assertGreaterEqual(te._PACE["interval"], 0.5 * 0.98)
        for h in seen_headers:
            self.assertFalse(any(k.lower() in ("authorization", "kalshi-access-key") for k in h))

    def test_candle_429_gives_up_after_three_tries(self) -> None:
        def always_429(req, timeout=0):
            raise urllib.error.HTTPError(req.full_url, 429, "Too Many", {}, io.BytesIO(b""))

        with mock.patch.object(te.urllib.request, "urlopen", side_effect=always_429) as m, mock.patch.object(te.time, "sleep"):
            with self.assertRaises(urllib.error.HTTPError):
                te.http_get("https://api.elections.kalshi.com/trade-api/v2/series/X/markets/Y/candlesticks?a=1")
        self.assertEqual(m.call_count, 3)


if __name__ == "__main__":
    unittest.main()
