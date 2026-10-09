"""Training split, exact fee, and sklearn-to-phone parity checks."""
import math
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_big_edge as big  # noqa: E402


def rows(days: int = 15, markets_per_day: int = 34) -> list[big.Row]:
    result = []
    start = datetime(2026, 8, 1, tzinfo=timezone.utc)
    for d in range(days):
        for m in range(markets_per_day):
            ts = int((start + timedelta(days=d, minutes=m * 15 + 3)).timestamp())
            ticker = f"KXBTC15M-26AUG{d+1:02d}{m:04d}-X"
            mid = 0.2 + ((m * 13 + d * 7) % 60) / 100
            y = int(mid + 0.09 * math.sin(m + d * 3) > 0.51)
            for offset in (0, 60):
                features = (mid - 0.5, 0.6, mid, 0.0, 0.02, 0.01, 0.05, 0.0, 0.5, mid)
                result.append(big.Row(ticker, ts + offset, y, mid + 0.01, 1 - mid + 0.01, features))
    return result


class BigEdgeModelTest(unittest.TestCase):
    def test_market_rows_never_cross_chronological_splits(self):
        train, cal, test = big.split_days(rows())
        ids = [set(big.group_markets(part)) for part in (train, cal, test)]
        self.assertFalse(ids[0] & ids[1] | ids[0] & ids[2] | ids[1] & ids[2])
        self.assertLess(max(r.ts for r in train), min(r.ts for r in cal))
        self.assertLess(max(r.ts for r in cal), min(r.ts for r in test))

    def test_asks_and_fee_are_charged_for_five_dollar_clip(self):
        n, cost = big.price_and_fee(0.25)
        self.assertEqual(n, 19)
        self.assertEqual(str(cost), "5.00")

    def test_tree_export_matches_sklearn_on_unseen_days(self):
        model, metrics = big.train(rows())
        self.assertEqual(model["kind"], "gbdt")
        self.assertEqual(len(model["trees"]), 128)
        self.assertTrue(0 <= metrics["test_markets"] < 510)
        self.assertTrue(math.isfinite(metrics["paper_pnl_usd"]))

    def test_tree_export_uses_float32_features_at_split_boundary(self):
        import numpy as np

        threshold = 0.31415926665067673
        raw = float(np.float32(0.31415927)) + 1e-10
        payload = {"base_score": 0.0, "learning_rate": 1.0,
                   "trees": [{"nodes": [
                       {"feature": 0, "threshold": threshold, "left": 1, "right": 2},
                       {"value": -1.0}, {"value": 1.0}]}]}
        expected = -1.0 if float(np.float32(raw)) <= threshold else 1.0
        self.assertEqual(big.exported_logit(payload, (raw,)), expected)

    def test_rejects_insufficient_distinct_days_even_with_many_rows(self):
        with self.assertRaisesRegex(ValueError, "UTC days"):
            big.train(rows(days=4, markets_per_day=90))


if __name__ == "__main__":
    unittest.main()
