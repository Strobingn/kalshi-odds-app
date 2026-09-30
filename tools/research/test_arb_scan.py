#!/usr/bin/env python3
"""No-network unit tests for tools/research/arb_scan.py (stdlib only).

Run: python3 tools/research/test_arb_scan.py
Fixture: tools/research/fixtures/arb/ (events.json + books.json, hand-written).
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import arb_scan as a  # noqa: E402
from pipeline import kalshi_total_cost  # noqa: E402  (path set by arb_scan)

FIX = HERE / "fixtures" / "arb"


def book(yes=(), no=()) -> a.Book:
    """yes / no: [(price $, qty)] bids."""
    return a.parse_orderbook({"orderbook_fp": {"yes_dollars": [[f"{p:.4f}", f"{q}.00"] for p, q in yes],
                                               "no_dollars": [[f"{p:.4f}", f"{q}.00"] for p, q in no]}})


def mkt(ticker, st=None, floor=None, cap=None) -> dict:
    m = {"ticker": ticker, "status": "active"}
    if st:
        m["strike_type"] = st
    if floor is not None:
        m["floor_strike"] = floor
    if cap is not None:
        m["cap_strike"] = cap
    return m


def close(x, y, eps=1e-9) -> bool:
    return abs(x - y) <= eps


def test_orderbook_parsing_and_asks() -> None:
    # dollars_fp form, legacy cents form, and unsorted levels all end up best-bid-first.
    b = a.parse_orderbook({"orderbook_fp": {"yes_dollars": [["0.3000", "5.00"], ["0.3500", "2.50"]],
                                            "no_dollars": [["0.6000", "4.00"]]}})
    assert b.yes_bids == [(0.35, 2), (0.30, 5)], b.yes_bids  # 2.5 floors to 2 whole contracts
    assert b.yes_asks() == [(0.40, 4)]
    assert b.no_asks() == [(0.65, 2), (0.70, 5)]
    c = a.parse_orderbook({"orderbook": {"yes": [[30, 5], [35, 2]], "no": [[60, 4]], "yes_dollars": None}})
    assert c.yes_bids == [(0.35, 2), (0.30, 5)] and c.no_bids == [(0.60, 4)]
    assert a.parse_orderbook({"orderbook": {"yes": None, "no": None}}).yes_asks() == []
    # listing quotes: dollars preferred, legacy cents, 0/1 placeholders ignored, complement fill-in
    assert a.top_quote({"yes_bid_dollars": "0.4100", "yes_ask_dollars": "0.4300"}) == (0.41, 0.43)
    assert a.top_quote({"yes_bid": 41, "yes_ask": 43}) == (0.41, 0.43)
    assert a.top_quote({"yes_ask_dollars": "1.0000", "no_bid_dollars": "0.5500"}) == (None, 0.45)


def test_fee_math_matches_pipeline() -> None:
    for q, p in ((1, 0.5), (7, 0.33), (100, 0.02), (13, 0.97)):
        assert a.fills_cost([(p, q)]) == kalshi_total_cost(q, p)
    # single leg, single level: size_combo cost is exactly pipeline's
    s = a.size_combo([[(0.40, 10)]], 1.0)
    assert s.contracts == 10 and close(s.cost, kalshi_total_cost(10, 0.40))
    assert close(s.profit, 10 - kalshi_total_cost(10, 0.40))
    assert close(s.fees, kalshi_total_cost(10, 0.40) - 4.0, 1e-6)


def test_depth_walk_multiple_levels() -> None:
    leg1 = [(0.40, 5), (0.45, 5), (0.52, 50)]
    leg2 = [(0.50, 40)]
    s = a.size_combo([leg1, leg2], 1.0)
    # units 1-5: 1-.90-fees>0; 6-10: 1-.95-.0348>0; 11+: 1-1.02<0  -> 10 contracts
    assert s.contracts == 10, s
    want_cost = kalshi_total_cost(5, 0.40) + kalshi_total_cost(5, 0.45) + kalshi_total_cost(10, 0.50)
    assert close(s.cost, round(want_cost, 2)) and close(s.profit, round(10 - want_cost, 2))
    assert s.legs[0][0] == [(0.40, 5), (0.45, 5)] and s.legs[1][0] == [(0.50, 10)]
    assert a.walk(leg1, 61) is None and a.walk(leg1, 7) == [(0.40, 5), (0.45, 2)]
    # thin second leg caps size
    assert a.size_combo([leg1, [(0.50, 3)]], 1.0).contracts == 3


def test_box() -> None:
    ev = {"event_ticker": "E"}
    m = mkt("E-X")
    # crossed book: YES bid .55 + NO bid .50 -> YES ask .50 + NO ask .45 = .95
    o = a.check_box(ev, m, book(yes=[(0.55, 6)], no=[(0.50, 4)]))
    assert o["contracts"] == 4 and o["profit"] > 0
    want = 4 - kalshi_total_cost(4, 0.50) - kalshi_total_cost(4, 0.45)
    assert close(o["profit"], round(want, 2))
    # normal book (spread 2c) -> not profitable
    o2 = a.check_box(ev, m, book(yes=[(0.48, 6)], no=[(0.50, 4)]))
    assert o2["profit"] <= 0 and o2["edge_top_per_contract"] < 0


def _range_event(flag=True):
    ev = {"event_ticker": "R", "mutually_exclusive": flag}
    ms = [mkt("R-LO", "less", cap=100), mkt("R-MID", "between", 100, 110), mkt("R-HI", "greater", floor=110)]
    return ev, ms


def test_exhaustive_set_long() -> None:
    ev, ms = _range_event()
    st = a.set_structure(ev, ms)
    assert st["exhaustive"] == "verified" and st["exclusive"] == "verified"
    books = {"R-LO": book(yes=[(0.2, 9)], no=[(0.70, 10)]),
             "R-MID": book(yes=[(0.2, 9)], no=[(0.69, 10)]),
             "R-HI": book(yes=[(0.2, 9)], no=[(0.70, 3), (0.64, 10)])}
    opps = {o["kind"]: o for o in a.check_sets(ev, ms, books)}
    lo = opps["set-long"]
    # asks .30,.31,.30 (3 deep) then .36: 1-.91-fees>0, 1-.97-fees<0 -> 3 contracts
    assert lo["contracts"] == 3 and lo["risk_free"] and lo["payoff"] == 3.0
    want = kalshi_total_cost(3, 0.30) + kalshi_total_cost(3, 0.31) + kalshi_total_cost(3, 0.30)
    assert close(lo["profit"], round(3 - want, 2)) and lo["profit"] > 0
    assert opps["set-short"]["profit"] < 0


def test_exclusive_set_short() -> None:
    ev, ms = _range_event()
    books = {t: book(yes=[(0.40, 5)], no=[(0.55, 5)]) for t in ("R-LO", "R-MID", "R-HI")}
    sh = {o["kind"]: o for o in a.check_sets(ev, ms, books)}["set-short"]
    # sum YES bids 1.20 > 1: buy 3 NO at .60, payoff n-1 = 2 per set
    assert sh["payoff_per_contract"] == 2.0 and sh["contracts"] == 5 and sh["risk_free"]
    want = 3 * kalshi_total_cost(5, 0.60)
    assert close(sh["profit"], round(10 - want, 2)) and sh["profit"] > 0


def test_non_exhaustive_not_flagged() -> None:
    # gap between 110 and 120, no tails, no flag: cheap YES asks must NOT be a set-long arb
    ev = {"event_ticker": "G", "mutually_exclusive": False}
    ms = [mkt("G-A", "between", 100, 110), mkt("G-B", "between", 120, 130)]
    st = a.set_structure(ev, ms)
    assert st["exhaustive"] == "no"
    books = {"G-A": book(yes=[(0.2, 50)], no=[(0.78, 50)]), "G-B": book(yes=[(0.2, 50)], no=[(0.78, 50)])}
    kinds = [o["kind"] for o in a.check_sets(ev, ms, books)]
    assert "set-long" not in kinds
    # flag true but a gap -> "assumed", reported but not risk-free
    ev2 = {"event_ticker": "G2", "mutually_exclusive": True}
    ms2 = [mkt("A", "less", cap=100), mkt("B", "between", 101, 110), mkt("C", "greater", floor=110)]
    st2 = a.set_structure(ev2, ms2)
    assert st2["exhaustive"] == "assumed", st2
    books2 = {t: book(yes=[(0.1, 9)], no=[(0.75, 9)]) for t in ("A", "B", "C")}
    lo = [o for o in a.check_sets(ev2, ms2, books2) if o["kind"] == "set-long"][0]
    assert lo["profit"] > 0 and not lo["risk_free"] and not a.is_risk_free(lo)
    # no strikes, no flag -> no set checks at all; no strikes + flag -> assumed
    assert a.set_structure({"mutually_exclusive": False}, [mkt("x"), mkt("y")])["exhaustive"] == "no"
    assert a.set_structure({"mutually_exclusive": True}, [mkt("x"), mkt("y")])["exhaustive"] == "assumed"
    # missing open-ended tail -> no
    ms3 = [mkt("A", "less", cap=100), mkt("B", "between", 100, 110)]
    assert a.set_structure({"mutually_exclusive": True}, ms3)["exhaustive"] == "no"


def test_ladder_violation() -> None:
    ev = {"event_ticker": "L"}
    ms = [mkt("L-100", "greater", floor=100), mkt("L-110", "greater", floor=110), mkt("L-120", "greater", floor=120)]
    books = {
        "L-100": book(yes=[(0.48, 30)], no=[(0.50, 12)]),   # YES ask .50
        "L-110": book(yes=[(0.60, 7)], no=[(0.38, 30)]),    # YES bid .60 > ask(100) -> NO ask .40
        "L-120": book(yes=[(0.20, 7)], no=[(0.78, 30)]),
    }
    opps = [o for o in a.check_ladders(ev, ms, books) if o["profit"] > 0]
    assert len(opps) == 1, opps
    o = opps[0]
    assert o["tickers"] == ["L-100", "L-110"] and o["legs"][0]["side"] == "YES" and o["legs"][1]["side"] == "NO"
    assert o["contracts"] == 7
    assert close(o["profit"], round(7 - kalshi_total_cost(7, 0.50) - kalshi_total_cost(7, 0.40), 2))
    # "below" ladder: P(<100) must be <= P(<110); violated when YES bid(100) > YES ask(110)
    msd = [mkt("D-100", "less", cap=100), mkt("D-110", "less", cap=110)]
    bd = {"D-100": book(yes=[(0.55, 5)], no=[(0.40, 5)]), "D-110": book(yes=[(0.40, 5)], no=[(0.52, 5)])}
    od = [o for o in a.check_ladders(ev, msd, bd) if o["profit"] > 0]
    assert len(od) == 1 and od[0]["tickers"] == ["D-110", "D-100"] and od[0]["direction"] == "down"
    # monotone ladder -> nothing profitable
    ok = {"L-100": book(yes=[(0.60, 9)], no=[(0.38, 9)]), "L-110": book(yes=[(0.40, 9)], no=[(0.58, 9)]),
          "L-120": book(yes=[(0.20, 9)], no=[(0.78, 9)])}
    assert not [o for o in a.check_ladders(ev, ms, ok) if o["profit"] > 0]


def test_ladder_only_nests_same_subject() -> None:
    """Scan 1 bug: 'BC by 2+' vs NOT 'SMU by 10+' was called risk-free (both lose if SMU wins by 15)."""
    assert a.ladder_subject("KXNCAAFSPREAD-26OCT03BCSMU-BC2") == "KXNCAAFSPREAD-26OCT03BCSMU-BC"
    assert a.ladder_subject("KXNCAAFSPREAD-26OCT03BCSMU-SMU10") == "KXNCAAFSPREAD-26OCT03BCSMU-SMU"
    assert a.ladder_subject("KXNFLRSHYDS-26OCT01PITCLE-CLERSANDERS23-25") == "KXNFLRSHYDS-26OCT01PITCLE-CLERSANDERS23"
    assert a.ladder_subject("KXBTCD-26SEP3017-T84999.99") == a.ladder_subject("KXBTCD-26SEP3017-T85249.99")
    ev = {"event_ticker": "S"}
    ms = [mkt("S-BC2", "greater", floor=1.5), mkt("S-SMU10", "greater", floor=9.5)]
    books = {"S-BC2": book(yes=[(0.05, 50)], no=[(0.90, 50)]), "S-SMU10": book(yes=[(0.80, 50)], no=[(0.10, 50)])}
    assert a.check_ladders(ev, ms, books) == []
    ms.append(mkt("S-BC5", "greater", floor=4.5))
    books["S-BC5"] = book(yes=[(0.20, 9)], no=[(0.70, 9)])
    opps = [o for o in a.check_ladders(ev, ms, books) if o["profit"] > 0]
    assert [o["tickers"] for o in opps] == [["S-BC2", "S-BC5"]], opps
    # Same subject but different written rules or close times: not nested.
    r7 = dict(mkt("R-7.0", "less", cap=7), rules_primary="If fewer than 7 Starship flights reach space in 2026, resolves Yes.")
    r5 = dict(mkt("R-5.0", "less", cap=5), rules_primary="If fewer than 5 Starship flights reach space before Jul 1, 2026, resolves Yes.")
    rb = {"R-7.0": book(yes=[(0.01, 9)], no=[(0.97, 9)]), "R-5.0": book(yes=[(0.47, 9)], no=[(0.40, 9)])}
    assert a.check_ladders(ev, [r7, r5], rb) == []
    r5["rules_primary"] = r7["rules_primary"].replace("7", "5")
    assert len(a.check_ladders(ev, [r7, r5], rb)) == 1
    r5["close_time"] = "2026-07-01T00:00:00Z"
    assert a.check_ladders(ev, [r7, r5], rb) == []


def test_fixture_end_to_end_and_persistence() -> None:
    with tempfile.TemporaryDirectory() as td:
        md, js = Path(td) / "r.md", Path(td) / "r.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            rc = a.main(["--fixture", str(FIX), "--repeat", "2", "--interval", "0", "--out", str(md), "--json", str(js)])
        assert rc == 0
        rep = md.read_text()
        data = json.loads(js.read_text())
    snap = data["snapshots"][-1]
    kinds = sorted((o["kind"], o["event_ticker"]) for o in snap["opportunities"])
    assert ("set-long", "KXTEST-RANGE") in kinds and ("ladder", "KXTEST-LADDER") in kinds, kinds
    assert not any(e == "KXTEST-GAP" and k == "set-long" for k, e in kinds)
    rng = [o for o in snap["opportunities"] if o["kind"] == "set-long"][0]
    assert rng["contracts"] == 8 and rng["exhaustive"] == "verified"  # MID has 8 deep
    lad = [o for o in snap["opportunities"] if o["kind"] == "ladder"][0]
    assert lad["contracts"] == 7
    total = round(rng["profit"] + lad["profit"], 2)
    assert f"2 risk-free opportunities, total locked profit ${total:.2f}" in rep, rep[:600]
    assert data["persistence"]["with_opportunity"] == 2
    assert all(r["longest_streak"] == 2 for r in data["persistence"]["rows"])
    assert "Cross-venue" in rep and "NOT risk-free" in rep


def test_cross_venue_shape_and_graceful_skip() -> None:
    import time
    from datetime import datetime, timezone
    now = time.time()
    close_s = int(now // 900 * 900 + 900)
    ev = {"event_ticker": "KXBTC15M-X", "series_ticker": "KXBTC15M", "markets": [
        {"ticker": "KXBTC15M-X-00", "status": "active", "floor_strike": 100000,
         "close_time": datetime.fromtimestamp(close_s, timezone.utc).isoformat().replace("+00:00", "Z")}]}
    slug = f"btc-updown-15m-{close_s - 900}"

    class C:
        def poly_event(self, s):
            assert s == slug
            return [{"markets": [{"question": "BTC Up or Down", "outcomes": '["Up", "Down"]',
                                  "clobTokenIds": '["tu", "td"]', "description": "Resolves via Chainlink BTC/USD."}]}]

        def poly_book(self, tok):
            return {"asks": [{"price": "0.47" if tok == "td" else "0.55", "size": "100"}], "bids": []}

        def orderbook(self, t):
            return {"orderbook_fp": {"yes_dollars": [["0.4800", "10"]], "no_dollars": [["0.5000", "10"]]}}

    res = a.cross_venue(C(), [ev], {}, now, print)
    row = res["rows"][0]
    g = row["Kalshi YES(up) + Poly Down"]
    assert close(g["gross_gap"], 1 - (0.50 + 0.47)) and g["gap_after_kalshi_fee"] < g["gross_gap"]
    assert row["polymarket_source_mentions"] == ["Chainlink"]

    class Down(C):
        def poly_event(self, s):
            raise RuntimeError("unreachable")

    assert a.cross_venue(Down(), [ev], {}, now, print)["status"] == "unreachable"
    assert a.cross_venue(C(), [], {}, now, print)["status"] == "skipped"


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
