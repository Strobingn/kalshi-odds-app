#!/usr/bin/env python3
"""Tests for recordings.py and lag_study.py (stdlib unittest).

    python3 tools/research/test_recordings.py
"""
from __future__ import annotations

import gzip
import math
import shutil
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import lag_study  # noqa: E402
from recordings import (  # noqa: E402
    HEADERS,
    list_days,
    load_day,
    load_days,
    parse_csv,
    read_gzip_tolerant,
)

FIXTURE = HERE / "fixtures" / "recordings"


def sync_flushed(text: str) -> bytes:
    """What the app leaves on disk for an open member: header + sync-flushed data, no trailer."""
    c = zlib.compressobj(6, zlib.DEFLATED, 16 + zlib.MAX_WBITS)
    return c.compress(text.encode()) + c.flush(zlib.Z_SYNC_FLUSH)


class GzipToleranceTest(unittest.TestCase):
    def test_concatenated_members(self):
        data = gzip.compress(b"a\nb\n") + gzip.compress(b"c\n")
        self.assertEqual(read_gzip_tolerant(data), b"a\nb\nc\n")
        self.assertEqual(gzip.decompress(data), b"a\nb\nc\n")  # stdlib agrees

    def test_truncated_tail_keeps_flushed_lines(self):
        data = gzip.compress(b"h\n1\n") + sync_flushed("2\n3\n")
        self.assertEqual(read_gzip_tolerant(data), b"h\n1\n2\n3\n")
        with self.assertRaises(EOFError):
            gzip.decompress(data)

    def test_truncated_member_followed_by_new_member(self):
        data = sync_flushed("h\n1\n2\n") + gzip.compress(b"3\n4\n")
        self.assertEqual(read_gzip_tolerant(data), b"h\n1\n2\n3\n4\n")

    def test_partial_line_and_cut_bytes_are_dropped(self):
        full = sync_flushed("h\n" + "".join(f"{i},row\n" for i in range(2000)))
        cut = full[: len(full) // 2]  # crash mid-write: arbitrary byte prefix
        text = read_gzip_tolerant(cut + gzip.compress(b"9999,row\n")).decode()
        lines = text.splitlines()
        self.assertEqual(lines[0], "h")
        self.assertEqual(lines[-1], "9999,row")
        for i, line in enumerate(lines[1:-1]):
            self.assertEqual(line, f"{i},row")

    def test_garbage_prefix_and_empty(self):
        self.assertEqual(read_gzip_tolerant(b""), b"")
        self.assertEqual(read_gzip_tolerant(b"xx" + gzip.compress(b"a\n")), b"a\n")


class ParseTest(unittest.TestCase):
    def test_rows_written_by_the_app(self):
        # Exact strings from MarketDataRecorderTest.formatRowsMatchTheResearchContract.
        t0 = 1790596800000
        text = "\n".join([
            ",".join(HEADERS["book"]),
            f"{t0},KXBTC15M-X,65000,{t0 + 60000},0.55,10,0.57,4,0.43,4,0.45,10",
            f"{t0},KXBTC15M-X,,,0.55,,,,,,0.45,",
            "garbage,line",
        ])
        rows = parse_csv("book", text)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["close_ms"], t0 + 60000)
        self.assertEqual(rows[0]["yes_ask_qty"], 4.0)
        self.assertIsNone(rows[1]["strike"])
        self.assertIsNone(rows[1]["yes_ask"])
        self.assertEqual(rows[1]["no_ask"], 0.45)
        trades = parse_csv("trades", f"{','.join(HEADERS['trades'])}\n{t0},KXBTC15M-X,0.56,,\n")
        self.assertEqual(trades, [{"ts_ms": t0, "ticker": "KXBTC15M-X", "yes_price": 0.56,
                                   "count": None, "taker_side": None}])

    def test_headers_match_the_app(self):
        self.assertEqual(",".join(HEADERS["spot"]), "ts_ms,product,price")
        self.assertEqual(
            ",".join(HEADERS["book"]),
            "ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,"
            "no_bid,no_bid_qty,no_ask,no_ask_qty",
        )
        self.assertEqual(",".join(HEADERS["trades"]), "ts_ms,ticker,yes_price,count,taker_side")
        self.assertEqual(",".join(HEADERS["settle"]), "ticker,close_ms,strike,result")


class FixtureLoadTest(unittest.TestCase):
    def test_list_and_load(self):
        self.assertEqual(list_days(FIXTURE), ["2026-09-26", "2026-09-27"])
        day = load_day(FIXTURE, "2026-09-26")
        self.assertEqual(len(day["spot"]), 240)  # includes the truncated first member
        self.assertEqual(len(day["book"]), 175)
        self.assertEqual(len(day["trades"]), 3)
        self.assertEqual(day["settle"][0]["result"], "yes")
        self.assertEqual(day["spot"][0]["product"], "BTC-USD")
        self.assertIsInstance(day["spot"][0]["ts_ms"], int)
        self.assertIsNone(day["trades"][2]["taker_side"])
        ts = [r["ts_ms"] for r in day["spot"]]
        self.assertEqual(ts, sorted(ts))

    def test_missing_day_and_dir(self):
        self.assertEqual(load_day(FIXTURE, "2020-01-01"), {"spot": [], "book": [], "trades": [], "settle": []})
        self.assertEqual(list_days(FIXTURE / "nope"), [])

    def test_load_days_merges(self):
        both = load_days(FIXTURE)
        self.assertEqual(len(both["spot"]), 480)
        self.assertEqual(len(both["settle"]), 2)


class LagStudyTest(unittest.TestCase):
    def test_fixture_lead_lag(self):
        r = lag_study.run(FIXTURE)
        self.assertEqual(r["markets"], 2)
        self.assertEqual(r["peak_lag"], 3)  # fixture Kalshi mid follows spot by 3 s
        self.assertGreater(r["xcorr"]["3"]["corr"], 0.9)
        self.assertEqual(r["events"], 2)
        self.assertEqual(r["delay_median"], 3)
        self.assertEqual(r["bets"], 2)
        self.assertEqual(r["wins"], 2)
        self.assertEqual({b["side"] for b in r["bet_list"]}, {"yes", "no"})
        self.assertGreater(r["pnl_total"], 0)
        lo, hi = r["pnl_ci95"]
        self.assertTrue(lo <= r["pnl_mean"] <= hi)
        self.assertIn("Peak lag: **+3 s**", lag_study.report(r))

    def test_high_margin_places_no_bets(self):
        r = lag_study.run(FIXTURE, margin=0.5)
        self.assertEqual(r["bets"], 0)
        self.assertTrue(math.isnan(r["pnl_ci95"][0]))
        lag_study.report(r)  # renders without data

    def test_single_day_and_empty_dir(self):
        r = lag_study.run(FIXTURE, days=["2026-09-26"])
        self.assertEqual(r["bets"], 1)
        self.assertTrue(math.isnan(r["pnl_ci95"][0]))  # day-block CI needs >= 2 days
        tmp = Path(tempfile.mkdtemp())
        try:
            empty = lag_study.run(tmp)
            self.assertEqual(empty["events"], 0)
            self.assertIsNone(empty["peak_lag"])
        finally:
            shutil.rmtree(tmp)

    def test_book_mid_needs_both_sides(self):
        self.assertIsNone(lag_study.mid_of({"yes_bid": 0.4, "yes_ask": None}))
        self.assertAlmostEqual(lag_study.mid_of({"yes_bid": 0.4, "yes_ask": 0.42}), 0.41)


if __name__ == "__main__":
    unittest.main()
