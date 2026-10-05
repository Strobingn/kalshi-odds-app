#!/usr/bin/env python3
"""No-network tests for tools/research/open_study.py (stdlib only).

Run: python3 tools/research/test_open_study.py

Builds a tiny recordings dir in a tempdir:
  day 1  KXBTC15M-OPA  00:45 -> 01:00, strike 59500, spot ~60000 (fair YES ~ 1), settles yes.
         Book from open + 3 s: YES 0.49 / 0.51 (qty 10 / 20) until open + 40 s, then 0.90 / 0.92.
         Taker-NO prints at yes 0.50: 6 lots at open + 20 s, 10 lots at open + 50 s.
  day 2  KXETH15M-OPB  00:45 -> 01:00, strike 3100, spot ~3000 (fair YES ~ 0), settles no.
         Book from open: YES 0.40 / 0.44, NO 0.56 / 0.60. No trades.
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

import maker_sim as ms  # noqa: E402
import open_study as os_  # noqa: E402
from pipeline import kalshi_total_cost, size_all_in  # noqa: E402

DAY1, DAY2 = "2026-09-01", "2026-09-02"
T1 = 1788220800000  # 2026-09-01T00:00:00Z
T2 = T1 + 86_400_000
BOOK_H = "ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty"


def _gz(path: Path, lines: list[str]) -> None:
    path.write_bytes(gzip.compress(("\n".join(lines) + "\n").encode(), mtime=0))


def _spot(t0: int, product: str, base: float, amp: float) -> list[str]:
    out = []
    for i in range(0, 60 * 60):
        zig = (amp if (i // 60) % 2 else -amp) + (i % 60) * amp / 120.0
        out.append(f"{t0 + i * 1000},{product},{base + zig:.4f}")
    return out


def build(d: Path) -> None:
    # day 1, BTC
    open1 = T1 + 45 * 60_000
    close1 = open1 + 900_000
    _gz(d / f"spot_{DAY1}.csv.gz", ["ts_ms,product,price"] + _spot(T1, "BTC-USD", 60000.0, 20.0))
    book = [BOOK_H]
    for s in range(3, 900):
        yb, ya = (0.49, 0.51) if s < 40 else (0.90, 0.92)
        book.append(f"{open1 + s * 1000},KXBTC15M-OPA,59500,{close1},{yb:.2f},10,{ya:.2f},20,"
                    f"{1 - ya:.2f},20,{1 - yb:.2f},10")
    _gz(d / f"book_{DAY1}.csv.gz", book)
    _gz(d / f"trades_{DAY1}.csv.gz", ["ts_ms,ticker,yes_price,count,taker_side",
                                       f"{open1 + 20_000},KXBTC15M-OPA,0.50,6,no",
                                       f"{open1 + 50_000},KXBTC15M-OPA,0.50,10,no"])
    (d / f"settle_{DAY1}.csv").write_text(f"ticker,close_ms,strike,result\nKXBTC15M-OPA,{close1},59500,yes\n")
    # day 2, ETH
    open2 = T2 + 45 * 60_000
    close2 = open2 + 900_000
    _gz(d / f"spot_{DAY2}.csv.gz", ["ts_ms,product,price"] + _spot(T2, "ETH-USD", 3000.0, 1.0))
    book = [BOOK_H] + [f"{open2 + s * 1000},KXETH15M-OPB,3100,{close2},0.40,50,0.44,50,0.56,50,0.60,50"
                       for s in range(0, 900)]
    _gz(d / f"book_{DAY2}.csv.gz", book)
    _gz(d / f"trades_{DAY2}.csv.gz", ["ts_ms,ticker,yes_price,count,taker_side"])
    (d / f"settle_{DAY2}.csv").write_text(f"ticker,close_ms,strike,result\nKXETH15M-OPB,{close2},3100,no\n")


def _run():
    td = tempfile.TemporaryDirectory()
    build(Path(td.name))
    return td, os_.run(Path(td.name))


def _pick(bets, coin, kind, d_s, mg):
    got = [b for b in bets if b.coin == coin and b.kind == kind and b.decision_s == d_s and b.margin == mg]
    assert len(got) <= 1, got
    return got[0] if got else None


def test_halving_unit() -> None:
    path = {5: (-0.40, 0.9, 0.02), 10: (-0.30, 0.9, 0.02), 20: (-0.20, 0.9, 0.02), 30: (0.0, 0.9, 0.02)}
    assert os_.halving(path) == (-0.40, 15, True)          # s0 = 5, |gap| <= 0.20 first at s = 20
    assert os_.halving({0: (0.02, 0.5, 0.02), 9: (0.0, 0.5, 0.02)}) == (0.02, None, False)  # below 0.03
    assert os_.halving({100: (0.3, 0.9, 0.02)}) == (None, None, False)  # nothing quoted in [0, 90]
    g0, th, el = os_.halving({0: (0.10, 0.9, 0.02), 301: (0.0, 0.9, 0.02)})
    assert (th, el) == (None, True)                         # censored: only reached after 300 s
    assert os_.halving({0: (0.10, 0.9, 0.02), 300: (0.05, 0.9, 0.02)})[1] == 300


def test_gap_and_coverage() -> None:
    td, r = _run()
    with td:
        btc = next(o for o in r["obs"] if o.coin == "BTC")
        eth = next(o for o in r["obs"] if o.coin == "ETH")
        assert btc.first_quote_s == 3.0 and eth.first_quote_s == 0.0
        assert btc.gaps[0] is None                         # no quote yet at the open
        gap15, lag15, spread15 = btc.gaps[15]
        assert -0.5 <= gap15 < -0.4 and abs(lag15 + gap15) < 1e-12 and abs(spread15 - 0.02) < 1e-9
        assert btc.gaps[60][1] > 0 and btc.gaps[60][1] < 0.11  # mid 0.91 vs fair ~1
        assert btc.halve_eligible and btc.g0 < -0.4 and btc.t_half == 37  # s0 = 3, book reprices at 40
        assert eth.gaps[0][1] > 0.35 and eth.halve_eligible and eth.t_half is None  # never reprices: censored


def test_taker_bets() -> None:
    td, r = _run()
    with td:
        b = _pick(r["bets"], "BTC", "taker", 15, 0.02)
        c = size_all_in(0.51, 5.0, 0.07)[0]
        assert (b.side, b.price, b.contracts, b.won, b.capped) == ("YES", 0.51, c, True, False)
        assert abs(b.pnl - (c - kalshi_total_cost(c, 0.51, 0.07))) < 1e-9 and b.cost <= 5.0
        b60 = _pick(r["bets"], "BTC", "taker", 60, 0.04)
        assert b60.price == 0.92 and b60.won
        e = _pick(r["bets"], "ETH", "taker", 30, 0.02)
        ce = size_all_in(0.60, 5.0, 0.07)[0]
        assert (e.side, e.price, e.contracts, e.won) == ("NO", 0.60, ce, True)
        assert abs(e.pnl - (ce - kalshi_total_cost(ce, 0.60, 0.07))) < 1e-9
        # one bet per market per config
        assert len([x for x in r["bets"] if x.kind == "taker"]) == 2 * len(os_.DECISION_S) * len(os_.MARGINS)


def test_maker_fills() -> None:
    td, r = _run()
    with td:
        m15 = _pick(r["bets"], "BTC", "maker", 15, 0.02)
        # 1c better: 0.50, queue 0, 10 contracts at fee 0; only the 6-lot print falls in (15, 45] s
        assert (m15.side, m15.price, m15.contracts) == ("YES", 0.50, 6) and abs(m15.pnl - 3.0) < 1e-9
        m30 = _pick(r["bets"], "BTC", "maker", 30, 0.02)
        assert m30.contracts == 10 and abs(m30.pnl - 5.0) < 1e-9   # 10-lot print at 50 s within (30, 60]
        assert _pick(r["bets"], "BTC", "maker", 60, 0.02) is None   # posted at 0.91, no prints
        posts60 = [p for p in r["posts"] if p.coin == "BTC" and p.decision_s == 60 and p.rate == 0.0]
        assert len(posts60) == 2 and not any(p.filled for p in posts60)
        assert _pick(r["bets"], "ETH", "maker", 15, 0.02) is None   # no trades on day 2
        # sensitivity rate is kept apart from the main maker rows
        s = _pick(r["bets"], "BTC", f"maker@{os_.MAKER_SENSITIVITY_FEE:g}", 30, 0.02)
        assert s is not None and s.cost == kalshi_total_cost(s.contracts, 0.50, os_.MAKER_SENSITIVITY_FEE)


def test_depth_cap_and_margin() -> None:
    open_ms = T1
    snap = ms.Snap(open_ms + 15_000, 0.49, 10, 0.51, 3, 0.49, 3, 0.51, 10)
    m = ms.Market("KXBTC15M-X", open_ms + 900_000, 59500.0, "no", [snap], [], [], [])
    b = os_.taker_bet(m, "BTC", 15, 0.02, 0.90)
    assert b.capped and b.contracts == 3 and not b.won and abs(b.pnl + kalshi_total_cost(3, 0.51, 0.07)) < 1e-9
    assert os_.taker_bet(m, "BTC", 15, 0.02, 0.52) is None   # fair - ask - fee < margin on both sides
    assert os_.taker_bet(m, "BTC", 15, 0.02, None) is None
    assert os_.taker_bet(m, "BTC", 90, 0.02, 0.90) is None   # quote older than 5 s


def test_report_and_main() -> None:
    td, r = _run()
    with td:
        txt = os_.report(r, iters=200)
        for needle in ("EXPLORATORY", "(PRIMARY)", "## (a)", "## (b)", "## (c)", "Verdict", "| ALL |"):
            assert needle in txt, needle
        ci_rows = [ln for ln in txt.splitlines() if ln.startswith("| ALL **(PRIMARY)** | 30 | 0.02 |")]
        # taker, maker, maker sensitivity; the taker cell has bets on 2 days -> a CI is computed
        assert len(ci_rows) == 3 and "[" in ci_rows[0], ci_rows
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            assert os_.main(["--dir", td.name, "--iters", "50"]) == 0
            assert os_.main(["--dir", td.name + "/missing"]) == 0
            assert os_.main(["--dir", td.name, "--json", "--coins", "btc"]) == 0
        out = buf.getvalue()
        assert "Window-open study" in out and "nothing to study" in out and '"ticker": "KXBTC15M-OPA"' in out
    with tempfile.TemporaryDirectory() as empty:
        txt = os_.report(os_.run(Path(empty)), iters=10)
        assert "EXPLORATORY" in txt and not math.isnan(len(txt))


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
