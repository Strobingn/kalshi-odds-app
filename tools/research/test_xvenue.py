#!/usr/bin/env python3
"""No-network unit tests for tools/research/xvenue_scan.py (stdlib only).

Run: python3 tools/research/test_xvenue.py
Fixture: tools/research/fixtures/xvenue/ (synthetic; regenerate with make_fixture.py there).
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import xvenue_scan as x  # noqa: E402
from pipeline import kalshi_total_cost  # noqa: E402  (path set by arb_scan)

FIX = HERE / "fixtures" / "xvenue"


def close(a, b, eps=1e-9) -> bool:
    return abs(a - b) <= eps


def fixture_matches():
    c = x.FixtureClient(FIX)
    k = x.kalshi_records(c.events_page(None)["events"])
    p = x.poly_records(c.poly_markets_page(0, 1000))
    m, stats = x.match(k, p)
    return {mm["kalshi"].key: mm["poly"].key for mm in m}, stats, k, p


def kr(q, label="", times=("2026-10-28T18:00:00Z",), cat="economics", explicit=True, group="E"):
    r = x.Rec("kalshi", "K-" + q[:10], q, label, cat, explicit, [x._ts(t) for t in times], "", "", None, group)
    r.norm = x.normalize(q)
    return r


def pr(q, label="", times=("2026-10-28T00:00:00Z",), cat="", explicit=False, group="P"):
    r = x.Rec("poly", "P-" + q[:10], q, label, cat, explicit, [x._ts(t) for t in times], "", "", None, group)
    r.norm = x.normalize(q)
    return r


def test_normalize_numbers_dates_comparators() -> None:
    a = x.normalize("Will Bitcoin be above $120,000 on Oct 31, 2026?")
    b = x.normalize("Will Bitcoin be above $120k on October 31?")
    assert a.numbers == b.numbers == {120000.0}, (a.numbers, b.numbers)
    assert a.dates == b.dates == {(10, 31)} and a.years == {2026} and not b.years
    assert a.comparator == b.comparator == "gt"
    assert x.normalize("CPI below 3.0%").comparator == "lt"
    assert x.normalize("CPI year-over-year above 3%").comparator == "gt"   # "over" in yoy is not a comparator
    assert x.normalize("CPI above 3%").numbers == x.normalize("CPI above 3.0 percent").numbers == {3.0}
    assert x.normalize("cut 25bps").numbers == x.normalize("decrease by 25 basis points").numbers == {25.0}
    assert {"cut", "fed", "rate"} <= x.normalize("Fed decreases interest rates").tokens
    assert x.normalize("Will X not win?").negated and not x.normalize("Will X win?").negated
    assert x.normalize("2026-27 NBA season").years == {2026} and not x.normalize("2026-27 NBA season").numbers


def test_team_aliases() -> None:
    assert {"kansa", "city"} <= x.normalize("Chiefs vs. Bills").tokens
    assert "buffalo" in x.normalize("Chiefs vs. Bills").tokens
    # a full team name is not re-mapped ("Florida Panthers" is not Carolina)
    assert "carolina" not in x.normalize("Florida Panthers vs. Boston Bruins").tokens
    # shared nicknames across leagues map to nothing; shared cities keep their letter
    assert x.TEAM_ALIASES["cardinals"] == set()
    assert x.label_expand("Jets") != x.label_expand("Giants")
    assert "j" in x.label_tokens("New York J")


def test_fixture_positive_matches() -> None:
    got, stats, k, p = fixture_matches()
    assert got == {
        "KXFEDDECISION-26OCT-C25": "poly:101",
        "KXNFLGAME-26OCT12KCBUF-KC": "poly:201:0",
        "KXNFLGAME-26OCT12KCBUF-BUF": "poly:201:1",
        "KXSENATEOH-26-JDOE": "poly:301",
        "KXCPIYOY-26SEP-T3.0": "poly:401",
        "KXBTCD-26OCT31-T120000": "poly:501",
    }, got
    # skipped records: multivariate parlay, 3-outcome market, order book disabled
    assert not any("MVE" in r.key for r in k)
    assert not any(r.key.startswith("poly:601") or r.key.startswith("poly:701") for r in p)


def test_fixture_negative_cases() -> None:
    got, stats, _, _ = fixture_matches()
    matched_poly = set(got.values())
    assert "poly:202:0" not in matched_poly and "poly:202:1" not in matched_poly   # same teams, Dec 20
    assert "poly:502" not in matched_poly                                           # same strike, Nov 30
    assert "poly:402" not in matched_poly                                           # below vs above
    assert "KXSENATEOH-26-JROE" not in got                                          # absent candidate
    assert "KXFEDDECISION-26OCT-C50" not in got   # exactly 50 vs "50+" (comparator) -- needs a human
    assert "KXCPIYOY-26SEP-T3.5" not in got and "KXBTCD-26OCT31-T130000" not in got
    assert stats.get("rejected: comparator differs", 0) >= 1 and stats.get("rejected: outcome labels", 0) >= 1


def test_hard_constraints_direct() -> None:
    ok = x.constraints(kr("Will the Lakers win on Oct 20?"), pr("Will the Lakers win on Oct 20?"), 2)
    assert ok is None, ok
    assert "dates" in x.constraints(kr("Lakers win on Oct 20?"), pr("Lakers win on Oct 21?"), 2)
    assert "end dates" in x.constraints(kr("Lakers win?"), pr("Lakers win?", times=("2026-11-28T00:00:00Z",)), 2)
    assert "thresholds" in x.constraints(kr("Bitcoin above $120k?"), pr("Bitcoin above $125k?"), 2)
    assert "comparator" in x.constraints(kr("CPI above 3%?"), pr("CPI below 3%?"), 2)
    assert "negation" in x.constraints(kr("Will Smith win?"), pr("Will Smith not win?"), 2)
    assert "years" in x.constraints(kr("Ohio Senate 2026"), pr("Ohio Senate 2028"), 2)
    assert "category" in x.constraints(kr("Apple event", cat="tech"), pr("Apple event", cat="culture", explicit=True), 2)
    # inferred (non-explicit) Polymarket category never rejects on its own
    assert x.constraints(kr("Apple event", cat="tech"), pr("Apple event", cat="culture"), 2) is None
    # different candidates on both sides
    why = x.constraints(kr("Ohio Senate winner — Jane Doe", label="Jane Doe"),
                        pr("Will John Roe win Ohio Senate?", label="John Roe"), 2)
    assert why and "labels" in why, why
    why = x.constraints(kr("Will Jane Doe beat Mark Poe?"), pr("Will Jane Doe beat Lisa Fox?"), 2)
    assert why and "entities" in why, why


def test_ambiguous_unlabelled_poly_dropped() -> None:
    # One Polymarket Yes/No question matching both outcomes of one Kalshi game must not be paired.
    k1 = kr("Kansas City at Buffalo Winner? — Kansas City", label="Kansas City", cat="sports", group="G")
    k2 = kr("Kansas City at Buffalo Winner? — Buffalo", label="Buffalo", cat="sports", group="G")
    k1.key, k2.key = "G-KC", "G-BUF"
    p = pr("Kansas City vs Buffalo winner", cat="sports")
    m, stats = x.match([k1, k2], [p], min_score=0.3)
    assert m == [] and any(k.startswith("dropped: ambiguous") for k in stats), (m, stats)


def test_pair_cost_math() -> None:
    # top of book: 0.60 + 0.35, Kalshi fee 0.07*.6*.4, Poly econ fee 0.05*.35*.65
    c = x.pair_cost([(0.60, 100), (0.62, 200)], [(0.35, 50.7), (0.37, 500)], 0.07, 0.05)
    assert close(c["gross"], 0.05, 1e-9)
    assert close(c["kalshi_fee"], round(0.07 * 0.6 * 0.4, 4)) and close(c["poly_fee"], round(0.05 * 0.35 * 0.65, 4))
    assert close(c["net"], round(0.05 - 0.07 * 0.24 - 0.05 * 0.35 * 0.65, 4))
    # depth walk: 50 @ (.60,.35) edge +.0218, 50 @ (.60,.37) edge +.0015, then (.62,.37) negative -> 100 units
    assert c["units"] == 100, c
    assert c["kalshi_fills"] == [(0.60, 100)] and c["poly_fills"] == [(0.35, 50), (0.37, 50)]
    k_cost = kalshi_total_cost(100, 0.60)
    p_cost = x.poly_level_cost(50, 0.35, 0.05) + x.poly_level_cost(50, 0.37, 0.05)
    assert close(c["cost"], round(k_cost + p_cost, 4)) and close(c["profit"], round(100 - k_cost - p_cost, 4))
    assert close(x.poly_level_cost(50, 0.35, 0.05), 17.5 + 0.5688)   # fee 0.56875 rounded up to 1e-4
    # no positive unit -> zero size; empty book -> None
    neg = x.pair_cost([(0.62, 10)], [(0.40, 10)], 0.07, 0.05)
    assert neg["units"] == 0 and neg["net"] < 0
    assert x.pair_cost([], [(0.4, 1)], 0.07, 0.05) is None
    # feesEnabled=false and override
    r = pr("q", cat="sports")
    assert x.poly_fee_rate(r) == x.POLY_FEE_RATES["sports"] and x.poly_fee_rate(r, 0.02) == 0.02
    r.fees_enabled = False
    assert x.poly_fee_rate(r, 0.02) == 0.0


def test_rule_flags() -> None:
    f = x.rule_flags("Includes overtime. If the game ends in a tie or is postponed, resolves 50-50. Source: https://www.nfl.com/x")
    assert f["overtime"] and f["ties"] and f["cancel"] and f["fifty_fifty"] and "www.nfl.com" in f["sources"]
    g = x.rule_flags("annual CPI inflation as released by the Bureau of Labor Statistics")
    assert g["sources"] == ["Bureau of Labor Statistics"], g        # "NFL" is not found inside "inflation"
    assert x.rule_flags("resolves Yes if it happens by December 31, 2026")["deadline"].startswith("by/before")
    assert x.rule_flags("as first released by the BLS")["deadline"] == ""


def test_end_to_end_fixture_report() -> None:
    with tempfile.TemporaryDirectory() as td:
        out, js = Path(td) / "r.md", Path(td) / "r.json"
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            rc = x.main(["--fixture", str(FIX), "--repeat", "2", "--interval", "0", "--out", str(out), "--json", str(js)])
        assert rc == 0
        rep = out.read_text()
        assert "VERIFY RULES" in rep and "risk-free" not in rep.replace("NOT risk-free", "")
        assert "## Persistence" in rep and "2/2" in rep
        data = json.loads(js.read_text())
        assert len(data["matches"]) == 6 and len(data["snapshots"]) == 2
        rows = {(r["kalshi"], r["poly"]): r for r in data["snapshots"][0]["rows"]}
        fed = rows[("KXFEDDECISION-26OCT-C25", "poly:101")]["combos"]["Kalshi YES + Poly NO"]
        assert fed["units"] == 100 and fed["profit"] > 0
        cpi = rows[("KXCPIYOY-26SEP-T3.0", "poly:401")]["combos"]["Kalshi NO + Poly YES"]
        assert close(cpi["gross"], 0.07) and cpi["units"] == 40      # Kalshi NO ask = 1 - YES bid .62


def test_api_failure_is_graceful() -> None:
    class Down(x.FixtureClient):
        def events_page(self, cursor):
            raise RuntimeError("kalshi unreachable")

        def poly_markets_page(self, offset, limit):
            raise RuntimeError("gamma unreachable")

    a = argparse.Namespace(max_pages=1, poly_pages=1, poly_limit=10, min_score=0.45, max_date_diff_days=2.0)
    m, info = x.build_matches(Down(FIX), a, lambda s: None)
    assert m == [] and info["kalshi_status"].startswith("error") and info["kalshi_markets"] == 0


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
