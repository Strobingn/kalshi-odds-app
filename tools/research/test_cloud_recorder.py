#!/usr/bin/env python3
"""No-network checks: cloud_recorder rows round-trip through recordings.load_day."""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import cloud_recorder as c  # noqa: E402
from recordings import load_day  # noqa: E402

TS = 1_790_000_000_000  # 2026-09-21


def test_book_row_from_fp_orderbook() -> None:
    body = {"orderbook_fp": {"yes_dollars": [["0.4500", "10"], ["0.4600", "3"]], "no_dollars": [["0.5100", "7"]]}}
    r = c.book_row(TS, "KXBTC15M-X", 84000.0, TS + 600_000, body)
    assert r[4:8] == [0.46, 3, 0.49, 7], r
    assert r[8:12] == [0.51, 7, 0.54, 3], r
    assert c.book_row(TS, "X", None, None, None) is None


def test_trade_row_dollars_and_cents() -> None:
    t = {"ticker": "K", "created_time": "2026-09-21T12:00:00Z", "yes_price_dollars": "0.4700", "count_fp": "5.00",
         "taker_side": "no"}
    assert c.trade_row(t) == [1789992000000, "K", 0.47, 5, "no"]
    t2 = {"ticker": "K", "created_time": "2026-09-21T12:00:01Z", "yes_price": 33, "count": 2, "taker_side": "YES"}
    assert c.trade_row(t2)[2:] == [0.33, 2, "yes"]
    assert c.trade_row({"ticker": "K"}) is None


def test_sink_roundtrip_multi_member() -> None:
    with tempfile.TemporaryDirectory() as d:
        s = c.Sink(Path(d))
        s.add("spot", TS, [TS, "BTC-USD", 84000.5])
        s.flush()
        s.add("spot", TS + 1000, [TS + 1000, "BTC-USD", 84001.0])
        s.add("book", TS, c.book_row(TS, "KXBTC15M-X", 84000.0, TS + 600_000,
                                     {"orderbook_fp": {"yes_dollars": [["0.45", "10"]], "no_dollars": [["0.51", "7"]]}}))
        s.add("settle", TS, ["KXBTC15M-X", TS + 600_000, 84000.0, "yes"])
        s.flush()
        day = load_day(d, c.day_of(TS))
        assert [r["price"] for r in day["spot"]] == [84000.5, 84001.0], day["spot"]
        assert day["book"][0]["yes_ask"] == 0.49 and day["book"][0]["close_ms"] == TS + 600_000
        assert day["settle"][0]["result"] == "yes"


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
