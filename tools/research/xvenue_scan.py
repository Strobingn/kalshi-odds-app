#!/usr/bin/env python3
"""Cross-venue scan: the same real-world question priced differently on Kalshi and Polymarket.

READ-ONLY research tool. It never places orders, never authenticates, and never
calls anything "risk-free": every gap it prints is labelled VERIFY RULES, because
buying YES on one venue and NO on the other only locks in the gap if both rule
sets resolve identically (same source, same date/deadline, same treatment of
ties, overtime, postponement, cancellation/void, and strike boundaries).

What it does (all categories: sports, politics, economics, weather, crypto, ...):
  1. Lists open Kalshi events with nested markets
       GET https://api.elections.kalshi.com/trade-api/v2/events?status=open&with_nested_markets=true
     (cursor pagination, rate-limited HttpClient from arb_scan.py), and active
     Polymarket markets
       GET https://gamma-api.polymarket.com/markets?active=true&closed=false&limit=..&offset=..
  2. Matches questions across venues. Titles are normalised (lowercase, punctuation
     and stopwords stripped, light stemming, synonyms such as cut/decrease and
     sports nicknames -> city), then candidate pairs (sharing a rare token) are
     scored with IDF-weighted Jaccard. HARD constraints, any failure rejects:
       - resolution/end dates within --max-date-diff-days (default 2)
       - explicit calendar dates in the titles equal, months equal, years overlap
       - numeric thresholds equal (120k == 120,000; 3% == 3.0%)
       - comparator (above/below) and negation equal
       - category bucket equal (when both are known)
       - outcome labels (Kalshi yes_sub_title / Polymarket groupItemTitle or
         outcome name) refer to the same entity, and the two sides do not BOTH
         carry proper nouns the other lacks (different teams / candidates)
       - mutual best match, and ambiguous pairs (one Polymarket question matching
         several outcomes of one Kalshi event) dropped
     Only pairs scoring >= --min-score survive ("high-confidence"). Both full
     questions and both resolution texts are printed side by side for a human.
  3. For each match fetches both order books (Kalshi /markets/{t}/orderbook,
     Polymarket https://clob.polymarket.com/book?token_id=..) and costs
       combo A: Kalshi YES + Polymarket NO      combo B: Kalshi NO + Polymarket YES
     (each pays exactly $1 per unit IF the rules match) at top of book and by a
     joint depth walk, after Kalshi's exact taker fee (pipeline.kalshi_total_cost,
     one cent-rounded debit per price level, conservative) and Polymarket's taker fee.

Polymarket fee (looked up 2026-10-05 via web search; docs.polymarket.com/trading/fees
was not directly fetchable from the build sandbox, so treat as approximate):
  taker fee = C * rate * p * (1 - p), makers pay nothing; rate by category:
  crypto 0.07, sports 0.03, finance/politics/tech/mentions 0.04,
  economics/culture/weather/other 0.05, geopolitics 0. A third-party summary of
  the same period lists sports at 0.05 instead, so POLY_FEE_RATES below is a best
  guess (override with --poly-fee-rate) and every row shows the GROSS gap too. A
  market whose gamma record says feesEnabled=false is costed at 0.
  (Polymarket US, the CFTC-regulated exchange, filed Fee = 0.0695*C*p*(1-p) from
  2026-09-17; this tool reads the international gamma/CLOB API, not Polymarket US.)
  Kalshi: 0.07*C*P*(1-P) by default; some series (e.g. index markets) use a lower
  multiplier, so Kalshi fees here can be slightly conservative.

Legal access: the international Polymarket order books read here have been
geo-blocked for US persons since the 2022 CFTC settlement; US residents can only
trade through Polymarket US (the CFTC-registered exchange acquired via QCEX,
rolled out from Dec 2025, iOS-only and fully opened in May 2026), whose markets,
prices and rules may differ from the ones shown here -- check eligibility before
treating any gap as actionable.

Other caveats: top-of-book can move between the two book fetches (seconds apart);
capital is locked until both resolve (different payout dates); Polymarket settles
in USDC on Polygon (bridge/withdrawal costs and delays not included); Polymarket
order books are mirrored YES/NO so "NO ask" is read from the NO token's book.

Python 3 stdlib only.

Usage:
  python3 tools/research/xvenue_scan.py                              # one snapshot
  python3 tools/research/xvenue_scan.py --repeat 6 --interval 120
  python3 tools/research/xvenue_scan.py --fixture tools/research/fixtures/xvenue   # offline
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
import unicodedata
import urllib.parse
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import ROUND_CEILING, Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import arb_scan as A  # noqa: E402  (also puts tools/backtest on sys.path)
from arb_scan import FEE_RATE, Book, HttpClient, _f, _jsonish, _poly_asks, fills_cost, parse_orderbook, top_quote  # noqa: E402

GAMMA = A.GAMMA
LABEL = "VERIFY RULES"

POLY_FEE_RATES = {
    "crypto": 0.07, "sports": 0.03, "finance": 0.04, "politics": 0.04, "tech": 0.04, "mentions": 0.04,
    "economics": 0.05, "culture": 0.05, "weather": 0.05, "other": 0.05, "geopolitics": 0.0,
}


# --------------------------------------------------------------------------- clients


class XClient(HttpClient):
    def poly_markets_page(self, offset: int, limit: int) -> list:
        q = {"active": "true", "closed": "false", "limit": str(limit), "offset": str(offset)}
        body = self.get(f"{GAMMA}/markets?{urllib.parse.urlencode(q)}", retries=3)
        if isinstance(body, dict):  # tolerate {"data": [...]} shapes
            body = body.get("data") or body.get("markets") or []
        return body if isinstance(body, list) else []


class FixtureClient:
    """Offline: kalshi_events.json, kalshi_books.json {ticker: body}, poly_markets.json [..], poly_books.json {token: body}."""

    def __init__(self, directory: Path):
        d = Path(directory)
        self.events = json.loads((d / "kalshi_events.json").read_text())
        self.kbooks = json.loads((d / "kalshi_books.json").read_text())
        self.pmarkets = json.loads((d / "poly_markets.json").read_text())
        self.pbooks = json.loads((d / "poly_books.json").read_text())
        self.calls = 0

    def events_page(self, cursor):
        self.calls += 1
        return {"events": self.events.get("events", []), "cursor": None}

    def orderbook(self, ticker):
        self.calls += 1
        return self.kbooks.get(ticker)

    def poly_markets_page(self, offset, limit):
        self.calls += 1
        return self.pmarkets[offset:offset + limit]

    def poly_book(self, token):
        self.calls += 1
        return self.pbooks.get(token)


# --------------------------------------------------------------------------- text normalisation

STOP = set("""a an the will be is are was were do does did of to in on at by for from and or with this that it its as
than then if who what which when where whose how many much there their any into per vs v versus yes no market
question resolve resolves resolved price winner happen get has have most next""".split())
MONTHS = {m: i + 1 for i, m in enumerate(
    "january february march april may june july august september october november december".split())}
MONTHS.update({k[:3]: v for k, v in list(MONTHS.items())})
MONTHS["sept"] = 9
WEEKDAYS = set("monday tuesday wednesday thursday friday saturday sunday".split())
SYN = {
    "decrease": "cut", "decreases": "cut", "cuts": "cut", "lowers": "cut", "reduce": "cut", "reduces": "cut",
    "increase": "hike", "increases": "hike", "raise": "hike", "raises": "hike", "hikes": "hike",
    "bp": "bps", "bip": "bps", "basis": "bps", "federal": "fed", "reserve": "fed", "fomc": "fed",
    "btc": "bitcoin", "eth": "ethereum", "sol": "solana", "xrp": "ripple",
    "presidential": "president", "race": "election", "elected": "election", "wins": "win", "won": "win",
    "beat": "win", "beats": "win", "defeat": "win", "defeats": "win", "championship": "champion",
    "u.s.": "us", "usa": "us", "america": "us", "american": "us", "cpi": "cpi", "inflation": "cpi",
    "temp": "temperature", "high": "high", "highest": "high", "unemployment": "unemployment",
    "jobless": "unemployment", "nominee": "nomination", "nominated": "nomination", "points": "point",
}
GT_PAT = re.compile(r"\b(above|over|more than|greater than|at least|exceeds?|higher than|or more|or higher|or above)\b|>|≥|\+(?=\s|$)")
LT_PAT = re.compile(r"\b(below|under|less than|fewer than|lower than|at most|or less|or lower|or below)\b|<|≤")
NEG_PAT = re.compile(r"\b(not|n't|never|without)\b")

# Major-league nicknames -> city/region tokens (so "Chiefs" can meet Kalshi's "Kansas City").
# Kalshi abbreviates shared cities ("New York J", "Los Angeles C"); the letter stays a token so
# Jets/Giants or Chargers/Rams never collapse onto each other.
_TEAMS = """
nfl: cardinals=arizona falcons=atlanta ravens=baltimore bills=buffalo panthers=carolina bears=chicago bengals=cincinnati
browns=cleveland cowboys=dallas broncos=denver lions=detroit packers=green_bay texans=houston colts=indianapolis
jaguars=jacksonville chiefs=kansas_city raiders=las_vegas chargers=los_angeles_c rams=los_angeles_r dolphins=miami
vikings=minnesota patriots=new_england saints=new_orleans giants=new_york_g jets=new_york_j eagles=philadelphia
steelers=pittsburgh 49ers=san_francisco seahawks=seattle buccaneers=tampa_bay titans=tennessee commanders=washington
nba: hawks=atlanta celtics=boston nets=brooklyn hornets=charlotte bulls=chicago cavaliers=cleveland mavericks=dallas
nuggets=denver pistons=detroit warriors=golden_state rockets=houston pacers=indiana clippers=los_angeles_c
lakers=los_angeles_l grizzlies=memphis heat=miami bucks=milwaukee timberwolves=minnesota pelicans=new_orleans
knicks=new_york thunder=oklahoma_city magic=orlando 76ers=philadelphia suns=phoenix blazers=portland kings=sacramento
spurs=san_antonio raptors=toronto jazz=utah wizards=washington
mlb: diamondbacks=arizona braves=atlanta orioles=baltimore red_sox=boston cubs=chicago_c white_sox=chicago_ws reds=cincinnati
guardians=cleveland rockies=colorado tigers=detroit astros=houston royals=kansas_city angels=los_angeles_a
dodgers=los_angeles_d marlins=miami brewers=milwaukee twins=minnesota mets=new_york_m yankees=new_york_y athletics=oakland
phillies=philadelphia pirates=pittsburgh padres=san_diego mariners=seattle cardinals=st_louis rays=tampa_bay
rangers=texas blue_jays=toronto nationals=washington
"""
TEAM_ALIASES: dict = {}
for _line in _TEAMS.strip().splitlines():
    for _pair in _line.split()[1 if _line.split()[0].endswith(":") else 0:]:
        _nick, _city = _pair.split("=")
        # first league wins for shared nicknames (cardinals): ambiguous ones expand to nothing
        _toks = set(_city.split("_"))
        if _nick in TEAM_ALIASES and TEAM_ALIASES[_nick] != _toks:
            TEAM_ALIASES[_nick] = set()
        else:
            TEAM_ALIASES[_nick] = _toks
TEAM_ALIASES = {k.replace("_", " "): v for k, v in TEAM_ALIASES.items()}

DATE_PAT = re.compile(
    r"\b(" + "|".join(sorted(MONTHS, key=len, reverse=True)) + r")\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s*(\d{4}))?"
    r"|\b(\d{1,2})(?:st|nd|rd|th)?\s+(" + "|".join(sorted(MONTHS, key=len, reverse=True)) + r")\b"
    r"|\b(\d{4})-(\d{2})-(\d{2})\b|\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b", re.I)
NUM_PAT = re.compile(r"(?<![\w.])\$?\s?(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s?(k|m|b|bn|million|billion|thousand|%|percent|bps)?(?![\w])", re.I)


def _ascii(s: str) -> str:
    s = re.sub(r"\s[\u2013\u2014-]+\s", " ; ", s or "")  # " — " separators are not part of a name
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()


def stem(t: str) -> str:
    if len(t) > 4 and t.endswith("ies"):
        return t[:-3] + "y"
    if len(t) > 3 and t.endswith("s") and not t.endswith("ss") and not t.endswith("us"):
        return t[:-1]
    return t


def canon(w: str) -> str:
    w = SYN.get(w, w)
    w = stem(w)
    return SYN.get(w, w)


def expand_aliases(text: str) -> set:
    """City tokens for team nicknames, unless the text already names the team in full
    ("Florida Panthers" must not become Carolina)."""
    out = set()
    raw = _ascii(text)
    low = " " + re.sub(r"[^a-z0-9 ]", " ", raw.lower()) + " "
    for nick, city in TEAM_ALIASES.items():
        city = {canon(c) for c in city}
        if not city or f" {nick} " not in low:
            continue
        full = False
        for m in re.finditer(r"([A-Za-z]+)\s+" + re.escape(nick), raw, re.I):
            prev = m.group(1)
            if (prev[0].isupper() and prev.lower() not in STOP and canon(prev.lower()) not in city
                    and prev.lower() not in TEAM_ALIASES):
                full = True
        if not full:
            out |= city
    return out


@dataclass
class Norm:
    tokens: set
    entities: set           # proper-noun tokens (capitalised in the source text)
    dates: set              # (month, day)
    months: set
    years: set
    numbers: set            # normalised floats (thresholds)
    comparator: str         # "gt" / "lt" / "" / "both"
    negated: bool


def extract_dates(text: str) -> tuple[set, set, str]:
    dates, years = set(), set()

    def repl(m):
        g = m.groups()
        try:
            if g[0]:
                dates.add((MONTHS[g[0].lower()], int(g[1])))
                if g[2]:
                    years.add(int(g[2]))
            elif g[3]:
                dates.add((MONTHS[g[4].lower()], int(g[3])))
            elif g[5]:
                dates.add((int(g[6]), int(g[7])))
                years.add(int(g[5]))
            elif g[8]:
                mo, d = int(g[8]), int(g[9])
                if 1 <= mo <= 12 and 1 <= d <= 31:
                    dates.add((mo, d))
                    if g[10]:
                        y = int(g[10])
                        years.add(y + 2000 if y < 100 else y)
                else:
                    return m.group(0)
        except (KeyError, ValueError, TypeError):
            return m.group(0)
        # keep the month word as a plain token, drop the day number so it is not read as a threshold
        return " " + (g[0] or g[4] or "") + " "
    rest = DATE_PAT.sub(repl, text)
    return dates, years, rest


def extract_numbers(text: str) -> tuple[set, set, str]:
    nums, years = set(), set()

    def repl(m):
        raw, unit = m.group(1).replace(",", ""), (m.group(2) or "").lower()
        v = float(raw)
        if not unit and "." not in raw and "$" not in m.group(0) and 1900 <= v <= 2100:
            years.add(int(v))
            return " "
        mult = {"k": 1e3, "thousand": 1e3, "m": 1e6, "million": 1e6, "b": 1e9, "bn": 1e9, "billion": 1e9}.get(unit, 1.0)
        nums.add(round(v * mult, 6))
        return " bps " if unit == "bps" else " "
    rest = NUM_PAT.sub(repl, text)
    # "2026-27 season": the trailing -27 is part of a season, not a threshold
    return nums, years, rest


def normalize(text: str) -> Norm:
    raw = re.sub(r"year[\s-]+over[\s-]+year", "yoy", _ascii(text), flags=re.I)
    low = raw.lower()
    comp_gt, comp_lt = bool(GT_PAT.search(low)), bool(LT_PAT.search(low))
    comparator = "both" if comp_gt and comp_lt else "gt" if comp_gt else "lt" if comp_lt else ""
    negated = bool(NEG_PAT.search(low))
    dates, years, rest = extract_dates(raw)
    rest = re.sub(r"\b(\d{4})-(\d{2})\b", lambda m: f" {m.group(1)} ", rest)  # 2026-27 season
    nums, y2, rest = extract_numbers(rest)
    years |= y2
    entities = set()
    for w in re.findall(r"[A-Za-z][A-Za-z0-9'&.\-]*", rest):
        if w[0].isupper():
            t = w.lower().strip(".'-").replace("'s", "")
            if t and t not in STOP and t not in MONTHS and t not in WEEKDAYS and len(t) > 1:
                entities.add(canon(t))
    words = re.findall(r"[a-z0-9][a-z0-9'.]*", rest.lower())
    tokens = set()
    months = set()
    for w in words:
        w = w.strip(".'").replace("'s", "")
        if not w or w in STOP or w in WEEKDAYS:
            continue
        if w in MONTHS:
            months.add(MONTHS[w])
            continue
        w = canon(w)
        if len(w) == 1 and not w.isalpha():
            continue
        tokens.add(w)
    for d in dates:
        months.add(d[0])
    tokens |= expand_aliases(rest)  # team nicknames -> city tokens
    tokens -= {"above", "below", "over", "under", "more", "less", "least", "greater", "higher", "lower", "fewer"}
    return Norm(tokens, entities, dates, months, years, nums, comparator, negated)


def label_tokens(label: str) -> set:
    if not label:
        return set()
    n = normalize(label)
    # single capital letters ("New York J") are kept: they disambiguate shared cities
    letters = {w.lower() for w in re.findall(r"\b[A-Z]\b", _ascii(label))}
    return (n.tokens | letters) - {"a"} if letters else n.tokens


def label_expand(label: str) -> set:
    return label_tokens(label) | expand_aliases(label)


# --------------------------------------------------------------------------- categories

KALSHI_CAT = {
    "sports": "sports", "politics": "politics", "elections": "politics", "economics": "economics",
    "financials": "finance", "crypto": "crypto", "climate and weather": "weather", "weather": "weather",
    "entertainment": "culture", "culture": "culture", "science and technology": "tech", "tech": "tech",
    "companies": "finance", "world": "geopolitics", "mentions": "mentions", "health": "other",
    "social": "culture", "transportation": "other",
}
KEYWORDS = [
    ("crypto", r"\b(bitcoin|btc|ethereum|eth|solana|xrp|dogecoin|crypto)\b"),
    ("economics", r"\b(fed|fomc|interest rate|cpi|inflation|gdp|unemployment|jobs report|payrolls|recession|rate cut)\b"),
    ("weather", r"\b(temperature|rain|snow|hurricane|weather|degrees|heat)\b"),
    ("sports", r"\b(nfl|nba|mlb|nhl|ncaa|premier league|champions league|super bowl|world series|stanley cup|grand slam|"
               r"ufc|f1|formula 1|grand prix|open|masters|vs\.?|game \d|match|playoffs?|finals)\b"),
    ("politics", r"\b(election|president|senate|house|governor|mayor|congress|democrat|republican|primary|nominee|"
                 r"parliament|prime minister|trump|vote)\b"),
    ("culture", r"\b(oscar|grammy|emmy|box office|album|movie|billboard|eurovision|award)\b"),
    ("tech", r"\b(openai|gpt|apple|google|ai model|iphone|spacex|launch)\b"),
]


def bucket(explicit: str | None, text: str) -> tuple[str, bool]:
    """(bucket, explicit?)."""
    e = (explicit or "").strip().lower()
    if e:
        if e in KALSHI_CAT:
            return KALSHI_CAT[e], True
        for k in POLY_FEE_RATES:
            if k in e:
                return k, True
        if "pop" in e or "entertain" in e:
            return "culture", True
        if "business" in e or "finance" in e:
            return "finance", True
        if "science" in e or "tech" in e or "ai" == e:
            return "tech", True
    low = (text or "").lower()
    for b, pat in KEYWORDS:
        if re.search(pat, low):
            return b, False
    return "", False


# --------------------------------------------------------------------------- venue records


def _ts(s) -> float | None:
    if not s or not isinstance(s, str):
        return None
    try:
        if len(s) == 10:
            s = s + "T00:00:00Z"
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


@dataclass
class Rec:
    venue: str
    key: str                 # Kalshi ticker / "poly:<market id>[:outcome]"
    question: str
    label: str
    category: str
    cat_explicit: bool
    times: list              # candidate resolution timestamps
    rules: str
    source: str
    norm: Norm = None
    group: str = ""          # Kalshi event ticker / Polymarket market id
    yes_token: str = ""
    no_token: str = ""
    yes_ask: float | None = None   # listing quotes (prefilter only)
    no_ask: float | None = None
    fees_enabled: bool = True
    outcome_note: str = ""
    raw: dict = field(default_factory=dict)


def kalshi_records(events: list) -> list:
    out = []
    for ev in events:
        et = ev.get("event_ticker") or ""
        if "MVE" in et.upper() or "MVE" in (ev.get("series_ticker") or "").upper():
            continue  # multivariate parlays: no single counterpart
        mkts = [m for m in ev.get("markets") or [] if A.market_live(m)]
        multi = len(ev.get("markets") or []) > 1
        for m in mkts:
            label = (m.get("yes_sub_title") or m.get("subtitle") or "") if multi else ""
            parts = []
            for p in (ev.get("title"), m.get("title"), label):
                if p and p not in parts:
                    parts.append(p)
            q = " — ".join(parts)
            times = [t for t in (_ts(m.get(k)) for k in ("expected_expiration_time", "close_time", "expiration_time"))
                     if t] + [t for t in [_ts(ev.get("strike_date"))] if t]
            cat, ex = bucket(ev.get("category"), q)
            yb, ya = top_quote(m)
            out.append(Rec("kalshi", m.get("ticker") or "", q, label, cat, ex, times,
                           "\n".join(x for x in (m.get("rules_primary"), m.get("rules_secondary")) if x),
                           "", None, et, yes_ask=ya, no_ask=round(1 - yb, 4) if yb else None,
                           outcome_note=f"event mutually_exclusive={ev.get('mutually_exclusive')}, {len(ev.get('markets') or [])} market(s)",
                           raw=dict(event_title=ev.get("title"), market_title=m.get("title"),
                                    yes_sub_title=m.get("yes_sub_title"), no_sub_title=m.get("no_sub_title"),
                                    close_time=m.get("close_time"), expected_expiration_time=m.get("expected_expiration_time"))))
    return out


def poly_records(markets: list) -> list:
    out = []
    for pm in markets:
        if not isinstance(pm, dict) or pm.get("closed") is True or pm.get("active") is False:
            continue
        if pm.get("enableOrderBook") is False or pm.get("acceptingOrders") is False:
            continue
        outcomes = [str(o) for o in (_jsonish(pm.get("outcomes")) or [])]
        tokens = [str(t) for t in (_jsonish(pm.get("clobTokenIds")) or [])]
        if len(outcomes) != 2 or len(tokens) != 2:
            continue
        evs = pm.get("events") or []
        ev0 = evs[0] if evs and isinstance(evs[0], dict) else {}
        q = pm.get("question") or ""
        gi = (pm.get("groupItemTitle") or "").strip()
        times = [t for t in (_ts(pm.get(k)) for k in ("endDate", "endDateIso", "gameStartTime")) if t]
        times += [t for t in [_ts(ev0.get("endDate"))] if t]
        cat_src = pm.get("category") or ev0.get("category") or ""
        rules = pm.get("description") or ev0.get("description") or ""
        src = pm.get("resolutionSource") or ev0.get("resolutionSource") or ""
        bb, ba = _f(pm.get("bestBid")), _f(pm.get("bestAsk"))
        fees = pm.get("feesEnabled") is not False
        mid = str(pm.get("id") or pm.get("conditionId") or pm.get("slug"))
        lo = [o.lower() for o in outcomes]
        common = dict(rules=rules, source=src, times=times, group=mid, fees_enabled=fees,
                      raw=dict(slug=pm.get("slug"), event_title=ev0.get("title"), endDate=pm.get("endDate"),
                               outcomes=outcomes, groupItemTitle=gi))
        if lo == ["yes", "no"]:
            text = q if (not gi or gi.lower() in q.lower()) else f"{q} — {gi}"
            cat, ex = bucket(cat_src, text)
            out.append(Rec("poly", f"poly:{mid}", text, gi, cat, ex, yes_token=tokens[0], no_token=tokens[1],
                           yes_ask=ba, no_ask=round(1 - bb, 4) if bb else None, outcome_note="Yes/No", **common))
        else:
            # head-to-head ("Chiefs vs. Bills"): YES on outcome i == NO on the other outcome
            for i in (0, 1):
                text = f"{q} — {outcomes[i]}"
                cat, ex = bucket(cat_src, text)
                ya = ba if i == 0 else (round(1 - bb, 4) if bb else None)
                na = (round(1 - bb, 4) if bb else None) if i == 0 else ba
                out.append(Rec("poly", f"poly:{mid}:{i}", text, outcomes[i], cat, ex,
                               yes_token=tokens[i], no_token=tokens[1 - i], yes_ask=ya, no_ask=na,
                               outcome_note=f"two named outcomes {outcomes}; NO here = '{outcomes[1 - i]}' wins",
                               **common))
    return out


# --------------------------------------------------------------------------- matching


def idf_table(recs: list) -> dict:
    df = Counter()
    for r in recs:
        df.update(r.norm.tokens)
    n = len(recs) or 1
    return {t: math.log((n + 1) / (c + 1)) + 1.0 for t, c in df.items()}, df


def wjaccard(a: set, b: set, idf: dict) -> float:
    u = a | b
    if not u:
        return 0.0
    return sum(idf.get(t, 1.0) for t in a & b) / sum(idf.get(t, 1.0) for t in u)


def date_gap_days(k: Rec, p: Rec) -> float | None:
    if not k.times or not p.times:
        return None
    return min(abs(a - b) for a in k.times for b in p.times) / 86400.0


def quick_reject(k: Rec, p: Rec, max_days: float) -> str | None:
    gap = date_gap_days(k, p)
    if gap is None:
        return "missing end date"
    if gap > max_days:
        return f"end dates {gap:.1f}d apart"
    if k.norm.numbers != p.norm.numbers:
        return f"thresholds differ {sorted(k.norm.numbers)} vs {sorted(p.norm.numbers)}"
    return None


def constraints(k: Rec, p: Rec, max_days: float) -> str | None:
    """None if every hard constraint passes, else the reason (first two words = reason code)."""
    nk, np_ = k.norm, p.norm
    why = quick_reject(k, p, max_days)
    if why:
        return why
    if nk.dates and np_.dates and nk.dates != np_.dates:
        return "different dates in titles"
    if nk.months and np_.months and nk.months != np_.months:
        return "different months in titles"
    if nk.years and np_.years and not (nk.years & np_.years):
        return "different years"
    if nk.comparator != np_.comparator:
        return f"comparator differs ({nk.comparator or 'none'} vs {np_.comparator or 'none'})"
    if nk.negated != np_.negated:
        return "negation differs"
    if k.cat_explicit and p.cat_explicit and k.category != p.category:
        return f"category {k.category} vs {p.category}"
    lk, lp = label_tokens(k.label), label_tokens(p.label)
    if lk and lp:
        if not (lk <= label_expand(p.label) or lp <= label_expand(k.label)):
            return f"outcome labels differ ({k.label!r} vs {p.label!r})"
    elif lk and not lk <= np_.tokens | lp:
        return f"Kalshi outcome {k.label!r} not in Polymarket question"
    elif lp and not lp <= nk.tokens:
        return f"Polymarket outcome {p.label!r} not in Kalshi question"
    extra_k = nk.entities - np_.tokens - np_.entities
    extra_p = np_.entities - nk.tokens - nk.entities
    if extra_k and extra_p:
        return f"different named entities {sorted(extra_k)[:3]} vs {sorted(extra_p)[:3]}"
    return None


def match(krecs: list, precs: list, min_score: float = 0.45, max_days: float = 2.0,
          max_df: int = 400, rare_tokens: int = 5) -> tuple[list, dict]:
    for r in krecs + precs:
        if r.norm is None:
            r.norm = normalize(r.question)
    idf, df = idf_table(krecs + precs)
    index = defaultdict(list)
    for j, p in enumerate(precs):
        for t in p.norm.tokens:
            index[t].append(j)
    stats = Counter()
    cands = defaultdict(list)   # k index -> [(score, j)]
    for i, k in enumerate(krecs):
        ranked = sorted(k.norm.tokens, key=lambda t: -idf.get(t, 0))
        rare = [t for t in ranked if df.get(t, 0) <= max_df][:rare_tokens] or ranked[:1]
        seen = set()
        for t in rare:
            seen.update(index.get(t, ()))
        for j in seen:
            p = precs[j]
            stats["pairs_considered"] += 1
            if quick_reject(k, p, max_days):
                stats["quick-rejected (date/threshold)"] += 1
                continue
            s = wjaccard(k.norm.tokens, p.norm.tokens, idf)
            if s < min_score:
                stats["below min score"] += 1
                continue
            why = constraints(k, p, max_days)
            if why:
                stats["rejected: " + " ".join(why.split()[:2])] += 1
                continue
            cands[i].append((s, j))
    # mutual best + ambiguity
    best_p = {}
    for i, lst in cands.items():
        lst.sort(reverse=True)
        if len(lst) > 1 and lst[0][0] - lst[1][0] < 0.02 and precs[lst[0][1]].group != precs[lst[1][1]].group:
            stats["dropped: ambiguous (two Polymarket questions)"] += 1
            continue
        best_p[i] = lst[0]
    by_p = defaultdict(list)
    for i, (s, j) in best_p.items():
        by_p[j].append((s, i))
    out = []
    for j, lst in by_p.items():
        lst.sort(reverse=True)
        groups = {krecs[i].group for _, i in lst}
        if len(lst) > 1 and len(groups) < len(lst) and not precs[j].label:
            stats["dropped: ambiguous (one Polymarket question, several outcomes of a Kalshi event)"] += 1
            continue
        s, i = lst[0]
        out.append(dict(kalshi=krecs[i], poly=precs[j], score=round(s, 3),
                        date_gap_days=round(date_gap_days(krecs[i], precs[j]), 2)))
    out.sort(key=lambda m: -m["score"])
    stats["matches"] = len(out)
    return out, dict(stats)


# --------------------------------------------------------------------------- rules checklist

SOURCES = ["Associated Press", "AP", "Bureau of Labor Statistics", "BLS", "Federal Reserve", "FOMC", "NOAA",
           "National Weather Service", "NWS", "CF Benchmarks", "Binance", "Coinbase", "Chainlink", "Pyth", "ESPN",
           "NFL", "NBA", "MLB", "NHL", "UFC", "FIFA", "BEA", "Bureau of Economic Analysis", "Census", "Decision Desk",
           "Fox News", "CNN", "NBC", "official", "UMA"]


def rule_flags(text: str) -> dict:
    t = (text or "")
    low = t.lower()
    urls = sorted({u.split("/")[2] for u in re.findall(r"https?://[^\s)\"']+", t) if u.count("/") >= 2})
    return dict(
        deadline=("by/before" if re.search(r"\b(before|no later than|on or before|prior to)\b|\bby (the end of )?"
                                           r"(" + "|".join(MONTHS) + r"|\d|end|year|close)\b", low) else "")
        + (" on/at" if re.search(r"\b(on|at the end of|as of)\b\s+(the\s+)?(\w+\s+\d|\d)", low) else ""),
        ties=bool(re.search(r"\b(tie|ties|tied|draw|push|dead heat)\b", low)),
        overtime=bool(re.search(r"\b(overtime|extra time|extra innings|penalt(y|ies) shoot|regulation)\b", low)),
        cancel=bool(re.search(r"\b(cancel|postpon|void|suspend|abandon|reschedul|delay)", low)),
        fifty_fifty=bool(re.search(r"50[-/ ]50|\b0\.5\b|\$0\.50", low)),
        sources=sorted({s for s in SOURCES if re.search(r"\b" + re.escape(s) + r"\b", t if s.isupper() else low,
                                                       0 if s.isupper() else re.I)} | set(urls)),
    )


def checklist(m: dict) -> dict:
    k, p = m["kalshi"], m["poly"]
    fk, fp = rule_flags(k.rules), rule_flags(p.rules + "\n" + p.source)
    diffs = [n for n in ("ties", "overtime", "cancel", "fifty_fifty") if fk[n] != fp[n]]
    if fk["deadline"] != fp["deadline"]:
        diffs.append("deadline wording")
    if set(fk["sources"]) != set(fp["sources"]):
        diffs.append("sources")
    return dict(kalshi=fk, poly=fp, wording_differs=diffs)


# --------------------------------------------------------------------------- costing


def poly_fee_rate(p: Rec, override: float | None = None) -> float:
    if not p.fees_enabled:
        return 0.0
    if override is not None:
        return override
    return POLY_FEE_RATES.get(p.category or "other", POLY_FEE_RATES["other"])


def poly_level_cost(n: float, price: float, rate: float) -> float:
    """n shares at price + taker fee rate*n*p*(1-p), fee rounded up to 1e-4 USDC."""
    fee = Decimal(repr(rate * n * price * (1 - price))).quantize(Decimal("0.0001"), rounding=ROUND_CEILING)
    return float(Decimal(repr(n * price)) + fee)


def pair_cost(k_asks: list, p_asks: list, krate: float, prate: float) -> dict | None:
    """Top-of-book edge and a joint depth walk for 1 Kalshi contract + 1 Polymarket share per unit (payoff $1)."""
    if not k_asks or not p_asks:
        return None
    kp, kq = k_asks[0]
    pp, pq = p_asks[0]
    kfee = krate * kp * (1 - kp)
    pfee = prate * pp * (1 - pp)
    gross = 1.0 - kp - pp
    res = dict(kalshi_ask=kp, kalshi_qty=kq, poly_ask=pp, poly_qty=round(pq, 2), gross=round(gross, 4),
               kalshi_fee=round(kfee, 4), poly_fee=round(pfee, 4), net=round(gross - kfee - pfee, 4),
               units=0, cost=0.0, profit=0.0)
    # joint walk over whole units (Polymarket sizes floored: conservative)
    kl = [[p, int(q)] for p, q in k_asks]
    pl = [[p, int(math.floor(q + 1e-9))] for p, q in p_asks]
    i = j = 0
    kf, pf = [], []
    while i < len(kl) and j < len(pl):
        if kl[i][1] <= 0:
            i += 1
            continue
        if pl[j][1] <= 0:
            j += 1
            continue
        a, b = kl[i][0], pl[j][0]
        if 1.0 - a - b - krate * a * (1 - a) - prate * b * (1 - b) <= 0:
            break
        n = min(kl[i][1], pl[j][1])
        for fl, px in ((kf, a), (pf, b)):  # merge consecutive fills at one price (one debit per level)
            if fl and fl[-1][0] == px:
                fl[-1] = (px, fl[-1][1] + n)
            else:
                fl.append((px, n))
        kl[i][1] -= n
        pl[j][1] -= n
    units = sum(n for _, n in kf)
    if units:
        cost = fills_cost(kf, krate) + sum(poly_level_cost(n, b, prate) for b, n in pf)
        res.update(units=units, cost=round(cost, 4), profit=round(units - cost, 4),
                   kalshi_fills=kf, poly_fills=pf)
    return res


# --------------------------------------------------------------------------- snapshot


def list_kalshi(client, max_pages: int) -> list:
    return A.list_events(client, max_pages)


def list_poly(client, max_pages: int, limit: int, log) -> list:
    out, offset = [], 0
    for i in range(max_pages):
        try:
            page = client.poly_markets_page(offset, limit)
        except Exception as e:  # noqa: BLE001 - partial listing is still useful
            log(f"gamma page {i} (offset {offset}) failed: {e}")
            break
        if not page:
            break
        out.extend(page)
        offset += len(page)  # the server may cap limit below what we asked for
    return out


def build_matches(client, a, log) -> tuple[list, dict]:
    info = dict(kalshi_status="ok", poly_status="ok")
    try:
        events = list_kalshi(client, a.max_pages)
    except Exception as e:  # noqa: BLE001
        events = []
        info["kalshi_status"] = f"error: {e}"
    try:
        pmk = list_poly(client, a.poly_pages, a.poly_limit, log)
    except Exception as e:  # noqa: BLE001
        pmk = []
        info["poly_status"] = f"error: {e}"
    krecs, precs = kalshi_records(events), poly_records(pmk)
    info.update(kalshi_events=len(events), kalshi_markets=len(krecs), poly_markets=len(pmk), poly_entries=len(precs),
                kalshi_categories=dict(Counter(r.category or "?" for r in krecs).most_common(12)),
                poly_categories=dict(Counter(r.category or "?" for r in precs).most_common(12)))
    if not krecs or not precs:
        return [], info
    t0 = time.time()
    matches, stats = match(krecs, precs, a.min_score, a.max_date_diff_days)
    info.update(match_stats=stats, match_seconds=round(time.time() - t0, 1))
    return matches, info


def price_matches(client, matches: list, a, log) -> list:
    """Fetch books for up to a.max_matches matches (best listing gap first) and cost both combos."""
    def listing_gap(m):
        k, p = m["kalshi"], m["poly"]
        g = [1 - x - y for x, y in ((k.yes_ask, p.no_ask), (k.no_ask, p.yes_ask)) if x and y]
        return max(g) if g else -1.0
    rows = []
    for m in sorted(matches, key=listing_gap, reverse=True)[:a.max_matches]:
        k, p = m["kalshi"], m["poly"]
        row = dict(kalshi=k.key, poly=p.key, score=m["score"], listing_gap=round(listing_gap(m), 4), combos={})
        try:
            kb = parse_orderbook(client.orderbook(k.key))
            yes_b, no_b = client.poly_book(p.yes_token), client.poly_book(p.no_token)
        except Exception as e:  # noqa: BLE001
            row["error"] = str(e)
            rows.append(row)
            continue
        if kb is None:
            kb = Book([], [])
        prate = poly_fee_rate(p, a.poly_fee_rate)
        row["poly_fee_rate"] = prate
        row["combos"]["Kalshi YES + Poly NO"] = pair_cost(kb.yes_asks(), _poly_asks(no_b), a.fee_rate, prate)
        row["combos"]["Kalshi NO + Poly YES"] = pair_cost(kb.no_asks(), _poly_asks(yes_b), a.fee_rate, prate)
        rows.append(row)
    return rows


def snapshot(client, matches: list, info: dict, a, log) -> dict:
    t0 = time.time()
    ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
    rows = price_matches(client, matches, a, log) if matches else []
    return dict(ts=ts, rows=rows, seconds=round(time.time() - t0, 1), info=dict(info))


# --------------------------------------------------------------------------- report


def _cell(s: str, n: int = 0) -> str:
    s = (s or "").replace("|", "/").replace("\n", " ").strip()
    return (s[:n] + "…") if n and len(s) > n else s


def best_instances(snaps: list) -> dict:
    best = {}
    for s in snaps:
        for r in s["rows"]:
            for name, c in (r.get("combos") or {}).items():
                if not c:
                    continue
                key = (r["kalshi"], r["poly"], name)
                if key not in best or c["net"] > best[key][1]["net"]:
                    best[key] = (r, c, s["ts"])
    return best


def persistence(snaps: list, interval: float) -> list:
    seen = defaultdict(list)
    for i, s in enumerate(snaps):
        for r in s["rows"]:
            for name, c in (r.get("combos") or {}).items():
                if c and c["net"] > 0:
                    seen[(r["kalshi"], r["poly"], name)].append((i, c["net"]))
    out = []
    for key, lst in seen.items():
        idx = [i for i, _ in lst]
        streak = best = 1
        for x, y in zip(idx, idx[1:]):
            streak = streak + 1 if y == x + 1 else 1
            best = max(best, streak)
        out.append(dict(key=" / ".join(key), snapshots=len(idx), longest=best,
                        approx_s=round((best - 1) * interval), max_net=max(n for _, n in lst)))
    return sorted(out, key=lambda r: (-r["snapshots"], -r["max_net"]))


def render(snaps: list, matches: list, interval: float, a) -> str:
    last = snaps[-1]
    info = last["info"]
    L = [f"# Cross-venue scan: Kalshi vs Polymarket — every gap is labelled {LABEL}", ""]
    L.append(f"Snapshots: {len(snaps)} (interval {interval:g}s), first {snaps[0]['ts']}, last {last['ts']}. "
             "Read-only; no orders placed.")
    L.append("NOT risk-free unless the two rule sets resolve identically. A positive *net* gap below means: "
             "buying both legs at the shown asks costs less than $1 per unit after taker fees, so it pays $1 "
             "only if both venues resolve the same way.")
    L.append(f"Fees: Kalshi taker {a.fee_rate}·C·P·(1−P) (pipeline.kalshi_total_cost, cent-rounded per level). "
             "Polymarket taker rate·C·p·(1−p), rate by category "
             + ("(override " + str(a.poly_fee_rate) + ")" if a.poly_fee_rate is not None else
                "(" + ", ".join(f"{k} {v}" for k, v in POLY_FEE_RATES.items()) + "; approximate, see docstring)")
             + ". Gross gaps shown too.")
    L.append("")
    L.append("## Coverage")
    L.append(f"- Kalshi: {info.get('kalshi_status')}; events {info.get('kalshi_events', 0)}, live markets {info.get('kalshi_markets', 0)}; "
             f"categories {info.get('kalshi_categories', {})}")
    L.append(f"- Polymarket: {info.get('poly_status')}; markets {info.get('poly_markets', 0)}, binary entries {info.get('poly_entries', 0)}; "
             f"categories {info.get('poly_categories', {})}")
    ms = info.get("match_stats") or {}
    L.append(f"- Matching (min score {a.min_score}, end dates within {a.max_date_diff_days}d): "
             + (", ".join(f"{k} {v}" for k, v in sorted(ms.items(), key=lambda t: -t[1])) or "not run"))
    L.append(f"- High-confidence matches: {len(matches)}; priced each snapshot: up to {a.max_matches}")
    L.append("")
    best = best_instances(snaps)
    pos = sorted((v for v in best.values() if v[1]["net"] > 0), key=lambda v: -v[1]["net"])
    lastpos = [1 for r in last["rows"] for c in (r.get("combos") or {}).values() if c and c["net"] > 0]
    L.append(f"**Summary: {len(matches)} matched questions; {len(lastpos)} combos with positive top-of-book gap "
             f"after fees in the last snapshot; {len(pos)} distinct across all snapshots. {LABEL} before acting.**")
    L.append("")
    L.append(f"## Gaps (best instance per match and combo across snapshots, sorted by net $/unit) — {LABEL}")
    allb = sorted(best.values(), key=lambda v: -v[1]["net"])
    if not allb:
        L.append("No priced matches.")
    else:
        L.append("| # | Kalshi | Polymarket | combo | K ask | P ask | gross | K fee | P fee | net/unit | top qty K/P | walk units | walk cost | walk profit | rules wording differs | ts |")
        L.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        mk = {(m["kalshi"].key, m["poly"].key): (n + 1, m) for n, m in enumerate(matches)}
        for r, c, ts in allb[:a.report_rows]:
            n, m = mk.get((r["kalshi"], r["poly"]), ("?", None))
            diffs = ", ".join(checklist(m)["wording_differs"]) if m else ""
            L.append(f"| {n} | {r['kalshi']} | {r['poly']} | {[k for k, v in r['combos'].items() if v is c][0]} | "
                     f"{c['kalshi_ask']:.4f} | {c['poly_ask']:.4f} | {c['gross']:+.4f} | {c['kalshi_fee']:.4f} | "
                     f"{c['poly_fee']:.4f} | **{c['net']:+.4f}** | {c['kalshi_qty']}/{c['poly_qty']:g} | {c['units']} | "
                     f"${c['cost']:.2f} | ${c['profit']:.2f} | {diffs or 'none detected (still verify)'} | {ts} |")
    if len(snaps) > 1:
        L.append("")
        L.append("## Persistence (combos with positive net gap)")
        per = persistence(snaps, interval)
        if per:
            L.append("| Kalshi / Polymarket / combo | snapshots | longest consecutive | ≈ s | max net/unit |")
            L.append("|---|---|---|---|---|")
            for r in per[:30]:
                L.append(f"| {r['key']} | {r['snapshots']}/{len(snaps)} | {r['longest']} | {r['approx_s']} | {r['max_net']:+.4f} |")
        else:
            L.append("No positive net gap in any snapshot.")
        L.append("")
        L.append("| snapshot | priced | positive net | best net | s |")
        L.append("|---|---|---|---|---|")
        for s in snaps:
            nets = [c["net"] for r in s["rows"] for c in (r.get("combos") or {}).values() if c]
            L.append(f"| {s['ts']} | {len(s['rows'])} | {sum(1 for x in nets if x > 0)} | "
                     f"{max(nets) if nets else float('nan'):+.4f} | {s['seconds']} |")
    L.append("")
    L.append(f"## Matched questions side by side — {LABEL}")
    L.append("Checklist fields are keyword detections in each venue's rules text, not a judgement that rules match.")
    for n, m in enumerate(matches[:a.detail_rows]):
        k, p = m["kalshi"], m["poly"]
        ck = checklist(m)
        L.append("")
        L.append(f"### {n + 1}. score {m['score']} · end dates {m['date_gap_days']}d apart · category {k.category or '?'} / {p.category or '?'}")
        L.append("| | Kalshi | Polymarket |")
        L.append("|---|---|---|")
        L.append(f"| id | {k.key} (event {k.group}) | {p.key} ({p.raw.get('slug')}) |")
        L.append(f"| question | {_cell(k.question)} | {_cell(p.question)} |")
        L.append(f"| outcome | {_cell(k.label) or '(single market)'}; {_cell(k.outcome_note)} | {_cell(p.label) or '-'}; {_cell(p.outcome_note)} |")
        L.append(f"| close / end | {k.raw.get('expected_expiration_time') or ''} / {k.raw.get('close_time') or ''} | {p.raw.get('endDate') or ''} |")
        for f_ in ("deadline", "ties", "overtime", "cancel", "fifty_fifty"):
            mark = " ⚠" if (f_ in ck["wording_differs"] or (f_ == "deadline" and "deadline wording" in ck["wording_differs"])) else ""
            L.append(f"| {f_}{mark} | {ck['kalshi'][f_]} | {ck['poly'][f_]} |")
        mark = " ⚠" if "sources" in ck["wording_differs"] else ""
        L.append(f"| sources{mark} | {', '.join(ck['kalshi']['sources']) or '-'} | {', '.join(ck['poly']['sources']) or '-'} |")
        L.append(f"| rules | {_cell(k.rules, a.rules_chars)} | {_cell(p.rules, a.rules_chars)}"
                 + (f" (source: {_cell(p.source, 120)})" if p.source else "") + " |")
    if len(matches) > a.detail_rows:
        L.append("")
        L.append(f"({len(matches) - a.detail_rows} more matches in the JSON output.)")
    L.append("")
    return "\n".join(L)


def match_json(m: dict) -> dict:
    k, p = m["kalshi"], m["poly"]
    return dict(score=m["score"], date_gap_days=m["date_gap_days"], checklist=checklist(m),
                kalshi=dict(ticker=k.key, event=k.group, question=k.question, label=k.label, category=k.category,
                            rules=k.rules, **k.raw),
                poly=dict(key=p.key, question=p.question, label=p.label, category=p.category,
                          yes_token=p.yes_token, no_token=p.no_token, rules=p.rules, resolution_source=p.source, **p.raw))


# --------------------------------------------------------------------------- CLI


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--interval", type=float, default=120.0, help="seconds between snapshot starts")
    ap.add_argument("--relist-every", type=int, default=0,
                    help="re-list both venues and re-match every N snapshots (0: only the first)")
    ap.add_argument("--max-pages", type=int, default=60, help="Kalshi event pages (200 each)")
    ap.add_argument("--poly-pages", type=int, default=60, help="gamma market pages")
    ap.add_argument("--poly-limit", type=int, default=500)
    ap.add_argument("--min-score", type=float, default=0.45)
    ap.add_argument("--max-date-diff-days", type=float, default=2.0)
    ap.add_argument("--max-matches", type=int, default=150, help="matches priced (books fetched) per snapshot")
    ap.add_argument("--fee-rate", type=float, default=FEE_RATE, help="Kalshi taker fee rate")
    ap.add_argument("--poly-fee-rate", type=float, default=None, help="override Polymarket taker rate for all categories")
    ap.add_argument("--rps", type=float, default=8.0)
    ap.add_argument("--report-rows", type=int, default=60)
    ap.add_argument("--detail-rows", type=int, default=40)
    ap.add_argument("--rules-chars", type=int, default=500)
    ap.add_argument("--fixture", type=Path, help="offline fixture directory")
    ap.add_argument("--out", type=Path, default=Path("xvenue_scan.md"))
    ap.add_argument("--json", type=Path, default=Path("xvenue_scan.json"))
    a = ap.parse_args(argv)

    client = FixtureClient(a.fixture) if a.fixture else XClient(rps=a.rps)
    log = lambda s: print(s, file=sys.stderr, flush=True)  # noqa: E731
    snaps, matches, info = [], [], {}
    for i in range(max(1, a.repeat)):
        start = time.monotonic()
        try:
            if i == 0 or (a.relist_every and i % a.relist_every == 0):
                matches, info = build_matches(client, a, log)
                log(f"listing: Kalshi {info.get('kalshi_markets', 0)} markets ({info.get('kalshi_status')}), "
                    f"Polymarket {info.get('poly_entries', 0)} entries ({info.get('poly_status')}), matches {len(matches)}")
            s = snapshot(client, matches, info, a, log)
        except Exception as e:  # noqa: BLE001 - keep partial results of a long run
            log(f"snapshot {i + 1} failed: {e}")
            continue
        snaps.append(s)
        nets = [c["net"] for r in s["rows"] for c in (r.get("combos") or {}).values() if c]
        log(f"[{s['ts']}] snapshot {i + 1}/{a.repeat}: priced {len(s['rows'])}, positive net {sum(1 for x in nets if x > 0)}, "
            f"best {max(nets) if nets else float('nan'):+.4f}, {s['seconds']}s")
        if i < a.repeat - 1:
            rest = a.interval - (time.monotonic() - start)
            if rest > 0:
                time.sleep(rest)
    if not snaps:
        a.out.write_text("# Cross-venue scan\n\nNo snapshot succeeded (API unreachable?). See the job log.\n")
        log("no snapshot succeeded")
        return 1
    report = render(snaps, matches, a.interval, a)
    a.out.write_text(report)
    a.json.write_text(json.dumps(dict(
        generated=datetime.now(timezone.utc).isoformat(timespec="seconds"), label=LABEL,
        kalshi_fee_rate=a.fee_rate, poly_fee_rates=POLY_FEE_RATES, poly_fee_override=a.poly_fee_rate,
        matches=[match_json(m) for m in matches], snapshots=snaps,
        persistence=persistence(snaps, a.interval)), indent=1, default=str))
    print(report)
    ok = info.get("kalshi_status") == "ok" and info.get("poly_status") == "ok"
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
