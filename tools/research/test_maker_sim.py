#!/usr/bin/env python3
"""No-network unit tests for tools/research/maker_sim.py (stdlib only).

Run: python3 tools/research/test_maker_sim.py
Fixture: tools/research/fixtures/maker/ (regenerate with make_fixture.py there).
"""
from __future__ import annotations

import contextlib
import gzip
import io
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import maker_sim as ms  # noqa: E402
from pipeline import kalshi_total_cost, size_all_in  # noqa: E402  (path set by maker_sim)

FIX = HERE / "fixtures" / "maker"
T = 1_000_000


def tr(dt_s: float, yes_price: float, count: float, side: str) -> ms.Trade:
    return ms.Trade(T + int(dt_s * 1000), yes_price, count, side)


def test_queue_position() -> None:
    trades = [tr(1, 0.50, 6, "no"), tr(2, 0.50, 6, "no"), tr(3, 0.49, 10, "no")]
    # 10 lots displayed ahead of us: the first 6 only eat the queue.
    assert ms.conservative_fill("YES", 0.50, 5, 10, trades[:1], T, T + 60_000) == (0, None)
    # next 6: 4 finish the queue, 2 fill us (partial), first fill at trade 2.
    assert ms.conservative_fill("YES", 0.50, 5, 10, trades[:2], T, T + 60_000) == (2, T + 2000)
    # a print through our price (0.49 < 0.50) fills the rest, capped at our size.
    assert ms.conservative_fill("YES", 0.50, 5, 10, trades, T, T + 60_000) == (5, T + 2000)
    # queue 0 (we improved the bid): first qualifying print fills immediately.
    assert ms.conservative_fill("YES", 0.50, 5, 0, trades[:1], T, T + 60_000) == (5, T + 1000)
    # fractional counts never over-fill
    assert ms.conservative_fill("YES", 0.50, 3, 0.5, [tr(1, 0.50, 2.9, "no")], T, T + 60_000) == (2, T + 1000)


def test_other_side_no_fill() -> None:
    far = T + 60_000
    # YES buy at 0.50
    assert ms.conservative_fill("YES", 0.50, 5, 0, [tr(1, 0.50, 50, "yes")], T, far) == (0, None)  # taker bought YES
    assert ms.conservative_fill("YES", 0.50, 5, 0, [tr(1, 0.51, 50, "no")], T, far) == (0, None)   # above our bid
    assert ms.conservative_fill("YES", 0.50, 5, 0, [tr(1, 0.50, 50, "")], T, far) == (0, None)     # unknown aggressor
    # NO buy at 0.50 == YES sell at 0.50: needs taker_side yes at yes_price ≥ 0.50
    assert ms.conservative_fill("NO", 0.50, 5, 0, [tr(1, 0.50, 50, "no")], T, far) == (0, None)
    assert ms.conservative_fill("NO", 0.50, 5, 0, [tr(1, 0.49, 50, "yes")], T, far) == (0, None)   # NO price 0.51
    assert ms.conservative_fill("NO", 0.50, 5, 0, [tr(1, 0.52, 50, "yes")], T, far) == (5, T + 1000)
    # optimistic model also ignores other-side prints
    assert ms.optimistic_fill("YES", 0.50, 5, [], [tr(1, 0.50, 50, "yes")], T, far) == (0, None)


def test_optimistic_touch() -> None:
    def snap(dt, ya):
        return ms.Snap(T + dt * 1000, 0.48, 10, ya, 5, round(1 - ya, 2), 5, 0.52, 10)
    book = [snap(0, 0.50), snap(5, 0.51), snap(10, 0.50), snap(20, 0.49)]
    # posted at 0.50 at T: the T snapshot itself does not count; first touch after is +10 s
    assert ms.optimistic_fill("YES", 0.50, 7, book, [], T, T + 60_000) == (7, T + 10_000)
    assert ms.optimistic_fill("YES", 0.50, 7, book, [], T, T + 9_000) == (0, None)


def test_fee_matches_pipeline() -> None:
    for p in (0.05, 0.12, 0.33, 0.50, 0.51, 0.77, 0.95):
        c, cost, _ = size_all_in(p, 5.0, 0.07)
        for n in (1, 3, c):
            assert ms.fill_cost(n, p, 0.07) == kalshi_total_cost(n, p, 0.07), (n, p)
        assert cost <= 5.0 + 1e-9
    # hand check: 10 @ 0.50, fee 0.07·10·0.25 = 0.175 → ceil-cent(5.175) = 5.18; so $5 buys 9.
    assert ms.fill_cost(10, 0.50, 0.07) == 5.18
    assert size_all_in(0.50, 5.0, 0.07)[0] == 9
    # at maker rate 0 the cost is exactly C·P
    assert ms.fill_cost(10, 0.50, 0.0) == 5.00
    assert abs(ms.maker_fee_pc(0.50, 0.0)) < 1e-12
    assert ms.maker_fee_pc(0.50, 0.07) > ms.maker_fee_pc(0.50, 0.0175) > 0


def test_cancellation_timing() -> None:
    close = T + 900_000
    assert ms.cancel_time(T, 60, close) == T + 60_000
    assert ms.cancel_time(close - 70_000, 60, close) == close - 60_000  # clamped to 60 s before close
    trades = [tr(0, 0.50, 5, "no"), tr(30, 0.50, 5, "no")]
    # same-ms print as the post does not fill; a print exactly at cancel does; 1 ms later does not
    assert ms.conservative_fill("YES", 0.50, 5, 0, trades[:1], T, T + 30_000) == (0, None)
    assert ms.conservative_fill("YES", 0.50, 5, 0, trades, T, T + 30_000) == (5, T + 30_000)
    assert ms.conservative_fill("YES", 0.50, 5, 0, trades, T, T + 29_999) == (0, None)
    # every decision time leaves room before the 60-s-before-close cancel
    assert max(ms.DECISION_ELAPSED_S) * 1000 < 900_000 - ms.CANCEL_BEFORE_CLOSE_MS


def test_settlement_pnl() -> None:
    pnl, cost, won = ms.settle_pnl(6, 0.80, "NO", "no", 0.0)
    assert won and cost == 4.80 and abs(pnl - 1.20) < 1e-9
    pnl, cost, won = ms.settle_pnl(5, 0.50, "YES", "no", 0.07)
    assert not won and cost == kalshi_total_cost(5, 0.50, 0.07) and pnl == -cost
    pnl, cost, won = ms.settle_pnl(9, 0.50, "YES", "yes", 0.07)
    assert won and abs(pnl - (9 - 4.66)) < 1e-9  # 4.50 + ceil6dp(0.1575) → 4.66


def test_quote_rules() -> None:
    s = ms.Snap(T, 0.50, 10, 0.53, 7, 0.47, 7, 0.50, 10)
    j = ms.quote(s, "YES", 0.60, ms.Config("join", 30, 0.02), 0.0)
    i = ms.quote(s, "YES", 0.60, ms.Config("improve", 30, 0.02), 0.0)
    f = ms.quote(s, "YES", 0.60, ms.Config("fair", 30, 0.02), 0.0)
    assert j[:2] == (0.50, 10) and i[:2] == (0.51, 0.0) and f[:2] == (0.51, 0.0)
    # fair rule backs off to the highest price that clears the margin
    f = ms.quote(s, "YES", 0.525, ms.Config("fair", 30, 0.02), 0.0)
    assert f[:2] == (0.50, 10), f
    # improving is refused when it would touch the ask (1¢ spread)
    tight = ms.Snap(T, 0.50, 10, 0.51, 7, 0.49, 7, 0.50, 10)
    assert ms.quote(tight, "YES", 0.70, ms.Config("improve", 30, 0.02), 0.0) is None
    # margin not met
    assert ms.quote(s, "YES", 0.51, ms.Config("join", 30, 0.02), 0.0) is None


def test_reader_multimember_truncated() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "trades_2026-01-01.csv.gz"
        m1 = gzip.compress(b"ts_ms,ticker,yes_price,count,taker_side\n1,KXBTC15M-X,0.5,1,no\n")
        m2 = gzip.compress(b"2,KXBTC15M-X,0.4,2,yes\n3,KXBTC15M-X,,1,no\n")
        m3 = gzip.compress(b"4,KXBTC15M-X,0.3,3,no\n")[:-12]  # truncated tail member
        p.write_bytes(m1 + m2 + m3)
        rows = list(ms._iter_rows(p))
        assert [r["ts_ms"] for r in rows[:3]] == ["1", "2", "3"], rows
        d = ms.load_day(Path(td), "2026-01-01", "BTC")
        assert [t.ts for t in d.trades["KXBTC15M-X"]][:2] == [1, 2]  # row 3 has empty price → skipped


def test_end_to_end_fixture() -> None:
    grid = [ms.Config("join", 60, 0.02), ms.Config("join", 15, 0.02)]
    results, taker, stats = ms.run(FIX, 0.0, grid=grid)
    assert stats["markets"] == 2 and stats["trades_no_side"] == 1
    r60 = results[(grid[0], 0.0, "conservative")]
    got = sorted((f.ticker, f.side, f.price, f.posted, f.filled) for f in r60.fills)
    # A: YES @0.50, 10 posted, queue 10, 15 lots print → 5 filled. B: NO @0.80, 6 posted, queue 8, 20 lots → 6.
    assert got == [("KXBTC15M-FIXA", "YES", 0.50, 10, 5), ("KXBTC15M-FIXB", "NO", 0.80, 6, 6)], got
    assert abs(sum(f.pnl for f in r60.fills) - (2.50 + 1.20)) < 1e-9
    # T15: B's orders are cancelled 5 s before its only NO-side print (01:02:00 post → 01:02:15 cancel, print 01:02:20)
    r15 = results[(grid[1], 0.0, "conservative")]
    assert [f.ticker for f in r15.fills] == ["KXBTC15M-FIXA"]
    txt = ms.report(results | {(c, r, m): ms.Result() for c in ms.GRID for r in (0.0, 0.0175, 0.035, 0.07)
                               for m in ("conservative", "optimistic") if (c, r, m) not in results},
                    taker, stats, 0.0, True, iters=50)
    assert "EXPLORATORY" in txt and "DEFAULT placeholder" in txt
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "r.md"
        with contextlib.redirect_stdout(io.StringIO()):
            assert ms.main(["--dir", str(FIX), "--maker-fee", "0.0175", "--out", str(out), "--iters", "20"]) == 0
        assert "0.0175 (as passed" in out.read_text()


def test_is_oos_report() -> None:
    days = [f"2026-09-{d:02d}" for d in range(1, 16)]
    results = {(c, r, m): ms.Result() for c in ms.GRID for r in (0.0, 0.0175, 0.035, 0.07)
               for m in ("conservative", "optimistic")}
    cfg = ms.GRID[0]
    for r in (0.0, 0.0175, 0.035, 0.07):
        res = results[(cfg, r, "conservative")]
        for di, day in enumerate(days):
            for k in range(5):
                won = (k + di) % 3 != 0
                res.fills.append(ms.Fill("X", day, "YES", 0.5, 10, 10, T, T, 5.0, 5.0 if won else -5.0, won,
                                         {5: 0.0, 30: 0.0, 60: 0.0}, {5: 0.0, 30: 0.0, 60: 0.0}))
                res.posted[day] += 2
                res.posted_ct[day] += 20
    stats = {"recording_days": days, "markets": 45, "book_rows": 1, "trade_rows": 1, "trades_no_side": 0}
    txt = ms.report(results, {m: [] for m in ms.MARGINS}, stats, 0.0175, False, iters=100)
    assert "IS/OOS split by day" in txt and f"Picked **{cfg.label()}**" in txt
    assert "Decision rule" in txt and "OOS 2026-09-11" in txt and "NOT EVALUABLE" not in txt


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} maker_sim tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
