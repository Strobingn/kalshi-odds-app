#!/usr/bin/env python3
"""Regenerate the hand-built weather fixtures (synthetic, shaped like the real APIs).

Scan scenario: now = 2025-10-05T22:30:00Z (NYC 18:30 EDT = 17:30 LST; Chicago 16:30 LST).
Study scenario: KXHIGHNY settled 2025-09-25 .. 2025-10-04, 4 buckets/day, candles at
the EVE/MORNING snapshot hours, Open-Meteo previous-runs hourly temps.
Run: python3 tools/research/fixtures/weather/make_fixture.py
"""
from __future__ import annotations

import json
import random
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

D = Path(__file__).resolve().parent


def w(rel: str, obj) -> None:
    p = D / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, indent=1, sort_keys=True) + "\n")


NY_SRC = [{"name": "National Weather Service",
           "url": "https://forecast.weather.gov/product.php?site=OKX&product=CLI&issuedby=NYC"}]
NY_RULES = ("If the highest temperature recorded in Central Park, New York for {d}, as reported by the "
            "National Weather Service's Climatological Report (Daily), is {b}, then the market resolves to Yes.")
CHI_RULES = ("If the highest temperature recorded at Chicago Midway, IL for {d}, as reported by the National "
             "Weather Service's Climatological Report (Daily), is {b}, then the market resolves to Yes.")


def mkt(ticker, event, sub, st, floor=None, cap=None, yb=None, ya=None, rules="", **kw):
    m = {"ticker": ticker, "event_ticker": event, "status": "active", "yes_sub_title": sub,
         "strike_type": st, "rules_primary": rules}
    if floor is not None:
        m["floor_strike"] = floor
    if cap is not None:
        m["cap_strike"] = cap
    m["yes_bid_dollars"] = f"{yb:.4f}" if yb is not None else "0.0000"
    m["yes_ask_dollars"] = f"{ya:.4f}" if ya is not None else "1.0000"
    m.update(kw)
    return m


def obs(ts, c=None, raw="", qc="V", unit="wmoUnit:degC"):
    return {"properties": {"timestamp": ts, "rawMessage": raw,
                           "temperature": {"value": c, "unitCode": unit, "qualityControl": qc}}}


def scan():
    w("series_list.json", {"series": [
        {"ticker": "KXHIGHNY", "title": "Highest temperature in NYC today?", "category": "Climate and Weather",
         "settlement_sources": NY_SRC, "fee_type": "quadratic", "fee_multiplier": 1},
        {"ticker": "KXHIGHCHI", "title": "Highest temperature in Chicago today?", "category": "Climate and Weather",
         "settlement_sources": [{"name": "National Weather Service", "url": "https://www.weather.gov/lot/"}]},
        {"ticker": "KXHIGHDEN", "title": "Highest temperature in Denver today?", "category": "Climate and Weather",
         "settlement_sources": []},
        {"ticker": "KXLOWNY", "title": "Lowest temperature in NYC today?", "category": "Climate and Weather"},
        {"ticker": "KXRAINNYC", "title": "Rain in NYC this month?", "category": "Climate and Weather"},
    ]})
    ev = "KXHIGHNY-25OCT05"
    r = lambda b: NY_RULES.format(d="Oct 5, 2025", b=b)  # noqa: E731
    ny = [
        mkt(f"{ev}-T67", ev, "66° or below", "less", cap=67, yb=0.01, ya=0.02, rules=r("below 67°")),
        mkt(f"{ev}-B67.5", ev, "67° to 68°", "between", 67, 68, yb=0.04, ya=0.06, rules=r("67-68°")),
        mkt(f"{ev}-B69.5", ev, "69° to 70°", "between", 69, 70, yb=None, ya=0.03, rules=r("69-70°")),
        mkt(f"{ev}-B71.5", ev, "71° to 72°", "between", 71, 72, yb=0.55, ya=0.60, rules=r("71-72°")),
        mkt(f"{ev}-B73.5", ev, "73° to 74°", "between", 73, 74, yb=0.20, ya=0.25, rules=r("73-74°")),
        mkt(f"{ev}-T74", ev, "75° or above", "greater", floor=74, yb=0.05, ya=0.08, rules=r("above 74°")),
    ]
    ev2 = "KXHIGHNY-25OCT06"
    ny2 = [mkt(f"{ev2}-B70.5", ev2, "70° to 71°", "between", 70, 71, yb=0.3, ya=0.35)]
    w("events/KXHIGHNY.json", {"events": [{"event_ticker": ev, "markets": ny},
                                          {"event_ticker": ev2, "markets": ny2}]})
    ce = "KXHIGHCHI-25OCT05"
    rc = lambda b: CHI_RULES.format(d="Oct 5, 2025", b=b)  # noqa: E731
    chi = [
        mkt(f"{ce}-B76.5", ce, "76° to 77°", "between", 70, 71, yb=0.01, ya=0.03, rules=rc("76-77°")),  # conflict
        mkt(f"{ce}-B78.5", ce, "78° to 79°", "between", 78, 79, yb=0.10, ya=0.14, rules=rc("78-79°")),
        mkt(f"{ce}-T79", ce, "80° or above", "greater", floor=79, yb=0.90, ya=0.93, rules=rc("above 79°")),
    ]
    w("events/KXHIGHCHI.json", {"events": [{"event_ticker": ce, "markets": chi}]})
    w("events/KXHIGHDEN.json", {"events": [{"event_ticker": "KXHIGHDEN-25OCT05", "markets": [
        mkt("KXHIGHDEN-25OCT05-B60.5", "KXHIGHDEN-25OCT05", "60° to 61°", "between", 60, 61, 0.2, 0.3,
            rules="If the highest temperature in Denver for Oct 5, 2025 is 60-61°, Yes.")]}]})
    w("orderbooks.json", {f"{ev}-B67.5": {"orderbook_fp": {
        "yes_dollars": [["0.0200", "100.00"], ["0.0400", "30.00"]], "no_dollars": [["0.9400", "50.00"]]}}})
    w("stations/KNYC.json", {"properties": {"timeZone": "America/New_York", "name": "New York City, Central Park"},
                             "geometry": {"type": "Point", "coordinates": [-73.96925, 40.77898]}})
    w("observations/KNYC.json", {"features": [
        obs("2025-10-05T04:51:00+00:00", 30.0, "METAR KNYC 050451Z AUTO 00000KT 10SM CLR 30/10 A3000 RMK AO2 T03000100"),
        obs("2025-10-05T10:51:00+00:00", 16.1, "METAR KNYC 051051Z 00000KT 10SM CLR 16/10 A3000 RMK AO2 T01610100"),
        obs("2025-10-05T14:51:00+00:00", 20.0),
        obs("2025-10-05T17:51:00+00:00", 21.0, "METAR KNYC 051751Z 00000KT 10SM CLR 21/10 A3000 RMK AO2 10211 20150 T02100100"),
        obs("2025-10-05T18:51:00+00:00", 21.7, "METAR KNYC 051851Z 00000KT 10SM CLR 22/10 A3000 RMK AO2 T02170100"),
        obs("2025-10-05T19:51:00+00:00", 22.2, "METAR KNYC 051951Z 00000KT 10SM CLR 22/10 A3000 RMK AO2 T02220100"),
        obs("2025-10-05T20:30:00+00:00", 35.0, "", qc="X"),
        obs("2025-10-05T21:51:00+00:00", 20.0, "METAR KNYC 052151Z 00000KT 10SM CLR 20/10 A3000 RMK AO2 T02000100"),
    ]})
    w("observations/KMDW.json", {"features": [
        obs("2025-10-05T05:53:00+00:00", 20.0, "METAR KMDW 050553Z 20/10 RMK AO2 T02000100"),
        obs("2025-10-05T20:53:00+00:00", 27.8, "METAR KMDW 052053Z 28/10 RMK AO2 T02780100"),
        obs("2025-10-05T21:53:00+00:00", 27.2, "METAR KMDW 052153Z 27/10 RMK AO2 T02720100"),
    ]})
    w("cli/NYC.json", [
        {"issuanceTime": "2025-10-05T21:30:00+00:00", "productText": CLI_TEXT.format(
            when="400 PM EDT SUN OCT 5 2025", day="OCTOBER 5 2025", valid="VALID TODAY AS OF 0400 PM LOCAL TIME.",
            hdr="TODAY", mx="71")},
        {"issuanceTime": "2025-10-05T06:30:00+00:00", "productText": CLI_TEXT.format(
            when="130 AM EDT SUN OCT 5 2025", day="OCTOBER 4 2025", valid="", hdr="YESTERDAY", mx="75")},
    ])


CLI_TEXT = """
CDUS41 KOKX 052030
CLINYC

CLIMATE REPORT
NATIONAL WEATHER SERVICE NEW YORK, NY
{when}

...................................

...THE CENTRAL PARK NY CLIMATE SUMMARY FOR {day}...
{valid}
CLIMATE NORMAL PERIOD 1991 TO 2020
CLIMATE RECORD PERIOD 1869 TO 2025


WEATHER ITEM   OBSERVED TIME   RECORD YEAR NORMAL DEPARTURE LAST
                VALUE   (LST)  VALUE       VALUE  FROM      YEAR
                                                  NORMAL
...................................................................
TEMPERATURE (F)
 {hdr}
  MAXIMUM         {mx}    2:51 PM  88    1941  70      1       75
  MINIMUM         55    6:12 AM  38    1888  56     -1       60
  AVERAGE         63                          63      0       68
"""


def study():
    rng = random.Random(3)
    tz = ZoneInfo("America/New_York")
    markets, om_times, d1, d2 = [], [], [], []
    start = date(2025, 9, 25)
    for k in range(10):
        d = start + timedelta(days=k)
        ev = f"KXHIGHNY-{d.strftime('%y%b%d').upper()}"
        true = 68 + rng.randint(-4, 4)
        fc = true + rng.choice((-2, -1, 0, 1, 2)) - 1.0  # hourly forecast runs ~1F cool
        base = 2 * round((fc - 1) / 2) + 1  # odd lower edge
        buckets = [(None, base - 3), (base - 2, base - 1), (base, base + 1), (base + 2, None)]
        for lo, hi in buckets:
            if lo is None:
                sub, st, fl, cp, tk = f"{hi}° or below", "less", None, hi + 1, f"{ev}-T{hi + 1}"
            elif hi is None:
                sub, st, fl, cp, tk = f"{lo}° or above", "greater", lo - 1, None, f"{ev}-T{lo - 1}"
            else:
                sub, st, fl, cp, tk = f"{lo}° to {hi}°", "between", lo, hi, f"{ev}-B{lo + 0.5}"
            yes = (lo is None or true >= lo) and (hi is None or true <= hi)
            m = mkt(tk, ev, sub, st, fl, cp, rules=NY_RULES.format(d=d.isoformat(), b=sub))
            m.update(status="finalized", result="yes" if yes else "no", expiration_value=str(true))
            markets.append(m)
            # quotes: market prob from a rough normal around the forecast (longshots a bit rich)
            import math
            ph = lambda x: 0.5 * (1 + math.erf(x / (2.5 * math.sqrt(2))))  # noqa: E731
            p = (1 if hi is None else ph(hi + 0.5 - fc - 1)) - (0 if lo is None else ph(lo - 0.5 - fc - 1))
            p = min(0.95, max(0.06, p + 0.03))
            cs = []
            for off, hr in ((-1, 18), (0, 10)):
                t = datetime.combine(d + timedelta(days=off), datetime.min.time()).replace(hour=hr, tzinfo=tz)
                end = int(t.timestamp()) - 1800
                bid = round(max(0.01, p - 0.02), 2)
                ask = round(min(0.99, p + 0.02), 2)
                cs.append({"end_period_ts": end, "yes_bid": {"close_dollars": f"{bid:.4f}"},
                           "yes_ask": {"close_dollars": f"{ask:.4f}"}, "volume": 10})
            w(f"candles/{tk}.json", {"candlesticks": cs})
        # Open-Meteo hourly (GMT) for climate day: 05Z..04Z next
        for h in range(24):
            t = datetime(d.year, d.month, d.day, 5, tzinfo=timezone.utc) + timedelta(hours=h)
            om_times.append(t.strftime("%Y-%m-%dT%H:%M"))
            shape = fc - 12 + 12 * max(0.0, 1 - abs(h - 14) / 10)
            d1.append(round(shape, 1))
            d2.append(round(shape + rng.choice((-1, 0, 1)), 1))
    w("settled/KXHIGHNY.json", {"markets": markets})
    w("openmeteo/index.json", {f"{40.77898:.4f},{-73.96925:.4f}": "KNYC.json"})
    w("openmeteo/KNYC.json", {"hourly": {"time": om_times, "temperature_2m_previous_day1": d1,
                                         "temperature_2m_previous_day2": d2}})
    w("cutoff.json", {"market_settled_ts": "2025-06-01T00:00:00Z"})


if __name__ == "__main__":
    scan()
    study()
    print("fixtures written to", D)
