#!/usr/bin/env python3
"""No-network tests for weather_common / weather_scan / weather_study (stdlib only).

Run: python3 tools/research/test_weather.py
Fixtures: tools/research/fixtures/weather/ (regenerate with make_fixture.py there).
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import weather_common as wc  # noqa: E402
import weather_scan as ws  # noqa: E402
import weather_study as wst  # noqa: E402
from pipeline import kalshi_total_cost  # noqa: E402

FIX = HERE / "fixtures" / "weather"
NOW = datetime(2025, 10, 5, 22, 30, tzinfo=timezone.utc)


def close(a, b, tol=1e-9):
    return abs(a - b) <= tol


def quiet(fn, *a, **k):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
        rc = fn(*a, **k)
    return rc, buf.getvalue()


# --------------------------------------------------------------------------- parsing


def test_bucket_interval_subtitles():
    assert wc.bucket_interval({"yes_sub_title": "71° to 72°"})[:2] == (71, 72)
    assert wc.bucket_interval({"yes_sub_title": "66° or below"})[:2] == (None, 66)
    assert wc.bucket_interval({"yes_sub_title": "75° or above"})[:2] == (75, None)
    assert wc.bucket_interval({"yes_sub_title": "<67°"})[:2] == (None, 66)
    assert wc.bucket_interval({"yes_sub_title": ">74°"})[:2] == (75, None)


def test_bucket_interval_strikes_and_conflict():
    assert wc.bucket_interval({"strike_type": "between", "floor_strike": 71, "cap_strike": 72})[:2] == (71, 72)
    assert wc.bucket_interval({"strike_type": "greater", "floor_strike": 74})[:2] == (75, None)
    assert wc.bucket_interval({"strike_type": "less", "cap_strike": 67})[:2] == (None, 66)
    agree = wc.bucket_interval({"yes_sub_title": "75° or above", "strike_type": "greater", "floor_strike": 74})
    assert agree[:2] == (75, None) and "agree" in agree[2]
    bad = wc.bucket_interval({"yes_sub_title": "76° to 77°", "strike_type": "between", "floor_strike": 70, "cap_strike": 71})
    assert bad[:2] == (None, None) and bad[2].startswith("conflict")


def test_event_date():
    assert wc.event_date("KXHIGHNY-25OCT05") == date(2025, 10, 5)
    assert wc.event_date("KXHIGHNY-25OCT05-B71.5") == date(2025, 10, 5)
    assert wc.event_date("KXHIGHNY-25XYZ05") is None


def test_climate_window_dst_and_standard():
    s, e = wc.climate_window(date(2025, 10, 5), "America/New_York")  # EDT in effect
    assert s == datetime(2025, 10, 5, 5, tzinfo=timezone.utc) and (e - s).total_seconds() == 86400
    s, _ = wc.climate_window(date(2025, 1, 15), "America/New_York")
    assert s == datetime(2025, 1, 15, 5, tzinfo=timezone.utc)
    s, _ = wc.climate_window(date(2025, 7, 1), "America/Chicago")
    assert s == datetime(2025, 7, 1, 6, tzinfo=timezone.utc)
    # civil 10:00 EDT = 14:00Z, but 10:00 EST = 15:00Z
    assert wc.local_clock(date(2025, 10, 5), 10, "America/New_York") == datetime(2025, 10, 5, 14, tzinfo=timezone.utc)
    assert wc.local_clock(date(2025, 1, 5), 10, "America/New_York") == datetime(2025, 1, 5, 15, tzinfo=timezone.utc)


def test_f_floor_conversions():
    assert wc.f_floor_from_c(22.2, 0.05) == 72  # T-group 22.2C <- 72F
    assert wc.f_floor_from_c(22.0, 0.5) == 71   # whole 22C could be 71F
    assert wc.f_floor_from_c(-1.1, 0.05) == 30
    for f in range(-20, 115):  # every whole degF survives the ASOS tenths-degC round trip
        c = round((f - 32) / 1.8, 1)
        assert wc.f_floor_from_c(c, 0.05) == f, f


def test_obs_summary_window_qc_tgroup_6h():
    feats = json.loads((FIX / "observations" / "KNYC.json").read_text())["features"]
    s0, s1 = wc.climate_window(date(2025, 10, 5), "America/New_York")
    o = wc.obs_summary(feats, s0, s1)
    assert o["n"] == 6 and o["rejected"] == 1          # 04:51Z outside window; QC 'X' dropped
    assert o["cli_floor"] == 72 and o["est_hi"] == 72
    assert o["six_hour_groups"] == 1
    assert o["latest_f"] == 68.0 and o["max_time"].startswith("2025-10-05T19:51")
    # 6-hour group alone can raise the floor
    six = [{"properties": {"timestamp": "2025-10-05T17:51:00+00:00",
                           "rawMessage": "METAR KNYC 051751Z 21/10 RMK AO2 10250 20150 T02100100",
                           "temperature": {"value": 21.0}}}]
    assert wc.obs_summary(six, s0, s1)["cli_floor"] == 77  # 25.0C -> 77F
    # a 6h group whose window starts before the climate day is ignored
    early = [{"properties": {"timestamp": "2025-10-05T05:51:00+00:00",
                             "rawMessage": "METAR KNYC 050551Z 15/10 RMK AO2 10300 T01500100",
                             "temperature": {"value": 15.0}}}]
    assert wc.obs_summary(early, s0, s1)["six_hour_groups"] == 0


def test_parse_cli_prelim_and_final():
    prods = json.loads((FIX / "cli" / "NYC.json").read_text())
    pre = wc.parse_cli(prods[0]["productText"])
    fin = wc.parse_cli(prods[1]["productText"])
    assert pre == dict(date=date(2025, 10, 5), max_f=71, final=False, header="TODAY")
    assert fin["date"] == date(2025, 10, 4) and fin["max_f"] == 75 and fin["final"] is True
    mm = prods[1]["productText"].replace("MAXIMUM         75", "MAXIMUM         MM")
    assert wc.parse_cli(mm)["max_f"] is None
    assert wc.parse_cli("nothing here") is None


def test_station_mapping():
    st = wc.station_for({"settlement_sources": [{"url": "https://forecast.weather.gov/product.php?site=OKX&product=CLI&issuedby=NYC"}]})
    assert st["station"] == "KNYC" and st["cli"] == "NYC" and "issuedby" in st["basis"]
    st = wc.station_for({}, {"rules_primary": "highest temperature at Chicago Midway, IL as reported by the NWS"})
    assert st["station"] == "KMDW" and "name table" in st["basis"]
    st = wc.station_for({}, {"rules_primary": "as recorded at station KAUS in the NWS CLI"})
    assert st["station"] == "KAUS"
    assert wc.station_for({}, {"rules_primary": "Denver"})["station"] is None


def test_discovery_filters_and_seed_fallback():
    series, notes = wc.discover_series(wc.FixtureClient(FIX))
    assert [s["ticker"] for s in series] == ["KXHIGHCHI", "KXHIGHDEN", "KXHIGHNY"]
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / "series").mkdir()
        (Path(d) / "series" / "KXHIGHMIA.json").write_text(json.dumps({"series": {"ticker": "KXHIGHMIA"}}))
        series, notes = wc.discover_series(wc.FixtureClient(Path(d)))
        assert [s["ticker"] for s in series] == ["KXHIGHMIA"]
        assert any("seed fallback" in n for n in notes) and any("KXHIGHNY: not found" in n for n in notes)


# --------------------------------------------------------------------------- scan


def test_classify_margin():
    assert ws.classify(67, 68, 72) == "NO_LOCKED"
    assert ws.classify(69, 70, 72) == "NO_LOCKED"       # 72 >= 70 + 1 + 1
    assert ws.classify(69, 70, 71) == "OPEN"            # 71 reached but within margin
    assert ws.classify(69, 70, 71, margin=0) == "NO_LOCKED"
    assert ws.classify(75, None, 76) == "YES_LOCKED"
    assert ws.classify(75, None, 75) == "OPEN"
    assert ws.classify(None, 66, None) == "OPEN"
    assert ws.classify(71, 72, 99, exact=72) == "CLI_YES"
    assert ws.classify(None, 70, 0, exact=72) == "CLI_NO"


def test_edge_uses_exact_fee():
    # 10 NO at 0.96: 9.60 + ceil-cent(0.07*10*0.96*0.04 = 0.02688) = 9.63
    assert close(kalshi_total_cost(10, 0.96), 9.63)
    assert close(ws.edge_at(0.96), 0.037)
    assert close(ws.edge_at(0.99), 0.009)               # below the 1¢ bar
    assert close(ws.edge_at(0.99, 1), 0.0)              # 1 contract: fee rounds to a full cent
    assert ws.edge_at(None) is None
    assert ws.side_ask("NO", 0.04, 0.06) == 0.96 and ws.side_ask("YES", 0.04, 0.06) == 0.06
    assert ws.side_ask("NO", None, 0.03) is None


def test_scan_fixture_end_to_end():
    client = wc.FixtureClient(FIX)
    ctx = ws.setup(client, lambda s: None)
    by = {s["ticker"]: s for s in ctx["series"]}
    assert by["KXHIGHNY"]["station"] == "KNYC" and by["KXHIGHCHI"]["station"] == "KMDW"
    assert by["KXHIGHDEN"]["station"] is None
    snap = ws.snapshot(client, ctx, NOW)
    rows = {r["ticker"]: r for r in snap["rows"]}
    assert set(rows) == {"KXHIGHNY-25OCT05-T67", "KXHIGHNY-25OCT05-B67.5", "KXHIGHNY-25OCT05-B69.5",
                         "KXHIGHNY-25OCT05-T74", "KXHIGHCHI-25OCT05-B78.5", "KXHIGHCHI-25OCT05-T79"}
    b = rows["KXHIGHNY-25OCT05-B67.5"]
    assert b["state"] == "NO_LOCKED" and b["side"] == "NO" and close(b["ask"], 0.96)
    # book: NO asks 0.96 x30 (cost 28.89) + 0.98 x100 (cost 98.14) -> 130 contracts, $2.97
    assert b["depth"] == 130 and close(b["profit"], 2.97)
    assert rows["KXHIGHNY-25OCT05-T74"]["state"] == "LEAN_NO"
    assert rows["KXHIGHCHI-25OCT05-T79"]["state"] == "YES_LOCKED" and rows["KXHIGHCHI-25OCT05-T79"]["side"] == "YES"
    assert "KXHIGHNY-25OCT05-B71.5" not in rows and "KXHIGHNY-25OCT05-B73.5" not in rows
    assert any("B76.5" in e and "conflict" in e for e in snap["errors"])
    ev = {e["event"]: e for e in snap["events"]}
    assert ev["KXHIGHNY-25OCT06"]["status"] == "not started"
    assert ev["KXHIGHNY-25OCT05"]["cli"] == "prelim CLI ≥71°F" and ev["KXHIGHNY-25OCT05"]["floor"] == 72
    assert ev["KXHIGHCHI-25OCT05"]["lean"] is False  # 16:30 LST: before the lean hour


def test_scan_before_lean_hour_and_final_cli():
    client = wc.FixtureClient(FIX)
    ctx = ws.setup(client, lambda s: None)
    early = ws.snapshot(client, ctx, datetime(2025, 10, 5, 20, 0, tzinfo=timezone.utc))
    assert not any(r["state"] == "LEAN_NO" for r in early["rows"])

    class FinalCli(wc.FixtureClient):
        def cli_products(self, issuedby, n=3):
            txt = json.loads((FIX / "cli" / "NYC.json").read_text())[1]["productText"]
            return [{"productText": txt.replace("OCTOBER 4 2025", "OCTOBER 5 2025").replace("75", "72", 1)}]

    fc = FinalCli(FIX)
    snap = ws.snapshot(fc, ws.setup(fc, lambda s: None), NOW)
    st = {r["ticker"]: r["state"] for r in snap["rows"] if r["ticker"].startswith("KXHIGHNY")}
    assert st["KXHIGHNY-25OCT05-B71.5"] == "CLI_YES" and st["KXHIGHNY-25OCT05-B73.5"] == "CLI_NO"
    assert st["KXHIGHNY-25OCT05-T74"] == "CLI_NO"


def test_scan_main_report_and_api_errors():
    rc, out = quiet(ws.main, ["--fixture", str(FIX), "--now", "2025-10-05T22:30:00Z", "--repeat", "2", "--interval", "300"])
    assert rc == 0 and "## Locked buckets with edge" in out and "KXHIGHNY-25OCT05-B67.5" in out
    assert "| 2025-10-05T22:35:00Z |" in out and "issuedby=NYC" in out

    class Down(wc.FixtureClient):
        def events(self, series, status="open", max_pages=10):
            raise wc.ApiError("GET events: HTTP 503")

        def observations(self, sid, start, end):
            raise wc.ApiError("NWS down")

    down = Down(FIX)
    ctx = ws.setup(down, lambda s: None)
    snap = ws.snapshot(down, ctx, NOW)
    assert snap["rows"] == [] and any("HTTP 503" in e for e in snap["errors"])
    ns = argparse_ns()
    rep = ws.render(dict(ctx, snap_errors=[]), [snap], ns)
    assert "HTTP 503" in rep


def argparse_ns():
    class NS:
        margin, min_edge, fee_rate = ws.MARGIN_F, ws.MIN_EDGE, wc.FEE_RATE
    return NS()


# --------------------------------------------------------------------------- study


def test_bucket_prob_partition_and_phi():
    parts = [(None, 66), (67, 68), (69, 70), (71, None)]
    assert close(sum(wst.bucket_prob(lo, hi, 69.3, 2.1) for lo, hi in parts), 1.0, 1e-12)
    assert close(wst.phi(0.0), 0.5)


def test_quote_at_staleness_and_cents():
    cs = [{"end_period_ts": 1000, "yes_bid": {"close": 40}, "yes_ask": {"close": 45}},
          {"end_period_ts": 2000, "yes_bid": {"close_dollars": "0.4100"}, "yes_ask": {"close_dollars": "0.4600"}},
          {"end_period_ts": 3000, "yes_bid": {"close_dollars": "0.0000"}, "yes_ask": {"close_dollars": "0.5000"}}]
    assert wc.quote_at(cs, 1500) == (0.40, 0.45)
    assert wc.quote_at(cs, 2500) == (0.41, 0.46)
    assert wc.quote_at(cs, 3000) == (None, 0.50)
    assert wc.quote_at(cs, 1000 + 6 * 3600 + 1, max_stale_s=6 * 3600) == (None, 0.50)
    assert wc.quote_at(cs, 500) == (None, None)


def test_boot_ci_deterministic_and_min_days():
    rows = [dict(date=date(2025, 9, d), v=float(d % 3)) for d in range(1, 11)]
    stat = lambda s: sum(r["v"] for r in s) / len(s)  # noqa: E731
    a = wst.boot_ci(rows, stat, n=300)
    assert a == wst.boot_ci(rows, stat, n=300) and a[0] <= stat(rows) <= a[1]
    assert wst.boot_ci(rows[:4], stat) == (None, None)


def test_rule_trades_pnl():
    r = dict(date=date(2025, 9, 1), event="E1", bid=0.10, ask=0.14, mid=0.12, y=0, lo=1, hi=2)
    t = wst.rule_trades([r], "R1")
    assert len(t) == 1 and t[0]["side"] == "NO" and close(t[0]["px"], 0.90)
    assert close(t[0]["pnl"], 1 - kalshi_total_cost(10, 0.90) / 10)
    fav = dict(r, event="E2", bid=0.60, ask=0.64, mid=0.62, y=1)
    t2 = wst.rule_trades([dict(r, event="E2"), fav], "R2")
    assert len(t2) == 1 and t2[0]["side"] == "YES" and close(t2[0]["pnl"], 1 - kalshi_total_cost(10, 0.64) / 10)
    assert wst.rule_trades([dict(fav, ask=0.95)], "R2") == []
    t3 = wst.rule_trades([r], "R3", lambda _r: 0.30)
    assert t3[0]["side"] == "YES"  # 0.30 - cost(0.14) >= 0.05


def test_study_fixture_end_to_end():
    end = datetime(2025, 10, 5, 12, tzinfo=timezone.utc)
    data = wst.load(wc.FixtureClient(FIX), 15, end, lambda s: None)
    assert len(data["markets"]) == 40 and len(data["rows"]) == 80
    assert sum(m["y"] for m in data["markets"]) == 10  # exactly one YES per event
    assert len([k for k in data["fc"] if k[2].endswith("day1")]) == 10
    rc, out = quiet(wst.main, ["--fixture", str(FIX), "--end", "2025-10-05T12:00:00Z", "--days", "15"])
    assert rc == 0 and "R3 forecast edge" in out and "## MORNING snapshot" in out and "issuedby=NYC" in out
    # snapshot hours are local civil time (EDT): 10:00 EDT = 14:00Z
    m = [x for x in data["rows"] if x["ticker"] == "KXHIGHNY-25OCT01-T67" and x["snap"] == "MORNING"]
    assert m and m[0]["bid"] > 0


def test_study_api_failure_is_reported():
    class Down(wc.FixtureClient):
        def settled_markets(self, *a, **k):
            raise wc.ApiError("HTTP 500 markets")

    data = wst.load(Down(FIX), 15, datetime(2025, 10, 5, tzinfo=timezone.utc), lambda s: None)
    assert data["markets"] == [] and any("HTTP 500" in e for e in data["errors"])

    class NS:
        days, no_forecast = 15, False
    rep = wst.render(data, NS())
    assert "HTTP 500" in rep and "_no quotes_" in rep


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
