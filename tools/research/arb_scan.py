#!/usr/bin/env python3
"""Structural (risk-free) arbitrage scanner for Kalshi, using real order-book depth.

Prediction lost to Kalshi's prices, so this looks for *combinations* of
contracts whose payoff is >= $1 in every outcome but whose all-in cost
(price + taker fee) is below that payoff:

  a. box       buy YES + buy NO of one market               payoff 1
  b. set-long  buy YES of every bucket in an exhaustive set  payoff 1  (needs >= 1 YES always)
     set-short buy NO of every bucket of a mutually          payoff n-1 (needs <= 1 YES always)
               exclusive set
  c. ladder    "above" strikes K1 < K2 in one event:  buy YES K1 + NO K2   payoff >= 1
               "below" strikes K1 < K2 in one event:  buy YES K2 + NO K1   payoff >= 1
  d. cross-venue KXBTC15M vs Polymarket BTC up/down 15m  -- NOT risk-free
               (different settlement sources => basis risk; reported separately)

Order books (https://docs.kalshi.com/getting_started/orderbook_responses) list
bids only: YES bids and NO bids. YES ask at p = 1 - NO bid, NO ask = 1 - YES bid
(same identity as app/.../domain/ConsistentQuote.kt). Buying YES therefore walks
the NO-bid ladder from the highest NO bid down, and vice versa.

Fees: tools/backtest/pipeline.kalshi_total_cost (0.07*C*P*(1-P), fee ceil to
1e-6, debit ceil to the cent). A leg that sweeps several price levels is costed
as one kalshi_total_cost per level (each level a separate fill, each rounded up
to the cent) -- CONSERVATIVE; whether Kalshi rounds per fill or per order is not
verified here. Guaranteed profit = contracts * guaranteed payoff - sum(leg costs).

Exhaustiveness (set-long) and exclusivity (set-short) are checked from
strike_type / floor_strike / cap_strike and the event's mutually_exclusive flag.
Boundary inclusivity of strikes (is exactly K in "above K" or in the bucket
starting at K?) is NOT verified; label "verified" means strike intervals touch or
overlap with no gap, "assumed" means it rests on the flag or on a gap being
unreachable at the underlying's granularity. Only "verified" set-long
opportunities count toward the risk-free total; "assumed" ones are listed for
manual rule reading.

API shapes assumed (public, unauthenticated, Kalshi trade-api v2):
  GET /events?status=open&with_nested_markets=true&limit=200&cursor=...
      -> {"events":[{event_ticker, series_ticker, title, category,
                     mutually_exclusive, markets:[{ticker, status, strike_type,
                     floor_strike, cap_strike, yes_bid_dollars, yes_ask_dollars,
                     no_bid_dollars, no_ask_dollars, close_time, ...}]}], "cursor"}
  GET /markets/{ticker}/orderbook
      -> {"orderbook_fp": {"yes_dollars": [["0.4500","12.00"],...], "no_dollars": [...]}}
         or {"orderbook": {"yes_dollars"|"yes_dollars_fp": [[price$, qty]], ...,
                           "yes": [[cents, qty]], "no": [[cents, qty]]}}
  Polymarket (optional, unverified shapes): gamma-api /events?slug=btc-updown-15m-<start_unix>,
  clob /book?token_id=... -> {"bids":[{price,size}], "asks":[{price,size}]}

Python 3 stdlib only.

Usage:
  python3 tools/research/arb_scan.py                       # one snapshot
  python3 tools/research/arb_scan.py --repeat 20 --interval 60
  python3 tools/research/arb_scan.py --fixture tools/research/fixtures/arb   # offline
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import FEE_RATE, kalshi_total_cost  # noqa: E402

KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
GAMMA = "https://gamma-api.polymarket.com"
CLOB = "https://clob.polymarket.com"
UA = {"User-Agent": "DipHunterArbScan/0.1 (research; +https://github.com/Strobingn/kalshi-odds-app)"}

NEAR_MISS = 0.02          # $/contract of the combination
UP_TYPES = ("greater", "greater_or_equal")
DOWN_TYPES = ("less", "less_or_equal")
LIVE_STATUSES = ("active", "open", "")
MAX_Q = 1_000_000


# --------------------------------------------------------------------------- HTTP


class HttpClient:
    """Rate-limited GET with retries/backoff (pattern of tools/backtest/fetch._get)."""

    def __init__(self, rps: float = 8.0, retries: int = 5, verbose: bool = False):
        self.min_gap = 1.0 / max(rps, 0.1)
        self.retries = retries
        self.last = 0.0
        self.calls = 0
        self.verbose = verbose

    def get(self, url: str, retries: int | None = None) -> object:
        tries = self.retries if retries is None else retries
        last = None
        for i in range(tries):
            wait = self.min_gap - (time.monotonic() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.monotonic()
            self.calls += 1
            try:
                req = urllib.request.Request(url, headers=UA)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    return json.loads(resp.read().decode())
            except urllib.error.HTTPError as e:
                last = e
                if e.code == 404:
                    return None
                back = 1.0 * (2 ** min(i, 4))
                if e.code == 429:
                    ra = e.headers.get("Retry-After")
                    try:
                        back = max(back, float(ra)) if ra else max(back, 2.0)
                    except ValueError:
                        pass
                if e.code in (429, 500, 502, 503, 504):
                    time.sleep(back)
                    continue
                raise
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ConnectionError) as e:
                last = e
                time.sleep(1.0 * (2 ** min(i, 4)))
        raise RuntimeError(f"GET failed {url}: {last}")

    # Kalshi endpoints
    def events_page(self, cursor: str | None) -> dict:
        q = {"status": "open", "with_nested_markets": "true", "limit": "200"}
        if cursor:
            q["cursor"] = cursor
        return self.get(f"{KALSHI}/events?{urllib.parse.urlencode(q)}") or {}

    def orderbook(self, ticker: str) -> dict | None:
        return self.get(f"{KALSHI}/markets/{urllib.parse.quote(ticker)}/orderbook")

    # Polymarket endpoints (optional)
    def poly_event(self, slug: str) -> object:
        return self.get(f"{GAMMA}/events?{urllib.parse.urlencode({'slug': slug})}", retries=2)

    def poly_book(self, token_id: str) -> object:
        return self.get(f"{CLOB}/book?{urllib.parse.urlencode({'token_id': token_id})}", retries=2)


class FixtureClient:
    """Offline client: <dir>/events.json (one events page) + <dir>/books.json {ticker: body}."""

    def __init__(self, directory: Path):
        self.dir = Path(directory)
        self.events = json.loads((self.dir / "events.json").read_text())
        self.books = json.loads((self.dir / "books.json").read_text())
        poly = self.dir / "poly.json"
        self.poly = json.loads(poly.read_text()) if poly.exists() else {}
        self.calls = 0

    def events_page(self, cursor: str | None) -> dict:
        self.calls += 1
        return {"events": self.events.get("events", []), "cursor": None}

    def orderbook(self, ticker: str) -> dict | None:
        self.calls += 1
        return self.books.get(ticker)

    def poly_event(self, slug: str) -> object:
        return self.poly.get("events", {}).get(slug)

    def poly_book(self, token_id: str) -> object:
        return self.poly.get("books", {}).get(token_id)


# --------------------------------------------------------------------------- parsing


def _f(x) -> float | None:
    if x is None or x == "":
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def dollar_field(m: dict, dollars_key: str, cents_key: str) -> float | None:
    """`*_dollars` strings are dollars; legacy integer fields are cents (as the app reads them)."""
    v = _f(m.get(dollars_key))
    if v is None:
        c = _f(m.get(cents_key))
        v = c / 100.0 if c is not None else None
    if v is None or v <= 0.0 or v >= 1.0:
        return None  # 0 / 1 are "no quote" placeholders
    return round(v, 4)


def top_quote(m: dict) -> tuple[float | None, float | None]:
    """(yes_bid, yes_ask) from listing fields, completing one side from the other."""
    yb = dollar_field(m, "yes_bid_dollars", "yes_bid")
    ya = dollar_field(m, "yes_ask_dollars", "yes_ask")
    nb = dollar_field(m, "no_bid_dollars", "no_bid")
    na = dollar_field(m, "no_ask_dollars", "no_ask")
    if ya is None and nb is not None:
        ya = round(1.0 - nb, 4)
    if yb is None and na is not None:
        yb = round(1.0 - na, 4)
    return yb, ya


@dataclass
class Book:
    yes_bids: list  # [(price, qty)] best (highest) first
    no_bids: list

    def yes_asks(self) -> list:
        """Buy-YES ladder: 1 - NO bid, cheapest first."""
        return [(round(1.0 - p, 4), q) for p, q in self.no_bids]

    def no_asks(self) -> list:
        return [(round(1.0 - p, 4), q) for p, q in self.yes_bids]


def _levels(raw, cents: bool) -> list:
    out = []
    for lv in raw or []:
        try:
            if isinstance(lv, dict):
                p, q = _f(lv.get("price")), _f(lv.get("quantity", lv.get("size")))
            else:
                p, q = _f(lv[0]), _f(lv[1])
        except (IndexError, TypeError):
            continue
        if p is None or q is None:
            continue
        if cents:
            p /= 100.0
        p = round(p, 4)
        q = int(math.floor(q + 1e-9))  # whole contracts only (conservative)
        if 0.0 < p < 1.0 and q > 0:
            out.append((p, q))
    # merge duplicates, best bid first
    agg: dict = defaultdict(int)
    for p, q in out:
        agg[p] += q
    return sorted(agg.items(), key=lambda t: -t[0])


def parse_orderbook(body: dict | None) -> Book | None:
    if not isinstance(body, dict):
        return None
    ob = body.get("orderbook_fp") or body.get("orderbook") or body
    if not isinstance(ob, dict):
        return None

    def side(name: str) -> list:
        for key in (f"{name}_dollars_fp", f"{name}_dollars"):
            if ob.get(key) is not None:
                return _levels(ob[key], cents=False)
        return _levels(ob.get(name), cents=True)

    return Book(side("yes"), side("no"))


# --------------------------------------------------------------------------- costing


def fee_rate_per_contract(p: float, rate: float = FEE_RATE) -> float:
    return rate * p * (1.0 - p)


def walk(asks: list, q: int) -> list | None:
    """Fills [(price, n)] buying q contracts up an ask ladder; None if too thin."""
    fills, need = [], q
    for p, avail in asks:
        if need <= 0:
            break
        n = min(avail, need)
        fills.append((p, n))
        need -= n
    return fills if need <= 0 else None


def fills_cost(fills: list, rate: float = FEE_RATE) -> float:
    """One kalshi_total_cost per level (conservative: each fill rounded up to the cent)."""
    return round(sum(kalshi_total_cost(n, p, rate) for p, n in fills), 2)


def depth(asks: list) -> int:
    return sum(q for _, q in asks)


def price_at_unit(asks: list, k: int) -> float | None:
    """Price paid for the k-th (1-based) contract up the ladder."""
    cum = 0
    for p, q in asks:
        cum += q
        if k <= cum:
            return p
    return None


@dataclass
class Sized:
    contracts: int
    cost: float
    payoff: float
    profit: float
    fees: float
    legs: list  # [(fills, cost)]
    edge_top: float  # $/contract at top of book, unrounded fee


def size_combo(leg_asks: list, payoff_per_unit: float, rate: float = FEE_RATE) -> Sized | None:
    """Best integer size for buying 1 of each leg per unit, walking every leg's book.

    Marginal unit k earns payoff - sum(price_leg(k) + rate*p(1-p)); that is
    non-increasing in k up to fee-curvature noise, so we find the last positive
    unit Q* and evaluate exact (rounded) profit at Q* and at every level
    breakpoint below it, keeping the best.
    """
    if not leg_asks or any(not a for a in leg_asks):
        return None
    maxq = min(min(depth(a) for a in leg_asks), MAX_Q)
    if maxq <= 0:
        return None

    def marginal(k: int) -> float:
        tot = 0.0
        for a in leg_asks:
            p = price_at_unit(a, k)
            tot += p + fee_rate_per_contract(p, rate)
        return payoff_per_unit - tot

    edge_top = marginal(1)
    breaks = set()
    for a in leg_asks:
        cum = 0
        for _, q in a:
            cum += q
            if cum <= maxq:
                breaks.add(cum)
    breaks.add(maxq)
    ordered = sorted(breaks)
    # Q*: marginal is constant between breakpoints, so check the unit after each break.
    qstar = 0
    lo = 1
    for b in ordered:
        if marginal(lo) > 0:
            qstar = b
            lo = b + 1
        else:
            break
    cands = {q for q in ordered if q <= qstar} | {qstar, 1}
    best = None
    for q in sorted(c for c in cands if c >= 1):
        legs = []
        for a in leg_asks:
            fl = walk(a, q)
            legs.append((fl, fills_cost(fl, rate)))
        cost = round(sum(c for _, c in legs), 2)
        payoff = round(q * payoff_per_unit, 2)
        notional = sum(p * n for fl, _ in legs for p, n in fl)
        s = Sized(q, cost, payoff, round(payoff - cost, 2), round(cost - notional, 4), legs, edge_top)
        if best is None or s.profit > best.profit + 1e-9:
            best = s
    return best


# --------------------------------------------------------------------------- structure


def market_live(m: dict) -> bool:
    return (m.get("status") or "").lower() in LIVE_STATUSES


def interval(m: dict) -> tuple[float, float] | None:
    st = (m.get("strike_type") or "").lower()
    fl, cp = _f(m.get("floor_strike")), _f(m.get("cap_strike"))
    if st in UP_TYPES and fl is not None:
        return (fl, math.inf)
    if st in DOWN_TYPES and cp is not None:
        return (-math.inf, cp)
    if st == "between" and fl is not None and cp is not None:
        return (fl, cp)
    return None


def set_structure(event: dict, markets: list) -> dict:
    """Exhaustive (>=1 YES) and exclusive (<=1 YES) status for the event's market set."""
    flag = bool(event.get("mutually_exclusive"))
    ivs = [interval(m) for m in markets]
    notes = []
    if len(markets) < 2:
        return dict(exhaustive="no", exclusive="no", note="fewer than 2 markets")
    if all(iv is not None for iv in ivs):
        srt = sorted(ivs)
        gaps = [(a[1], b[0]) for a, b in zip(srt, srt[1:]) if b[0] > a[1] + 1e-12]
        overlaps = [(a[1], b[0]) for a, b in zip(srt, srt[1:]) if b[0] < a[1] - 1e-12]
        unb_lo, unb_hi = srt[0][0] == -math.inf, max(iv[1] for iv in srt) == math.inf
        if unb_lo and unb_hi and not gaps:
            exhaustive = "verified"
            notes.append("strike intervals cover (-inf, +inf) with no gap (boundary inclusivity assumed)")
        elif unb_lo and unb_hi and flag:
            exhaustive = "assumed"
            notes.append("gaps " + ", ".join(f"{a:g}..{b:g}" for a, b in gaps[:4]) + " assumed unreachable at underlying granularity")
        else:
            exhaustive = "no"
            notes.append("strike intervals do not cover all outcomes" + ("" if (unb_lo and unb_hi) else " (no open-ended tail bucket)"))
        if overlaps:
            exclusive = "no"
            notes.append("strike intervals overlap")
        elif flag:
            exclusive = "verified"
        else:
            exclusive = "assumed"
            notes.append("intervals disjoint but mutually_exclusive flag false")
    else:
        if flag:
            exhaustive, exclusive = "assumed", "verified"
            notes.append("mutually_exclusive flag only; no strike coverage to check (an unlisted/'none' outcome would break set-long)")
        else:
            exhaustive, exclusive = "no", "no"
    return dict(exhaustive=exhaustive, exclusive=exclusive, note="; ".join(notes))


def ladder_subject(ticker: str | None) -> str:
    """What a strike is measured on: the ticker with its strike number removed.

    One event can hold ladders on different things (BC by 2+ and SMU by 10+ in
    a spread event; each player's rushing yards). Only strikes on the same
    subject are nested, so only those form a risk-free ladder.
      KXNCAAFSPREAD-26OCT03BCSMU-BC2        -> ...-BC
      KXNFLRSHYDS-26OCT01PITCLE-CLERSANDERS23-25 -> ...-CLERSANDERS23
      KXBTCD-26SEP3017-T84999.99            -> ...-T
    """
    parts = (ticker or "").split("-")
    if len(parts) >= 2 and re.fullmatch(r"[0-9.]+", parts[-1]):
        return "-".join(parts[:-1])
    parts[-1] = re.sub(r"[0-9.]+$", "", parts[-1])
    return "-".join(parts)


def rules_template(m: dict) -> str:
    """rules_primary with every number blanked: nested strikes differ only in the number."""
    return re.sub(r"[0-9][0-9,]*(\.[0-9]+)?", "#", (m.get("rules_primary") or "").strip().lower())


def ladder_key(m: dict) -> tuple:
    """Same subject, same close time, same written rule (numbers aside)."""
    return (ladder_subject(m.get("ticker")), m.get("close_time") or "", rules_template(m))


def ladders(markets: list) -> list:
    """[(direction, [(strike, market)...] sorted by strike)] for same-type, same-subject strike ladders."""
    out = []
    for direction, types, key in (("up", UP_TYPES, "floor_strike"), ("down", DOWN_TYPES, "cap_strike")):
        for st in types:
            groups: dict = {}
            for m in markets:
                if (m.get("strike_type") or "").lower() != st:
                    continue
                k = _f(m.get(key))
                if k is not None:
                    groups.setdefault(ladder_key(m), []).append((k, m))
            for grp in groups.values():
                if len({k for k, _ in grp}) >= 2:
                    out.append((direction, sorted(grp, key=lambda t: t[0])))
    return out


def classify(event: dict, markets: list) -> str:
    types = Counter((m.get("strike_type") or "").lower() for m in markets)
    if len(markets) < 2:
        return "binary"
    if types.get("between") or event.get("mutually_exclusive"):
        return "range/exclusive"
    if ladders(markets):
        return "ladder"
    return "multi-other"


# --------------------------------------------------------------------------- checks


def _leg(m: dict, side: str, sized_leg: tuple) -> dict:
    fills, cost = sized_leg
    return dict(ticker=m.get("ticker"), side=side, fills=[[p, n] for p, n in fills], cost=cost,
                subtitle=m.get("yes_sub_title") or m.get("subtitle"))


def _opp(kind: str, event: dict, s: Sized, legs: list, payoff_unit: float, extra: dict) -> dict:
    return dict(
        kind=kind,
        event_ticker=event.get("event_ticker"),
        title=event.get("title"),
        category=event.get("category"),
        legs=legs,
        contracts=s.contracts,
        cost=s.cost,
        payoff=s.payoff,
        payoff_per_contract=payoff_unit,
        fees=s.fees,
        profit=s.profit,
        profit_pct=round(100.0 * s.profit / s.cost, 3) if s.cost > 0 else None,
        edge_top_per_contract=round(s.edge_top, 4),
        **extra,
    )


def check_box(event: dict, m: dict, book: Book, rate: float = FEE_RATE) -> dict | None:
    ya, na = book.yes_asks(), book.no_asks()
    s = size_combo([ya, na], 1.0, rate)
    if s is None:
        return None
    legs = [_leg(m, "YES", s.legs[0]), _leg(m, "NO", s.legs[1])]
    return _opp("box", event, s, legs, 1.0, dict(tickers=[m.get("ticker")]))


def check_sets(event: dict, markets: list, books: dict, rate: float = FEE_RATE) -> list:
    st = set_structure(event, markets)
    out = []
    bk = [books.get(m.get("ticker")) for m in markets]
    if any(b is None for b in bk):
        return out
    n = len(markets)
    tick = [m.get("ticker") for m in markets]
    if st["exhaustive"] != "no":
        s = size_combo([b.yes_asks() for b in bk], 1.0, rate)
        if s is not None:
            legs = [_leg(m, "YES", lg) for m, lg in zip(markets, s.legs)]
            out.append(_opp("set-long", event, s, legs, 1.0,
                            dict(tickers=tick, exhaustive=st["exhaustive"], exclusive=st["exclusive"], structure_note=st["note"],
                                 risk_free=st["exhaustive"] == "verified")))
    if st["exclusive"] != "no":
        s = size_combo([b.no_asks() for b in bk], float(n - 1), rate)
        if s is not None:
            legs = [_leg(m, "NO", lg) for m, lg in zip(markets, s.legs)]
            out.append(_opp("set-short", event, s, legs, float(n - 1),
                            dict(tickers=tick, exhaustive=st["exhaustive"], exclusive=st["exclusive"], structure_note=st["note"],
                                 risk_free=st["exclusive"] == "verified")))
    return out


def check_ladders(event: dict, markets: list, books: dict, rate: float = FEE_RATE) -> list:
    out = []
    for direction, grp in ladders(markets):
        for i in range(len(grp)):
            for j in range(i + 1, len(grp)):
                (k1, m1), (k2, m2) = grp[i], grp[j]
                if k1 == k2:
                    continue
                b1, b2 = books.get(m1.get("ticker")), books.get(m2.get("ticker"))
                if b1 is None or b2 is None:
                    continue
                # up: P(>K1) >= P(>K2): buy YES K1 + NO K2.  down: P(<K1) <= P(<K2): buy YES K2 + NO K1.
                yes_m, yes_b, no_m, no_b = (m1, b1, m2, b2) if direction == "up" else (m2, b2, m1, b1)
                s = size_combo([yes_b.yes_asks(), no_b.no_asks()], 1.0, rate)
                if s is None:
                    continue
                legs = [_leg(yes_m, "YES", s.legs[0]), _leg(no_m, "NO", s.legs[1])]
                out.append(_opp("ladder", event, s, legs, 1.0,
                                dict(tickers=[yes_m.get("ticker"), no_m.get("ticker")], direction=direction,
                                     strikes=[k1, k2], strike_type=(m1.get("strike_type") or "").lower(),
                                     risk_free=True)))
    return out


# --------------------------------------------------------------------------- prefilter (listing quotes)


def prefilter_gap(event: dict, markets: list) -> float:
    """Best gross (fee-free) top-of-book $/unit edge from listing quotes; -inf if unknown."""
    q = {m.get("ticker"): top_quote(m) for m in markets}
    best = -math.inf
    if len(markets) >= 2:
        st = set_structure(event, markets)
        asks = [q[m.get("ticker")][1] for m in markets]
        bids = [q[m.get("ticker")][0] for m in markets]
        if st["exhaustive"] != "no" and all(a is not None for a in asks):
            best = max(best, 1.0 - sum(asks))
        if st["exclusive"] != "no" and all(b is not None for b in bids):
            best = max(best, sum(bids) - 1.0)
        for direction, grp in ladders(markets):
            for i in range(len(grp)):
                for j in range(i + 1, len(grp)):
                    lo, hi = grp[i][1], grp[j][1]
                    yes_m, no_m = (lo, hi) if direction == "up" else (hi, lo)
                    ya, nb_as_yes_bid = q[yes_m.get("ticker")][1], q[no_m.get("ticker")][0]
                    if ya is not None and nb_as_yes_bid is not None:
                        best = max(best, nb_as_yes_bid - ya)
    return best


# --------------------------------------------------------------------------- Polymarket (optional, NOT risk-free)


def _jsonish(x):
    if isinstance(x, str):
        try:
            return json.loads(x)
        except json.JSONDecodeError:
            return None
    return x


def _poly_asks(body) -> list:
    if not isinstance(body, dict):
        return []
    out = []
    for lv in body.get("asks") or []:
        p, s = _f((lv or {}).get("price")), _f((lv or {}).get("size"))
        if p is not None and s is not None and 0 < p < 1 and s > 0:
            out.append((p, s))
    return sorted(out)


def cross_venue(client, events: list, books: dict, now_s: float, log) -> dict:
    """Kalshi KXBTC15M vs Polymarket btc-updown-15m-<start>. Gross/Kalshi-fee gaps only."""
    res = dict(status="ok", rows=[], note="")
    k15 = []
    for ev in events:
        if (ev.get("series_ticker") or "").upper() != "KXBTC15M":
            continue
        for m in ev.get("markets") or []:
            if not market_live(m):
                continue
            close = m.get("close_time")
            try:
                close_s = datetime.fromisoformat(close.replace("Z", "+00:00")).timestamp()
            except (AttributeError, ValueError):
                continue
            if close_s > now_s:
                k15.append((close_s, ev, m))
    k15.sort(key=lambda t: t[0])
    if not k15:
        res.update(status="skipped", note="no open KXBTC15M market in the listing")
        return res
    for close_s, ev, m in k15[:3]:
        start = int(close_s) - 900
        slug = f"btc-updown-15m-{start}"
        try:
            pe = client.poly_event(slug)
        except Exception as e:  # noqa: BLE001 - optional section must never kill the scan
            res.update(status="unreachable", note=f"gamma-api: {e}")
            return res
        if isinstance(pe, list):
            pe = pe[0] if pe else None
        if not isinstance(pe, dict):
            res["rows"].append(dict(kalshi=m.get("ticker"), slug=slug, status="no Polymarket event for this window"))
            continue
        pms = pe.get("markets") or []
        pm = pms[0] if pms else None
        if not isinstance(pm, dict):
            res["rows"].append(dict(kalshi=m.get("ticker"), slug=slug, status="Polymarket event has no market"))
            continue
        outcomes = _jsonish(pm.get("outcomes")) or []
        tokens = _jsonish(pm.get("clobTokenIds")) or []
        tok = {str(o).lower(): t for o, t in zip(outcomes, tokens)}
        if "up" not in tok or "down" not in tok:
            res["rows"].append(dict(kalshi=m.get("ticker"), slug=slug, status=f"unexpected outcomes {outcomes}"))
            continue
        try:
            up_asks = _poly_asks(client.poly_book(tok["up"]))
            dn_asks = _poly_asks(client.poly_book(tok["down"]))
        except Exception as e:  # noqa: BLE001
            res["rows"].append(dict(kalshi=m.get("ticker"), slug=slug, status=f"clob book error: {e}"))
            continue
        kb = books.get(m.get("ticker"))
        if kb is None:
            try:
                kb = parse_orderbook(client.orderbook(m.get("ticker")))
            except Exception:  # noqa: BLE001
                kb = None
        if kb is None:
            continue
        desc = (pm.get("description") or pe.get("description") or "")
        src = [w for w in ("Chainlink", "Binance", "Coinbase", "CF Benchmarks", "Pyth") if w.lower() in desc.lower()]
        row = dict(kalshi=m.get("ticker"), kalshi_strike=_f(m.get("floor_strike")), slug=slug,
                   polymarket_question=pm.get("question"), polymarket_source_mentions=src,
                   polymarket_desc=desc[:300], status="ok")
        for name, k_asks, p_asks in (("Kalshi YES(up) + Poly Down", kb.yes_asks(), dn_asks),
                                     ("Kalshi NO(down) + Poly Up", kb.no_asks(), up_asks)):
            if not k_asks or not p_asks:
                row[name] = None
                continue
            kp, kq = k_asks[0]
            pp, pq = p_asks[0]
            gross = 1.0 - (kp + pp)
            row[name] = dict(kalshi_ask=kp, kalshi_qty=kq, poly_ask=pp, poly_qty=pq,
                             gross_gap=round(gross, 4),
                             gap_after_kalshi_fee=round(gross - fee_rate_per_contract(kp), 4),
                             polymarket_fee="unknown (not included)")
        res["rows"].append(row)
    return res


# --------------------------------------------------------------------------- snapshot


def list_events(client, max_pages: int) -> list:
    events, cursor = [], None
    for _ in range(max_pages):
        body = client.events_page(cursor)
        events.extend(body.get("events") or [])
        cursor = body.get("cursor") or None
        if not cursor:
            break
    return events


def snapshot(client, max_books: int = 400, max_pages: int = 60, max_event_markets: int = 60,
             prefilter: float = -0.10, rate: float = FEE_RATE, polymarket: bool = True, log=print) -> dict:
    t0 = time.time()
    ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
    events = list_events(client, max_pages)
    counts = Counter()
    kal_cat = Counter()
    markets_total = 0
    cands = []
    for ev in events:
        ms = [m for m in (ev.get("markets") or []) if market_live(m)]
        markets_total += len(ms)
        cls = classify(ev, ms)
        counts[cls] += 1
        kal_cat[ev.get("category") or "?"] += 1
        if cls in ("binary", "multi-other") or len(ms) < 2:
            continue
        # A set check on an event with any non-live market would be incomplete.
        all_live = len(ms) == len(ev.get("markets") or [])
        gap = prefilter_gap(ev, ms)
        cands.append((gap, cls, ev, ms, all_live))
    cands.sort(key=lambda t: -t[0] if math.isfinite(t[0]) else math.inf)

    books: dict = {}
    scanned = Counter()
    skipped = Counter()
    budget = max_books
    opps, near = [], []
    box_near = [0]

    def consider(o: dict) -> None:
        if o["profit"] > 0:
            opps.append(o)
        elif o["kind"] == "box":
            # A box needs a crossed book (YES bid + NO bid > $1), which the matching
            # engine never displays; tight-spread tail markets would flood the list.
            box_near[0] += o["edge_top_per_contract"] >= -NEAR_MISS
        elif o["edge_top_per_contract"] >= -NEAR_MISS:
            near.append(o)

    for gap, cls, ev, ms, all_live in cands:
        if gap < prefilter:
            skipped["below prefilter"] += 1
            continue
        if len(ms) > max_event_markets:
            skipped["too many markets"] += 1
            continue
        need = [m for m in ms if m.get("ticker") not in books]
        if len(need) > budget:
            skipped["book budget"] += 1
            continue
        for m in need:
            try:
                books[m["ticker"]] = parse_orderbook(client.orderbook(m["ticker"]))
            except Exception as e:  # noqa: BLE001
                log(f"orderbook {m.get('ticker')}: {e}")
                books[m["ticker"]] = None
            budget -= 1
        scanned[cls] += 1
        live_books = {k: v for k, v in books.items() if v is not None}
        if all_live:
            for o in check_sets(ev, ms, live_books, rate):
                consider(o)
        else:
            skipped["set check: non-live market in event"] += 1
        for o in check_ladders(ev, ms, live_books, rate):
            consider(o)
        for m in ms:
            b = live_books.get(m.get("ticker"))
            if b is not None:
                o = check_box(ev, m, b, rate)
                if o is not None:
                    consider(o)

    stamp = dict(ts=ts)
    for o in opps + near:
        o.update(stamp)
    cv = None
    if polymarket:
        try:
            cv = cross_venue(client, events, {k: v for k, v in books.items() if v}, time.time(), log)
        except Exception as e:  # noqa: BLE001
            cv = dict(status="error", note=str(e), rows=[])
    return dict(
        ts=ts,
        seconds=round(time.time() - t0, 1),
        events=len(events),
        markets=markets_total,
        by_class=dict(counts),
        by_category=dict(kal_cat),
        candidates=len(cands),
        scanned_events=dict(scanned),
        skipped=dict(skipped),
        books_fetched=len(books),
        opportunities=sorted(opps, key=lambda o: -o["profit"]),
        near_misses=sorted(near, key=lambda o: -o["edge_top_per_contract"])[:40],
        near_miss_total=len(near),
        box_near_misses=box_near[0],
        cross_venue=cv,
    )


# --------------------------------------------------------------------------- aggregation / report


def opp_key(o: dict) -> str:
    return f"{o['kind']}|{o.get('direction', '')}|" + ",".join(f"{lg['side']}:{lg['ticker']}" for lg in o["legs"])


def is_risk_free(o: dict) -> bool:
    return o["kind"] in ("box", "ladder") or bool(o.get("risk_free"))


def locked_total(opps: list) -> float:
    """Best risk-free opportunity per event (overlapping legs within an event are not additive)."""
    best: dict = {}
    for o in opps:
        if is_risk_free(o):
            e = o["event_ticker"]
            if e not in best or o["profit"] > best[e]["profit"]:
                best[e] = o
    return round(sum(o["profit"] for o in best.values()), 2)


def persistence(snaps: list, interval: float) -> dict:
    seen: dict = defaultdict(list)
    for i, s in enumerate(snaps):
        for o in s["opportunities"]:
            if is_risk_free(o):
                seen[opp_key(o)].append((i, o))
    rows = []
    for k, lst in seen.items():
        idx = [i for i, _ in lst]
        streak = best = 1
        for a, b in zip(idx, idx[1:]):
            streak = streak + 1 if b == a + 1 else 1
            best = max(best, streak)
        rows.append(dict(key=k, snapshots=len(idx), longest_streak=best,
                         approx_longest_s=round((best - 1) * interval), max_profit=max(o["profit"] for _, o in lst),
                         first=lst[0][1]["ts"], last=lst[-1][1]["ts"]))
    with_any = sum(1 for s in snaps if any(is_risk_free(o) for o in s["opportunities"]))
    return dict(snapshots=len(snaps), with_opportunity=with_any, rows=sorted(rows, key=lambda r: -r["snapshots"]))


def _fmt_legs(o: dict) -> str:
    parts = []
    for lg in o["legs"]:
        fills = " + ".join(f"{n}@{p:.4f}" for p, n in lg["fills"])
        sub = f" ({lg['subtitle']})" if lg.get("subtitle") else ""
        parts.append(f"{lg['side']} {lg['ticker']}{sub} [{fills}] ${lg['cost']:.2f}")
    return "<br>".join(parts)


def _opp_table(rows: list) -> list:
    out = ["| kind | event | legs (qty@price, all-in cost) | contracts | cost | payoff | fees | profit | % | edge/ct top | struct | ts |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for o in rows:
        struct = ""
        if o["kind"].startswith("set"):
            struct = f"exh {o.get('exhaustive')} / excl {o.get('exclusive')}"
        elif o["kind"] == "ladder":
            struct = f"{o.get('direction')} {o.get('strike_type')} {o['strikes'][0]:g}<{o['strikes'][1]:g}"
        out.append(f"| {o['kind']} | {o['event_ticker']} | {_fmt_legs(o)} | {o['contracts']} | ${o['cost']:.2f} | "
                   f"${o['payoff']:.2f} | ${o['fees']:.2f} | ${o['profit']:.2f} | {o['profit_pct']}% | "
                   f"{o['edge_top_per_contract']:+.4f} | {struct} | {o.get('ts', '')} |")
    return out


def render(snaps: list, interval: float) -> str:
    last = snaps[-1]
    all_rf = [o for s in snaps for o in s["opportunities"] if is_risk_free(o)]
    manual = [o for s in snaps for o in s["opportunities"] if not is_risk_free(o)]
    uniq: dict = {}
    for o in all_rf:
        k = opp_key(o)
        if k not in uniq or o["profit"] > uniq[k]["profit"]:
            uniq[k] = o
    last_rf = [o for o in last["opportunities"] if is_risk_free(o)]
    L = ["# Kalshi structural arbitrage scan", ""]
    L.append(f"Snapshots: {len(snaps)} (interval {interval:g}s), first {snaps[0]['ts']}, last {last['ts']}.")
    L.append("Fees: pipeline.kalshi_total_cost (taker 0.07·C·P·(1−P)), one debit per price level (conservative). "
             "Asks derived from bids: YES ask = 1 − NO bid.")
    L.append("")
    L.append(f"**Summary (last snapshot): {len(last_rf)} risk-free opportunities, total locked profit "
             f"${locked_total(last['opportunities']):.2f} at available size.**")
    if len(snaps) > 1:
        L.append(f"**Across all snapshots: {len(uniq)} distinct risk-free opportunities; "
                 f"{sum(1 for s in snaps if any(is_risk_free(o) for o in s['opportunities']))}/{len(snaps)} snapshots had at least one.**")
    L.append("")
    L.append("## Coverage (last snapshot)")
    L.append(f"- events listed: {last['events']}, live markets: {last['markets']}, order books fetched: {last['books_fetched']}, took {last['seconds']}s")
    L.append("- events by structure: " + ", ".join(f"{k} {v}" for k, v in sorted(last["by_class"].items())))
    L.append("- multi-market candidates: " + str(last["candidates"]) + "; scanned with books: "
             + (", ".join(f"{k} {v}" for k, v in sorted(last["scanned_events"].items())) or "none"))
    L.append("- skipped: " + (", ".join(f"{k} {v}" for k, v in sorted(last["skipped"].items())) or "none"))
    L.append("- Kalshi categories: " + ", ".join(f"{k} {v}" for k, v in sorted(last["by_category"].items(), key=lambda t: -t[1])[:15]))
    L.append("")
    L.append("## Risk-free opportunities (best instance of each, all snapshots)")
    L.extend(_opp_table(sorted(uniq.values(), key=lambda o: -o["profit"])) if uniq else ["None found."])
    L.append("")
    if manual:
        L.append("## Needs manual rule check (exhaustiveness / exclusivity only assumed) — NOT counted as risk-free")
        mu: dict = {}
        for o in manual:
            mu.setdefault(opp_key(o), o)
        L.extend(_opp_table(list(mu.values())[:30]))
        for o in list(mu.values())[:30]:
            L.append(f"- {o['event_ticker']}: {o.get('structure_note', '')}")
        L.append("")
    L.append(f"## Near misses (last snapshot; top-of-book edge within {NEAR_MISS * 100:.0f}¢/contract of break-even, after fees)")
    nm = last["near_misses"]
    L.append(f"{last.get('near_miss_total', len(nm))} set/ladder near misses (showing up to 25); "
             f"{last.get('box_near_misses', 0)} single-market boxes within {NEAR_MISS * 100:.0f}¢ (not listed).")
    if nm:
        L.extend(_opp_table(nm[:25]))
    else:
        L.append("None.")
    if len(snaps) > 1:
        p = persistence(snaps, interval)
        L.append("")
        L.append("## Persistence")
        L.append(f"{p['with_opportunity']}/{p['snapshots']} snapshots contained a risk-free opportunity.")
        if p["rows"]:
            L.append("| opportunity | snapshots seen | longest consecutive | ≈ duration s | max profit | first | last |")
            L.append("|---|---|---|---|---|---|---|")
            for r in p["rows"][:30]:
                L.append(f"| {r['key']} | {r['snapshots']} | {r['longest_streak']} | {r['approx_longest_s']} | "
                         f"${r['max_profit']:.2f} | {r['first']} | {r['last']} |")
        L.append("")
        L.append("| snapshot | risk-free | locked $ | near misses | books |")
        L.append("|---|---|---|---|---|")
        for s in snaps:
            L.append(f"| {s['ts']} | {sum(1 for o in s['opportunities'] if is_risk_free(o))} | "
                     f"${locked_total(s['opportunities']):.2f} | {len(s['near_misses'])} | {s['books_fetched']} |")
    L.append("")
    L.append("## Cross-venue: Kalshi KXBTC15M vs Polymarket BTC up/down 15m — NOT risk-free (basis risk)")
    L.append("Kalshi settles on CF Benchmarks BRTI (60 s average, per repo notes); Polymarket settles on its own "
             "stated source (see description excerpt). Gaps below are top-of-book only; Polymarket fees are NOT "
             "included (unknown here).")
    cvs = [s.get("cross_venue") for s in snaps if s.get("cross_venue")]
    if not cvs:
        L.append("Not run.")
    else:
        cv = cvs[-1]
        L.append(f"Status: {cv.get('status')} {cv.get('note', '')}")
        for r in cv.get("rows", []):
            if r.get("status") != "ok":
                L.append(f"- {r.get('kalshi')} / {r.get('slug')}: {r.get('status')}")
                continue
            L.append(f"- {r['kalshi']} (strike {r.get('kalshi_strike')}) vs {r['slug']} — sources mentioned: "
                     f"{', '.join(r.get('polymarket_source_mentions') or []) or 'none found'}")
            for name in ("Kalshi YES(up) + Poly Down", "Kalshi NO(down) + Poly Up"):
                g = r.get(name)
                if g:
                    L.append(f"  - {name}: {g['kalshi_ask']:.4f} + {g['poly_ask']:.4f} → gross gap {g['gross_gap']:+.4f}, "
                             f"after Kalshi fee {g['gap_after_kalshi_fee']:+.4f} (qty {g['kalshi_qty']} / {g['poly_qty']:g})")
        best = [g for cv2 in cvs for r in cv2.get("rows", []) for n in ("Kalshi YES(up) + Poly Down", "Kalshi NO(down) + Poly Up")
                if isinstance(r.get(n), dict) for g in [r[n]]]
        if best:
            L.append(f"Best gap after Kalshi fee across snapshots: {max(g['gap_after_kalshi_fee'] for g in best):+.4f} $/contract.")
    L.append("")
    return "\n".join(L)


# --------------------------------------------------------------------------- CLI


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--interval", type=float, default=60.0, help="seconds between snapshot starts")
    ap.add_argument("--max-books", type=int, default=400, help="order books per snapshot")
    ap.add_argument("--max-pages", type=int, default=60, help="event listing pages (200 events each)")
    ap.add_argument("--max-event-markets", type=int, default=60)
    ap.add_argument("--prefilter", type=float, default=-0.10,
                    help="skip events whose listing-quote gross edge ($/unit) is below this")
    ap.add_argument("--fee-rate", type=float, default=FEE_RATE)
    ap.add_argument("--rps", type=float, default=8.0)
    ap.add_argument("--no-polymarket", action="store_true")
    ap.add_argument("--fixture", type=Path, help="offline: read events.json/books.json from this dir")
    ap.add_argument("--out", type=Path, default=Path("arb_scan.md"))
    ap.add_argument("--json", type=Path, default=Path("arb_scan.json"))
    a = ap.parse_args(argv)

    client = FixtureClient(a.fixture) if a.fixture else HttpClient(rps=a.rps)
    log = lambda s: print(s, file=sys.stderr, flush=True)  # noqa: E731
    snaps = []
    for i in range(max(1, a.repeat)):
        start = time.monotonic()
        try:
            s = snapshot(client, a.max_books, a.max_pages, a.max_event_markets, a.prefilter, a.fee_rate,
                         polymarket=not a.no_polymarket, log=log)
        except Exception as e:  # noqa: BLE001 - keep partial results of a long repeat run
            log(f"snapshot {i + 1} failed: {e}")
            if not snaps and i == a.repeat - 1:
                raise
            continue
        snaps.append(s)
        rf = [o for o in s["opportunities"] if is_risk_free(o)]
        log(f"[{s['ts']}] snapshot {i + 1}/{a.repeat}: events {s['events']}, books {s['books_fetched']}, "
            f"risk-free {len(rf)} (${locked_total(s['opportunities']):.2f}), near {len(s['near_misses'])}, {s['seconds']}s")
        if i < a.repeat - 1:
            rest = a.interval - (time.monotonic() - start)
            if rest > 0:
                time.sleep(rest)
    if not snaps:
        log("no snapshot succeeded")
        return 1
    report = render(snaps, a.interval)
    a.out.write_text(report)
    a.json.write_text(json.dumps(dict(
        generated=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        fee_rate=a.fee_rate,
        snapshots=[{k: v for k, v in s.items()} for s in snaps],
        persistence=persistence(snaps, a.interval),
    ), indent=1, default=str))
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
