#!/usr/bin/env python3
"""Fixture-based tests for fee_probe.py (no network). Run: python3 tools/research/test_fee_probe.py"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fee_probe as fp  # noqa: E402

BASE = "https://example.test/trade-api/v2"
FIX = json.loads((HERE / "fixtures" / "fee_probe" / "responses.json").read_text())
NOW = datetime(2026, 10, 5, 12, 0, tzinfo=timezone.utc)


def fake_get(calls: list[str] | None = None, fail: set[str] | None = None):
    def get(u: str):
        assert u.startswith(BASE), u
        path = u[len(BASE):]
        if calls is not None:
            calls.append(path)
        if fail and any(path.startswith(f) for f in fail):
            raise RuntimeError("HTTP 500 simulated")
        return FIX.get(path)  # missing -> None, like a 404
    return get


def run(series=("KXBTC15M", "KXETH15M", "KXSOL15M", "KXBTCD"), **kw):
    return fp.run(fake_get(**kw), BASE, tuple(series), NOW)


def by_series(res, t):
    return next(s for s in res["series"] if s["series"] == t)


def test_rates_quadratic_has_no_maker_fee():
    r = fp.rates_for("quadratic", 1)
    assert r["known"] and r["taker_rate"] == Decimal("0.07") and r["maker_rate"] == 0


def test_rates_maker_and_combo_and_multiplier():
    assert fp.rates_for("quadratic_with_maker_fees", 1)["maker_rate"] == Decimal("0.0175")
    assert fp.rates_for("quadratic_with_combo_maker_fees", 1)["maker_rate"] == Decimal("0.035")
    r = fp.rates_for("quadratic_with_maker_fees", 0.5)
    assert r["taker_rate"] == Decimal("0.035") and r["maker_rate"] == Decimal("0.00875")
    assert fp.rates_for("quadratic", None)["taker_rate"] == Decimal("0.07")  # default multiplier 1


def test_rates_unknown_types():
    for t in ("flat", "margin_market_maker_program_fees", None, "something_new"):
        r = fp.rates_for(t, 1)
        assert not r["known"] and r["maker_rate"] is None and r["taker_rate"] is None
    assert not fp.rates_for("quadratic", "not-a-number")["known"]


def test_trade_fee_rounding():
    # 0.07*10*0.5*0.5 = 0.175 exactly
    assert fp.trade_fee(Decimal("0.07"), 10, "0.50") == Decimal("0.1750")
    # 0.0175*10*0.3*0.7 = 0.03675 -> up to 0.0368 (centicent), 0.04 (cent)
    assert fp.trade_fee(Decimal("0.0175"), 10, "0.30") == Decimal("0.0368")
    assert fp.trade_fee_cent(Decimal("0.0175"), 10, "0.30") == Decimal("0.04")
    assert fp.trade_fee(Decimal("0"), 10, "0.50") == 0


def test_series_interpretation_from_fixtures():
    res = run()
    btc, eth, sol, btcd = (by_series(res, t) for t in ("KXBTC15M", "KXETH15M", "KXSOL15M", "KXBTCD"))
    assert btc["fee_type"] == "quadratic" and btc["maker_rate"] == 0 and not btc["errors"]
    assert btc["series_fee_fields"] == {"fee_type": "quadratic", "fee_multiplier": 1}
    assert eth["maker_rate"] == Decimal("0.0175")
    assert eth["examples"][2] == {"price": "0.50", "contracts": 10, "taker_fee": "0.1750",
                                  "maker_fee": "0.0438", "maker_fee_cent_rounded": "0.05"}
    assert not sol["known"] and sol["maker_rate"] is None and "examples" not in sol
    assert btcd["errors"] == ["series not found"] and btcd["maker_rate"] is None


def test_future_fee_change_flagged():
    btc = by_series(run(), "KXBTC15M")
    assert len(btc["fee_changes"]) == 2
    assert [c["id"] for c in btc["future_fee_changes"]] == ["c2"]
    assert "WARNING scheduled change" in fp.report(run())


def test_open_market_fee_fields():
    btc = by_series(run(), "KXBTC15M")
    assert btc["open_markets"] == [{"ticker": "KXBTC15M-26OCT051215-15", "fee_waiver_expiration_time": None}]


def test_event_fee_changes_paged_and_filtered():
    calls: list[str] = []
    ev = run(calls=calls)["event_fee_changes"]
    assert [c["id"] for c in ev["matches"]] == ["e1", "e3"]
    assert ev["pages"] == 2 and ev["exhausted"] and ev["error"] is None
    assert "/events/fee_changes?limit=200&cursor=page2" in calls


def test_incentives_filtered_and_converted():
    inc = run()["incentives"]
    act = inc["by_status"]["active"]
    assert act["total_programs"] == 2 and len(act["ours"]) == 1 and not act["truncated"]
    p = act["ours"][0]
    assert p["period_reward_usd"] == 20.0 and abs(p["reward_per_day_usd"] - 10.0) < 1e-9
    assert p["discount_factor"] == 0.5 and p["target_size"] == "500.00"
    assert ("KXBTC15M", 1) in act["top_series"] and ("KXNFLGAME", 1) in act["top_series"]
    assert inc["by_status"]["upcoming"]["total_programs"] == 0


def test_errors_are_contained():
    res = run(fail={"/series/KXETH15M", "/incentive_programs", "/events/fee_changes"})
    eth = by_series(res, "KXETH15M")
    assert any(e.startswith("series:") for e in eth["errors"]) and eth["maker_rate"] is None
    assert by_series(res, "KXBTC15M")["maker_rate"] == 0
    assert res["event_fee_changes"]["error"] and res["incentives"]["error"]
    text = fp.report(res)
    assert "ERROR" in text and "MAKER RATE UNKNOWN" in text


def test_report_verdict_lines():
    text = fp.report(run())
    assert "KXBTC15M: fee_type='quadratic' multiplier=1 MAKER_RATE=0 TAKER_RATE=0.07" in text
    assert "liquidity_incentives=1" in text
    assert "KXETH15M: fee_type='quadratic_with_maker_fees' multiplier=1 MAKER_RATE=0.0175" in text
    assert "KXBTCD: fee_type=None multiplier=None MAKER_RATE=UNKNOWN" in text


def test_url_and_json():
    assert fp.url(BASE, "/x", a=True, b=None, c=3) == BASE + "/x?a=true&c=3"
    assert json.dumps(fp.to_jsonable(run()))  # serializable


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
