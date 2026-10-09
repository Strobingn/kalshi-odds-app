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


def test_trade_row_keeps_part_contracts() -> None:
    t = {"ticker": "K", "created_time": "2026-09-21T12:00:00Z", "yes_price_dollars": "0.4700", "count_fp": "17.93",
         "taker_side": "yes"}
    assert c.trade_row(t)[3] == 17.93


def test_depth_row_best_levels_first() -> None:
    body = {"orderbook_fp": {"yes_dollars": [["0.4400", "500"], ["0.4500", "10.5"], ["0.4600", "3"]],
                             "no_dollars": [["0.5100", "7"]]}}
    assert c.depth_row(TS, "KXBTC15M-X", body) == [TS, "KXBTC15M-X", "0.46:3|0.45:10|0.44:500", "0.51:7"]
    assert c.depth_row(TS, "X", None) is None
    assert c.depth_row(TS, "X", {"orderbook_fp": {"yes_dollars": [], "no_dollars": []}}) is None
    many = {"orderbook_fp": {"yes_dollars": [[f"0.{i:02d}00", "1"] for i in range(10, 60)], "no_dollars": []}}
    assert len(c.depth_row(TS, "X", many)[2].split("|")) == c.DEPTH_LEVELS


def test_new_trades_pages_until_known_and_never_repeats() -> None:
    def trade(i: int) -> dict:
        return {"trade_id": f"t{i}", "ticker": "K", "created_time": "2026-09-21T12:00:00Z",
                "yes_price_dollars": "0.5000", "count_fp": "1.00", "taker_side": "yes"}
    seen: set = set()
    calls = []

    def pages(ids):
        for chunk in ids:
            calls.append(len(chunk))
            yield [trade(i) for i in chunk]

    # 2,300 new trades: two full pages and a short one, all read.
    rows = c.new_trades(pages([range(0, 1000), range(1000, 2000), range(2000, 2300)]), seen, set())
    assert len(rows) == 2300 and calls == [1000, 1000, 300]
    # The next poll overlaps: the first page has 5 new trades, the second is all known, so paging stops there.
    calls.clear()
    rows = c.new_trades(pages([list(range(2300, 2305)) + list(range(0, 995)), range(995, 1995), range(1995, 2300)]), seen, set())
    assert len(rows) == 5 and calls == [1000, 1000]
    # Ids of the previous generation are not written again.
    assert c.new_trades(pages([range(0, 10)]), set(), seen) == []


def test_sink_writes_depth_header() -> None:
    import gzip
    with tempfile.TemporaryDirectory() as d:
        s = c.Sink(Path(d))
        s.add("depth", TS, [TS, "KXBTC15M-X", "0.46:3", "0.51:7"])
        s.flush()
        text = gzip.open(Path(d) / f"depth_{c.day_of(TS)}.csv.gz", "rt").read().splitlines()
        assert text == ["ts_ms,ticker,yes_levels,no_levels", f"{TS},KXBTC15M-X,0.46:3,0.51:7"]


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
