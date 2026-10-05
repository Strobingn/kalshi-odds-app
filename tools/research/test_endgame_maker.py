#!/usr/bin/env python3
"""No-network tests for tools/research/endgame_maker.py on synthetic recordings (stdlib only).

Run: python3 tools/research/test_endgame_maker.py
"""
from __future__ import annotations

import contextlib
import gzip
import io
import math
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import endgame_maker as em  # noqa: E402
import maker_sim as ms  # noqa: E402

DAY = "2026-09-01"
T0 = 1788220800000           # 2026-09-01T00:00:00Z
OPEN = T0 + 3600_000         # 01:00
CLOSE = OPEN + 900_000       # 01:15
BASIS = 0.0002               # CF index sits 2 bp above Coinbase
BOOK_HDR = "ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty"


def zig(t: int) -> float:
    """Deterministic wiggle so the trailing 1-minute vol is non-zero."""
    return math.sin(t / 37_000.0) * 1e-3 + math.sin(t / 61_000.0) * 5e-4


def pre_open_strike(price) -> float:
    logs = [math.log(price(u)) for u in range(OPEN - 60_000, OPEN, 1000)]
    return math.exp(sum(logs) / len(logs) + BASIS)


# Market A (BTC): spot ends ~0.8% above strike -> YES strongly favoured; settles yes.
def px_btc(t: int) -> float:
    base = 60000.0 * math.exp(zig(t))
    return base * 1.008 if t >= CLOSE - 180_000 else base


# Market B (ETH): spot ends ~0.8% below strike -> NO strongly favoured; settles no.
def px_eth(t: int) -> float:
    base = 3000.0 * math.exp(zig(t))
    return base * 0.992 if t >= CLOSE - 180_000 else base


# Market C (SOL): spot pinned exactly at the (basis-adjusted) strike in the last 3 min -> ~50/50.
SOL_K = pre_open_strike(lambda t: 150.0 * math.exp(zig(t)))


def px_sol(t: int) -> float:
    return SOL_K * math.exp(-BASIS) if t >= CLOSE - 180_000 else 150.0 * math.exp(zig(t))


TK = {"BTC": "KXBTC15M-26SEP010115-15", "ETH": "KXETH15M-26SEP010115-15", "SOL": "KXSOL15M-26SEP010115-15"}


def book_line(t, tk, k, yb, ybq, ya, yaq):
    return f"{t},{tk},{k:.6f},{CLOSE},{yb:.2f},{ybq},{ya:.2f},{yaq},{1 - ya:.2f},{yaq},{1 - yb:.2f},{ybq}"


def write_recordings(d: Path) -> dict[str, float]:
    strikes = {"BTC": pre_open_strike(px_btc), "ETH": pre_open_strike(px_eth), "SOL": SOL_K}
    spot = ["ts_ms,product,price"]
    for t in range(T0 + 1800_000, CLOSE + 10_000, 1000):
        for coin, f in (("BTC", px_btc), ("ETH", px_eth), ("SOL", px_sol)):
            spot.append(f"{t},{coin}-USD,{f(t):.6f}")
    book = [BOOK_HDR]
    for t in range(OPEN, CLOSE + 1, 1000):
        book.append(book_line(t, TK["BTC"], strikes["BTC"], 0.90, 5, 0.93, 4))
        book.append(book_line(t, TK["ETH"], strikes["ETH"], 0.08, 6, 0.12, 9))   # NO bid 0.88 / NO ask 0.92
        book.append(book_line(t, TK["SOL"], strikes["SOL"], 0.48, 10, 0.52, 10))
    trades = [
        "ts_ms,ticker,yes_price,count,taker_side",
        f"{CLOSE - 20_000},{TK['BTC']},0.90,100,no",    # sells into YES bids at 0.90 (fills join and improve)
        f"{CLOSE - 3_000},{TK['ETH']},0.12,50,yes",     # NO sold at 0.88 -- but after our 5 s cancel
        f"{CLOSE - 20_000},{TK['SOL']},0.48,30,no",     # fills the YES bid (queue 10 ahead)
        f"{CLOSE - 15_000},{TK['SOL']},0.52,30,yes",    # fills the NO bid at 0.48 (queue 10 ahead)
    ]
    for kind, lines in (("spot", spot), ("book", book), ("trades", trades)):
        (d / f"{kind}_{DAY}.csv.gz").write_bytes(gzip.compress(("\n".join(lines) + "\n").encode(), mtime=0))
    (d / f"settle_{DAY}.csv").write_text(
        "ticker,close_ms,strike,result\n"
        f"{TK['BTC']},{CLOSE},{strikes['BTC']:.6f},yes\n"
        f"{TK['ETH']},{CLOSE},{strikes['ETH']:.6f},no\n"
        f"{TK['SOL']},{CLOSE},{strikes['SOL']:.6f},yes\n")
    return strikes


_CACHE: dict = {}


def study() -> em.Study:
    if "st" not in _CACHE:
        with tempfile.TemporaryDirectory() as td:
            write_recordings(Path(td))
            _CACHE["st"] = em.run(Path(td))
    return _CACHE["st"]


def market(strike: float, spot_fn, t_from: int, t_to: int) -> ms.Market:
    ts = list(range(t_from, t_to, 1000))
    return ms.Market("KXBTC15M-X", CLOSE, strike, "yes", [], [], ts, [spot_fn(t) for t in ts])


# --- tests -------------------------------------------------------------------------------

def test_grid_preregistered() -> None:
    assert len(em.GRID) == 12
    assert {c.start_s for c in em.GRID} == {90, 60, 30}
    assert {c.rule for c in em.GRID} == {"join", "improve"} and {c.margin for c in em.GRID} == {0.03, 0.06}
    assert em.RATES == (0.0, 0.0175) and em.CANCEL_BEFORE_CLOSE_MS == 5000
    assert em.PRIMARY[0] in em.GRID


def test_basis_estimate() -> None:
    m = market(pre_open_strike(px_btc), px_btc, T0 + 1800_000, CLOSE)
    b = em.estimate_basis(m)
    assert b is not None and abs(b - BASIS) < 1e-9, b
    # absurd basis (strike 1% away) is rejected
    assert em.estimate_basis(market(pre_open_strike(px_btc) * 1.01, px_btc, T0 + 1800_000, CLOSE)) is None
    # too little pre-open spot
    assert em.estimate_basis(market(60000.0, px_btc, OPEN, CLOSE)) is None


def test_partial_average_locks_in() -> None:
    k = 60000.0
    # first 55 s of the window 0.3% above the strike, then spot drops 0.05% below it
    f = lambda t: (k * 0.9995 if t >= CLOSE - 5_000 else k * 1.003) * math.exp(zig(t) * (t < CLOSE - 60_000))  # noqa: E731
    m = market(k, f, T0 + 1800_000, CLOSE)
    t = CLOSE - 5_000
    sig = em.sigma_at(m, t)
    assert sig is not None
    locked = em.fair_yes_at(m, t, "BTC", 0.0, sig)
    naive = ms.p_finish_above(m.spot_at(t), k, 5.0, sig)
    assert locked > 0.99 and naive < 0.2, (locked, naive)
    # basis shift moves the fair the right way at the open of the window
    m2 = market(k, lambda t: k * math.exp(zig(t) * (t < CLOSE - 120_000)), T0 + 1800_000, CLOSE)
    t2 = CLOSE - 90_000
    up, down = em.fair_yes_at(m2, t2, "BTC", 2e-4), em.fair_yes_at(m2, t2, "BTC", -2e-4)
    assert up > 0.5 > down, (up, down)


def test_quote_rules() -> None:
    s = ms.Snap(CLOSE, 0.90, 5, 0.93, 4, 0.07, 4, 0.10, 5)
    assert em.quote(s, "YES", 0.99, em.Cfg(60, "join", 0.03), 0.0) == (0.90, 5)
    assert em.quote(s, "YES", 0.99, em.Cfg(60, "improve", 0.03), 0.0) == (0.91, 0.0)
    assert em.quote(s, "YES", 0.95, em.Cfg(60, "improve", 0.06), 0.0) is None   # 0.95-0.91 < 0.06
    assert em.quote(s, "YES", 0.95, em.Cfg(60, "join", 0.03), 0.0) == (0.90, 5)
    # fee eats the margin: 0.935-0.90 = 0.035 clears 0.03 at fee 0, not at 0.0175 (fee/ct ~0.0016) + nudge
    assert em.quote(s, "YES", 0.9332, em.Cfg(60, "join", 0.03), 0.0) is not None
    assert em.quote(s, "YES", 0.9312, em.Cfg(60, "join", 0.03), 0.0175) is None
    tight = ms.Snap(CLOSE, 0.90, 5, 0.91, 4, 0.09, 4, 0.10, 5)
    assert em.quote(tight, "YES", 0.99, em.Cfg(60, "improve", 0.03), 0.0) is None  # would touch the ask
    assert em.quote(ms.Snap(CLOSE, None, None, 0.5, 1, 0.5, 1, None, None), "YES", 0.9,
                    em.Cfg(60, "join", 0.03), 0.0) is None


def test_end_to_end_btc_fills() -> None:
    st = study()
    assert st.markets == {"BTC": 1, "ETH": 1, "SOL": 1}, dict(st.markets)
    for cfg in em.GRID:
        for rate in em.RATES:
            posts = [p for p in st.posts[(cfg, rate)] if p.coin == "BTC"]
            assert len(posts) == 1, (cfg, rate, posts)
            p = posts[0]
            assert p.side == "YES" and p.would_win and p.fair > 0.99
            assert p.price == (0.90 if cfg.rule == "join" else 0.91)
            assert p.filled == p.contracts and p.fill_ms == CLOSE - 20_000   # 100 lots clear the 5-lot queue
            assert p.pnl > 0 and abs(p.pnl - (p.filled - ms.fill_cost(p.filled, p.price, rate))) < 1e-9
            assert p.mk_mid is not None and abs(p.mk_mid - (0.915 - p.price)) < 1e-9
            assert p.d_fair is not None and p.mk_fair is not None
    # join at fee 0: 5 contracts at 0.90 -> +0.50
    j = [p for p in st.posts[(em.Cfg(60, "join", 0.03), 0.0)] if p.coin == "BTC"][0]
    assert j.contracts == 5 and abs(j.pnl - 0.50) < 1e-9


def test_cancel_before_close_and_no_side() -> None:
    st = study()
    for cfg in em.GRID:
        posts = [p for p in st.posts[(cfg, 0.0)] if p.coin == "ETH"]
        assert len(posts) == 1 and posts[0].side == "NO", posts
        assert posts[0].filled == 0 and posts[0].would_win  # the only NO-side print is 3 s before close
    # SOL is ~50/50: the favoured side never clears a 3c margin over the 0.48 bid
    assert not any(p.coin == "SOL" for v in st.posts.values() for p in v)


def test_near_fifty() -> None:
    st = study()
    rows = [r for r in st.near if r.coin == "SOL"]
    assert sorted(r.s for r in rows) == [15, 30], rows
    for r in rows:
        assert abs(r.fair - 0.5) < 0.01, r.fair
        assert abs((r.yes_ask + r.no_ask - 1.0) - (r.yes_ask - r.yes_bid)) < 1e-9  # identity
    assert not [r for r in st.near if r.coin != "SOL"]
    yes = st.near_posts[("YES", 0.0)]
    no = st.near_posts[("NO", 0.0)]
    assert len(yes) == 1 and len(no) == 1
    # 30 lots, 10 queued ahead -> 20 available, we post 10 at 0.48 ($5): fully filled
    assert yes[0].filled == 10 and yes[0].would_win and abs(yes[0].pnl - (10 - 4.80)) < 1e-9
    assert no[0].filled == 10 and not no[0].would_win and abs(no[0].pnl + 4.80) < 1e-9


def test_summary_and_ci() -> None:
    def post(day, pnl, won):
        return em.Post("BTC", day, "X", "YES", 0.9, 0.97, 5, 5, CLOSE, won, pnl, 4.5, 0.05, 0.01, -0.01)
    posts = [post(f"2026-09-{d:02d}", 0.5 if d % 4 else -4.5, d % 4 != 0) for d in range(1, 13)]
    posts.append(em.Post("BTC", "2026-09-01", "X", "YES", 0.9, 0.97, 5, 0, None, True))
    s = em.summarize(posts, 500, 3)
    assert s["posted"] == 13 and s["fills"] == 12 and s["days"] == 12
    assert abs(s["pnl"] - (9 * 0.5 - 3 * 4.5)) < 1e-9 and abs(s["win"] - 0.75) < 1e-9
    assert abs(s["win_all"] - 10 / 13) < 1e-9
    lo, hi = s["ci99"]
    assert lo <= s["per"] <= hi and s["ci95"][0] >= lo and s["ci95"][1] <= hi
    assert abs(s["mk_settle"] - (0.75 - 0.9)) < 1e-9


def test_report_and_main() -> None:
    txt = em.report(study(), iters=50)
    for needle in ("EXPLORATORY", "(PRIMARY)", "NOT EVALUABLE", "Near 50/50", "Brier", "maker fee 0.0175",
                   "Conditional on fill", "Caveats", "| BTC | 1 | 1 |"):
        assert needle in txt, needle
    with tempfile.TemporaryDirectory() as td:
        write_recordings(Path(td))
        out = Path(td) / "r.md"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            assert em.main(["--dir", td, "--iters", "20", "--out", str(out), "--coins", "BTC"]) == 0
        body = out.read_text()
        assert "Endgame maker study" in body and "ETH" not in body.split("## Maker grid")[0].split("studied:")[1].split("\n")[0]
        assert buf.getvalue().strip() == body.strip()
    # empty directory still produces a report
    with tempfile.TemporaryDirectory() as td:
        with contextlib.redirect_stdout(io.StringIO()):
            assert em.main(["--dir", td, "--iters", "10"]) == 0


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
