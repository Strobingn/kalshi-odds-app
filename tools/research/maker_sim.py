#!/usr/bin/env python3
"""Maker (resting limit order) simulator for Kalshi 15-minute BTC markets.

Consumes the 1-second recordings (one gzip CSV per UTC day, header row,
empty field = missing; gzip may be multi-member / have a truncated tail):

  spot_YYYY-MM-DD.csv.gz    ts_ms,product,price
  book_YYYY-MM-DD.csv.gz    ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,
                            yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty
  trades_YYYY-MM-DD.csv.gz  ts_ms,ticker,yes_price,count,taker_side
  settle_YYYY-MM-DD.csv     ticker,close_ms,strike,result

Pre-registered grid (fixed before any data was seen; see docs/maker-research.md):

  price rule  join     post at the best bid on the chosen side (queue = its displayed qty)
              improve  post 1¢ above the best bid (queue 0), only if still below the ask
              fair     highest cent price P ≤ min(bid + 1¢, ask − 1¢) that clears the margin
  cancel T    15 / 30 / 60 s after posting, or 60 s before close, whichever first
  margin      0.02 / 0.04 (fair − P − maker_fee_per_contract(P) must exceed it)

Decision times every 30 s from 2:00 to 12:00 elapsed. Side = the side whose
edge (Φ(d2) fair from spot vs strike, σ from trailing 1-minute spot returns,
minus price minus maker fee) is larger. One $5 all-in order at a time per
market; once an order gets any fill the market is done (one position per
market, like tools/research/edge_search.py).

Fill models:
  conservative  Fill only from recorded trades on our side at or through our
                price (YES buy at P: taker_side == "no" and yes_price ≤ P;
                NO buy at Q: taker_side == "yes" and 1 − yes_price ≤ Q), strictly
                after the post time and no later than the cancel time. The
                displayed quantity at our price when we posted is ahead of us
                and must trade first; partial fills allowed. Queue never moves
                up from cancellations. Trades with no taker_side never fill us.
  optimistic    Full fill the first time the best ask on our side is ≤ P in a
                book snapshot, or any qualifying trade prints (queue ignored).

Fees use tools/backtest/pipeline.kalshi_total_cost / size_all_in with the
maker rate. The maker rate is NOT asserted here: pass --maker-fee from
Kalshi's current fee schedule. Results are always also shown at the taker
rate 0.07 and maker rates {0, 0.0175, 0.035} as sensitivity.

Python 3 stdlib only. Prints (and optionally writes) a Markdown report.
"""
from __future__ import annotations

import argparse
import bisect
import csv
import functools
import io
import math
import random
import sys
import zlib
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import (  # noqa: E402
    STAKE_USD,
    fee_per_contract,
    kalshi_total_cost,
    p_finish_above,
    realized_vol_bar_std,
    sigma_annual_from_bar_std,
    size_all_in,
)

TAKER_FEE = 0.07
SENSITIVITY_FEES = (0.0, 0.0175, 0.035, TAKER_FEE)
DEFAULT_MAKER_FEE = 0.0  # placeholder — set from Kalshi's current fee schedule

# --- pre-registered grid --------------------------------------------------------
PRICE_RULES = ("join", "improve", "fair")
CANCEL_SECS = (15, 30, 60)
MARGINS = (0.02, 0.04)
DECISION_ELAPSED_S = tuple(range(120, 721, 30))
CANCEL_BEFORE_CLOSE_MS = 60_000
TICK = 0.01
MIN_PRICE = 0.05
MAX_PRICE = 0.95
BOOK_STALE_MS = 5_000
SPOT_STALE_MS = 10_000
VOL_LOOKBACK_MIN = 30
MIN_VOL_POINTS = 10
MARKOUT_S = (5, 30, 60)
MIN_IS_FILLS = 20
MIN_DAYS_FOR_OOS = 6
MIN_OOS_DAYS_FOR_RULE = 5  # fewer OOS days: a 99% day-block CI is not meaningful
EPS = 1e-9


def _day(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def _f(x: str | None) -> float | None:
    if x is None:
        return None
    x = x.strip()
    if not x:
        return None
    try:
        v = float(x)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


# --- reader ---------------------------------------------------------------------

def _iter_rows(path: Path):
    """Yield dict rows from a (possibly multi-member, possibly truncated) gzip or plain CSV."""
    if not path.exists():
        return
    if path.suffix == ".gz":
        raw = path.read_bytes()
        chunks: list[bytes] = []
        pos = 0
        while pos < len(raw):
            d = zlib.decompressobj(16 + zlib.MAX_WBITS)
            try:
                chunks.append(d.decompress(raw[pos:]))
            except zlib.error:
                break  # corrupt tail: keep what we have
            if not d.eof:
                break  # truncated member (recorder still writing): keep partial
            used = len(raw) - pos - len(d.unused_data)
            if used <= 0:
                break
            pos += used
        text = b"".join(chunks).decode("utf-8", "replace")
    else:
        text = path.read_text()
    lines = text.splitlines()
    if lines and not text.endswith("\n"):
        lines = lines[:-1] if len(lines) > 1 else lines  # drop a partially written last line
    rdr = csv.reader(io.StringIO("\n".join(lines)))
    header = None
    for rec in rdr:
        if not rec:
            continue
        if header is None:
            header = [h.strip() for h in rec]
            continue
        if rec == header:
            continue  # a later gzip member may repeat the header
        if len(rec) != len(header):
            continue
        yield dict(zip(header, rec))


@dataclass
class Snap:
    ts: int
    yes_bid: float | None
    yes_bid_qty: float | None
    yes_ask: float | None
    yes_ask_qty: float | None
    no_bid: float | None
    no_bid_qty: float | None
    no_ask: float | None
    no_ask_qty: float | None

    def side(self, side: str) -> tuple[float | None, float | None, float | None, float | None]:
        """(bid, bid_qty, ask, ask_qty) for buying `side`. Ask falls back to 1 − opposite bid."""
        if side == "YES":
            ask = self.yes_ask if self.yes_ask is not None else (1.0 - self.no_bid if self.no_bid is not None else None)
            aq = self.yes_ask_qty if self.yes_ask is not None else self.no_bid_qty
            return self.yes_bid, self.yes_bid_qty, ask, aq
        ask = self.no_ask if self.no_ask is not None else (1.0 - self.yes_bid if self.yes_bid is not None else None)
        aq = self.no_ask_qty if self.no_ask is not None else self.yes_bid_qty
        return self.no_bid, self.no_bid_qty, ask, aq

    def mid(self, side: str) -> float | None:
        bid, _, ask, _ = self.side(side)
        if bid is None or ask is None:
            return None
        return 0.5 * (bid + ask)


@dataclass
class Trade:
    ts: int
    yes_price: float
    count: float
    taker_side: str  # "yes" | "no" | ""


@dataclass
class Day:
    spot_ts: list[int] = field(default_factory=list)
    spot_px: list[float] = field(default_factory=list)
    book: dict[str, list[Snap]] = field(default_factory=lambda: defaultdict(list))
    trades: dict[str, list[Trade]] = field(default_factory=lambda: defaultdict(list))
    meta: dict[str, tuple[float | None, int | None]] = field(default_factory=dict)  # ticker -> (strike, close_ms)
    n_book: int = 0
    n_trades: int = 0
    n_trades_no_side: int = 0


def load_day(d: Path, day: str, series: str) -> Day:
    out = Day()
    ser = series.upper()
    spot = []
    for r in _iter_rows(d / f"spot_{day}.csv.gz"):
        if ser not in (r.get("product") or "").upper():
            continue
        ts, px = _f(r.get("ts_ms")), _f(r.get("price"))
        if ts is not None and px is not None and px > 0:
            spot.append((int(ts), px))
    spot.sort()
    out.spot_ts = [t for t, _ in spot]
    out.spot_px = [p for _, p in spot]
    for r in _iter_rows(d / f"book_{day}.csv.gz"):
        tk = r.get("ticker") or ""
        ts = _f(r.get("ts_ms"))
        if ser not in tk.upper() or ts is None:
            continue
        g = lambda k: _f(r.get(k))  # noqa: E731
        out.book[tk].append(Snap(int(ts), g("yes_bid"), g("yes_bid_qty"), g("yes_ask"), g("yes_ask_qty"),
                                 g("no_bid"), g("no_bid_qty"), g("no_ask"), g("no_ask_qty")))
        cm = g("close_ms")
        out.meta.setdefault(tk, (g("strike"), int(cm) if cm is not None else None))
        out.n_book += 1
    for r in _iter_rows(d / f"trades_{day}.csv.gz"):
        tk = r.get("ticker") or ""
        ts, yp, n = _f(r.get("ts_ms")), _f(r.get("yes_price")), _f(r.get("count"))
        if ser not in tk.upper() or ts is None or yp is None or n is None or n <= 0:
            continue
        side = (r.get("taker_side") or "").strip().lower()
        out.trades[tk].append(Trade(int(ts), yp, n, side if side in ("yes", "no") else ""))
        out.n_trades += 1
        out.n_trades_no_side += side not in ("yes", "no")
    for lst in out.book.values():
        lst.sort(key=lambda s: s.ts)
    for lst in out.trades.values():
        lst.sort(key=lambda t: t.ts)
    return out


def load_settlements(d: Path, series: str) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for p in sorted(d.glob("settle_*.csv")) + sorted(d.glob("settle_*.csv.gz")):
        for r in _iter_rows(p):
            tk = r.get("ticker") or ""
            res = (r.get("result") or "").strip().lower()
            cm = _f(r.get("close_ms"))
            if series.upper() not in tk.upper() or res not in ("yes", "no") or cm is None:
                continue
            out[tk] = {"close_ms": int(cm), "strike": _f(r.get("strike")), "result": res}
    return out


def recording_days(d: Path) -> list[str]:
    days = set()
    for p in d.glob("book_*.csv.gz"):
        days.add(p.name[len("book_"):len("book_") + 10])
    return sorted(days)


# --- market view (may span two UTC days) ---------------------------------------

@dataclass
class Market:
    ticker: str
    close_ms: int
    strike: float
    result: str
    book: list[Snap]
    trades: list[Trade]
    spot_ts: list[int]
    spot_px: list[float]

    def __post_init__(self):
        self._book_ts = [s.ts for s in self.book]
        self._trade_ts = [t.ts for t in self.trades]

    @property
    def open_ms(self) -> int:
        return self.close_ms - 900_000

    @property
    def day(self) -> str:
        """UTC day the market opens (a 00:00 close belongs to the previous day)."""
        return _day(self.open_ms)

    def snap_at(self, t: int, stale: int = BOOK_STALE_MS) -> Snap | None:
        i = bisect.bisect_right(self._book_ts, t) - 1
        if i < 0 or t - self.book[i].ts > stale:
            return None
        return self.book[i]

    def spot_at(self, t: int, stale: int = SPOT_STALE_MS) -> float | None:
        i = bisect.bisect_right(self.spot_ts, t) - 1
        if i < 0 or t - self.spot_ts[i] > stale:
            return None
        return self.spot_px[i]

    def fair_yes(self, t: int) -> float | None:
        spot = self.spot_at(t)
        if spot is None or not self.strike:
            return None
        closes = [px for i in range(VOL_LOOKBACK_MIN, -1, -1) if (px := self.spot_at(t - i * 60_000)) is not None]
        if len(closes) < MIN_VOL_POINTS:
            return None
        sig = realized_vol_bar_std(closes)
        if sig is None:
            return None
        return p_finish_above(spot, self.strike, (self.close_ms - t) / 1000.0, sigma_annual_from_bar_std(sig))


# --- core mechanics (unit-tested) -----------------------------------------------

def floor_cent(x: float) -> float:
    return round(math.floor(x / TICK + EPS) * TICK, 4)


@functools.lru_cache(maxsize=None)
def maker_fee_pc(price: float, rate: float) -> float:
    """Per-contract fee amortized over a $5 ticket (pipeline.fee_per_contract) at `rate`."""
    return fee_per_contract(price, rate, STAKE_USD)


@functools.lru_cache(maxsize=None)
def contracts_for(price: float, rate: float) -> int:
    """Contracts a $5 all-in ticket buys at `price` (pipeline.size_all_in)."""
    return size_all_in(price, STAKE_USD, rate)[0]


def fill_cost(contracts: int, price: float, rate: float) -> float:
    """All-in debit for `contracts` filled at `price` (pipeline.kalshi_total_cost)."""
    return kalshi_total_cost(contracts, price, rate)


def settle_pnl(contracts: int, price: float, side: str, result: str, rate: float) -> tuple[float, float, bool]:
    """(pnl, cost, won) for a position held to settlement."""
    cost = fill_cost(contracts, price, rate)
    won = (result == "yes") == (side == "YES")
    return (contracts * 1.0 if won else 0.0) - cost, cost, won


def cancel_time(post_ms: int, cancel_s: int, close_ms: int) -> int:
    return min(post_ms + cancel_s * 1000, close_ms - CANCEL_BEFORE_CLOSE_MS)


def qualifies(tr: Trade, side: str, price: float) -> bool:
    """Trade is a taker selling into our resting bid at or through our price."""
    if side == "YES":
        return tr.taker_side == "no" and tr.yes_price <= price + EPS
    return tr.taker_side == "yes" and (1.0 - tr.yes_price) <= price + EPS


def conservative_fill(side: str, price: float, contracts: int, queue_ahead: float,
                      trades: list[Trade], post_ms: int, cancel_ms: int,
                      trade_ts: list[int] | None = None) -> tuple[int, int | None]:
    """(filled contracts, first fill ts). Trades in (post_ms, cancel_ms] only."""
    filled, first = 0, None
    q = max(0.0, queue_ahead)
    i = bisect.bisect_right(trade_ts if trade_ts is not None else [t.ts for t in trades], post_ms)
    while i < len(trades) and trades[i].ts <= cancel_ms and filled < contracts:
        tr = trades[i]
        i += 1
        if not qualifies(tr, side, price):
            continue
        v = tr.count
        eat = min(v, q)
        q -= eat
        v -= eat
        if v > EPS:
            take = min(int(math.floor(v + EPS)), contracts - filled)
            if take > 0:
                filled += take
                first = first if first is not None else tr.ts
    return filled, first


def optimistic_fill(side: str, price: float, contracts: int, book: list[Snap],
                    trades: list[Trade], post_ms: int, cancel_ms: int,
                    book_ts: list[int] | None = None, trade_ts: list[int] | None = None) -> tuple[int, int | None]:
    """Full fill at the first ask touch (ask ≤ P) or any qualifying trade in (post, cancel]."""
    t_book = None
    i0 = bisect.bisect_right(book_ts if book_ts is not None else [s.ts for s in book], post_ms)
    for s in book[i0:]:
        if s.ts > cancel_ms:
            break
        _, _, ask, _ = s.side(side)
        if ask is not None and ask <= price + EPS:
            t_book = s.ts
            break
    t_tr = None
    j = bisect.bisect_right(trade_ts if trade_ts is not None else [t.ts for t in trades], post_ms)
    while j < len(trades) and trades[j].ts <= cancel_ms:
        if qualifies(trades[j], side, price):
            t_tr = trades[j].ts
            break
        j += 1
    ts = [x for x in (t_book, t_tr) if x is not None]
    return (contracts, min(ts)) if ts else (0, None)


@dataclass(frozen=True)
class Config:
    rule: str
    cancel_s: int
    margin: float

    def label(self) -> str:
        return f"{self.rule}/T{self.cancel_s}/m{self.margin:.2f}"


GRID = [Config(r, t, m) for r in PRICE_RULES for t in CANCEL_SECS for m in MARGINS]


def quote(snap: Snap, side: str, fair_side: float, cfg: Config, rate: float) -> tuple[float, float, float] | None:
    """(price, queue_ahead, edge) for a resting buy on `side`, or None if the rule/margin says no."""
    bid, bid_qty, ask, _ = snap.side(side)
    if bid is None or ask is None or bid_qty is None:
        return None
    bid, ask = round(bid, 4), round(ask, 4)
    if cfg.rule == "join":
        p, q = bid, bid_qty
    elif cfg.rule == "improve":
        p, q = round(bid + TICK, 4), 0.0
    elif cfg.rule == "fair":
        cap = min(round(bid + TICK, 4), round(ask - TICK, 4))
        p = floor_cent(min(cap, fair_side - cfg.margin))
        while p >= MIN_PRICE and fair_side - p - maker_fee_pc(p, rate) <= cfg.margin:
            p = round(p - TICK, 4)
        q = 0.0 if p > bid + EPS else bid_qty  # below the bid: depth unknown, displayed best-bid qty as proxy
    else:
        raise ValueError(cfg.rule)
    if p >= ask - EPS or p < MIN_PRICE - EPS or p > MAX_PRICE + EPS:
        return None
    edge = fair_side - p - maker_fee_pc(p, rate)
    if edge <= cfg.margin:
        return None
    return p, q, edge


@dataclass
class Fill:
    ticker: str
    day: str
    side: str
    price: float
    posted: int
    filled: int
    post_ms: int
    fill_ms: int
    cost: float
    pnl: float
    won: bool
    d_post: dict[int, float | None]    # side mid(fill + h) − side mid(post)
    markout: dict[int, float | None]   # side mid(fill + h) − fill price


@dataclass
class Result:
    fills: list[Fill] = field(default_factory=list)
    posted: dict[str, int] = field(default_factory=lambda: defaultdict(int))        # day -> orders posted
    posted_ct: dict[str, int] = field(default_factory=lambda: defaultdict(int))     # day -> contracts posted
    filled_orders: dict[str, int] = field(default_factory=lambda: defaultdict(int))


def simulate_market(m: Market, cfg: Config, rate: float, model: str, res: Result,
                    fairs: dict[int, float | None] | None = None) -> None:
    day = m.day
    busy_until = -1
    for e in DECISION_ELAPSED_S:
        t = m.open_ms + e * 1000
        if t >= m.close_ms - CANCEL_BEFORE_CLOSE_MS or t <= busy_until:
            continue
        snap = m.snap_at(t)
        fy = fairs[t] if fairs is not None else m.fair_yes(t)
        if snap is None or fy is None:
            continue
        best = None
        for side, fs in (("YES", fy), ("NO", 1.0 - fy)):
            qt = quote(snap, side, fs, cfg, rate)
            if qt is not None and (best is None or qt[2] > best[1][2]):
                best = (side, qt)
        if best is None:
            continue
        side, (p, q, _) = best
        c = contracts_for(p, rate)
        if c <= 0:
            continue
        cancel = cancel_time(t, cfg.cancel_s, m.close_ms)
        busy_until = cancel
        res.posted[day] += 1
        res.posted_ct[day] += c
        if model == "conservative":
            f, fts = conservative_fill(side, p, c, q, m.trades, t, cancel, m._trade_ts)
        else:
            f, fts = optimistic_fill(side, p, c, m.book, m.trades, t, cancel, m._book_ts, m._trade_ts)
        if f <= 0:
            continue
        pnl, cost, won = settle_pnl(f, p, side, m.result, rate)
        mid0 = snap.mid(side)
        d_post, mk = {}, {}
        for h in MARKOUT_S:
            s2 = m.snap_at(fts + h * 1000)
            mh = s2.mid(side) if s2 is not None else None
            d_post[h] = (mh - mid0) if mh is not None and mid0 is not None else None
            mk[h] = (mh - p) if mh is not None else None
        res.fills.append(Fill(m.ticker, day, side, p, c, f, t, fts, cost, pnl, won, d_post, mk))
        res.filled_orders[day] += 1
        return  # one position per market


@dataclass
class TakerBet:
    day: str
    pnl: float
    cost: float
    won: bool
    price: float


def taker_reference(m: Market, margin: float, fairs: dict[int, float | None]) -> TakerBet | None:
    """Same fair/side logic, but take the ask at the taker rate 0.07 (depth-capped)."""
    for e in DECISION_ELAPSED_S:
        t = m.open_ms + e * 1000
        snap = m.snap_at(t)
        fy = fairs.get(t)
        if snap is None or fy is None:
            continue
        best = None
        for side, fs in (("YES", fy), ("NO", 1.0 - fy)):
            _, _, ask, aq = snap.side(side)
            if ask is None or not (MIN_PRICE <= ask <= MAX_PRICE):
                continue
            ev = fs - ask - fee_per_contract(ask, TAKER_FEE)
            if ev > margin and (best is None or ev > best[2]):
                best = (side, ask, ev, aq)
        if best is None:
            continue
        side, ask, _, aq = best
        c, _, _ = size_all_in(ask, STAKE_USD, TAKER_FEE)
        if aq is not None:
            c = min(c, int(aq))
        if c <= 0:
            continue
        pnl, cost, won = settle_pnl(c, ask, side, m.result, TAKER_FEE)
        return TakerBet(m.day, pnl, cost, won, ask)
    return None


# --- stats ------------------------------------------------------------------------

def day_block_ci(by_day: dict[str, tuple[float, float]], level: float, iters: int, seed: int) -> tuple[float, float]:
    """CI of sum(num)/sum(den) with whole days resampled. by_day: day -> (num, den)."""
    days = [d for d in by_day if by_day[d][1] > 0]
    if len(days) < 2 or iters <= 0:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    vals = []
    for _ in range(iters):
        n = dn = 0.0
        for _ in days:
            a, b = by_day[days[rng.randrange(len(days))]]
            n += a
            dn += b
        if dn > 0:
            vals.append(n / dn)
    if not vals:
        return float("nan"), float("nan")
    vals.sort()
    a = (1.0 - level) / 2.0
    return vals[int(a * (len(vals) - 1))], vals[int((1.0 - a) * (len(vals) - 1))]


def _mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def summarize(res: Result, days: set[str] | None, iters: int, seed: int) -> dict:
    fills = [f for f in res.fills if days is None or f.day in days]
    posted = sum(v for d, v in res.posted.items() if days is None or d in days)
    posted_ct = sum(v for d, v in res.posted_ct.items() if days is None or d in days)
    n = len(fills)
    s = {"posted": posted, "fills": n, "fill_rate": n / posted if posted else None,
         "ct_rate": sum(f.filled for f in fills) / posted_ct if posted_ct else None}
    if not n:
        return s
    pnl = sum(f.pnl for f in fills)
    cost = sum(f.cost for f in fills)
    per_fill: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    per_dollar: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    for f in fills:
        per_fill[f.day][0] += f.pnl
        per_fill[f.day][1] += 1
        per_dollar[f.day][0] += f.pnl
        per_dollar[f.day][1] += f.cost
    pf = {d: tuple(v) for d, v in per_fill.items()}
    pd = {d: tuple(v) for d, v in per_dollar.items()}
    s.update(
        win=sum(f.won for f in fills) / n, price=sum(f.price for f in fills) / n, pnl=pnl, per=pnl / n,
        per_dollar=pnl / cost if cost else None, days=len(pf),
        ci95=day_block_ci(pf, 0.95, iters, seed), ci99=day_block_ci(pf, 0.99, iters, seed),
        ci99_dollar=day_block_ci(pd, 0.99, iters, seed),
        d_post={h: _mean([f.d_post[h] for f in fills]) for h in MARKOUT_S},
        markout={h: _mean([f.markout[h] for f in fills]) for h in MARKOUT_S},
    )
    return s


# --- driver --------------------------------------------------------------------------

def build_markets(d: Path, series: str, stats: dict):
    """Yield Market objects, loading at most a few days of recordings at a time."""
    settle = load_settlements(d, series)
    rec_days = recording_days(d)
    cache: dict[str, Day] = {}

    def get(day: str) -> Day:
        if day not in cache:
            if len(cache) >= 3:
                cache.pop(min(cache))
            cache[day] = load_day(d, day, series)
            stats["book_rows"] += cache[day].n_book
            stats["trade_rows"] += cache[day].n_trades
            stats["trades_no_side"] += cache[day].n_trades_no_side
            stats["days_loaded"].add(day)
        return cache[day]

    stats["settled"] = len(settle)
    stats["recording_days"] = rec_days
    for tk, info in sorted(settle.items(), key=lambda kv: kv[1]["close_ms"]):
        close_ms = info["close_ms"]
        days_needed = sorted({_day(close_ms - 900_000 - VOL_LOOKBACK_MIN * 60_000 - 60_000),
                              _day(close_ms - 900_000), _day(close_ms)})
        if not any(dd in rec_days for dd in days_needed):
            continue
        book: list[Snap] = []
        trades: list[Trade] = []
        sts: list[int] = []
        spx: list[float] = []
        strike = info.get("strike")
        for dd in days_needed:
            if dd not in rec_days:
                continue
            D = get(dd)
            book += D.book.get(tk, [])
            trades += D.trades.get(tk, [])
            sts += D.spot_ts
            spx += D.spot_px
            if strike is None and tk in D.meta:
                strike = D.meta[tk][0]
        if not book or strike is None:
            continue
        book.sort(key=lambda s: s.ts)
        trades.sort(key=lambda t: t.ts)
        order = sorted(range(len(sts)), key=sts.__getitem__)
        m = Market(tk, close_ms, strike, info["result"], book, trades,
                   [sts[i] for i in order], [spx[i] for i in order])
        stats["markets"] += 1
        yield m


def run(d: Path, maker_fee: float, series: str = "BTC", grid: list[Config] | None = None) -> tuple[dict, dict, dict]:
    """Returns (results[(cfg, rate, model)] -> Result, taker[margin] -> list[TakerBet], stats)."""
    grid = grid or GRID
    rates = sorted({maker_fee, *SENSITIVITY_FEES})
    stats = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0, "days_loaded": set()}
    results: dict = {(c, r, mdl): Result() for c in grid for r in rates for mdl in ("conservative", "optimistic")}
    taker: dict[float, list[TakerBet]] = {mg: [] for mg in MARGINS}
    for m in build_markets(d, series, stats):
        fairs = {m.open_ms + e * 1000: m.fair_yes(m.open_ms + e * 1000) for e in DECISION_ELAPSED_S}
        for (c, r, mdl), res in results.items():
            simulate_market(m, c, r, mdl, res, fairs)
        for mg in MARGINS:
            b = taker_reference(m, mg, fairs)
            if b is not None:
                taker[mg].append(b)
    return results, taker, stats


# --- report --------------------------------------------------------------------------

def _c(x, fmt="{:+.3f}"):
    return "—" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def _ci(ci):
    return "—" if ci is None or math.isnan(ci[0]) else f"[{ci[0]:+.3f}, {ci[1]:+.3f}]"


def row(label: str, s: dict) -> str:
    if not s.get("fills"):
        return (f"| {label} | {s.get('posted', 0)} | 0 | {_c(s.get('fill_rate'), '{:.1%}')} | — | — | — | — | — | — | — | — | — | — |")
    dp, mk = s["d_post"], s["markout"]
    ok = " **excl 0**" if s["ci99"][0] > 0 else ""
    return (
        f"| {label} | {s['posted']} | {s['fills']} | {s['fill_rate']:.1%} | {s['ct_rate']:.1%} | {s['win']:.1%} | "
        f"{s['price'] * 100:.1f}¢ | {s['pnl']:+.2f} | {s['per']:+.3f} | {_c(s['per_dollar'])} | "
        f"{_ci(s['ci95'])} | {_ci(s['ci99'])}{ok} | "
        f"{_c(dp[5])} / {_c(dp[30])} / {_c(dp[60])} | {_c(mk[5])} / {_c(mk[60])} |"
    )


HEAD = [
    "| Config | posted | fills | fill rate | ct fill rate | win% | avg P | P&L $ | $/fill | P&L per $ risked | 95% CI $/fill | 99% CI $/fill | Δmid vs post +5/+30/+60 s | markout vs P +5/+60 s |",
    "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|",
]


def pick_is(results: dict, rate: float, model: str, is_days: set[str], iters: int, seed: int) -> tuple[Config | None, dict]:
    best, best_s = None, {}
    for c in GRID:
        s = summarize(results[(c, rate, model)], is_days, iters, seed)
        if s.get("fills", 0) < MIN_IS_FILLS:
            continue
        if best is None or s["pnl"] > best_s["pnl"]:
            best, best_s = c, s
    return best, best_s


def report(results: dict, taker: dict, stats: dict, maker_fee: float, fee_is_default: bool,
           iters: int = 2000, seed: int = 11) -> str:
    rates = sorted({maker_fee, *SENSITIVITY_FEES})
    days = sorted({f.day for res in results.values() for f in res.fills} |
                  {d for res in results.values() for d in res.posted})
    L = ["# Maker (resting limit order) simulation — Kalshi 15m BTC", ""]
    fee_lbl = f"{maker_fee:g}" + (" (DEFAULT placeholder — not verified; set from Kalshi's current fee schedule)"
                                  if fee_is_default else " (as passed on --maker-fee; verify against Kalshi's current fee schedule)")
    ns = stats["trades_no_side"]
    L += [
        f"Maker fee rate: **{fee_lbl}**. Sensitivity rates: {', '.join(f'{r:g}' for r in rates)} (0.07 = taker rate).",
        "",
        f"Recording days: {len(stats['recording_days'])} · settled markets simulated: {stats['markets']} · "
        f"days with orders: {len(days)} · book rows: {stats['book_rows']} · trades: {stats['trade_rows']} "
        f"({ns} without taker_side, {ns / stats['trade_rows']:.1%} — these never fill the conservative model)"
        if stats["trade_rows"] else
        f"Recording days: {len(stats['recording_days'])} · settled markets simulated: {stats['markets']} · no trades recorded",
        "",
        "One $5 all-in maker order at a time per market, first fill ends the market, held to settlement. "
        "Decision every 30 s from 2:00–12:00 elapsed; cancel after T s or 60 s before close. "
        "Δmid = side mid after fill − side mid at post (negative = adverse selection). "
        "CIs: day-block bootstrap of $ per filled order.",
        "",
    ]
    oos_mode = len(days) >= MIN_DAYS_FOR_OOS
    if oos_mode:
        n_oos = max(2, round(len(days) / 3))
        is_days, oos_days = set(days[:-n_oos]), set(days[-n_oos:])
        L += [f"**IS/OOS split by day**: IS {days[0]} → {days[-n_oos - 1]} ({len(is_days)} d), "
              f"OOS {days[-n_oos]} → {days[-1]} ({len(oos_days)} d). Config picked on IS P&L (≥ {MIN_IS_FILLS} fills) "
              "at the --maker-fee rate, separately per fill model.", ""]
    else:
        is_days, oos_days = set(days), set()
        L += [f"**EXPLORATORY** — only {len(days)} day(s) of data (< {MIN_DAYS_FOR_OOS}): no IS/OOS split, "
              "no config selection, no claims. Collect ≥ 2–4 weeks of recordings before reading anything into this.", ""]

    for model in ("conservative", "optimistic"):
        L += [f"## {model.capitalize()} fill model — full grid, {'IS days' if oos_mode else 'all days'}, maker fee {maker_fee:g}", ""]
        L += HEAD
        for c in GRID:
            L.append(row(c.label(), summarize(results[(c, maker_fee, model)], is_days, iters, seed)))
        L.append("")
        if oos_mode:
            best, bs = pick_is(results, maker_fee, model, is_days, iters, seed)
            L += [f"### {model.capitalize()}: IS-picked config on OOS days", ""]
            if best is None:
                L += [f"No config reached {MIN_IS_FILLS} IS fills — nothing to evaluate.", ""]
            else:
                L += [f"Picked **{best.label()}** (IS P&L {bs['pnl']:+.2f} on {bs['fills']} fills).", ""] + HEAD
                for r in rates:
                    tag = " (maker fee)" if r == maker_fee else (" (taker rate)" if r == TAKER_FEE else "")
                    L.append(row(f"{best.label()} @ fee {r:g}{tag}", summarize(results[(best, r, model)], oos_days, iters, seed)))
                L.append("")
                if model == "conservative":
                    s = summarize(results[(best, maker_fee, model)], oos_days, iters, seed)
                    passed = bool(s.get("fills")) and s["ci99"][0] > 0
                    verdict = ("NOT EVALUABLE — --maker-fee was not given (unverified default)" if fee_is_default
                               else f"NOT EVALUABLE — only {len(oos_days)} OOS days (< {MIN_OOS_DAYS_FOR_RULE}); "
                               "collect more recordings" if len(oos_days) < MIN_OOS_DAYS_FOR_RULE
                               else "PASSES (confirm the rate is Kalshi's current maker fee before paper trading)"
                               if passed else "does not pass")
                    L += [f"**Decision rule** (conservative, OOS, 99% day-block CI excludes 0 at the maker fee): {verdict}.", ""]
        L += [f"### {model.capitalize()}: fee sensitivity, P&L $ (fills) per config, {'IS days' if oos_mode else 'all days'}", "",
              "| Config | " + " | ".join(f"fee {r:g}" for r in rates) + " |",
              "|---|" + "---:|" * len(rates)]
        for c in GRID:
            cells = []
            for r in rates:
                s = summarize(results[(c, r, model)], is_days, 0, seed)
                cells.append(f"{s['pnl']:+.2f} ({s['fills']})" if s.get("fills") else "— (0)")
            L.append(f"| {c.label()} | " + " | ".join(cells) + " |")
        L.append("")

    # The app's live rule (CentBetterRule / "REST 1¢ BETTER": bid + 1¢, margin
    # 0.02, cancel after 30 s) is fixed in advance, so it is scored on every
    # day and on the held-out days without any selection.
    app_cfg = Config("improve", 30, 0.02)
    if any(k[0] == app_cfg for k in results):
        L += ["## Pre-registered: the app's 1¢-better-bid rule (improve/T30/m0.02), maker fee "
              f"{maker_fee:g}", ""] + HEAD
        for model in ("conservative", "optimistic"):
            for lbl, ds in (("all days", None),) + ((("OOS days", oos_days),) if oos_mode else ()):
                L.append(row(f"{model} · {lbl}", summarize(results[(app_cfg, maker_fee, model)], ds, iters, seed)))
        L.append("")

    L += ["## Taker reference (same fair/side logic, take the ask at fee 0.07, capped at displayed ask qty)", "",
          "| Margin | days | bets | win% | avg ask | P&L $ | $/bet | 99% CI $/bet |", "|---|---:|---:|---:|---:|---:|---:|---|"]
    for mg, bets in taker.items():
        for lbl, ds in (("all", None),) + ((("OOS", oos_days),) if oos_mode else ()):
            bb = [b for b in bets if ds is None or b.day in ds]
            if not bb:
                L.append(f"| {mg:.2f} ({lbl}) | 0 | 0 | — | — | — | — | — |")
                continue
            by: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
            for b in bb:
                by[b.day][0] += b.pnl
                by[b.day][1] += 1
            pnl = sum(b.pnl for b in bb)
            ci = day_block_ci({d: tuple(v) for d, v in by.items()}, 0.99, iters, seed)
            L.append(f"| {mg:.2f} ({lbl}) | {len(by)} | {len(bb)} | {sum(b.won for b in bb) / len(bb):.1%} | "
                     f"{sum(b.price for b in bb) / len(bb) * 100:.1f}¢ | {pnl:+.2f} | {pnl / len(bb):+.3f} | {_ci(ci)} |")
    L += ["",
          "Caveats: queue position ignores cancels ahead of us (pessimistic) and other orders joining ahead via "
          "hidden priority (none on Kalshi); below-bid posts use the best-bid qty as a queue proxy (true depth "
          "unknown from top-of-book recordings); fees computed on the filled total (Kalshi may round per fill); "
          "settlement uses the recorded result; Φ(d2) fair uses Coinbase spot, not the settlement index."]
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dir", required=True, help="directory with spot_/book_/trades_/settle_ recordings")
    ap.add_argument("--maker-fee", type=float, default=None,
                    help=f"maker fee rate (default {DEFAULT_MAKER_FEE} = placeholder; set from Kalshi's current fee schedule)")
    ap.add_argument("--out", default=None, help="write the Markdown report here")
    ap.add_argument("--series", default="BTC", help="ticker/product substring filter (default BTC)")
    ap.add_argument("--iters", type=int, default=2000, help="bootstrap iterations")
    args = ap.parse_args(argv)
    fee_default = args.maker_fee is None
    maker_fee = DEFAULT_MAKER_FEE if fee_default else args.maker_fee
    if not (0.0 <= maker_fee <= 0.25):
        ap.error("--maker-fee must be in [0, 0.25]")
    d = Path(args.dir)
    if not d.is_dir():
        ap.error(f"--dir {d} is not a directory")
    results, taker, stats = run(d, maker_fee, args.series)
    text = report(results, taker, stats, maker_fee, fee_default, iters=args.iters)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
