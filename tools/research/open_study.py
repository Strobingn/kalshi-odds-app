#!/usr/bin/env python3
"""Window-open study: is the Kalshi 15-minute crypto market mispriced in its first 90 s?

Idea under test
---------------
KXBTC15M / KXETH15M / KXSOL15M settle YES if the 60 s average of the CF
Benchmarks index before close is >= the strike, and the strike is the previous
window's 60 s average. When a new window opens, spot has usually already moved
away from the strike, but the book may open near 50/50 and take a while to
reprice. If so, in the first 0-90 s the YES mid lags the digital fair value and
buying the side the fair favours has positive expected value. Earlier studies
(lag_study, maker_sim, edge_search) all decide at >= 1-2 min elapsed and the
app's entry filter blocks early entries, so the open itself was never tested.

Pre-registered rules (written before any recorded data was looked at)
-----------------------------------------------------------------------
Data: tools/research/cloud_recorder.py recordings (recordings.py format), loaded
with maker_sim's reader. Only markets with a recorded settlement (settle_*.csv)
are used. Coins BTC, ETH, SOL are reported separately and pooled.

Fair value: maker_sim.Market.fair_yes(t) unchanged — Phi(d2)
(pipeline.p_finish_above) from the last Coinbase spot (<= 10 s old) vs the
strike, sigma from the trailing 30 one-minute spot closes
(pipeline.realized_vol_bar_std -> sigma_annual_from_bar_std), tte = close - t.
Quote: last book row of the market <= 5 s old (maker_sim.Market.snap_at).
YES mid = (YES bid + YES ask) / 2 (ask falls back to 1 - NO bid).
open = close - 900 s.

(a) Gap. gap(s) = YES mid - fair at s seconds after open.
    * At s in GAP_TIMES = 0, 15, 30, 60, 90, 120, 300: n, median |gap|, mean
      "lag" = (fair - mid) * sign(fair - 0.5) (positive = the market
      under-prices the side the fair favours), median YES spread.
    * Halving: s0 = first second in [0, 90] with both a quote and a fair;
      g0 = gap(s0). Only |g0| >= HALVE_MIN_GAP (0.03) counts. t_half = first
      second in (s0, s0 + 300] with |gap| <= |g0| / 2. Not reached = censored.
      Reported: n, share halved, median t_half among halved, and the median
      over all with censored counted as > 300 s.
    * Also: seconds from open to the market's first recorded book row.

Grid for (b) and (c): decision time D in DECISION_S = 15, 30, 60, 90 s after
open x margin M in MARGINS = 0.02, 0.04. One decision per market per config
(so one bet / order per market per config). Prices outside 5-95c are skipped.

(b) Taker. At open + D: for each side, edge = fair_side - ask -
    pipeline.fee_per_contract(ask, 0.07). Take the larger edge if it is > M.
    Buy at the recorded ask, $5 all-in (pipeline.size_all_in at the taker rate
    0.07, exact Kalshi fee via pipeline.kalshi_total_cost), capped at the
    displayed ask quantity, hold to settlement.

(c) Maker "1c better". At open + D: price P = best bid on the side + 1c, queue
    ahead 0, only if P < that side's ask (maker_sim.quote with rule "improve").
    edge = fair_side - P - maker fee per contract; post the side with the larger
    edge if > M. $5 all-in at the maker rate (default 0 = "no fee", as the
    hypothesis states; 0.0175 shown as sensitivity — check Kalshi's current
    fee schedule). Fills: maker_sim.conservative_fill — only recorded trades on
    our side at or through P, strictly after posting, until cancel at D + 30 s;
    partial fills allowed; trades without taker_side never fill us. Hold any
    fill to settlement.

Statistics: P&L per bet (taker) / per filled order (maker), mean and day-block
bootstrap 95% and 99% CIs (maker_sim.day_block_ci; whole UTC days resampled,
days = the day the market opens, pooled across coins for "ALL").
PRIMARY test, fixed in advance: pooled coins, D = 30 s, M = 0.02, taker and
maker. The other 15 cells per method are secondary (multiple comparisons:
expect about one 95% CI in twenty to exclude 0 by chance).
Decision rule: the primary cell "supports the idea" only if there are >= 6
recording days AND its 99% CI lower bound is > 0. With fewer than 6 days the
whole report is EXPLORATORY: describe, do not conclude.

Known limits (also printed): the recorder lists new markets only every 30 s,
so the first seconds after open can be missing (see first-quote delay);
fills at the recorded top-of-book ask with no latency; Coinbase spot is not the
CF settlement index; Phi(d2) ignores the 60 s settlement averaging.

Usage:  python3 tools/research/open_study.py --dir rec [--iters 2000] [--maker-fee 0]
Prints a Markdown report. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import maker_sim as ms  # noqa: E402  (also puts tools/backtest on sys.path)
from pipeline import STAKE_USD, fee_per_contract, size_all_in  # noqa: E402

COINS = ("BTC", "ETH", "SOL")
TAKER_FEE = 0.07
MAKER_SENSITIVITY_FEE = 0.0175
GAP_TIMES = (0, 15, 30, 60, 90, 120, 300)  # must include every DECISION_S
DECISION_S = (15, 30, 60, 90)
MARGINS = (0.02, 0.04)
CANCEL_S = 30
HALVE_MIN_GAP = 0.03
HALVE_SEARCH_START_S = 90
HALVE_HORIZON_S = 300
PATH_S = HALVE_SEARCH_START_S + HALVE_HORIZON_S
PRIMARY = (30, 0.02)
MIN_DAYS = 6
EPS = 1e-9


# --- per-market measurements ------------------------------------------------------

@dataclass
class Bet:
    coin: str
    day: str
    ticker: str
    kind: str          # "taker" | "maker"
    decision_s: int
    margin: float
    side: str
    price: float
    fair: float        # fair of the side bought
    contracts: int     # contracts held (maker: filled)
    cost: float
    pnl: float
    won: bool
    capped: bool = False   # taker: displayed depth < $5 size


@dataclass
class Post:
    coin: str
    day: str
    decision_s: int
    margin: float
    rate: float
    filled: bool


@dataclass
class MarketObs:
    coin: str
    day: str
    ticker: str
    first_quote_s: float | None
    gaps: dict[int, tuple[float, float, float | None] | None]  # s -> (gap, lag, spread)
    g0: float | None
    t_half: int | None     # seconds after s0; None = censored or not eligible
    halve_eligible: bool


def gap_path(m: ms.Market, horizon_s: int = PATH_S) -> dict[int, tuple[float, float, float | None]]:
    """s -> (mid - fair, fair, YES spread) for each second 0..horizon with a quote and a fair."""
    out = {}
    for s in range(0, horizon_s + 1):
        t = m.open_ms + s * 1000
        if t >= m.close_ms:
            break
        snap = m.snap_at(t)
        if snap is None:
            continue
        mid = snap.mid("YES")
        if mid is None:
            continue
        fy = m.fair_yes(t)
        if fy is None:
            continue
        bid, _, ask, _ = snap.side("YES")
        out[s] = (mid - fy, fy, (ask - bid) if bid is not None and ask is not None else None)
    return out


def halving(path: dict[int, tuple], start_max: int = HALVE_SEARCH_START_S, horizon: int = HALVE_HORIZON_S,
            min_gap: float = HALVE_MIN_GAP) -> tuple[float | None, int | None, bool]:
    """(g0, seconds to halve or None if censored, eligible)."""
    s0 = next((s for s in range(0, start_max + 1) if s in path), None)
    if s0 is None:
        return None, None, False
    g0 = path[s0][0]
    if abs(g0) < min_gap:
        return g0, None, False
    for s in range(s0 + 1, s0 + horizon + 1):
        v = path.get(s)
        if v is not None and abs(v[0]) <= abs(g0) / 2.0 + EPS:
            return g0, s - s0, True
    return g0, None, True


def observe(m: ms.Market, coin: str) -> MarketObs:
    path = gap_path(m)
    gaps = {}
    for s in GAP_TIMES:
        v = path.get(s)
        if v is None:
            gaps[s] = None
            continue
        gap, fy, spread = v
        lag = (-gap) * (1.0 if fy >= 0.5 else -1.0)
        gaps[s] = (gap, lag, spread)
    g0, th, elig = halving(path)
    first = (m.book[0].ts - m.open_ms) / 1000.0 if m.book else None
    return MarketObs(coin, m.day, m.ticker, first, gaps, g0, th, elig)


def taker_bet(m: ms.Market, coin: str, d_s: int, margin: float, fy: float | None) -> Bet | None:
    t = m.open_ms + d_s * 1000
    snap = m.snap_at(t)
    if snap is None or fy is None:
        return None
    best = None
    for side, fs in (("YES", fy), ("NO", 1.0 - fy)):
        _, _, ask, aq = snap.side(side)
        if ask is None or not (ms.MIN_PRICE - EPS <= ask <= ms.MAX_PRICE + EPS):
            continue
        edge = fs - ask - fee_per_contract(ask, TAKER_FEE)
        if edge > margin + EPS and (best is None or edge > best[0]):
            best = (edge, side, fs, round(ask, 4), aq)
    if best is None:
        return None
    _, side, fs, ask, aq = best
    c = size_all_in(ask, STAKE_USD, TAKER_FEE)[0]
    capped = False
    if aq is not None and aq < c:
        c, capped = int(math.floor(aq + EPS)), True
    if c <= 0:
        return None
    pnl, cost, won = ms.settle_pnl(c, ask, side, m.result, TAKER_FEE)
    return Bet(coin, m.day, m.ticker, "taker", d_s, margin, side, ask, fs, c, cost, pnl, won, capped)


def maker_order(m: ms.Market, coin: str, d_s: int, margin: float, rate: float,
                fy: float | None) -> tuple[Post | None, Bet | None]:
    t = m.open_ms + d_s * 1000
    snap = m.snap_at(t)
    if snap is None or fy is None:
        return None, None
    cfg = ms.Config("improve", CANCEL_S, margin)
    best = None
    for side, fs in (("YES", fy), ("NO", 1.0 - fy)):
        q = ms.quote(snap, side, fs, cfg, rate)
        if q is not None and (best is None or q[2] > best[1][2]):
            best = (side, q, fs)
    if best is None:
        return None, None
    side, (p, queue, _), fs = best
    c = ms.contracts_for(p, rate)
    if c <= 0:
        return None, None
    cancel = ms.cancel_time(t, CANCEL_S, m.close_ms)
    f, _ts = ms.conservative_fill(side, p, c, queue, m.trades, t, cancel, m._trade_ts)
    post = Post(coin, m.day, d_s, margin, rate, f > 0)
    if f <= 0:
        return post, None
    pnl, cost, won = ms.settle_pnl(f, p, side, m.result, rate)
    return post, Bet(coin, m.day, m.ticker, "maker", d_s, margin, side, p, fs, f, cost, pnl, won)


# --- driver -----------------------------------------------------------------------

def run(d: Path, coins=COINS, maker_fee: float = 0.0) -> dict:
    rates = sorted({maker_fee, MAKER_SENSITIVITY_FEE})
    obs: list[MarketObs] = []
    bets: list[Bet] = []
    posts: list[Post] = []
    stats: dict = {"coins": {}, "recording_days": ms.recording_days(d) if d.is_dir() else []}
    for coin in coins:
        st = {"book_rows": 0, "trade_rows": 0, "trades_no_side": 0, "markets": 0, "days_loaded": set()}
        if d.is_dir():
            for m in ms.build_markets(d, coin, st):
                obs.append(observe(m, coin))
                fairs = {s: m.fair_yes(m.open_ms + s * 1000) for s in DECISION_S}
                for d_s in DECISION_S:
                    for mg in MARGINS:
                        b = taker_bet(m, coin, d_s, mg, fairs[d_s])
                        if b is not None:
                            bets.append(b)
                        for r in rates:
                            p, fb = maker_order(m, coin, d_s, mg, r, fairs[d_s])
                            if p is not None:
                                posts.append(p)
                            if fb is not None and r == maker_fee:
                                bets.append(fb)
                            elif fb is not None:
                                fb.kind = f"maker@{r:g}"
                                bets.append(fb)
        st.pop("days_loaded", None)
        stats["coins"][coin] = st
    return {"obs": obs, "bets": bets, "posts": posts, "stats": stats, "maker_fee": maker_fee, "rates": rates}


# --- stats ------------------------------------------------------------------------

def median(xs):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    n = len(xs)
    return xs[n // 2] if n % 2 else 0.5 * (xs[n // 2 - 1] + xs[n // 2])


def quantile(xs, q):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    return xs[min(len(xs) - 1, max(0, int(round(q * (len(xs) - 1)))))]


def summarize_bets(bets: list[Bet], iters: int, seed: int = 11) -> dict:
    n = len(bets)
    if not n:
        return {"n": 0}
    by: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    for b in bets:
        by[b.day][0] += b.pnl
        by[b.day][1] += 1
    byd = {k: tuple(v) for k, v in by.items()}
    pnl = sum(b.pnl for b in bets)
    cost = sum(b.cost for b in bets)
    return {
        "n": n, "days": len(byd), "win": sum(b.won for b in bets) / n,
        "fair": sum(b.fair for b in bets) / n, "price": sum(b.price for b in bets) / n,
        "pnl": pnl, "per": pnl / n, "per_dollar": pnl / cost if cost else None,
        "capped": sum(b.capped for b in bets),
        "ci95": ms.day_block_ci(byd, 0.95, iters, seed), "ci99": ms.day_block_ci(byd, 0.99, iters, seed),
    }


def _c(x, fmt="{:+.3f}"):
    return "—" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def _ci(ci):
    return "—" if ci is None or math.isnan(ci[0]) else f"[{ci[0]:+.3f}, {ci[1]:+.3f}]"


def _sel(items, coin):
    return [x for x in items if coin == "ALL" or x.coin == coin]


# --- report -----------------------------------------------------------------------

def report(r: dict, iters: int = 2000, seed: int = 11) -> str:
    obs, bets, posts, stats = r["obs"], r["bets"], r["posts"], r["stats"]
    maker_fee = r["maker_fee"]
    groups = [c for c in stats["coins"]] + (["ALL"] if len(stats["coins"]) > 1 else [])
    days = sorted({o.day for o in obs})
    L = ["# Window-open study — Kalshi 15m crypto, first 90 s after open", ""]
    L += [f"Recording days in dir: {len(stats['recording_days'])} · days with settled markets: {len(days)}"
          + (f" ({days[0]} → {days[-1]})" if days else ""), ""]
    L += ["| Coin | settled markets | book rows | trades | trades w/o taker_side |", "|---|---:|---:|---:|---:|"]
    for c, st in stats["coins"].items():
        L.append(f"| {c} | {st['markets']} | {st['book_rows']} | {st['trade_rows']} | {st['trades_no_side']} |")
    L.append("")
    if len(days) < MIN_DAYS:
        L += [f"**EXPLORATORY** — {len(days)} day(s) of data (< {MIN_DAYS}). Day-block CIs on this few days "
              "are unreliable; nothing below is evidence of an edge either way. Describe, do not conclude.", ""]
    L += ["Rules are pre-registered in the open_study.py docstring (fixed grid D = 15/30/60/90 s × margin "
          f"0.02/0.04; PRIMARY = pooled, D = {PRIMARY[0]} s, margin {PRIMARY[1]:.2f}). "
          f"Maker fee rate used: {maker_fee:g} (sensitivity {MAKER_SENSITIVITY_FEE:g}; verify against Kalshi's "
          "current schedule). Taker fee: exact Kalshi formula at 0.07.", ""]

    # first quote delay
    L += ["## Data coverage at the open", "",
          "Seconds from open to the market's first recorded book row (the recorder lists new markets every 30 s), "
          "and the share of markets with a usable quote + fair at each decision time.", "",
          "| Coin | n | median s | p90 s | " + " | ".join(f"usable @{s}s" for s in DECISION_S) + " |",
          "|---|---:|---:|---:|" + "---:|" * len(DECISION_S)]
    for g in groups:
        oo = _sel(obs, g)
        fq = [o.first_quote_s for o in oo]
        cov = [f"{sum(o.gaps[s] is not None for o in oo) / len(oo):.0%}" if oo else "—" for s in DECISION_S]
        L.append(f"| {g} | {len(oo)} | {_c(median(fq), '{:.1f}')} | {_c(quantile(fq, 0.9), '{:.1f}')} | "
                 + " | ".join(cov) + " |")
    L.append("")

    # (a)
    L += ["## (a) YES mid vs Φ(d2) fair", "",
          "gap = mid − fair; lag = (fair − mid)·sign(fair − 0.5), positive = market under-prices the side the "
          "fair favours (what the idea predicts). 300 s is a later-window reference.", "",
          "| Coin | s after open | n | median abs gap | mean lag | median lag | median YES spread |",
          "|---|---:|---:|---:|---:|---:|---:|"]
    for g in groups:
        oo = _sel(obs, g)
        for s in GAP_TIMES:
            vals = [o.gaps[s] for o in oo if o.gaps[s] is not None]
            if not vals:
                L.append(f"| {g} | {s} | 0 | — | — | — | — |")
                continue
            lags = [v[1] for v in vals]
            L.append(f"| {g} | {s} | {len(vals)} | {_c(median([abs(v[0]) for v in vals]), '{:.3f}')} | "
                     f"{_c(sum(lags) / len(lags))} | {_c(median(lags))} | {_c(median([v[2] for v in vals]), '{:.3f}')} |")
    L += ["", f"Halving: first quoted second s0 ≤ {HALVE_SEARCH_START_S} s with |gap| ≥ {HALVE_MIN_GAP}; seconds until "
          f"|gap| ≤ half of it (search {HALVE_HORIZON_S} s, else censored).", "",
          "| Coin | quote+fair in first 90 s | \\|g0\\| ≥ 0.03 | halved | median s to halve (halved) | median s (censored = >300) |",
          "|---|---:|---:|---:|---:|---:|"]
    for g in groups:
        oo = _sel(obs, g)
        q = [o for o in oo if o.g0 is not None]
        el = [o for o in oo if o.halve_eligible]
        hv = [o.t_half for o in el if o.t_half is not None]
        allv = sorted([o.t_half if o.t_half is not None else math.inf for o in el])
        med_all = None
        if allv:
            mv = allv[(len(allv) - 1) // 2]
            med_all = ">300" if math.isinf(mv) else f"{mv}"
        L.append(f"| {g} | {len(q)} | {len(el)} | {len(hv)} ({_c(len(hv) / len(el) if el else None, '{:.0%}')}) | "
                 f"{_c(median(hv), '{:.1f}')} | {med_all or '—'} |")
    L.append("")

    # (b), (c)
    head = ["| Coin | D s | margin | bets | days | win% | avg fair | avg price | P&L $ | $/bet | P&L per $ | 95% CI $/bet | 99% CI $/bet | depth-capped |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|"]

    def row(g, d_s, mg, s, extra=""):
        tag = " **(PRIMARY)**" if g == "ALL" and (d_s, mg) == PRIMARY else ""
        if not s.get("n"):
            return f"| {g}{tag} | {d_s} | {mg:.2f} | 0 | — | — | — | — | — | — | — | — | — | {extra or '—'} |"
        return (f"| {g}{tag} | {d_s} | {mg:.2f} | {s['n']} | {s['days']} | {s['win']:.1%} | {s['fair']:.3f} | "
                f"{s['price'] * 100:.1f}¢ | {s['pnl']:+.2f} | {s['per']:+.3f} | {_c(s['per_dollar'])} | "
                f"{_ci(s['ci95'])} | {_ci(s['ci99'])} | {extra or s['capped']} |")

    L += ["## (b) Taker at the recorded ask, exact fee (0.07), $5 all-in, hold to settlement", ""] + head
    prim = {}
    for g in groups:
        for d_s in DECISION_S:
            for mg in MARGINS:
                bb = [b for b in _sel(bets, g) if b.kind == "taker" and b.decision_s == d_s and b.margin == mg]
                s = summarize_bets(bb, iters, seed)
                if g == groups[-1] and (d_s, mg) == PRIMARY:
                    prim["taker"] = s
                L.append(row(g, d_s, mg, s))
    L.append("")

    for rate in r["rates"]:
        kind = "maker" if rate == maker_fee else f"maker@{rate:g}"
        title = (f"## (c) Maker 1¢ better (queue 0), conservative fills, cancel {CANCEL_S} s, fee rate {maker_fee:g}"
                 if rate == maker_fee else f"### (c) sensitivity: same at maker fee rate {rate:g}")
        L += [title, "", "Last column: orders posted / filled (fill rate)."]
        L += [""] + [h.replace("bets", "fills").replace("$/bet", "$/fill").replace("depth-capped", "posted / filled")
                     for h in head]
        for g in groups if rate == maker_fee else ["ALL" if "ALL" in groups else groups[0]]:
            for d_s in DECISION_S:
                for mg in MARGINS:
                    bb = [b for b in _sel(bets, g) if b.kind == kind and b.decision_s == d_s and b.margin == mg]
                    pp = [p for p in _sel(posts, g) if p.rate == rate and p.decision_s == d_s and p.margin == mg]
                    nf = sum(p.filled for p in pp)
                    extra = f"{len(pp)} / {nf}" + (f" ({nf / len(pp):.0%})" if pp else "")
                    s = summarize_bets(bb, iters, seed)
                    if rate == maker_fee and g == groups[-1] and (d_s, mg) == PRIMARY:
                        prim["maker"] = s
                    L.append(row(g, d_s, mg, s, extra))
        L.append("")

    L += ["## Verdict on the PRIMARY cell (pooled, D = 30 s, margin 0.02)", ""]
    for k in ("taker", "maker"):
        s = prim.get(k, {"n": 0})
        if len(days) < MIN_DAYS:
            v = f"EXPLORATORY — {len(days)} day(s) < {MIN_DAYS}; no conclusion."
        elif not s.get("n"):
            v = "no bets — nothing to evaluate."
        elif s["ci99"][0] > 0:
            v = (f"99% CI lower bound {s['ci99'][0]:+.3f} > 0 — supports the idea on this sample "
                 "(next: confirm on later, unseen days before any paper/live use).")
        else:
            v = f"99% CI {_ci(s['ci99'])} includes 0 or is below — idea NOT supported."
        L.append(f"- {k}: {s.get('n', 0)} {'bets' if k == 'taker' else 'fills'}, "
                 f"mean {_c(s.get('per'))} $/bet — {v}")
    L += ["",
          "Caveats: the recorder discovers new markets only every 30 s, so early decision times can be missing or "
          "biased toward markets found quickly; taker fills assume the recorded top-of-book ask is still there "
          "(no latency) and are capped at displayed size; maker fills ignore cancels ahead of us and assume we are "
          "first at bid + 1¢; fair uses Coinbase spot (not the CF settlement index) and Φ(d2) without the 60 s "
          "settlement average; 16 cells per method are shown, so some will look good by chance — only the "
          "PRIMARY cell is a test."]
    return "\n".join(L)


def to_json(r: dict) -> str:
    return json.dumps({"stats": r["stats"], "maker_fee": r["maker_fee"],
                       "bets": [asdict(b) for b in r["bets"]], "posts": [asdict(p) for p in r["posts"]],
                       "obs": [asdict(o) for o in r["obs"]]}, indent=1, default=str)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dir", required=True, help="recordings directory")
    ap.add_argument("--coins", nargs="*", default=list(COINS))
    ap.add_argument("--maker-fee", type=float, default=0.0, help="maker fee rate (default 0 = 'no fee' per the idea)")
    ap.add_argument("--iters", type=int, default=2000, help="bootstrap iterations")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    d = Path(args.dir)
    if not d.is_dir():
        print(f"No recordings directory at {d} — nothing to study.")
        return 0
    r = run(d, tuple(c.upper() for c in args.coins), args.maker_fee)
    print(to_json(r) if args.json else report(r, iters=args.iters))
    return 0


if __name__ == "__main__":
    sys.exit(main())
