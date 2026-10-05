#!/usr/bin/env python3
"""Endgame maker study: resting bids in the last 90 s of Kalshi 15m crypto markets.

KXBTC15M / KXETH15M / KXSOL15M settle YES iff the simple average of the CF
Benchmarks index over the final 60 s is >= the strike (ties YES; strike = the
previous window's 60 s average). Inside the final minute part of that average
is already locked in, so the settlement-aware fair (pipeline.p_settle_at_least,
the Python mirror of DigitalOptionFairValue.pSettleAtLeast) becomes sharp.
Taking the ask there was tested before and lost on real books. This study asks
the maker question instead: does a RESTING bid on the side that fair favours,
posted in the last 90/60/30 s, get filled by late sellers below fair -- and
does it make money once adverse selection (who sells to us that late?) is
counted?

Input: 1-second recordings (tools/research/cloud_recorder.py, format in
recordings.py), loaded with maker_sim.build_markets. Read-only research: no
orders, no network.

PRE-REGISTERED DESIGN (fixed before looking at any endgame result)
------------------------------------------------------------------
Fair (settlement-aware), at time t with s = seconds to close:
  * spot = Coinbase print (maker_sim.Market.spot_at, <= 10 s stale);
  * sigma = 1-minute log-return std over the trailing 30 min (as maker_sim),
    annualised with pipeline.sigma_annual_from_bar_std;
  * basis b = ln(strike) - mean ln(Coinbase) over the 60 s before the market
    opened (the strike IS the CF 60 s average of that window, so this is the
    CF-vs-Coinbase basis known at the open, no look-ahead). Needs >= 30 of 60
    seconds and |b| <= 50 bp, else b = 0 ("raw"). Spot and the observed
    partial average are shifted by b;
  * observed partial average (s < 60): mean of ln(spot)+b on the 1 s grid from
    close-60 s to t (needs >= half the elapsed seconds);
  * index noise = pipeline.index_noise_log(coin) (settlement-study floors:
    BTC 0.5e-4, ETH 0.9e-4, SOL 1.1e-4).
  fair_yes = p_settle_at_least(spot*e^b, strike, s, sigma, observed, noise).

Maker grid (12 configs, every one reported -- no selection):
  start    post once at 90 / 60 / 30 s before close (one order per market)
  rule     join     = at the favoured side's best bid (queue = displayed qty)
           improve  = 1c above that bid (queue 0), only if still below the ask
  margin   0.03 / 0.06: fair_side - P - maker_fee_per_contract(P) must exceed it
  side     the side with fair >= 0.5 (the side fair favours)
  cancel   5 s before close; fills only from recorded trades on our side at or
           through P in (post, cancel], queue ahead must trade first
           (maker_sim.conservative_fill; partial fills allowed)
  size     $5 all-in (pipeline.size_all_in), P in [0.03, 0.97], held to settlement
  fees     maker rate 0 and 0.0175 (Kalshi's actual maker fee is NOT asserted)
Reported per coin and pooled: posted, fills, fill rate, win rate of fills vs
win rate of all posts (the gap is adverse selection), avg P, P&L, $/fill,
P&L per $ risked, day-block bootstrap 95/99% CIs of $/fill, and markouts:
  fair@fill - P, YES/NO mid 5 s after fill - P, fair@fill - fair@post,
  settlement payout - P.
PRIMARY hypothesis (the only one a "pass" can come from): pooled coins,
start 60 s, improve, margin 0.03, maker fee 0.0175. Pass = >= 6 days, >= 30
fills, and the 99% day-block CI of $/fill excludes 0 above. Everything else
is secondary (36 coin x config cells per fee: expect some CI exclusions by
chance). Fewer than 6 days of data => EXPLORATORY, no claims.

"Both sides overpriced near 50/50" check (pre-registered):
  at 30 s and 15 s before close, markets with basis-adjusted fair_yes in
  [0.35, 0.65] and a two-sided book. Reports sum of asks - 1 against the
  spread (on Kalshi's single book YES ask = 1 - NO bid, so the recorder's
  asks make sum-of-asks - 1 == spread by construction: the literal check is
  an identity and is shown only as a data sanity check), then the
  substantive versions with day-block 99% CIs: E[outcome - bid] and
  E[outcome - ask] per side (unconditionally the two bid edges sum to the
  mean spread, so both cannot be negative), YES-mid and fair calibration,
  and the conditional-on-fill version: join the bid on BOTH sides at 30 s,
  cancel at 5 s, conservative fills, $/fill at fees 0 and 0.0175.

Python 3 stdlib only. Markdown report to stdout (and --out).
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

import maker_sim as ms  # noqa: E402
from pipeline import (  # noqa: E402
    SETTLE_WINDOW_SECONDS,
    index_noise_log,
    p_settle_at_least,
    realized_vol_bar_std,
    sigma_annual_from_bar_std,
)

# --- pre-registered constants ---------------------------------------------------
COINS = ("BTC", "ETH", "SOL")
STARTS_S = (90, 60, 30)
RULES = ("join", "improve")
MARGINS = (0.03, 0.06)
RATES = (0.0, 0.0175)
CANCEL_BEFORE_CLOSE_MS = 5_000
MIN_PRICE, MAX_PRICE = 0.03, 0.97
TICK = 0.01
EPS = 1e-9
MAX_BASIS = 0.005
BASIS_MIN_POINTS = 30
GRID_STALE_MS = 3_000
MARKOUT_MID_S = 5
NEAR_BAND = (0.35, 0.65)
NEAR_TIMES_S = (30, 15)
NEAR_MAKER_START_S = 30
MIN_DAYS = 6
PRIMARY_MIN_FILLS = 30


@dataclass(frozen=True)
class Cfg:
    start_s: int
    rule: str
    margin: float

    def label(self) -> str:
        return f"T-{self.start_s}s/{self.rule}/m{self.margin:.2f}"


GRID = [Cfg(s, r, m) for s in STARTS_S for r in RULES for m in MARGINS]
PRIMARY = (Cfg(60, "improve", 0.03), 0.0175)


# --- fair --------------------------------------------------------------------------

def coin_of(ticker: str) -> str:
    t = ticker.upper()
    for c in COINS:
        if c in t:
            return c
    return ""


def _grid_logs(m: ms.Market, start_ms: int, end_ms: int) -> list[float]:
    """ln(spot) on the 1 s grid start..end (inclusive), skipping stale seconds."""
    out = []
    u = start_ms
    while u <= end_ms:
        px = m.spot_at(u, GRID_STALE_MS)
        if px is not None and px > 0:
            out.append(math.log(px))
        u += 1000
    return out


def estimate_basis(m: ms.Market) -> float | None:
    """ln(strike) - mean ln(Coinbase) over the 60 s before the open (CF-vs-Coinbase basis)."""
    if not m.strike or m.strike <= 0:
        return None
    logs = _grid_logs(m, m.open_ms - 60_000, m.open_ms - 1000)
    if len(logs) < BASIS_MIN_POINTS:
        return None
    b = math.log(m.strike) - sum(logs) / len(logs)
    return b if abs(b) <= MAX_BASIS else None


def sigma_at(m: ms.Market, t: int) -> float | None:
    closes = [px for i in range(ms.VOL_LOOKBACK_MIN, -1, -1)
              if (px := m.spot_at(t - i * 60_000)) is not None]
    if len(closes) < ms.MIN_VOL_POINTS:
        return None
    sd = realized_vol_bar_std(closes)
    return sigma_annual_from_bar_std(sd) if sd is not None else None


def fair_yes_at(m: ms.Market, t: int, coin: str, basis: float | None,
                sigma: float | None = None) -> float | None:
    """Settlement-aware P(YES) at time t (basis-shifted Coinbase as the CF proxy)."""
    s = (m.close_ms - t) / 1000.0
    spot = m.spot_at(t)
    if spot is None or not m.strike or s < 0:
        return None
    sig = sigma if sigma is not None else sigma_at(m, t)
    if sig is None:
        return None
    b = basis or 0.0
    obs = None
    if s < SETTLE_WINDOW_SECONDS:
        w0 = m.close_ms - int(SETTLE_WINDOW_SECONDS * 1000)
        logs = _grid_logs(m, w0, t)
        need = max(1, ((t - w0) // 1000 + 1) // 2)
        if len(logs) < need:
            return None
        obs = sum(logs) / len(logs) + b
    return p_settle_at_least(spot * math.exp(b), m.strike, s, sig, obs, index_noise_log(coin))


# --- quoting / simulation ----------------------------------------------------------

def quote(snap: ms.Snap, side: str, fair_side: float, cfg: Cfg, rate: float) -> tuple[float, float] | None:
    """(price, queue_ahead) for a resting buy on `side`, or None."""
    bid, bid_qty, ask, _ = snap.side(side)
    if bid is None or ask is None:
        return None
    bid, ask = round(bid, 4), round(ask, 4)
    if cfg.rule == "join":
        if bid_qty is None:
            return None
        p, q = bid, bid_qty
    elif cfg.rule == "improve":
        p, q = round(bid + TICK, 4), 0.0
    else:
        raise ValueError(cfg.rule)
    if p >= ask - EPS or p < MIN_PRICE - EPS or p > MAX_PRICE + EPS:
        return None
    if fair_side - p - ms.maker_fee_pc(p, rate) <= cfg.margin:
        return None
    return p, q


@dataclass
class Post:
    coin: str
    day: str
    ticker: str
    side: str
    price: float
    fair: float          # fair of our side at post
    contracts: int
    filled: int
    fill_ms: int | None
    would_win: bool      # our side won (whether or not we filled)
    pnl: float = 0.0
    cost: float = 0.0
    mk_fair: float | None = None   # fair_side(fill) - P
    mk_mid: float | None = None    # side mid(fill + 5 s) - P
    d_fair: float | None = None    # fair_side(fill) - fair_side(post)


@dataclass
class NearRow:
    coin: str
    day: str
    s: int
    fair: float
    yes_bid: float
    yes_ask: float
    no_bid: float
    no_ask: float
    y: int


@dataclass
class Study:
    posts: dict = field(default_factory=lambda: defaultdict(list))        # (cfg, rate) -> [Post]
    no_data: dict = field(default_factory=lambda: defaultdict(int))       # (coin, start) -> markets skipped
    near: list = field(default_factory=list)                              # [NearRow]
    near_posts: dict = field(default_factory=lambda: defaultdict(list))   # (side, rate) -> [Post]
    calib: list = field(default_factory=list)   # (coin, day, s, fair_adj, fair_raw, yes_mid, y)
    basis: dict = field(default_factory=lambda: defaultdict(list))        # coin -> [b]
    markets: dict = field(default_factory=lambda: defaultdict(int))       # coin -> n
    days: dict = field(default_factory=lambda: defaultdict(set))          # coin -> {day}
    stats: dict = field(default_factory=dict)


def _side_fair(fy: float, side: str) -> float:
    return fy if side == "YES" else 1.0 - fy


def place(m: ms.Market, coin: str, basis: float | None, t0: int, side: str, p: float, q: float,
          fair_side: float, rate: float, sigma: float | None) -> Post | None:
    c = ms.contracts_for(p, rate)
    if c <= 0:
        return None
    cancel = m.close_ms - CANCEL_BEFORE_CLOSE_MS
    f, fts = ms.conservative_fill(side, p, c, q, m.trades, t0, cancel, m._trade_ts)
    would_win = (m.result == "yes") == (side == "YES")
    post = Post(coin, m.day, m.ticker, side, p, fair_side, c, f, fts, would_win)
    if f > 0:
        post.pnl, post.cost, _ = ms.settle_pnl(f, p, side, m.result, rate)
        fy2 = fair_yes_at(m, fts, coin, basis, sigma)
        if fy2 is not None:
            post.mk_fair = _side_fair(fy2, side) - p
            post.d_fair = _side_fair(fy2, side) - fair_side
        s2 = m.snap_at(fts + MARKOUT_MID_S * 1000)
        mid = s2.mid(side) if s2 is not None else None
        post.mk_mid = (mid - p) if mid is not None else None
    return post


def study_market(m: ms.Market, st: Study) -> None:
    coin = coin_of(m.ticker)
    st.markets[coin] += 1
    st.days[coin].add(m.day)
    basis = estimate_basis(m)
    if basis is not None:
        st.basis[coin].append(basis)
    y = 1 if m.result == "yes" else 0
    fairs: dict[int, tuple] = {}

    def fair_pair(s: int):
        if s not in fairs:
            t = m.close_ms - s * 1000
            sig = sigma_at(m, t)
            fa = fair_yes_at(m, t, coin, basis, sig) if sig is not None else None
            fr = fair_yes_at(m, t, coin, None, sig) if sig is not None else None
            fairs[s] = (fa, fr, sig)
        return fairs[s]

    for s in sorted(set(STARTS_S) | set(NEAR_TIMES_S), reverse=True):
        fa, fr, _ = fair_pair(s)
        snap = m.snap_at(m.close_ms - s * 1000)
        mid = snap.mid("YES") if snap is not None else None
        if fa is not None and fr is not None and mid is not None:
            st.calib.append((coin, m.day, s, fa, fr, mid, y))

    for start in STARTS_S:
        t0 = m.close_ms - start * 1000
        fy, _, sig = fair_pair(start)
        snap = m.snap_at(t0)
        if fy is None or snap is None:
            st.no_data[(coin, start)] += 1
            continue
        side = "YES" if fy >= 0.5 else "NO"
        fs = _side_fair(fy, side)
        for cfg in GRID:
            if cfg.start_s != start:
                continue
            for rate in RATES:
                qt = quote(snap, side, fs, cfg, rate)
                if qt is None:
                    continue
                post = place(m, coin, basis, t0, side, qt[0], qt[1], fs, rate, sig)
                if post is not None:
                    st.posts[(cfg, rate)].append(post)

    for s in NEAR_TIMES_S:
        fy, _, sig = fair_pair(s)
        t = m.close_ms - s * 1000
        snap = m.snap_at(t)
        if fy is None or snap is None or not (NEAR_BAND[0] <= fy <= NEAR_BAND[1]):
            continue
        yb, _, ya, _ = snap.side("YES")
        nb, _, na, _ = snap.side("NO")
        if None in (yb, ya, nb, na):
            continue
        st.near.append(NearRow(coin, m.day, s, fy, yb, ya, nb, na, y))
        if s != NEAR_MAKER_START_S:
            continue
        for side in ("YES", "NO"):
            bid, bq, _, _ = snap.side(side)
            if bid is None or bq is None or not (MIN_PRICE - EPS <= bid <= MAX_PRICE + EPS):
                continue
            for rate in RATES:
                post = place(m, coin, basis, t, side, round(bid, 4), bq, _side_fair(fy, side), rate, sig)
                if post is not None:
                    st.near_posts[(side, rate)].append(post)


def run(d: Path, coins: tuple[str, ...] = COINS) -> Study:
    st = Study()
    agg = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0,
           "days_loaded": set(), "recording_days": [], "settled": 0}
    for coin in coins:
        stats = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0, "days_loaded": set()}
        for m in ms.build_markets(d, coin, stats):
            if coin_of(m.ticker) != coin:
                continue
            study_market(m, st)
        for k in ("book_rows", "trade_rows", "trades_no_side", "markets", "settled"):
            agg[k] += stats.get(k, 0)
        agg["recording_days"] = stats.get("recording_days", [])
    st.stats = agg
    return st


# --- stats ---------------------------------------------------------------------------

def _mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def _by_day(items, num, den=lambda _: 1.0) -> dict[str, tuple[float, float]]:
    agg: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    for it in items:
        agg[it.day][0] += num(it)
        agg[it.day][1] += den(it)
    return {k: (a, b) for k, (a, b) in agg.items()}


def summarize(posts: list[Post], iters: int, seed: int) -> dict:
    fills = [p for p in posts if p.filled > 0]
    n = len(fills)
    s = {"posted": len(posts), "fills": n, "fill_rate": n / len(posts) if posts else None,
         "win_all": _mean([1.0 if p.would_win else 0.0 for p in posts]),
         "days": len({p.day for p in fills})}
    if not n:
        return s
    pnl = sum(p.pnl for p in fills)
    cost = sum(p.cost for p in fills)
    pf = _by_day(fills, lambda p: p.pnl)
    s.update(
        win=sum(p.would_win for p in fills) / n, price=_mean([p.price for p in fills]),
        fair=_mean([p.fair for p in fills]), pnl=pnl, per=pnl / n, per_dollar=pnl / cost if cost else None,
        ci95=ms.day_block_ci(pf, 0.95, iters, seed), ci99=ms.day_block_ci(pf, 0.99, iters, seed),
        mk_fair=_mean([p.mk_fair for p in fills]), mk_mid=_mean([p.mk_mid for p in fills]),
        d_fair=_mean([p.d_fair for p in fills]),
        mk_settle=_mean([(1.0 if p.would_win else 0.0) - p.price for p in fills]),
    )
    return s


# --- report --------------------------------------------------------------------------

def _c(x, fmt="{:+.3f}"):
    return "—" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def _ci(ci):
    return "—" if ci is None or math.isnan(ci[0]) else f"[{ci[0]:+.3f}, {ci[1]:+.3f}]"


HEAD = [
    "| Config | posted | fills | fill rate | win% fills | win% all posts | avg P | avg fair | P&L $ | $/fill | "
    "P&L/$ risked | 95% CI $/fill | 99% CI $/fill | fair@fill−P | mid+5s−P | Δfair post→fill | payout−P |",
    "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---:|---:|",
]


def row(label: str, s: dict) -> str:
    if not s.get("fills"):
        return (f"| {label} | {s.get('posted', 0)} | 0 | {_c(s.get('fill_rate'), '{:.1%}')} | — | "
                f"{_c(s.get('win_all'), '{:.1%}')} | — | — | — | — | — | — | — | — | — | — | — |")
    flag = " **excl 0**" if s["ci99"][0] > 0 or s["ci99"][1] < 0 else ""
    return (f"| {label} | {s['posted']} | {s['fills']} | {s['fill_rate']:.1%} | {s['win']:.1%} | "
            f"{_c(s['win_all'], '{:.1%}')} | {s['price'] * 100:.1f}¢ | {s['fair'] * 100:.1f}¢ | {s['pnl']:+.2f} | "
            f"{s['per']:+.3f} | {_c(s['per_dollar'])} | {_ci(s['ci95'])} | {_ci(s['ci99'])}{flag} | "
            f"{_c(s['mk_fair'])} | {_c(s['mk_mid'])} | {_c(s['d_fair'])} | {_c(s['mk_settle'])} |")


def _days_flag(n_days: int) -> str:
    return f" — **EXPLORATORY ({n_days} day(s) < {MIN_DAYS})**" if n_days < MIN_DAYS else ""


def _q(xs: list[float], q: float) -> float | None:
    if not xs:
        return None
    xs = sorted(xs)
    return xs[int(q * (len(xs) - 1))]


def report(st: Study, iters: int = 2000, seed: int = 11) -> str:
    coins = [c for c in COINS if st.markets.get(c)]
    groups = [(c, {c}) for c in coins] + [("pooled", set(coins))]
    all_days = set().union(*st.days.values()) if st.days else set()
    S = st.stats
    L = ["# Endgame maker study — resting bids in the last 90 s (Kalshi 15m crypto)", ""]
    L += [f"Recording days: {len(S.get('recording_days', []))} · settled markets studied: "
          + ", ".join(f"{c} {st.markets[c]} ({len(st.days[c])} d)" for c in coins)
          + f" · book rows {S.get('book_rows', 0)} · trades {S.get('trade_rows', 0)} "
          f"({S.get('trades_no_side', 0)} without taker_side — never fill us)", ""]
    if len(all_days) < MIN_DAYS:
        L += [f"**EXPLORATORY** — only {len(all_days)} day(s) of data (< {MIN_DAYS}). Nothing here is a "
              "finding; collect more recordings first.", ""]
    L += ["Design is pre-registered in the script docstring (12 configs, all shown, no selection). "
          "One $5 all-in resting bid per market per config on the side the settlement-aware fair favours, "
          "posted at T-90/60/30 s, cancelled 5 s before close, conservative fills (recorded trades at/through "
          "our price after queue), held to settlement. CIs: day-block bootstrap of $ per filled order "
          f"({iters} resamples; pooled resamples whole days across coins). Maker fee rates 0 and 0.0175 "
          "are sensitivities — Kalshi's current maker fee is not asserted here.", "",
          "Markouts (per contract, our side): fair@fill−P = settlement-aware fair at the fill time minus our "
          "price; mid+5s−P = book mid 5 s after the fill minus price; Δfair = fair at fill minus fair at "
          "post (negative = sellers knew something: adverse selection); payout−P = realised settlement "
          "value minus price. 'win% all posts' is how often the favoured side won for every post, filled "
          "or not; win% of fills below it is adverse selection in plain terms.", ""]

    # basis + calibration
    L += ["## CF-vs-Coinbase basis and fair calibration", "",
          "Basis = ln(strike) − mean ln(Coinbase) over the 60 s before each open (strike is the CF 60 s "
          f"average of that window). Kept when ≥ {BASIS_MIN_POINTS} s of spot and |b| ≤ {MAX_BASIS * 1e4:.0f} bp.", "",
          "| Coin | markets | basis ok | median bp | p10 bp | p90 bp | index noise floor bp |",
          "|---|---:|---:|---:|---:|---:|---:|"]
    for c in coins:
        b = st.basis.get(c, [])
        bp = [_c(None if (v := _q(b, q)) is None else v * 1e4, "{:+.2f}") for q in (0.5, 0.1, 0.9)]
        L.append(f"| {c} | {st.markets[c]} | {len(b)} | {bp[0]} | {bp[1]} | {bp[2]} | "
                 f"{index_noise_log(c) * 1e4:.1f} |")
    L += ["", "Brier score vs settlement (lower is better): fair with basis, fair without, YES book mid.", "",
          "| Coin | s to close | n | Brier fair (basis) | Brier fair (raw) | Brier YES mid |",
          "|---|---:|---:|---:|---:|---:|"]
    for g, cs in groups:
        for s in sorted(set(STARTS_S) | set(NEAR_TIMES_S), reverse=True):
            rows = [r for r in st.calib if r[0] in cs and r[2] == s]
            if not rows:
                continue
            br = lambda i: sum((r[i] - r[6]) ** 2 for r in rows) / len(rows)  # noqa: E731
            L.append(f"| {g} | {s} | {len(rows)} | {br(3):.4f} | {br(4):.4f} | {br(5):.4f} |")
    L.append("")

    # maker grid
    pc, pr = PRIMARY
    for rate in RATES:
        for g, cs in groups:
            nd = len(set().union(*(st.days[c] for c in cs))) if cs else 0
            L += [f"## Maker grid — {g}, maker fee {rate:g}{_days_flag(nd)}", ""] + HEAD
            for cfg in GRID:
                posts = [p for p in st.posts.get((cfg, rate), []) if p.coin in cs]
                tag = " (PRIMARY)" if g == "pooled" and cfg == pc and rate == pr else ""
                L.append(row(cfg.label() + tag, summarize(posts, iters, seed)))
            L.append("")
    skipped = ", ".join(f"{c} T-{s}: {st.no_data[(c, s)]}" for c in coins for s in STARTS_S if st.no_data.get((c, s)))
    if skipped:
        L += [f"Markets skipped for missing book/spot/vol at the start time: {skipped}.", ""]

    ps = summarize([p for p in st.posts.get((pc, pr), [])], iters, seed)
    if len(all_days) < MIN_DAYS:
        verdict = f"NOT EVALUABLE — {len(all_days)} day(s) < {MIN_DAYS}"
    elif ps.get("fills", 0) < PRIMARY_MIN_FILLS:
        verdict = f"NOT EVALUABLE — {ps.get('fills', 0)} fills < {PRIMARY_MIN_FILLS}"
    elif ps["ci99"][0] > 0:
        verdict = ("99% CI of $/fill excludes 0 on the positive side — worth a paper-trading follow-up, "
                   "NOT a profit claim (fee rate unverified, one study, simulated fills)")
    else:
        verdict = "does not pass (99% CI of $/fill does not exclude 0 on the positive side)"
    L += [f"**Primary hypothesis** (pooled, {pc.label()}, fee {pr:g}): {verdict}.", ""]
    n_cells = len(GRID) * len(coins) * len(RATES)
    n_excl = 0
    for rate in RATES:
        for c in coins:
            for cfg in GRID:
                fills = [p for p in st.posts.get((cfg, rate), []) if p.coin == c and p.filled > 0]
                ci = ms.day_block_ci(_by_day(fills, lambda p: p.pnl), 0.99, iters, seed)
                n_excl += bool(fills) and not math.isnan(ci[0]) and (ci[0] > 0 or ci[1] < 0)
    L += [f"Secondary cells (coin × config × fee): {n_cells}; with a 99% CI excluding 0: {n_excl}. "
          "Cells share data (same markets, nested fees), so this is not a clean multiple-testing count.", ""]

    # near 50/50
    L += ["## Near 50/50 in the final 30 s — are both sides overpriced?", "",
          f"Markets whose basis-adjusted settlement-aware fair_yes is in [{NEAR_BAND[0]}, {NEAR_BAND[1]}] at "
          f"{' and '.join(str(s) for s in NEAR_TIMES_S)} s before close, two-sided book. Edges are per contract, "
          "no fees: E[Y − YES bid] is what a YES buyer at the bid earns per contract; negative on both sides "
          "would mean both bids are overpriced. Unconditionally the two bid edges add up to the mean spread, "
          "so both cannot be negative on average — only conditional on getting filled (table after).", "",
          "| Group | s | n | days | YES mid | fair | YES won | asks−1 | spread | identity breaks | "
          "E[Y−YESbid] 99% CI | E[N−NObid] 99% CI | E[Y−YESask] 99% CI | E[N−NOask] 99% CI | E[Y−mid] 99% CI |",
          "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|"]

    def ci_cell(rows, f):
        mean = sum(f(r) for r in rows) / len(rows)
        ci = ms.day_block_ci(_by_day(rows, f), 0.99, iters, seed)
        return f"{mean:+.3f} {_ci(ci)}"

    for g, cs in groups:
        for s in NEAR_TIMES_S:
            rows = [r for r in st.near if r.coin in cs and r.s == s]
            if not rows:
                L.append(f"| {g} | {s} | 0 | 0 | — | — | — | — | — | — | — | — | — | — | — |")
                continue
            n = len(rows)
            nd = len({r.day for r in rows})
            over = _mean([r.yes_ask + r.no_ask - 1.0 for r in rows])
            spread = _mean([r.yes_ask - r.yes_bid for r in rows])
            breaks = sum(abs((r.yes_ask + r.no_ask - 1.0) - (r.yes_ask - r.yes_bid)) > 0.005 for r in rows)
            L.append(
                f"| {g}{' ⚠' if nd < MIN_DAYS else ''} | {s} | {n} | {nd} | "
                f"{_mean([(r.yes_bid + r.yes_ask) / 2 for r in rows]):.3f} | {_mean([r.fair for r in rows]):.3f} | "
                f"{_mean([float(r.y) for r in rows]):.1%} | {over:+.3f} | {spread:.3f} | {breaks} | "
                f"{ci_cell(rows, lambda r: r.y - r.yes_bid)} | {ci_cell(rows, lambda r: (1 - r.y) - r.no_bid)} | "
                f"{ci_cell(rows, lambda r: r.y - r.yes_ask)} | {ci_cell(rows, lambda r: (1 - r.y) - r.no_ask)} | "
                f"{ci_cell(rows, lambda r: r.y - (r.yes_bid + r.yes_ask) / 2)} |")
    L += ["", f"⚠ = fewer than {MIN_DAYS} days (exploratory). 'identity breaks' counts rows where "
          "(YES ask + NO ask − 1) differs from the YES spread by > 0.5¢ (should be 0: asks are derived from "
          "the opposite bids).", "",
          f"### Conditional on fill: join the bid on BOTH sides at T-{NEAR_MAKER_START_S}s (near-50/50 markets), "
          "cancel 5 s before close", ""] + HEAD
    for rate in RATES:
        for g, cs in groups:
            for side in ("YES", "NO"):
                posts = [p for p in st.near_posts.get((side, rate), []) if p.coin in cs]
                L.append(row(f"{g} {side} bid @ fee {rate:g}", summarize(posts, iters, seed)))
            both = [p for side in ("YES", "NO") for p in st.near_posts.get((side, rate), []) if p.coin in cs]
            L.append(row(f"{g} both bids @ fee {rate:g}", summarize(both, iters, seed)))
    L += ["", "Here 'avg fair' is the settlement-aware fair of the side bought; 'win% all posts' is the "
          "unconditional win rate of that side among near-50/50 posts.", ""]

    L += ["## Caveats", "",
          "- Spot is Coinbase, not the CF Benchmarks index; the basis shift is a per-market constant "
          "estimated at the open and the index-noise floor is the settlement-study value per coin. "
          "Residual basis drift inside 15 min is not modelled.",
          "- Fills are simulated from recorded trades (exchange timestamps) against a top-of-book snapshot "
          "(runner receipt clock); queue position never improves from cancels ahead of us (pessimistic) "
          "and our own order would itself change what late takers do (not modelled).",
          "- One post per market per config, no re-quoting; at most ~1 s granularity; latency ignored.",
          "- Maker fee rates are sensitivities, not Kalshi's verified schedule; fee rounded on the filled total.",
          "- Read-only research. No result here is a profit claim; a positive cell is at most a reason to "
          "paper-trade the exact pre-registered rule on new days."]
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dir", required=True, help="recordings directory (spot_/book_/trades_/settle_ files)")
    ap.add_argument("--coins", default=",".join(COINS), help="comma list of BTC,ETH,SOL")
    ap.add_argument("--iters", type=int, default=2000, help="bootstrap resamples")
    ap.add_argument("--out", default=None, help="also write the Markdown report here")
    args = ap.parse_args(argv)
    d = Path(args.dir)
    if not d.is_dir():
        ap.error(f"--dir {d} is not a directory")
    coins = tuple(c.strip().upper() for c in args.coins.split(",") if c.strip())
    bad = [c for c in coins if c not in COINS]
    if bad:
        ap.error(f"unknown coin(s): {bad}")
    text = report(run(d, coins), iters=args.iters)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
