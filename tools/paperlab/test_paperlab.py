import json
import math
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from paperlab import Account, Rules, ask, book_levels, cost, cycle, forecast, size


class PaperLabTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "account.sqlite"
        self.account = Account(self.path)
        self.now = 1800000300.0

    def tearDown(self):
        self.account.db.close()
        self.tmp.cleanup()

    def fill(self, ticker="KXBTC15M-test", side="YES", price=0.25, depth=100, p=0.8):
        return self.account.fill(ticker, side, price, depth, p, self.now+600, self.now)

    def test_fee_and_affordable_size(self):
        self.assertEqual(cost(10, 0.5), (518, 18))
        self.assertEqual(cost(19, 0.25), (500, 25))
        self.assertEqual(size(0.25, 100, 500, 0.07), 19)
        self.assertEqual(size(0.25, 2.9, 500, 0.07), 2)
        self.assertEqual(size(0, 100, 500, 0.07), 0)

    def test_initial_cash_restart_and_idempotent_win(self):
        self.assertEqual(self.account.summary()["cash"], 1000)
        self.assertTrue(self.fill())
        self.assertEqual(self.account.summary()["cash"], 995)
        self.assertFalse(self.fill())
        self.assertFalse(self.account.settle("KXBTC15M-test", "yes", self.now))
        self.account.db.close()
        self.account = Account(self.path)
        self.assertEqual(self.account.summary()["open"], 1)
        self.assertTrue(self.account.settle("KXBTC15M-test", "yes", self.now+601))
        self.assertFalse(self.account.settle("KXBTC15M-test", "yes", self.now+602))
        self.assertEqual(self.account.summary()["cash"], 1014)
        self.assertEqual(self.account.summary()["realized_pnl"], 14)

    def test_losing_no_debits_only_once_and_unknown_stays_open(self):
        self.assertTrue(self.fill(side="NO", p=0.2))
        self.assertFalse(self.account.settle("KXBTC15M-test", "void", self.now+601))
        self.assertEqual(self.account.summary()["open"], 1)
        self.assertTrue(self.account.settle("KXBTC15M-test", "yes", self.now+601))
        self.assertEqual(self.account.summary()["cash"], 995)
        self.assertEqual(self.account.summary()["realized_pnl"], -5)

    def test_reject_negative_value_and_expired_window(self):
        self.assertFalse(self.fill(p=0.26))
        self.assertFalse(self.account.fill("KXBTC15M-late", "YES", 0.25, 100, 0.8,
                                           self.now+20, self.now))
        self.assertEqual(self.account.summary()["cash"], 1000)

    def test_daily_stop_counts_pending_losses(self):
        filled = 0
        for i in range(20):
            if not self.fill(ticker=f"KXBTC15M-{i}"):
                break
            filled += 1
        self.assertGreaterEqual(filled, 4)
        self.assertLessEqual(self.account.summary()["open_cost"], 20)
        self.assertEqual(size(0.25, 100, self.account.capacity(self.now), 0.07), 0)
        self.assertFalse(self.fill(ticker="KXBTC15M-extra"))
        for i in range(filled):
            self.account.settle(f"KXBTC15M-{i}", "no", self.now+601)
        self.assertEqual(size(0.25, 100, self.account.capacity(self.now+601), 0.07), 0)
        self.assertGreater(self.account.capacity(self.now+86400), 0)

    def test_rules_cannot_change_in_existing_experiment(self):
        with self.assertRaisesRegex(ValueError, "rules changed"):
            Account(self.path, Rules(margin=0.01))

    def test_fixed_point_and_legacy_book_are_equivalent(self):
        fp = book_levels({"orderbook_fp": {"yes_dollars": [["0.50", "10.5"]],
                                            "no_dollars": [["0.25", "20"]]}})
        old = book_levels({"orderbook": {"yes": [[50, 10.5]], "no": [[25, 20]]}})
        self.assertEqual(fp, old)
        self.assertEqual(ask(fp, "NO"), (0.5, 10.5))
        with self.assertRaisesRegex(ValueError, "crossed"):
            book_levels({"orderbook": {"yes": [[80, 10]], "no": [[30, 20]]}})

    def candles(self):
        end = int(self.now // 60) * 60 - 60
        return [[end-i*60, 0, 0, 0, 100*math.exp(0.002*math.sin(i)), 1] for i in range(70)]

    def test_forecast_excludes_future_candles_and_rejects_gaps(self):
        bars = self.candles()
        p = forecast(0.5, 102, 100, 600, bars, self.now, 0.25)
        future = bars + [[self.now+60, 0, 0, 0, 0.01, 1]]
        self.assertEqual(p, forecast(0.5, 102, 100, 600, future, self.now, 0.25))
        self.assertGreater(p, 0.5)
        self.assertLessEqual(p, 0.625)
        with self.assertRaisesRegex(ValueError, "gap"):
            forecast(0.5, 102, 100, 600, bars[:20]+bars[21:], self.now, 0.25)

    def fake_feed(self, worse=False, stale=False):
        test = self
        def iso(ts):
            return datetime.fromtimestamp(ts, timezone.utc).isoformat()
        class Feed:
            calls = 0
            def get(self, url, timely=True):
                if "/candles" in url:
                    return test.candles()
                if "/ticker" in url:
                    return {"time": iso(test.now-10 if stale else test.now), "price": "102"}
                return {"markets": [{"ticker": "KXBTC15M-cycle", "status": "active",
                            "open_time": iso(test.now-300), "close_time": iso(test.now+600),
                            "floor_strike": 100, "strike_type": "greater", "cap_strike": None}]}
            def book(self, ticker):
                self.calls += 1
                return {"yes": [(0.49, 100)], "no": [(0.40 if worse and self.calls>1 else 0.49, 100)]}
        return Feed()

    def test_delayed_quote_movement_prevents_same_quote_fill(self):
        feed = self.fake_feed(worse=True)
        with patch("paperlab.time.time", return_value=self.now), patch("paperlab.time.sleep") as sleep:
            cycle(self.account, feed)
        self.assertEqual(feed.calls, 2)
        sleep.assert_called_once_with(1.0)
        self.assertEqual(self.account.summary()["open"], 0)
        self.assertEqual(self.account.db.execute("SELECT kind FROM observations ORDER BY id DESC").fetchone()[0], "unfilled")

    def test_paper_cycle_and_stale_spot(self):
        with patch("paperlab.time.time", return_value=self.now), patch("paperlab.time.sleep"):
            cycle(self.account, self.fake_feed())
        self.assertEqual(self.account.summary()["open"], 1)
        # Separate account: stale data must not create a fill.
        self.account.db.close()
        self.account = Account(Path(self.tmp.name) / "stale.sqlite")
        with patch("paperlab.time.time", return_value=self.now):
            with self.assertRaisesRegex(ValueError, "stale spot"):
                cycle(self.account, self.fake_feed(stale=True))
        self.assertEqual(self.account.summary()["open"], 0)


if __name__ == "__main__":
    unittest.main()
