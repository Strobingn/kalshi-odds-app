#!/usr/bin/env python3
"""Publish-gate extras for 0.3.37: champion, walk-forward, sanity, bootstrap.

Stdlib only. Does not read a Kalshi key.
"""
from __future__ import annotations

import math
import os
import random
import unittest


HOLDOUT_SECONDS = 4 * 86400
HALF_LIFE_DAYS = 14.0


def scrub_kalshi_env() -> list[str]:
    removed = []
    for key in list(os.environ):
        if key.upper().startswith("KALSHI"):
            os.environ.pop(key, None)
            removed.append(key)
    return removed


def walk_forward_split(times: list[int]) -> tuple[list[int], list[int]]:
    if not times:
        return [], []
    cutoff = max(times) - HOLDOUT_SECONDS
    train = [i for i, t in enumerate(times) if t < cutoff]
    hold = [i for i, t in enumerate(times) if t >= cutoff]
    return train, hold


def recency_weights(times: list[int], half_life_days: float = HALF_LIFE_DAYS) -> list[float]:
    if not times:
        return []
    newest = max(times)
    lam = math.log(2.0) / (half_life_days * 86400.0)
    return [math.exp(-lam * max(0, newest - t)) for t in times]


def challenger_gate(metrics: dict) -> dict:
    reasons: list[str] = []
    mb = float(metrics.get("model_brier", 1.0))
    kb = float(metrics.get("market_brier", 1.0))
    ml = float(metrics.get("model_logloss", 1.0))
    kl = float(metrics.get("market_logloss", 1.0))
    if mb >= kb:
        reasons.append("candidate Brier does not beat the market")
    if ml >= kl:
        reasons.append("candidate log loss does not beat the market")
    if metrics.get("champion_brier") is not None:
        if mb >= float(metrics["champion_brier"]):
            reasons.append("candidate Brier does not beat the champion")
        if ml >= float(metrics.get("champion_logloss", 0.0)):
            reasons.append("candidate log loss does not beat the champion")
    final_n = int(metrics.get("final60_n") or 0)
    if final_n < 30:
        reasons.append("final-60s bucket is not shown to be calibrated")
    elif float(metrics.get("final60_model_brier", 1.0)) > float(metrics.get("final60_market_brier", 0.0)):
        reasons.append("final-60s bucket is worse than the market")
    if float(metrics.get("book_missing_fraction") or 0.0) > 0.02:
        reasons.append("model depends on missing or stale book data")
    exec_pnl = metrics.get("exec_pnl")
    if exec_pnl is None or float(exec_pnl) <= 0.0:
        reasons.append("execution-aware replay is not positive after fees")
    boot = metrics.get("brier_diff_ci_high")
    if boot is not None and float(boot) >= 0.0:
        reasons.append("bootstrap CI on Brier difference does not clear zero")
    for key in ("yes_no_ask_sum_ok", "favourite_tracks_price", "settlement_audit_ok"):
        if metrics.get(key) is False:
            reasons.append(f"sanity gate failed: {key}")
    return {"publishable": not reasons, "reasons": reasons}


def paired_bootstrap_diff(model_scores: list[float], base_scores: list[float], clusters: list[str], draws: int = 200, seed: int = 1) -> dict:
    rng = random.Random(seed)
    by = {}
    for s, b, c in zip(model_scores, base_scores, clusters):
        by.setdefault(c, []).append(s - b)
    keys = list(by)
    diffs = []
    for _ in range(draws):
        chosen = [keys[rng.randrange(len(keys))] for _ in keys]
        vals = [v for k in chosen for v in by[k]]
        diffs.append(sum(vals) / len(vals))
    diffs.sort()
    lo = diffs[int(0.025 * (len(diffs) - 1))]
    hi = diffs[int(0.975 * (len(diffs) - 1))]
    return {"ci_low": lo, "ci_high": hi}


def queue_fill(depth_ahead: float, our_size: float, prints: list[tuple[float, float]], limit: float, optimistic: bool) -> bool:
    seen = 0.0
    through = False
    for price, size in prints:
        if price <= limit + 1e-12:
            seen += size
        if price + 1e-12 < limit:
            through = True
        cleared = seen + 1e-9 >= depth_ahead + our_size
        if cleared or (optimistic and through):
            return True
    return False


def sanity_asks(yes_ask: float, no_ask: float) -> bool:
    cents = (yes_ask + no_ask) * 100.0
    return 99.0 - 1e-6 <= cents <= 112.0 + 1e-6


class EvalExtraTest(unittest.TestCase):
    def test_holdout_is_the_last_four_days_only(self) -> None:
        t0 = 1_700_000_000
        times = [t0 + i * 3600 for i in range(24 * 10)]
        train, hold = walk_forward_split(times)
        cutoff = max(times) - HOLDOUT_SECONDS
        self.assertTrue(all(times[i] < cutoff for i in train))
        self.assertTrue(all(times[i] >= cutoff for i in hold))
        self.assertTrue(set(train).isdisjoint(hold))

    def test_recency_weights_prefer_newer_rows(self) -> None:
        times = [0, 10 * 86400, 40 * 86400]
        w = recency_weights(times)
        self.assertGreater(w[-1], w[0])
        self.assertAlmostEqual(w[-1], 1.0, places=6)

    def test_challenger_must_beat_champion_and_market(self) -> None:
        bad = challenger_gate(
            {
                "model_brier": 0.2,
                "market_brier": 0.18,
                "model_logloss": 0.6,
                "market_logloss": 0.5,
                "champion_brier": 0.17,
                "champion_logloss": 0.48,
                "final60_n": 40,
                "final60_model_brier": 0.3,
                "final60_market_brier": 0.2,
                "book_missing_fraction": 0.2,
                "exec_pnl": -1.0,
                "brier_diff_ci_high": 0.01,
                "yes_no_ask_sum_ok": False,
                "favourite_tracks_price": False,
                "settlement_audit_ok": False,
            }
        )
        self.assertFalse(bad["publishable"])
        good = challenger_gate(
            {
                "model_brier": 0.15,
                "market_brier": 0.18,
                "model_logloss": 0.45,
                "market_logloss": 0.50,
                "champion_brier": 0.16,
                "champion_logloss": 0.47,
                "final60_n": 80,
                "final60_model_brier": 0.16,
                "final60_market_brier": 0.18,
                "book_missing_fraction": 0.0,
                "exec_pnl": 3.0,
                "brier_diff_ci_high": -0.001,
                "yes_no_ask_sum_ok": True,
                "favourite_tracks_price": True,
                "settlement_audit_ok": True,
            }
        )
        self.assertTrue(good["publishable"])

    def test_scrub_removes_kalshi_env(self) -> None:
        os.environ["KALSHI_API_KEY"] = "should-not-leak"
        removed = scrub_kalshi_env()
        self.assertIn("KALSHI_API_KEY", removed)
        self.assertNotIn("KALSHI_API_KEY", os.environ)

    def test_queue_and_sanity(self) -> None:
        prints = [(0.40, 5.0), (0.39, 3.0)]
        self.assertFalse(queue_fill(10, 4, prints, 0.40, optimistic=False))
        self.assertTrue(queue_fill(10, 4, prints, 0.40, optimistic=True))
        self.assertTrue(sanity_asks(0.48, 0.53))
        self.assertFalse(sanity_asks(0.10, 0.10))


if __name__ == "__main__":
    unittest.main()
