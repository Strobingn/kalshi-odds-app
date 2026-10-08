#!/usr/bin/env python3
"""No-network unit tests for tools/research/queue_maker.py (stdlib only).

Run: python3 tools/research/test_queue_maker.py
"""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import maker_sim as ms  # noqa: E402
import queue_maker as qm  # noqa: E402

FIX = HERE / "fixtures" / "maker"
OPEN = 1_000_000_000
CLOSE = OPEN + 900_000


def snap(t_s: float, yb, ybq, ya, yaq) -> ms.Snap:
    return ms.Snap(OPEN + int(t_s * 1000), yb, ybq, ya, yaq, round(1 - ya, 4), yaq, round(1 - yb, 4), ybq)


def trade(t_s: float, yes_price: float, count: float, side: str) -> ms.Trade:
    return ms.Trade(OPEN + int(t_s * 1000), yes_price, count, side)


def market(book, trades, result="yes") -> ms.Market:
    return ms.Market("KXBTC15M-T", CLOSE, 60000.0, result, book, trades, [], [])


def order(day="2026-10-01", side="YES", price=0.60, queue=100.0, elapsed=60, filled=10, won=True, d_mid=None):
    return qm.Order(day, "T", side, price, queue, elapsed, 30, filled, won, d_mid)


def test_quote_joins_the_bid_with_its_real_queue() -> None:
    s = snap(0, 0.62, 340, 0.63, 80)
    assert qm.quote(s, "YES") == (0.62, 340.0)
    # NO bid is 1 − YES ask = 0.37, and its displayed size is the YES ask size
    assert qm.quote(s, "NO") == (0.37, 80.0)
    # outside 5–95¢, or a crossed/locked book: no order
    assert qm.quote(snap(0, 0.03, 10, 0.04, 10), "YES") is None
    assert qm.quote(snap(0, 0.50, 10, 0.50, 10), "YES") is None


def test_buckets_and_cells() -> None:
    assert [qm.queue_bucket(q) for q in (0, 100, 101, 250, 251, 2000, 5000, 5001)] == [0, 0, 1, 1, 2, 3, 4, 5]
    assert [qm.bucket(p, qm.PRICE_EDGES) for p in (0.05, 0.20, 0.49, 0.50, 0.65, 0.95)] == [0, 1, 2, 3, 4, 5]
    h1, h2, h3, h4 = qm.CELLS.values()
    thin_dog = order(price=0.30, queue=250.0)
    assert h1(thin_dog) and not h2(thin_dog)
    fav_late_thick = order(price=0.70, queue=4000.0, elapsed=600)
    assert h2(fav_late_thick) and not h1(fav_late_thick) and not h3(fav_late_thick) and not h4(fav_late_thick)
    fav_early_thin = order(price=0.50, queue=2000.0, elapsed=300)
    assert h2(fav_early_thin) and h3(fav_early_thin) and h4(fav_early_thin)
    assert not h2(order(price=0.91)) and not h2(order(price=0.49))


def test_simulate_queue_must_trade_first() -> None:
    book = [snap(t, 0.62, 40, 0.63, 500) for t in range(0, 900, 5)]
    trades = [
        trade(35, 0.62, 45, "no"),   # sells YES into the 0.62 bid: 40 ahead, 5 for us
        trade(40, 0.63, 100, "yes"),  # buys YES at 0.63 = sells NO at 0.37: 500 ahead of our NO bid, none for us
    ]
    orders: list[qm.Order] = []
    qm.simulate_market(market(book, trades, result="yes"), 30, orders)
    first_yes = next(o for o in orders if o.side == "YES")
    first_no = next(o for o in orders if o.side == "NO")
    assert (first_yes.elapsed_s, first_yes.price, first_yes.queue, first_yes.filled) == (30, 0.62, 40.0, 5)
    assert first_yes.won and abs(first_yes.pnl(0.0) - 5 * 0.38) < 1e-9
    assert (first_no.price, first_no.queue, first_no.filled) == (0.37, 500.0, 0)
    assert first_no.pnl(0.0) == 0.0 and not first_no.won
    # one order per side at a time: 30 s apart, never inside the last minute
    yes = [o for o in orders if o.side == "YES"]
    assert all(b.elapsed_s - a.elapsed_s >= 30 for a, b in zip(yes, yes[1:]))
    assert all(OPEN + o.elapsed_s * 1000 < CLOSE - ms.CANCEL_BEFORE_CLOSE_MS for o in orders)
    # a longer cancel makes each order block the next decision time
    slow: list[qm.Order] = []
    qm.simulate_market(market(book, trades), 60, slow)
    y60 = [o for o in slow if o.side == "YES"]
    assert all(b.elapsed_s - a.elapsed_s >= 60 for a, b in zip(y60, y60[1:]))


def test_summary_is_cents_per_filled_contract() -> None:
    orders = [
        order(day="2026-10-01", price=0.60, filled=10, won=True, d_mid=0.01),    # +4.00
        order(day="2026-10-02", price=0.60, filled=5, won=False, d_mid=-0.03),   # −3.00
        order(day="2026-10-02", price=0.60, filled=0, won=True),                 # no fill
    ]
    s = qm.summarize(orders, 0.0, 200, 1)
    assert (s["orders"], s["fills"], s["contracts"]) == (3, 2, 15)
    assert abs(s["cents"] - 100.0 * 1.0 / 15) < 1e-9
    assert abs(s["win"] - 10 / 15) < 1e-9 and abs(s["price"] - 0.60) < 1e-9
    assert s["pos_days"] == 1 and s["days"] == 2
    assert abs(s["d_mid"] + 0.01) < 1e-12
    # a maker fee lowers it
    assert qm.summarize(orders, qm.STRESS_MAKER_FEE, 200, 1)["cents"] < s["cents"]
    assert qm.summarize([], 0.0, 10, 1) == {"orders": 0}


def test_verdicts() -> None:
    days = [f"2026-10-{d:02d}" for d in range(1, 9)]
    good = [order(day=d, price=0.60, filled=10, won=(i % 5 != 0)) for d in days for i in range(20)]   # 80% wins at 60¢
    bad = [order(day=d, price=0.60, filled=10, won=(i % 5 == 0)) for d in days for i in range(20)]    # 20% wins
    flat = [order(day=d, price=0.60, filled=10, won=i % 5 < (4 if k % 2 == 0 else 2))
            for k, d in enumerate(days) for i in range(20)]                                           # 80% / 40% by day
    assert qm.verdict(qm.summarize(good, 0.0, 400, 3), len(days)).startswith("PASS")
    assert qm.verdict(qm.summarize(bad, 0.0, 400, 3), len(days)).startswith("FAIL")
    assert qm.verdict(qm.summarize(flat, 0.0, 400, 3), len(days)).startswith("NO EDGE SHOWN")
    assert qm.verdict(qm.summarize(good, 0.0, 400, 3), 4).startswith("NOT EVALUABLE")
    assert qm.verdict({"orders": 5}, 8).startswith("NOT EVALUABLE")


def test_end_to_end_fixture() -> None:
    orders, stats = qm.run(FIX)
    assert stats["markets"] == 2, stats
    assert set(orders) == set(qm.CANCEL_SECS) and orders[30]
    text = qm.report(orders, stats, 0.0, iters=50)
    assert text.startswith("# Queue-aware resting bids")
    assert all(label in text for label in qm.CELLS) and "NOT EVALUABLE" in text
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "queue.md"
        with contextlib.redirect_stdout(io.StringIO()):
            assert qm.main(["--dir", str(FIX), "--out", str(out), "--iters", "20"]) == 0
        assert out.read_text().startswith("# Queue-aware resting bids")


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
