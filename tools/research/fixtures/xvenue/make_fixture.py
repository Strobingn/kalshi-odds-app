#!/usr/bin/env python3
"""Writes the synthetic cross-venue fixtures used by tools/research/test_xvenue.py.

All names, prices and rule texts are invented (shapes mimic the Kalshi trade-api v2
events listing / orderbook and the Polymarket gamma /markets + CLOB /book responses).
Cases: Fed 25bps (match, depth-walk gap), Fed 50bps vs 50+bps (threshold wording),
NFL game with named outcomes (match) and the same teams on another date (reject),
Senate race with an absent candidate (reject), CPI above vs below (reject below),
Bitcoin $120k vs 120,000 (match) and another date (reject), a multivariate parlay
(skipped), a 3-outcome and an order-book-disabled Polymarket market (skipped).
"""
import json
from pathlib import Path

D = Path(__file__).resolve().parent


def km(t, title, sub, exp, close, rules1, rules2="", **kw):
    m = dict(ticker=t, status="active", title=title, yes_sub_title=sub, no_sub_title=sub,
             expected_expiration_time=exp, close_time=close, rules_primary=rules1, rules_secondary=rules2)
    m.update(kw)
    return m


EVENTS = {"events": [
    dict(event_ticker="KXFEDDECISION-26OCT", series_ticker="KXFEDDECISION", title="Fed decision in October 2026?",
         category="Economics", mutually_exclusive=True, markets=[
             km("KXFEDDECISION-26OCT-C25", "Will the Fed cut rates by 25bps at the October 2026 meeting?", "Cut 25bps",
                "2026-10-28T18:00:00Z", "2026-10-28T17:55:00Z",
                "If the Federal Reserve cuts the upper bound of the federal funds target range by exactly 25 bps at its "
                "meeting concluding Oct 28, 2026, then the market resolves to Yes.",
                "Source: Federal Reserve FOMC statement. If the meeting is postponed beyond Nov 15, 2026, the market "
                "resolves based on the next scheduled meeting.", yes_bid_dollars="0.5800", yes_ask_dollars="0.6000"),
             km("KXFEDDECISION-26OCT-C50", "Will the Fed cut rates by 50bps at the October 2026 meeting?", "Cut 50bps",
                "2026-10-28T18:00:00Z", "2026-10-28T17:55:00Z",
                "If the Federal Reserve cuts by exactly 50 bps at its meeting concluding Oct 28, 2026, then the market "
                "resolves to Yes.", yes_bid_dollars="0.1000", yes_ask_dollars="0.1200"),
             km("KXFEDDECISION-26OCT-H0", "Will the Fed hold rates at the October 2026 meeting?", "Hold",
                "2026-10-28T18:00:00Z", "2026-10-28T17:55:00Z",
                "If the Federal Reserve leaves the target range unchanged at its meeting concluding Oct 28, 2026, then "
                "the market resolves to Yes.", yes_bid_dollars="0.2800", yes_ask_dollars="0.3000")]),
    dict(event_ticker="KXNFLGAME-26OCT12KCBUF", series_ticker="KXNFLGAME", title="Kansas City at Buffalo",
         category="Sports", mutually_exclusive=True, markets=[
             km("KXNFLGAME-26OCT12KCBUF-KC", "Kansas City at Buffalo Winner?", "Kansas City",
                "2026-10-13T03:00:00Z", "2026-10-27T00:00:00Z",
                "If Kansas City wins the Kansas City at Buffalo professional football game originally scheduled for "
                "Oct 12, 2026, then the market resolves to Yes.",
                "The outcome includes overtime. If the game ends in a tie, the market resolves to the last traded "
                "price. If the game is postponed and not played within two weeks, the market resolves to No.",
                yes_bid_dollars="0.5300", yes_ask_dollars="0.5500"),
             km("KXNFLGAME-26OCT12KCBUF-BUF", "Kansas City at Buffalo Winner?", "Buffalo",
                "2026-10-13T03:00:00Z", "2026-10-27T00:00:00Z",
                "If Buffalo wins the Kansas City at Buffalo professional football game originally scheduled for "
                "Oct 12, 2026, then the market resolves to Yes.",
                "The outcome includes overtime. If the game ends in a tie, the market resolves to the last traded price.",
                yes_bid_dollars="0.4500", yes_ask_dollars="0.4700")]),
    dict(event_ticker="KXSENATEOH-26", series_ticker="KXSENATEOH", title="Who will win the Ohio Senate race in 2026?",
         category="Elections", mutually_exclusive=True, markets=[
             km("KXSENATEOH-26-JDOE", "Will Jane Doe win the Ohio Senate race in 2026?", "Jane Doe",
                "2026-11-04T15:00:00Z", "2026-11-03T23:00:00Z",
                "If Jane Doe is certified as the winner of the 2026 Ohio Senate election, then the market resolves to "
                "Yes. Source: Associated Press call, confirmed by certification.",
                yes_bid_dollars="0.6000", yes_ask_dollars="0.6200"),
             km("KXSENATEOH-26-JROE", "Will John Roe win the Ohio Senate race in 2026?", "John Roe",
                "2026-11-04T15:00:00Z", "2026-11-03T23:00:00Z",
                "If John Roe is certified as the winner of the 2026 Ohio Senate election, then the market resolves to "
                "Yes.", yes_bid_dollars="0.3700", yes_ask_dollars="0.3900")]),
    dict(event_ticker="KXCPIYOY-26SEP", series_ticker="KXCPIYOY", title="CPI year-over-year in September 2026",
         category="Economics", mutually_exclusive=False, markets=[
             km("KXCPIYOY-26SEP-T3.0", "Will CPI be above 3.0% in September 2026?", "Above 3.0%",
                "2026-10-14T12:30:00Z", "2026-10-14T12:25:00Z",
                "If the year-over-year change in CPI-U for September 2026, as first released by the Bureau of Labor "
                "Statistics, is above 3.0%, then the market resolves to Yes.",
                strike_type="greater", floor_strike=3.0, yes_bid_dollars="0.6200", yes_ask_dollars="0.6400"),
             km("KXCPIYOY-26SEP-T3.5", "Will CPI be above 3.5% in September 2026?", "Above 3.5%",
                "2026-10-14T12:30:00Z", "2026-10-14T12:25:00Z",
                "If the year-over-year change in CPI-U for September 2026 is above 3.5%, then the market resolves to Yes.",
                strike_type="greater", floor_strike=3.5, yes_bid_dollars="0.1000", yes_ask_dollars="0.1200")]),
    dict(event_ticker="KXBTCD-26OCT31", series_ticker="KXBTCD", title="Bitcoin price on Oct 31, 2026?",
         category="Crypto", mutually_exclusive=False, markets=[
             km("KXBTCD-26OCT31-T120000", "Will Bitcoin be above $120,000 on Oct 31, 2026?", "$120,000 or above",
                "2026-10-31T21:00:00Z", "2026-10-31T21:00:00Z",
                "If the CF Benchmarks Bitcoin Real Time Index (BRTI) 60-second average before 5 PM ET on Oct 31, 2026 "
                "is above $120,000, then the market resolves to Yes.",
                strike_type="greater", floor_strike=120000, yes_bid_dollars="0.2800", yes_ask_dollars="0.3000"),
             km("KXBTCD-26OCT31-T130000", "Will Bitcoin be above $130,000 on Oct 31, 2026?", "$130,000 or above",
                "2026-10-31T21:00:00Z", "2026-10-31T21:00:00Z",
                "If BRTI is above $130,000, then the market resolves to Yes.",
                strike_type="greater", floor_strike=130000, yes_bid_dollars="0.0800", yes_ask_dollars="0.1000")]),
    dict(event_ticker="KXMVESPORTS-PARLAY1", series_ticker="KXMVESPORTS", title="Chiefs and Bills parlay",
         category="Sports", mutually_exclusive=False, markets=[
             km("KXMVESPORTS-PARLAY1-A", "Kansas City and Buffalo", "yes", "2026-10-13T03:00:00Z",
                "2026-10-13T03:00:00Z", "parlay")]),
]}


def bk(yes, no):
    return {"orderbook_fp": {"yes_dollars": [[f"{p:.4f}", f"{q}.00"] for p, q in yes],
                             "no_dollars": [[f"{p:.4f}", f"{q}.00"] for p, q in no]}}


KBOOKS = {
    "KXFEDDECISION-26OCT-C25": bk([(0.58, 150)], [(0.40, 100), (0.38, 200)]),
    "KXFEDDECISION-26OCT-C50": bk([(0.10, 100)], [(0.88, 100)]),
    "KXFEDDECISION-26OCT-H0": bk([(0.28, 100)], [(0.70, 100)]),
    "KXNFLGAME-26OCT12KCBUF-KC": bk([(0.53, 40)], [(0.45, 40)]),
    "KXNFLGAME-26OCT12KCBUF-BUF": bk([(0.45, 30)], [(0.53, 30)]),
    "KXSENATEOH-26-JDOE": bk([(0.60, 100)], [(0.38, 100)]),
    "KXSENATEOH-26-JROE": bk([(0.37, 100)], [(0.61, 100)]),
    "KXCPIYOY-26SEP-T3.0": bk([(0.62, 70)], [(0.36, 70)]),
    "KXCPIYOY-26SEP-T3.5": bk([(0.10, 70)], [(0.88, 70)]),
    "KXBTCD-26OCT31-T120000": bk([(0.28, 500)], [(0.70, 500)]),
    "KXBTCD-26OCT31-T130000": bk([(0.08, 500)], [(0.90, 500)]),
}


def pm(i, q, end, toks, cat="", desc="", outcomes=("Yes", "No"), gi="", bb=None, ba=None, src="", **kw):
    d = dict(id=str(i), question=q, slug=f"fixture-{i}", endDate=end, category=cat, description=desc,
             outcomes=json.dumps(list(outcomes)), clobTokenIds=json.dumps(list(toks)), active=True, closed=False,
             enableOrderBook=True, acceptingOrders=True, groupItemTitle=gi, resolutionSource=src,
             bestBid=bb, bestAsk=ba, events=[{"id": f"e{i}", "title": q}])
    d.update(kw)
    return d


PMARKETS = [
    pm(101, "Fed decreases interest rates by 25 bps after October 2026 meeting?", "2026-10-28T00:00:00Z",
       ("fed25-yes", "fed25-no"), "",
       "This market will resolve to \"Yes\" if the upper bound of the target federal funds rate is decreased by 25 bps "
       "at the October 2026 FOMC meeting. The resolution source is the FOMC statement. If no statement is released by "
       "November 15, 2026, this market resolves to \"No\".", gi="25 bps decrease", bb=0.64, ba=0.66),
    pm(102, "Fed decreases interest rates by 50+ bps after October 2026 meeting?", "2026-10-28T00:00:00Z",
       ("fed50-yes", "fed50-no"), "",
       "Resolves Yes if rates are decreased by 50 or more bps at the October 2026 FOMC meeting.",
       gi="50+ bps decrease", bb=0.11, ba=0.13),
    pm(201, "Chiefs vs. Bills", "2026-10-12T20:25:00Z", ("chiefs-1012", "bills-1012"), "Sports",
       "In the upcoming NFL game scheduled for October 12, 2026, if the Kansas City Chiefs win, this market resolves "
       "to \"Chiefs\". If the game is postponed past October 19, 2026 or ends in a tie, this market resolves 50-50.",
       outcomes=("Chiefs", "Bills"), bb=0.57, ba=0.58),
    pm(202, "Chiefs vs. Bills", "2026-12-20T18:00:00Z", ("chiefs-1220", "bills-1220"), "Sports",
       "Rematch scheduled for December 20, 2026.", outcomes=("Chiefs", "Bills"), bb=0.50, ba=0.52),
    pm(301, "Will Jane Doe win the 2026 Ohio Senate election?", "2026-11-03T12:00:00Z", ("jdoe-yes", "jdoe-no"),
       "Politics", "This market will resolve to \"Yes\" if Jane Doe wins the 2026 US Senate election in Ohio, per "
       "Associated Press, Fox News and NBC calls. Otherwise \"No\".", gi="Jane Doe", bb=0.62, ba=0.63),
    pm(401, "Will September 2026 CPI be above 3.0%?", "2026-10-14T12:00:00Z", ("cpi30-yes", "cpi30-no"), "",
       "Resolves Yes if the annual CPI inflation for September 2026 published by the Bureau of Labor Statistics "
       "(https://www.bls.gov/cpi/) is above 3.0%.", bb=0.53, ba=0.55),
    pm(402, "Will September 2026 CPI be below 3.0%?", "2026-10-14T12:00:00Z", ("cpi30b-yes", "cpi30b-no"), "",
       "Resolves Yes if annual CPI for September 2026 is below 3.0%.", bb=0.40, ba=0.42),
    pm(501, "Will Bitcoin be above $120k on October 31?", "2026-10-31T16:00:00Z", ("btc120-yes", "btc120-no"),
       "Crypto", "Resolves Yes if the Binance BTC/USDT 1 minute candle for 12:00 ET on October 31 has a final close "
       "above $120,000.", bb=0.29, ba=0.31),
    pm(502, "Will Bitcoin be above $120k on November 30?", "2026-11-30T17:00:00Z", ("btc120n-yes", "btc120n-no"),
       "Crypto", "Binance BTC/USDT 12:00 ET November 30 close above $120,000.", bb=0.40, ba=0.42),
    pm(601, "Who will win the Ohio Senate election?", "2026-11-03T12:00:00Z", ("x1", "x2", "x3"), "Politics",
       outcomes=("Doe", "Roe", "Other")),
    pm(701, "Will Jane Doe win the 2026 Ohio Senate election?", "2026-11-03T12:00:00Z", ("old-yes", "old-no"),
       "Politics", enableOrderBook=False),
]


def pb(asks, bids=()):
    return {"asks": [{"price": f"{p}", "size": f"{s}"} for p, s in sorted(asks, reverse=True)],
            "bids": [{"price": f"{p}", "size": f"{s}"} for p, s in bids]}


PBOOKS = {
    "fed25-yes": pb([(0.66, 80)], [(0.64, 80)]), "fed25-no": pb([(0.35, 50), (0.37, 500)], [(0.34, 50)]),
    "fed50-yes": pb([(0.13, 100)]), "fed50-no": pb([(0.89, 100)]),
    "chiefs-1012": pb([(0.58, 100)]), "bills-1012": pb([(0.40, 60)]),
    "chiefs-1220": pb([(0.52, 100)]), "bills-1220": pb([(0.50, 100)]),
    "jdoe-yes": pb([(0.63, 100)]), "jdoe-no": pb([(0.38, 100)]),
    "cpi30-yes": pb([(0.55, 40)]), "cpi30-no": pb([(0.47, 40)]),
    "cpi30b-yes": pb([(0.42, 40)]), "cpi30b-no": pb([(0.60, 40)]),
    "btc120-yes": pb([(0.31, 300)]), "btc120-no": pb([(0.71, 300)]),
}

if __name__ == "__main__":
    for name, obj in (("kalshi_events", EVENTS), ("kalshi_books", KBOOKS), ("poly_markets", PMARKETS),
                      ("poly_books", PBOOKS)):
        (D / f"{name}.json").write_text(json.dumps(obj, indent=1) + "\n")
    print("wrote", D)
