#!/usr/bin/env python3
"""No-network unit tests for tools/research/pair_maker.py (stdlib only).

Run: python3 tools/research/test_pair_maker.py
Uses the maker_sim fixture (tools/research/fixtures/maker/) for the end-to-end check.
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
import pair_maker as pm  # noqa: E402

FIX = HERE / "fixtures" / "maker"
OPEN = 1_000_000_000
CLOSE = OPEN + 900_000


def snap(t_s: float, yb, ybq, ya, yaq, nb=None, nbq=None, na=None, naq=None) -> ms.Snap:
    nb = round(1 - ya, 4) if nb is None else nb
    na = round(1 - yb, 4) if na is None else na
    return ms.Snap(OPEN + int(t_s * 1000), yb, ybq, ya, yaq, nb, yaq if nbq is None else nbq, na,
                   ybq if naq is None else naq)


def trade(t_s: float, yes_price: float, count: float, side: str) -> ms.Trade:
    return ms.Trade(OPEN + int(t_s * 1000), yes_price, count, side)


def market(book, trades, result="yes") -> ms.Market:
    return ms.Market("KXBTC15M-T", CLOSE, 60000.0, result, book, trades, [], [])


def test_quote_rules() -> None:
    # YES 0.45/0.50, NO 0.50/0.55: join posts 0.45 + 0.50 = 0.95 → lock 5¢.
    s = snap(0, 0.45, 100, 0.50, 80)
    assert pm.pair_quote(s, "join", 0.03) == (0.45, 100, 0.50, 80)
    # improve posts 0.46 + 0.51 = 0.97 → lock 3¢, queue 0 on both legs.
    assert pm.pair_quote(s, "improve", 0.03) == (0.46, 0.0, 0.51, 0.0)
    assert pm.pair_quote(s, "improve", 0.04) is None  # lock too small
    # 1¢ spread: improve would cross the ask on both legs.
    tight = snap(0, 0.49, 100, 0.50, 80)
    assert pm.pair_quote(tight, "improve", 0.01) is None
    assert pm.pair_quote(tight, "join", 0.01) == (0.49, 100, 0.50, 80)
    assert pm.pair_quote(tight, "join", 0.02) is None
    # a leg outside 5–95¢ is never posted
    assert pm.pair_quote(snap(0, 0.03, 10, 0.08, 10), "join", 0.01) is None
    # missing NO bid mirrors the YES ask
    s2 = ms.Snap(OPEN, 0.45, 100, 0.50, 80, None, None, None, None)
    assert pm.pair_quote(s2, "join", 0.03) == (0.45, 100, 0.50, 80)


def test_episode_pnl_by_hand() -> None:
    no_asks = {"YES": None, "NO": None}
    # both legs fill 10: cost 4.50 + 5.00 at maker 0 → payout 10 → +0.50 whatever the result
    for res in ("yes", "no"):
        pnl, cost, done = pm.episode_pnl(10, 0.45, 10, 0.50, res, 0.0, "hold", no_asks)
        assert abs(pnl - 0.50) < 1e-9 and abs(cost - 9.50) < 1e-9 and done == 0, (res, pnl, cost)
    # only YES fills and is held: wins 10 − 4.50 or loses 4.50
    assert abs(pm.episode_pnl(10, 0.45, 0, 0.50, "yes", 0.0, "hold", no_asks)[0] - 5.50) < 1e-9
    assert abs(pm.episode_pnl(10, 0.45, 0, 0.50, "no", 0.0, "hold", no_asks)[0] + 4.50) < 1e-9
    # complete: buy 10 NO at a 0.60 ask with the taker fee → result-independent
    asks = {"YES": 0.42, "NO": 0.60}
    fee_cost = ms.fill_cost(10, 0.60, pm.TAKER_FEE)
    assert abs(fee_cost - 6.17) < 1e-9  # 6.00 + ceil_cent(0.07·10·0.6·0.4 = 0.168)
    for res in ("yes", "no"):
        pnl, cost, done = pm.episode_pnl(10, 0.45, 0, 0.50, res, 0.0, "complete", asks)
        assert done == 10 and abs(pnl - (10 - 4.50 - fee_cost)) < 1e-9, (res, pnl)
    # complete with no ask on the short side falls back to hold
    pnl, _, done = pm.episode_pnl(10, 0.45, 0, 0.50, "no", 0.0, "complete", no_asks)
    assert done == 0 and abs(pnl + 4.50) < 1e-9
    # partial: 10 YES, 4 NO, complete 6 NO
    pnl, _, done = pm.episode_pnl(10, 0.45, 4, 0.50, "yes", 0.0, "complete", asks)
    assert done == 6
    exp = 10 - ms.fill_cost(10, 0.45, 0.0) - ms.fill_cost(4, 0.50, 0.0) - ms.fill_cost(6, 0.60, 0.07)
    assert abs(pnl - exp) < 1e-9
    # a maker fee makes the same pair cost more
    assert pm.episode_pnl(10, 0.45, 10, 0.50, "yes", 0.0175, "hold", no_asks)[0] < 0.50


def test_simulate_fills_and_sequencing() -> None:
    book = [snap(t, 0.45, 5, 0.50, 3) for t in range(0, 900, 5)]
    trades = [
        trade(65, 0.45, 8, "no"),    # sells YES into our 0.45 bid: 5 queue + 3 for us
        trade(70, 0.50, 20, "yes"),  # buys YES at 0.50 = sells NO at 0.50: 3 queue + 10 for us
        trade(95, 0.45, 50, "no"),   # after the first 30 s episode ended
    ]
    m = market(book, trades, result="no")
    cfg = pm.Config("join", 0.03, 30, "hold")
    res = pm.Result()
    pm.simulate_market(m, cfg, 0.0, res)
    first = res.episodes[0]
    assert (first.post_ms, first.end_ms) == (OPEN + 60_000, OPEN + 90_000)
    assert (first.fy, first.fn) == (3, 10), (first.fy, first.fn)
    assert first.pairs == 3
    # NO wins: payout 10, cost 3·0.45 + 10·0.50 = 6.35
    assert abs(first.pnl - (10 - 6.35)) < 1e-9
    # next episode starts at the first decision time after 90 s, never overlapping
    assert res.episodes[1].post_ms == OPEN + 90_000
    assert all(a.end_ms <= b.post_ms for a, b in zip(res.episodes, res.episodes[1:]))
    # no episode is posted inside the last minute
    assert all(e.post_ms < CLOSE - pm.CANCEL_BEFORE_CLOSE_MS for e in res.episodes)
    assert all(e.end_ms <= CLOSE - pm.CANCEL_BEFORE_CLOSE_MS for e in res.episodes)


def test_spot_guard() -> None:
    # spot 60,000 at the post (60 s), drops 6 bps at 70 s, recovers at 90 s
    sts = [OPEN + s * 1000 for s in (59, 70, 90)]
    spx = [60000.0, 60000.0 * (1 - 6e-4), 60000.0]
    book = [snap(t, 0.45, 0, 0.50, 0) for t in range(0, 900, 5)]
    trades = [trade(70.5, 0.45, 10, "no"),   # YES fill inside the 1 s cancel latency
              trade(75, 0.45, 10, "no"),     # after the YES cancel took effect
              trade(80, 0.50, 10, "yes")]    # NO fill (spot moved down = NO's way: no guard)
    m = ms.Market("KXBTC15M-G", CLOSE, 60000.0, "no", book, trades, sts, spx)
    t, end = OPEN + 60_000, OPEN + 120_000
    assert pm.guard_cancel(m, "YES", t, end, 5.0) == OPEN + 71_000   # 70 s + 1 s latency
    assert pm.guard_cancel(m, "NO", t, end, 5.0) == end              # spot never rose 5 bps
    assert pm.guard_cancel(m, "YES", t, end, 10.0) == end            # 6 bps < 10 bps guard
    assert pm.guard_cancel(m, "YES", t, end, 0.0) == end             # guard off
    res_off, res_on = pm.Result(), pm.Result()
    pm.simulate_market(m, pm.Config("join", 0.03, 60, "hold", 0.0), 0.0, res_off)
    pm.simulate_market(m, pm.Config("join", 0.03, 60, "hold", 5.0), 0.0, res_on)
    off, on = res_off.episodes[0], res_on.episodes[0]
    assert (off.fy, off.fn) == (10, 10), (off.fy, off.fn)   # unguarded: both trades fill YES (capped at 10)
    assert (on.fy, on.fn) == (10, 10)                       # guard fired, but the 70.5 s print beat the cancel
    # with the in-latency print removed, the guard keeps the YES leg empty
    m2 = ms.Market("KXBTC15M-G", CLOSE, 60000.0, "no", book, trades[1:], sts, spx)
    r2 = pm.Result()
    pm.simulate_market(m2, pm.Config("join", 0.03, 60, "hold", 5.0), 0.0, r2)
    assert (r2.episodes[0].fy, r2.episodes[0].fn) == (0, 10)
    assert "G5" in pm.Config("join", 0.03, 60, "hold", 5.0).label()
    assert "/G" not in pm.Config("join", 0.03, 60, "hold").label()


def test_summary_split() -> None:
    res = pm.Result([
        pm.Episode("A", "2026-09-01", 0, 1, 0.45, 0.50, 10, 10, 0.50, 9.5, 10, 0, None),
        pm.Episode("B", "2026-09-02", 0, 1, 0.45, 0.50, 10, 0, -4.50, 4.5, 0, 0, -0.03),
        pm.Episode("C", "2026-09-02", 0, 1, 0.45, 0.50, 0, 0, 0.0, 0.0, 0, 0, None),
    ])
    s = pm.summarize(res, None, 200, 1)
    assert (s["episodes"], s["filled"], s["both"], s["one"]) == (3, 2, 1, 1)
    assert abs(s["locked"] - 0.50) < 1e-9 and abs(s["rest"] + 4.50) < 1e-9
    assert abs(s["per"] - (-4.0 / 3)) < 1e-9
    assert abs(s["d_mid"] + 0.03) < 1e-12
    assert pm.summarize(res, {"2026-09-01"}, 200, 1)["episodes"] == 1


def _days_result(pnl_by_day: dict[str, list[float]]) -> pm.Result:
    res = pm.Result()
    for day, pnls in pnl_by_day.items():
        for i, p in enumerate(pnls):
            res.episodes.append(pm.Episode(f"{day}-{i}", day, 0, 1, 0.45, 0.50, 10, 10, p, 9.5, 10, 0, None))
    return res


def test_is_oos_report() -> None:
    days = [f"2026-09-{d:02d}" for d in range(1, 16)]  # 15 days → 10 IS, 5 OOS
    good = pm.Config("join", 0.01, 30, "hold")
    bad = pm.Config("improve", 0.01, 30, "hold")
    results = {
        (good, 0.0): _days_result({d: [0.5, 0.4, 0.6] * 3 for d in days}),
        (bad, 0.0): _days_result({d: [-0.2] * 9 for d in days}),
        (good, pm.STRESS_MAKER_FEE): _days_result({d: [0.3] * 9 for d in days}),
        (bad, pm.STRESS_MAKER_FEE): _days_result({d: [-0.4] * 9 for d in days}),
    }
    stats = {"markets": 0, "trades_no_side": 0, "trade_rows": 0}
    text = pm.report(results, stats, 0.0, iters=200)
    assert "IS days: 10" in text and "OOS days: 5" in text, text
    assert f"Picked on IS: `{good.label()}`" in text
    assert "Decision: PASS" in text
    # flip the OOS days negative: the same IS pick must now fail out of sample
    for res in (results[(good, 0.0)],):
        for e in res.episodes:
            if e.day in days[-5:]:
                e.pnl = -0.5
    assert "Decision: FAIL" in pm.report(results, stats, 0.0, iters=200)
    # only 9 days → 3 OOS days: below the 5 needed, so not evaluable
    short = {k: pm.Result([e for e in v.episodes if e.day in days[:9]]) for k, v in results.items()}
    assert "NOT EVALUABLE" in pm.report(short, stats, 0.0, iters=200)


def test_end_to_end_fixture() -> None:
    results, stats = pm.run(FIX, 0.0)
    assert stats["markets"] == 2, stats
    assert len(results) == len(pm.GRID) * 2  # main + stress maker fee
    text = pm.report(results, stats, 0.0, iters=50)
    assert "EXPLORATORY" in text and "NOT EVALUABLE" in text
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "pair.md"
        with contextlib.redirect_stdout(io.StringIO()):
            assert pm.main(["--dir", str(FIX), "--out", str(out), "--iters", "20"]) == 0
        assert out.read_text().startswith("# Pair maker")


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
