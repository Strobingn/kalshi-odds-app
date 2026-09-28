#!/usr/bin/env python3
"""Unit tests for settlement_study.py / settlement_fetch.py on synthetic fixtures.

Run:   python3 tools/research/test_settlement.py
Regen: python3 tools/research/test_settlement.py --regen   (rewrites fixtures/settlement)

The fixture is a tiny cache: 8 UTC days × 2 BTC markets. Its settlement value
is, by construction, the 60 s per-second TWAP of the synthetic Coinbase trades,
so the window fit must pick `twap_60s` with ~0 residual.
"""
from __future__ import annotations

import json
import math
import random
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

import settlement_fetch as sf  # noqa: E402
import settlement_study as ss  # noqa: E402

FIX = HERE / "fixtures" / "settlement"
DAY0 = 1789948800  # 2026-09-21 00:00:00 UTC
STRIKE = 100_000.0


def _path(rng: random.Random, start: int, end: int, p0: float) -> dict[int, float]:
    px, out = p0, {}
    for t in range(start, end + 1):
        px *= math.exp(rng.gauss(0.0, 0.00006))
        out[t] = round(px, 2)
    return out


def make_fixture(dest: Path) -> None:
    rng = random.Random(5)
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "candles").mkdir(exist_ok=True)
    (dest / "settlement_trades").mkdir(exist_ok=True)
    for p in list((dest / "candles").glob("*.json")) + list((dest / "settlement_trades").glob("*.json")):
        p.unlink()
    markets, settles, bars = [], [], {}
    for day in range(8):
        for k in range(2):
            close = DAY0 + day * 86400 + 3600 * (10 + k)
            opn = close - 900
            ticker = f"KXBTC15M-FIX{day}{k}"
            path = _path(rng, close - 45 * 60, close + 180, STRIKE * (1 + rng.gauss(0, 0.0004)))
            settle = sum(path[close - 60 + i] for i in range(1, 61)) / 60.0
            y = "yes" if settle > STRIKE else "no"
            markets.append({
                "ticker": ticker, "series": "KXBTC15M", "coin": "BTC", "result": y, "floor_strike": STRIKE,
                "open_ms": opn * 1000, "close_ms": close * 1000, "settled_ms": close * 1000 + 8000,
                "source": "live", "title": "fixture", "yes_sub_title": "fixture",
            })
            settles.append({
                "ticker": ticker, "result": y, "floor_strike": STRIKE, "expiration_value": f"{settle:.2f}",
                "close_time": "fixture", "rules_primary": f"If the fixture index at {close} is above {STRIKE}, Yes.",
                "_ticker": ticker, "_via": "fixture", "_fetched_at": "fixture",
            })
            for t0 in range(close - 45 * 60, close + 180, 60):
                seg = [path[t] for t in range(t0, t0 + 60)]
                bars[t0] = [t0, min(seg), max(seg), seg[0], seg[-1], 1.0]
            cand = []
            for end in range(opn + 60, close + 1, 60):
                spot = path[end]
                tte = max(1, close - end)
                z = math.log(spot / STRIKE) / (0.0006 * math.sqrt(tte / 60.0))
                mid = min(0.97, max(0.03, round(ss.norm_cdf(z), 2)))
                yb, ya = round(mid - 0.01, 2), round(mid + 0.01, 2)
                cand.append({"end_ts": end, "yes_bid": {"open": yb, "high": yb, "low": yb, "close": yb},
                             "yes_ask": {"open": ya, "high": ya, "low": ya, "close": ya},
                             "price": {}, "volume": 10.0, "oi": 5.0, "mid": (yb + ya) / 2})
            (dest / "candles" / f"{ticker}.json").write_text(json.dumps(cand, separators=(",", ":")))
            if k == 0:  # trade sample: one trade per second, 300 ms before each mark
                tr = [[t * 1000 - 300, path[t], round(0.01 + rng.random(), 4)] for t in range(close - 150, close + 31)]
                (dest / "settlement_trades" / f"{ticker}.json").write_text(json.dumps(tr, separators=(",", ":")))
    (dest / "markets.jsonl").write_text("".join(json.dumps(m) + "\n" for m in markets))
    (dest / "settlement.jsonl").write_text("".join(json.dumps(s) + "\n" for s in settles))
    (dest / "spot_BTC-USD.json").write_text(json.dumps([bars[t] for t in sorted(bars)], separators=(",", ":")))
    (dest / "settlement_series.json").write_text(json.dumps(
        {"KXBTC15M": {"series": {"ticker": "KXBTC15M", "settlement_sources": [{"name": "fixture", "url": "x"}]}}}))


def row(ticker="T", day="2026-09-21", tte=60, y=1, z=0.2, dist_bp=1.0, sig=0.0006, mid=0.5, ya=0.51, na=0.51):
    return ss.CalRow(ticker, "BTC", day, tte, y, z, dist_bp, sig, mid, ya, na, ya, na)


class TradeRefs(unittest.TestCase):
    def test_last_first_twap_vwap(self):
        c = 1_000
        trades = [[(c - 20) * 1000, 100.0, 1.0], [(c - 5) * 1000 + 1, 110.0, 3.0], [c * 1000 + 500, 120.0, 1.0]]
        r = ss.trade_refs(trades, c)
        self.assertEqual(r["last_trade"], 110.0)
        self.assertEqual(r["first_after"], 120.0)
        # marks c-14..c: 100 through mark c-5 (the 110 trade is 1 ms later), 110 from c-4
        self.assertAlmostEqual(r["twap_15s"], (100.0 * 10 + 110.0 * 5) / 15)
        self.assertAlmostEqual(r["vwap_15s"], 110.0)  # only one trade inside (c-15, c]
        self.assertAlmostEqual(r["vwap_30s"], (100 + 330) / 4.0)
        self.assertNotIn("twap_120s", r)  # no trade before the first mark → too few marks

    def test_candle_refs(self):
        bars = {940: (1.0, 3.0, 2.0, 2.5), 880: (1.0, 1.0, 1.0, 1.0), 1000: (0, 0, 7.0, 0)}
        r = ss.candle_refs(bars, 1000)
        self.assertEqual(r["cb_1m_close"], 2.5)
        self.assertAlmostEqual(r["cb_ohlc4_last1m"], 2.125)
        self.assertAlmostEqual(r["cb_ohlc4_last2m"], (2.125 + 1.0) / 2)
        self.assertEqual(r["cb_open_next1m"], 7.0)

    def test_fit_stats(self):
        pairs = [(100.01, 100.0, 99.0, 1), (99.99, 100.0, 100.5, 0), (100.0, 100.0, 100.001, 1)]
        s = ss.fit_stats(pairs)
        self.assertEqual(s["n"], 3)
        self.assertAlmostEqual(s["med_abs_bp"], 1.0, places=6)
        self.assertAlmostEqual(s["sign_agree"], 2 / 3)
        self.assertEqual(s["near_n"], 1)


class Calibration(unittest.TestCase):
    def test_buckets(self):
        self.assertEqual(ss.z_bucket(-10), 0)
        self.assertEqual(ss.z_bucket(-0.1), 5)
        self.assertEqual(ss.z_bucket(0.0), 6)
        self.assertEqual(ss.z_bucket(99), len(ss.Z_EDGES))
        self.assertEqual(ss.bucket_label(0), "[-∞, -3)")

    def test_table(self):
        rows = [row(ticker=f"a{i}", z=0.7, y=int(i < 3), mid=0.5) for i in range(4)]
        t = ss.calibration_table(rows)[(60, ss.z_bucket(0.7))]
        self.assertEqual(t["n"], 4)
        self.assertAlmostEqual(t["p"], 0.75)
        self.assertAlmostEqual(t["gap"], 0.25)

    def test_bucket_model_shrinks_to_mid(self):
        rows = [row(ticker=f"a{i}", z=0.7, y=1, mid=0.6) for i in range(10)]
        bm = ss.BucketModel(rows, strength=10)
        self.assertAlmostEqual(bm.p(row(z=0.8)), (1.0 * 10 + 0.6 * 10) / 20)
        self.assertEqual(bm.p(row(z=-5, mid=0.2)), 0.2)  # unseen bucket → mid

    def test_digital_window(self):
        r = row(tte=60, dist_bp=10.0, sig=0.001)
        s2 = 0.001 ** 2 / 60
        self.assertAlmostEqual(ss.p_digital(r), ss.norm_cdf(0.001 / math.sqrt(s2 * 60)))
        self.assertAlmostEqual(ss.p_digital(r, 60), ss.norm_cdf(0.001 / math.sqrt(s2 * 60 / 3)))
        r2 = row(tte=120, dist_bp=10.0, sig=0.001)
        self.assertAlmostEqual(ss.p_digital(r2, 60, 2.0), ss.norm_cdf(0.001 / math.sqrt(s2 * 80 + 4e-8)))
        # W=120, tte=60: the elapsed minute averaged 0.999·spot → center shifts down
        r3 = row(tte=60, dist_bp=10.0, sig=0.001)
        r3.ohlc4_prev = (0.999,)
        center = math.log(math.exp(0.001) * ((60 * 0.999 + 60) / 120))
        self.assertAlmostEqual(ss.p_digital(r3, 120), ss.norm_cdf(center / math.sqrt(s2 * 60 ** 3 / (3 * 120 ** 2))))


class Bets(unittest.TestCase):
    def test_pnl_exact_fee(self):
        # 9 contracts @ 50¢: 4.50 + ceil(0.07·9·0.25 = 0.1575) → 4.66 debit
        self.assertAlmostEqual(ss.bet_pnl(0.50, True), 9 - 4.66)
        self.assertAlmostEqual(ss.bet_pnl(0.50, False), -4.66)
        self.assertIsNone(ss.bet_pnl(None, True))

    def test_ask_band_and_one_bet_per_market(self):
        rows = [row(ticker="m", tte=180, ya=0.05, na=0.96, y=1), row(ticker="m", tte=120, ya=0.40, y=1),
                row(ticker="m", tte=60, ya=0.40, y=1)]
        bets = ss.first_bets(rows, lambda r: "YES")
        self.assertEqual(len(bets), 1)
        self.assertEqual(bets[0].tte, 120)  # 180 skipped: 5¢ ask below the 10¢ floor
        self.assertTrue(bets[0].won)

    def test_ev_side(self):
        r = row(ya=0.50, na=0.52)
        self.assertEqual(ss.ev_side(0.60, r, 0.03), "YES")
        self.assertIsNone(ss.ev_side(0.52, r, 0.03))
        self.assertEqual(ss.ev_side(0.30, r, 0.03), "NO")

    def test_walk_forward_skips_training_days(self):
        rows = [row(ticker=f"d{d}", day=f"2026-09-{10 + d:02d}") for d in range(8)]
        seen = []

        def mk(train):
            seen.append(len(train))
            return lambda r: "YES"
        bets = ss.walk_forward(rows, mk, min_train_days=5)
        self.assertEqual([b.day for b in bets], ["2026-09-15", "2026-09-16", "2026-09-17"])
        self.assertEqual(seen, [5, 6, 7])

    def test_ci(self):
        bets = [ss.Bet("t", f"d{i % 4}", 60, "YES", 0.5, True, 1.0, None) for i in range(8)]
        lo, hi = ss.day_block_ci(bets, 0.99)
        self.assertAlmostEqual(lo, 1.0)
        self.assertAlmostEqual(hi, 1.0)


class FetchHelpers(unittest.TestCase):
    def test_spans(self):
        self.assertEqual(sf.spans([0, 60, 120, 5000], join_gap=1800), [(0, 120), (5000, 5000)])

    def test_census(self):
        c = sf.field_census([{"a": 1, "b": None, "_x": 1}, {"a": None}])
        self.assertEqual(c["fields"]["a"], {"present": 2, "non_null": 1, "example": "1"})
        self.assertNotIn("_x", c["fields"])

    def test_locate_page(self):
        # ids 1..100000, one trade per 10 ms starting at t=0 ms
        class Fake:
            calls = 0

            def page(self, before_id=None):
                self.calls += 1
                top = 100_000 if before_id is None else min(100_000, before_id - 1)
                return [(i, i * 10, 1.0, 1.0) for i in range(top, max(0, top - 1000), -1)]
        cl = Fake()
        pg = sf.locate_page(cl, 423_456, (100_000, 1_000_000), rate=0.05)  # rate off by 2×
        self.assertIsNotNone(pg)
        self.assertTrue(min(p[1] for p in pg) <= 423_456 <= max(p[1] for p in pg))
        self.assertLessEqual(cl.calls, 8)
        rows, anchor = sf.window_trades(Fake(), 400_000, 423_456, (100_000, 1_000_000), 0.1)
        self.assertEqual(rows[0][0], 400_000)
        self.assertEqual(rows[-1][0], 423_450)
        self.assertLessEqual(anchor[1], 400_000)


class Smoke(unittest.TestCase):
    def test_report_on_fixture(self):
        text = ss.build_report(FIX)
        self.assertIn("Best-fitting reference (lowest median |diff|): `twap_60s`", text)
        self.assertIn("result == (expiration_value > floor_strike): **100.00%**", text)
        self.assertIn("### tte = 60 s", text)
        self.assertIn("empirical z-bucket calibration (OOS)", text)
        self.assertIn("If the fixture index at", text)
        self.assertIn("settlement_sources", text)


if __name__ == "__main__":
    if "--regen" in sys.argv:
        make_fixture(FIX)
        print(f"wrote {FIX}")
        sys.exit(0)
    unittest.main(verbosity=1)
