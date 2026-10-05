#!/usr/bin/env python3
"""Cross-coin lead-lag: does the ETH/SOL Kalshi 15m book lag a BTC move?

Input: a 1 s recordings directory (cloud_recorder.py / the app; format in
recordings.py) with Coinbase spot for BTC-USD, ETH-USD, SOL-USD and book rows
for the open KXBTC15M, KXETH15M and KXSOL15M market. Stdlib only. Reuses
lag_study.py (grids, cross-correlation, event detection, response delays,
quote lookup, day-block bootstrap) and tools/backtest/pipeline.py (digital
fair value, exact Kalshi fee, $5 all-in sizing).

PRE-REGISTERED DESIGN (fixed 2026-10-05, before any result on real data was seen)
=================================================================================
Hypothesis: after a sharp BTC spot move, the KXETH15M / KXSOL15M YES mid
reprices more slowly than (i) the KXBTC15M mid and (ii) ETH/SOL spot itself,
so the ETH/SOL Kalshi side in the BTC move's direction can be bought before it
reprices. "C" below is ETH or SOL.

(a) Cross-correlation, 1 s grid, lags -5..+30 s (positive = second series
    after BTC). Pooled over markets as in lag_study.cross_correlation:
      BTC spot log-return  vs  C Kalshi YES-mid change     (the test)
      C spot log-return    vs  C Kalshi YES-mid change     (own-coin reference)
      BTC spot log-return  vs  BTC Kalshi YES-mid change   (BTC book reference)
      BTC spot log-return  vs  C spot log-return           (spot-spot lead)
    Supported for C if peak lag(BTC spot -> C Kalshi) is >= 2 s later than
    BOTH peak lag(BTC spot -> BTC Kalshi) and peak lag(BTC spot -> C spot).

(b) Event study. Event = |5 s BTC log-return| >= k * sigma1 * sqrt(5), k in
    {3, 4}, sigma1 = std of BTC 1 s log-returns over [t-900 s, t-5 s), >= 30
    returns, 30 s refractory (lag_study.find_events semantics, computed with
    rolling sums). Per event, seconds from detection until the series covered
    half of its 30 s response in the BTC direction, measured from t-5 s:
      C Kalshi mid  (needs a >= 1c response; lag_study.response_delays)
      C spot        (needs a log response >= C's own trailing sigma1)
      BTC Kalshi mid (reference)
    Supported for C if, at k = 4, the median paired delay (C Kalshi minus C
    spot, events where both responded) is >= 2 s on >= 30 pairs.

(c) Taker rule, one decision per (event, C market):
      side  = YES if the BTC move was up, NO if down (never the other side)
      fair  = "own":  Phi(d2) from C spot at entry, C trailing sigma1 scaled
                      to 1 min then annualized (pipeline.sigma_annual_from_bar_std)
              "beta": same, but C price = C spot at t-5 s * exp(beta * BTC
                      log-move from t-5 s to entry), beta = OLS slope of C on
                      BTC 1 s returns over [t-900, t-5)
      buy   if fair - ask - fee_per_contract(ask) >= margin (0.02), ask =
            last recorded top-of-book ask at or before entry, <= 5 s old,
            >= 30 s to close; size_all_in ($5 all-in incl. exact fee); one
            bet per market per variant; hold to settlement (recorded result).
      entry = event second (primary) or event second + 2 s (latency check).
    P&L per bet with a day-block bootstrap (whole UTC days) 95% and 99% CI.
    PRIMARY test: k = 4, "own" fair, entry +0 s, ETH and SOL pooled. It
    "passes" only if the 99% CI lower bound is > 0 with >= 6 days and >= 30
    bets. Every other row (k = 3, beta, +2 s, per coin) is secondary: 8 rule
    variants x 2 coins are reported, so a lone CI above zero elsewhere is
    expected by chance and is not evidence. Fewer than 6 recorded days ->
    the whole report is flagged EXPLORATORY.

Fill model: the recorded top-of-book ask, no latency beyond the entry delay,
no queue, no depth walk ("depth ok" counts bets whose top-of-book size covers
the order). Book rows are ~1 s REST polls, so sub-second lags are invisible.

    python3 tools/research/cross_lag.py rec            # Markdown to stdout
    python3 tools/research/cross_lag.py rec --json
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from array import array
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import lag_study as ls  # noqa: E402  (also puts tools/backtest on sys.path)
from pipeline import (  # noqa: E402
    STAKE_USD,
    fee_per_contract,
    p_finish_above,
    sigma_annual_from_bar_std,
    size_all_in,
    usable,
)
from recordings import list_days, load_day  # noqa: E402

COINS = {"BTC": ("BTC-USD", "KXBTC15M"), "ETH": ("ETH-USD", "KXETH15M"), "SOL": ("SOL-USD", "KXSOL15M")}
TARGETS = ("ETH", "SOL")
LAGS = list(range(-5, 31))
KS = (3.0, 4.0)
FAIRS = ("own", "beta")
ENTRY_DELAYS = (0, 2)
HORIZON = 5
WINDOW = 900
SKIP = 5
MIN_N = 30
REFRACTORY = 30
MAX_WAIT = 30
MIN_DAYS = 6
MIN_BETS = 30
PRIMARY = {"k": 4.0, "fair": "own", "delay": 0}


# --- data ----------------------------------------------------------------------------

def series_of(ticker: str) -> str | None:
    for coin, (_p, series) in COINS.items():
        if ticker.startswith(series + "-") or ticker == series:
            return coin
    return None


QUOTE_KEYS = ("yes_ask", "yes_ask_qty", "no_ask", "no_ask_qty")


def _merge_markets(into: dict[str, ls.Market], day_markets: dict[str, ls.Market]) -> None:
    """Add one day's markets; quotes slimmed to tuples (QUOTE_KEYS) to keep memory low."""
    for t, m in day_markets.items():
        quotes = [tuple(r.get(k) for k in QUOTE_KEYS) for r in m.quotes]
        cur = into.get(t)
        if cur is None:
            into[t] = ls.Market(t, m.strike, m.close_ms, m.mids, m.quote_secs, quotes)
            continue
        cur.mids.update(m.mids)  # a market spanning midnight: later day's rows win
        cur.quote_secs += m.quote_secs
        cur.quotes += quotes
        cur.strike = m.strike if m.strike is not None else cur.strike
        cur.close_ms = m.close_ms if m.close_ms is not None else cur.close_ms


def load(dir: str | Path, days: list[str] | None = None) -> dict:
    """Grids per coin, built one UTC day at a time (raw rows of all days never held at once)."""
    spot: dict[str, dict[int, float]] = {c: {} for c in COINS}
    markets: dict[str, dict[str, ls.Market]] = {c: {} for c in COINS}
    results: dict[str, str] = {}
    for day in (days if days is not None else list_days(dir)):
        data = load_day(dir, day)
        data["spot"].sort(key=lambda r: r["ts_ms"])
        for c, (prod, _s) in COINS.items():
            spot[c].update(ls.spot_grid(data["spot"], prod))
        book_by: dict[str, list[dict]] = defaultdict(list)
        for r in data["book"]:
            c = series_of(r.get("ticker") or "")
            if c:
                book_by[c].append(r)
        for c in COINS:
            _merge_markets(markets[c], ls.markets_from_book(book_by.get(c, [])))
        for r in data["settle"]:
            if r.get("ticker") and r.get("result") in ("yes", "no"):
                results[r["ticker"]] = r["result"]
        del data, book_by
    for c in COINS:
        spot[c] = dict(sorted(spot[c].items()))
        for m in markets[c].values():
            if any(b < a for a, b in zip(m.quote_secs, m.quote_secs[1:])):
                order = sorted(range(len(m.quote_secs)), key=m.quote_secs.__getitem__)
                m.quote_secs = [m.quote_secs[i] for i in order]
                m.quotes = [m.quotes[i] for i in order]
    return {
        "spot": spot,
        "ret": {c: ls.log_returns(g) for c, g in spot.items()},
        "markets": markets,
        "results": results,
    }


def day_of(sec: int) -> str:
    return datetime.fromtimestamp(sec, tz=timezone.utc).strftime("%Y-%m-%d")


# --- rolling trailing sigma (same window as lag_study.trailing_sigma1) ----------------

class RollingSigma:
    """std of 1 s returns in [sec - WINDOW, sec - SKIP) via prefix sums, O(1) per query."""

    def __init__(self, ret: dict[int, float]):
        self.lo = min(ret) if ret else 0
        hi = max(ret) + 1 if ret else 0
        n = hi - self.lo
        self.c, self.s1, self.s2 = array("d", [0.0]) * (n + 1), array("d", [0.0]) * (n + 1), array("d", [0.0]) * (n + 1)
        c = s1 = s2 = 0.0
        for i in range(n):
            x = ret.get(self.lo + i)
            if x is not None:
                c, s1, s2 = c + 1, s1 + x, s2 + x * x
            self.c[i + 1], self.s1[i + 1], self.s2[i + 1] = c, s1, s2
        self.n = n

    def _idx(self, sec: int) -> int:
        return min(self.n, max(0, sec - self.lo))

    def __call__(self, sec: int) -> float | None:
        a, b = self._idx(sec - WINDOW), self._idx(sec - SKIP)
        n = self.c[b] - self.c[a]
        if n < MIN_N:
            return None
        s1, s2 = self.s1[b] - self.s1[a], self.s2[b] - self.s2[a]
        var = (s2 - s1 * s1 / n) / (n - 1)
        return math.sqrt(var) if var > 0 else None


def find_events(spot: dict[int, float], sigma: RollingSigma, k: float) -> list[ls.Event]:
    """lag_study.find_events with the rolling sigma (horizon 5 s, refractory 30 s)."""
    events: list[ls.Event] = []
    next_ok = -1
    root = math.sqrt(HORIZON)
    for s in sorted(spot):
        if s < next_ok or s - HORIZON not in spot:
            continue
        mv = math.log(spot[s] / spot[s - HORIZON])
        sig = sigma(s)
        if sig is None:
            continue
        if abs(mv) >= k * sig * root:
            events.append(ls.Event(s, 1 if mv > 0 else -1, mv, sig))
            next_ok = s + REFRACTORY
    return events


def beta_at(x_ret: dict[int, float], y_ret: dict[int, float], sec: int) -> float | None:
    """OLS slope of y on x over [sec - WINDOW, sec - SKIP)."""
    xs, ys = [], []
    for s in range(sec - WINDOW, sec - SKIP):
        x, y = x_ret.get(s), y_ret.get(s)
        if x is not None and y is not None:
            xs.append(x)
            ys.append(y)
    if len(xs) < MIN_N:
        return None
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx <= 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx


# --- (a) cross-correlation -------------------------------------------------------------

def spot_xcorr(x_ret: dict[int, float], y_ret: dict[int, float], lags=LAGS) -> dict[int, dict]:
    """corr(x[t], y[t + lag]); positive lag = y after x."""
    out = {}
    for lag in lags:
        xs, ys = [], []
        for s, y in y_ret.items():
            x = x_ret.get(s - lag)
            if x is not None:
                xs.append(x)
                ys.append(y)
        out[lag] = {"corr": ls.pearson(xs, ys), "n": len(xs)}
    return out


# --- (b) event study -------------------------------------------------------------------

def spot_delays(events: list[ls.Event], grid: dict[int, float], sigma: RollingSigma) -> dict[int, int | None]:
    """Event sec -> seconds until C spot covered half its 30 s log response in the BTC direction.

    Missing key = no data; None = response < C's trailing sigma1 (no response).
    """
    out: dict[int, int | None] = {}
    for ev in events:
        t0 = ev.sec - HORIZON
        base, end = grid.get(t0), grid.get(ev.sec + MAX_WAIT)
        sig = sigma(ev.sec)
        if base is None or end is None or sig is None or ev.sec not in grid:
            continue
        resp = ev.direction * math.log(end / base)
        delay = None
        if resp >= sig:
            need = 0.5 * resp
            for s in range(t0, ev.sec + MAX_WAIT + 1):
                v = grid.get(s)
                if v is not None and ev.direction * math.log(v / base) >= need - 1e-15:
                    delay = s - ev.sec
                    break
        out[ev.sec] = delay
    return out


def kalshi_delays(events: list[ls.Event], markets: dict[str, ls.Market]) -> dict[int, int | None]:
    """Event sec -> half-response delay of the open market's YES mid (lag_study.response_delays)."""
    out: dict[int, int | None] = {}
    for d in ls.response_delays(events, markets, horizon=HORIZON, max_wait=MAX_WAIT):
        if d["sec"] not in out or out[d["sec"]] is None:
            out[d["sec"]] = d["delay_half"]
    return out


def summarize_delays(d: dict[int, int | None]) -> dict:
    vals = [v for v in d.values() if v is not None]
    return {"pairs": len(d), "responded": len(vals), "median": ls.quantile(vals, 0.5),
            "p25": ls.quantile(vals, 0.25), "p75": ls.quantile(vals, 0.75)}


def paired(a: dict[int, int | None], b: dict[int, int | None]) -> dict:
    diffs = [a[s] - b[s] for s in a if s in b and a[s] is not None and b[s] is not None]
    return {"n": len(diffs), "median": ls.quantile(diffs, 0.5),
            "share_ge2": (sum(1 for x in diffs if x >= 2) / len(diffs)) if diffs else None}


# --- (c) taker rule ----------------------------------------------------------------------

def taker_bets(events: list[ls.Event], coin: str, d: dict, sigmas: dict[str, RollingSigma],
               fair_kind: str, delay: int, margin: float, max_quote_age: int = 5,
               min_tte: int = 30) -> list[ls.Bet]:
    spot, ret = d["spot"], d["ret"]
    cs, btc = spot[coin], spot["BTC"]
    results = d["results"]
    bets: list[ls.Bet] = []
    done: set[str] = set()
    for ev in events:
        t = ev.sec + delay
        sig1 = sigmas[coin](ev.sec)
        if sig1 is None:
            continue
        if fair_kind == "own":
            px = cs.get(t)
        else:
            base, b0, b1 = cs.get(ev.sec - HORIZON), btc.get(ev.sec - HORIZON), btc.get(t)
            beta = beta_at(ret["BTC"], ret[coin], ev.sec)
            px = None if None in (base, b0, b1, beta) else base * math.exp(beta * math.log(b1 / b0))
        if px is None:
            continue
        sigma_annual = sigma_annual_from_bar_std(sig1 * math.sqrt(60.0))
        side = "yes" if ev.direction > 0 else "no"
        for m in d["markets"][coin].values():
            if m.ticker in done or m.ticker not in results or m.strike is None or m.close_ms is None:
                continue
            tte = m.close_ms / 1000.0 - t
            if tte < min_tte:
                continue
            q = ls.quote_at(m, t, max_quote_age)
            if q is None:
                continue
            p_yes = p_finish_above(px, m.strike, tte, sigma_annual)
            if p_yes is None:
                continue
            fair = p_yes if side == "yes" else 1.0 - p_yes
            i = QUOTE_KEYS.index(f"{side}_ask")
            ask, qty = usable(q[i]), q[i + 1]
            if ask is None or fair - ask - fee_per_contract(ask) < margin:
                continue
            c, cost, _fee = size_all_in(ask)
            if c <= 0:
                continue
            won = results[m.ticker] == side
            bets.append(ls.Bet(day_of(t), m.ticker, t, side, ask, fair, c, cost, won,
                               (c * 1.0 - cost) if won else -cost, depth_ok=qty is not None and qty >= c))
            done.add(m.ticker)
    return bets


def bet_stats(bets: list[ls.Bet]) -> dict:
    n = len(bets)
    lo95, hi95 = ls.day_block_ci(bets, 0.95)
    lo99, hi99 = ls.day_block_ci(bets, 0.99)
    return {
        "bets": n, "wins": sum(b.won for b in bets), "days": len({b.day for b in bets}),
        "pnl_total": sum(b.pnl for b in bets),
        "pnl_mean": sum(b.pnl for b in bets) / n if n else None,
        "staked": sum(b.cost for b in bets),
        "avg_ask": sum(b.ask for b in bets) / n if n else None,
        "avg_fair": sum(b.fair for b in bets) / n if n else None,
        "ci95": [lo95, hi95], "ci99": [lo99, hi99],
        "depth_ok": sum(b.depth_ok for b in bets),
    }


# --- driver --------------------------------------------------------------------------------

def run(dir: str | Path, days: list[str] | None = None, margin: float = 0.02, max_quote_age: int = 5) -> dict:
    d = load(dir, days)
    spot, ret, markets = d["spot"], d["ret"], d["markets"]
    rec_days = sorted({day_of(s) for s in spot["BTC"]})
    sigmas = {c: RollingSigma(ret[c]) for c in COINS}

    xc = {
        "BTC spot -> BTC Kalshi": ls.cross_correlation(ret["BTC"], markets["BTC"], LAGS),
    }
    for c in TARGETS:
        xc[f"BTC spot -> {c} Kalshi"] = ls.cross_correlation(ret["BTC"], markets[c], LAGS)
        xc[f"{c} spot -> {c} Kalshi"] = ls.cross_correlation(ret[c], markets[c], LAGS)
        xc[f"BTC spot -> {c} spot"] = spot_xcorr(ret["BTC"], ret[c], LAGS)
    peaks = {name: ls.peak_lag(v) for name, v in xc.items()}
    verdict_a = {}
    for c in TARGETS:
        p, pb, ps = peaks[f"BTC spot -> {c} Kalshi"], peaks["BTC spot -> BTC Kalshi"], peaks[f"BTC spot -> {c} spot"]
        verdict_a[c] = None if None in (p, pb, ps) else (p - pb >= 2 and p - ps >= 2)

    ev_out, rules = {}, []
    for k in KS:
        events = find_events(spot["BTC"], sigmas["BTC"], k)
        kd_btc = kalshi_delays(events, markets["BTC"])
        per = {"events": len(events), "BTC Kalshi": summarize_delays(kd_btc), "coins": {}}
        for c in TARGETS:
            kd, sd = kalshi_delays(events, markets[c]), spot_delays(events, spot[c], sigmas[c])
            pr = paired(kd, sd)
            per["coins"][c] = {"kalshi": summarize_delays(kd), "spot": summarize_delays(sd),
                               "paired_kalshi_minus_spot": pr,
                               "paired_vs_btc_kalshi": paired(kd, kd_btc),
                               "supported": (pr["n"] >= 30 and pr["median"] is not None and pr["median"] >= 2)
                               if k == 4.0 else None}
        ev_out[str(k)] = per
        for fair in FAIRS:
            for delay in ENTRY_DELAYS:
                by_coin = {c: taker_bets(events, c, d, sigmas, fair, delay, margin, max_quote_age) for c in TARGETS}
                pooled = [b for c in TARGETS for b in by_coin[c]]
                rules.append({"k": k, "fair": fair, "delay": delay,
                              "primary": k == PRIMARY["k"] and fair == PRIMARY["fair"] and delay == PRIMARY["delay"],
                              "pooled": bet_stats(pooled),
                              "coins": {c: bet_stats(by_coin[c]) for c in TARGETS},
                              "bet_list": [b.__dict__ for b in pooled]})
    prim = next(r for r in rules if r["primary"])["pooled"]
    lo99 = prim["ci99"][0]
    passed = (len(rec_days) >= MIN_DAYS and prim["bets"] >= MIN_BETS
              and lo99 is not None and not math.isnan(lo99) and lo99 > 0)
    return {
        "days": rec_days,
        "exploratory": len(rec_days) < MIN_DAYS,
        "spot_seconds": {c: len(spot[c]) for c in COINS},
        "markets": {c: len(markets[c]) for c in COINS},
        "settled": {c: sum(1 for t in markets[c] if t in d["results"]) for c in COINS},
        "xcorr": {name: {str(lag): v for lag, v in x.items()} for name, x in xc.items()},
        "peaks": peaks, "verdict_a": verdict_a,
        "events": ev_out, "rules": rules, "primary_pass": passed,
        "params": {"margin": margin, "max_quote_age": max_quote_age, "stake_usd": STAKE_USD},
    }


# --- report -----------------------------------------------------------------------------------

def _f(x, fmt="{:.3f}") -> str:
    return "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def _yn(v) -> str:
    return "n/a" if v is None else ("yes" if v else "no")


def _ci(ci) -> str:
    return f"[{_f(ci[0])}, {_f(ci[1])}]"


def report(r: dict) -> str:
    L = ["# Cross-coin lead-lag: BTC move -> ETH/SOL Kalshi 15m books", ""]
    if r["exploratory"]:
        L += [f"**EXPLORATORY: only {len(r['days'])} recorded day(s) (< {MIN_DAYS}). "
              "No conclusion either way.**", ""]
    L += [f"Days: {', '.join(r['days']) or 'none'}",
          "Spot seconds: " + ", ".join(f"{c} {n}" for c, n in r["spot_seconds"].items()),
          "Markets (settled): " + ", ".join(f"{c} {r['markets'][c]} ({r['settled'][c]})" for c in COINS),
          "", "## (a) Cross-correlation, lags -5..+30 s (positive = second series after the first)", "",
          "| pair | peak lag s | corr at peak | corr lag 0 | corr +1 | corr +2 | sum corr +2..+30 |",
          "|---|---:|---:|---:|---:|---:|---:|"]
    for name, x in r["xcorr"].items():
        pk = r["peaks"][name]
        tail = sum((x[str(lag)]["corr"] or 0.0) for lag in range(2, 31))
        L.append(f"| {name} | {_f(pk, '{:+d}')} | {_f(x[str(pk)]['corr'] if pk is not None else None)} | "
                 f"{_f(x['0']['corr'])} | {_f(x['1']['corr'])} | {_f(x['2']['corr'])} | {tail:.3f} |")
    L += ["", "Pre-registered (a): C Kalshi peak >= 2 s later than both the BTC Kalshi and the C spot peak: "
          + ", ".join(f"{c} **{_yn(v)}**" for c, v in r["verdict_a"].items()), ""]
    L += ["### Full table", "", "| lag | " + " | ".join(r["xcorr"]) + " |",
          "|---:|" + "---:|" * len(r["xcorr"])]
    for lag in LAGS:
        L.append(f"| {lag:+d} | " + " | ".join(_f(x[str(lag)]["corr"]) for x in r["xcorr"].values()) + " |")
    L.append(f"\nn per lag (first pair, lag 0): {next(iter(r['xcorr'].values()))['0']['n'] if r['xcorr'] else 0}")

    L += ["", "## (b) Event study: seconds from BTC event detection to half of the 30 s response", "",
          "| k | events | series | pairs | responded | median s | p25 | p75 |", "|---:|---:|---|---:|---:|---:|---:|---:|"]
    for k, e in r["events"].items():
        rows = [("BTC Kalshi", e["BTC Kalshi"])]
        for c, v in e["coins"].items():
            rows += [(f"{c} Kalshi", v["kalshi"]), (f"{c} spot", v["spot"])]
        for name, s in rows:
            L.append(f"| {k} | {e['events']} | {name} | {s['pairs']} | {s['responded']} | "
                     f"{_f(s['median'], '{}')} | {_f(s['p25'], '{}')} | {_f(s['p75'], '{}')} |")
    L += ["", "Paired delays (same event, both responded):", "",
          "| k | coin | C Kalshi - C spot: n | median s | share >= 2 s | C Kalshi - BTC Kalshi: n | median s |",
          "|---:|---|---:|---:|---:|---:|---:|"]
    for k, e in r["events"].items():
        for c, v in e["coins"].items():
            a, b = v["paired_kalshi_minus_spot"], v["paired_vs_btc_kalshi"]
            L.append(f"| {k} | {c} | {a['n']} | {_f(a['median'], '{}')} | {_f(a['share_ge2'], '{:.0%}')} | "
                     f"{b['n']} | {_f(b['median'], '{}')} |")
    e4 = r["events"].get("4.0")
    if e4:
        L += ["", "Pre-registered (b), k = 4: median paired C Kalshi - C spot >= 2 s on >= 30 pairs: "
              + ", ".join(f"{c} **{_yn(v['supported'])}**" for c, v in e4["coins"].items())]

    p = r["params"]
    L += ["", f"## (c) Taker: buy the ETH/SOL side in the BTC direction when fair - ask - fee >= {p['margin']}",
          "", f"${p['stake_usd']:.0f} all-in per bet, exact Kalshi fee, one bet per market per variant, held to settlement. "
          "P&L in $ per bet; CIs are day-block bootstrap.", "",
          "| k | fair | entry | coin | bets | won | days | avg ask | avg fair | P&L $ | mean $/bet | 95% CI | 99% CI | depth ok |",
          "|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|"]
    for rule in r["rules"]:
        tag = " **(primary)**" if rule["primary"] else ""
        for c, s in [("ETH+SOL", rule["pooled"])] + list(rule["coins"].items()):
            L.append(f"| {rule['k']:g} | {rule['fair']}{tag if c == 'ETH+SOL' else ''} | +{rule['delay']} s | {c} | "
                     f"{s['bets']} | {s['wins']} | {s['days']} | {_f(s['avg_ask'])} | {_f(s['avg_fair'])} | "
                     f"{s['pnl_total']:.2f} | {_f(s['pnl_mean'])} | {_ci(s['ci95'])} | {_ci(s['ci99'])} | "
                     f"{s['depth_ok']}/{s['bets']} |")
    prim = next(x for x in r["rules"] if x["primary"])["pooled"]
    L += ["", f"Pre-registered (c) primary (k = 4, own fair, entry +0 s, pooled): {prim['bets']} bets over "
          f"{len(r['days'])} day(s), 99% CI {_ci(prim['ci99'])} -> **{'PASS' if r['primary_pass'] else 'not passed'}** "
          f"(needs >= {MIN_DAYS} days, >= {MIN_BETS} bets, 99% CI lower bound > 0).", "",
          "Caveats: fills at the recorded top-of-book ask (1 s REST polls), no queue/latency/depth walk beyond the +2 s row; "
          "the other 23 rows (7 more rule variants x pooled/ETH/SOL) are multiple comparisons; the 'fair' is a lognormal digital, not a calibrated model. "
          "A pass here would justify a forward paper test, not live money."]
    return "\n".join(L) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dir", help="recordings directory")
    ap.add_argument("--days", nargs="*", help="UTC days (default: all)")
    ap.add_argument("--margin", type=float, default=0.02)
    ap.add_argument("--max-quote-age", type=int, default=5)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    r = run(a.dir, a.days or None, a.margin, a.max_quote_age)
    print(json.dumps(r, indent=2, default=str) if a.json else report(r))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
