#!/usr/bin/env python3
"""Lead-lag study: Coinbase BTC spot vs Kalshi KXBTC15M YES mid (1 s grid).

Input: a recordings directory from the app (see recordings.py for the format).

  (a) Cross-correlation of 1 s spot log-returns with 1 s changes in the
      Kalshi YES mid, pooled over markets, for lags -10..+30 s. A peak at a
      positive lag L means the Kalshi mid follows spot by about L seconds.
  (b) Event study: a spot move of at least k sigma within 5 s (sigma = trailing
      std of 1 s log-returns, scaled by sqrt(5)); for each open market, the
      seconds from detection until its YES mid covered half of its 30 s
      response in the spot direction (pairs whose mid did not move >= 1c
      that way count as no response).
  (c) Taker rule: at each event, digital fair = Phi(d2) (tools/backtest
      pipeline.p_finish_above) with sigma from the trailing 1 s returns scaled
      to 1 minute, then annualized like the app. Buy the side whose fair
      exceeds its recorded ask + Kalshi taker fee + margin, at that ask
      ($5 all-in, exact fee), hold to settlement. One bet per market. P&L with
      a day-block bootstrap CI.

Fills are assumed at the last recorded top-of-book ask at or before the event
second (and no older than --max-quote-age). Queue position, latency and depth
beyond the top are ignored; --require-depth only keeps bets whose top-of-book
size covers the whole order.

Prints a Markdown report (or JSON with --json). Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from bisect import bisect_right
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

from pipeline import (  # noqa: E402
    STAKE_USD,
    fee_per_contract,
    p_finish_above,
    sigma_annual_from_bar_std,
    size_all_in,
    usable,
)
from recordings import load_days  # noqa: E402

TICK = 0.01
LAGS = list(range(-10, 31))


# --- 1 s grids -------------------------------------------------------------------

def spot_grid(spot_rows: list[dict], product: str = "BTC-USD") -> dict[int, float]:
    """Last price in each epoch second, forward-filled over gaps <= 10 s."""
    last: dict[int, float] = {}
    for r in spot_rows:
        if r.get("product") != product or r.get("price") is None or r["price"] <= 0:
            continue
        last[r["ts_ms"] // 1000] = r["price"]
    return _ffill(last, max_gap=10)


def _ffill(points: dict[int, float], max_gap: int) -> dict[int, float]:
    if not points:
        return {}
    secs = sorted(points)
    out: dict[int, float] = {}
    for a, b in zip(secs, secs[1:] + [secs[-1] + 1]):
        v = points[a]
        for s in range(a, min(b, a + max_gap + 1)):
            out[s] = v
    return out


def mid_of(r: dict) -> float | None:
    bid, ask = usable(r.get("yes_bid")), usable(r.get("yes_ask"))
    if bid is None or ask is None or ask < bid:
        return None
    return (bid + ask) / 2.0


@dataclass
class Market:
    ticker: str
    strike: float | None
    close_ms: int | None
    mids: dict[int, float]          # second -> YES mid (forward-filled)
    quote_secs: list[int]           # seconds with a book row (sorted)
    quotes: list[dict]              # book rows aligned with quote_secs


def markets_from_book(book_rows: list[dict]) -> dict[str, Market]:
    by: dict[str, list[dict]] = defaultdict(list)
    for r in book_rows:
        if r.get("ticker"):
            by[r["ticker"]].append(r)
    out: dict[str, Market] = {}
    for t, rows in by.items():
        rows.sort(key=lambda r: r["ts_ms"])
        strike = next((r["strike"] for r in reversed(rows) if r.get("strike")), None)
        close_ms = next((r["close_ms"] for r in reversed(rows) if r.get("close_ms")), None)
        pts: dict[int, float] = {}
        for r in rows:
            m = mid_of(r)
            if m is not None:
                pts[r["ts_ms"] // 1000] = m
        mids = _ffill(pts, max_gap=6)  # heartbeat is 5 s
        if close_ms is not None:
            mids = {s: v for s, v in mids.items() if s * 1000 < close_ms}
        out[t] = Market(t, strike, close_ms, mids, [r["ts_ms"] // 1000 for r in rows], rows)
    return out


def log_returns(grid: dict[int, float]) -> dict[int, float]:
    return {s: math.log(v / grid[s - 1]) for s, v in grid.items() if s - 1 in grid and grid[s - 1] > 0 and v > 0}


def diffs(grid: dict[int, float]) -> dict[int, float]:
    return {s: v - grid[s - 1] for s, v in grid.items() if s - 1 in grid}


# --- (a) cross-correlation ----------------------------------------------------------

def pearson(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx <= 0 or syy <= 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def cross_correlation(spot_ret: dict[int, float], markets: dict[str, Market], lags=LAGS) -> dict[int, dict]:
    """corr(spot_ret[t], dmid[t + lag]) pooled over markets; positive lag = Kalshi after spot."""
    dms = {t: diffs(m.mids) for t, m in markets.items()}
    out: dict[int, dict] = {}
    for lag in lags:
        xs: list[float] = []
        ys: list[float] = []
        for dm in dms.values():
            for s, y in dm.items():
                x = spot_ret.get(s - lag)
                if x is not None:
                    xs.append(x)
                    ys.append(y)
        out[lag] = {"corr": pearson(xs, ys), "n": len(xs)}
    return out


def peak_lag(xc: dict[int, dict]) -> int | None:
    best = [(v["corr"], lag) for lag, v in xc.items() if v["corr"] is not None]
    return max(best)[1] if best else None


# --- (b) event study -----------------------------------------------------------------

@dataclass
class Event:
    sec: int          # second the 5 s move crossed k sigma
    direction: int    # +1 up, -1 down
    move: float       # 5 s log-return
    sigma1: float     # trailing 1 s return std


def _std(xs: list[float]) -> float | None:
    if len(xs) < 2:
        return None
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def trailing_sigma1(spot_ret: dict[int, float], sec: int, window: int = 900, skip: int = 5, min_n: int = 30) -> float | None:
    """Std of 1 s log-returns in [sec - window, sec - skip) — excludes the move itself."""
    xs = [spot_ret[s] for s in range(sec - window, sec - skip) if s in spot_ret]
    if len(xs) < min_n:
        return None
    sd = _std(xs)
    return sd if sd and sd > 0 else None


def find_events(spot: dict[int, float], spot_ret: dict[int, float], k: float, horizon: int = 5,
                refractory: int = 30) -> list[Event]:
    events: list[Event] = []
    next_ok = -1
    for s in sorted(spot):
        if s < next_ok or s - horizon not in spot:
            continue
        sig = trailing_sigma1(spot_ret, s)
        if sig is None:
            continue
        mv = math.log(spot[s] / spot[s - horizon])
        if abs(mv) >= k * sig * math.sqrt(horizon):
            events.append(Event(s, 1 if mv > 0 else -1, mv, sig))
            next_ok = s + refractory
    return events


def response_delays(events: list[Event], markets: dict[str, Market], horizon: int = 5,
                    max_wait: int = 30, min_move: float = TICK) -> list[dict]:
    """Per (event, open market): how fast the YES mid followed the spot move.

    ``base`` = mid at the start of the 5 s move, ``resp`` = its change in the
    spot direction ``max_wait`` s after detection. If ``resp`` >= one tick,
    ``delay_half`` is the first second (relative to detection, so it can be
    negative) at which the mid had covered half of ``resp`` (at least a tick);
    otherwise the pair counts as "no response" (None).
    """
    out = []
    for ev in events:
        t0 = ev.sec - horizon
        for m in markets.values():
            base = m.mids.get(t0)
            end = m.mids.get(ev.sec + max_wait)
            if base is None or end is None or ev.sec not in m.mids:
                continue
            resp = ev.direction * (end - base)
            delay = None
            if resp >= min_move - 1e-9:
                need = max(min_move, 0.5 * resp) - 1e-9
                for s in range(t0, ev.sec + max_wait + 1):
                    v = m.mids.get(s)
                    if v is not None and ev.direction * (v - base) >= need:
                        delay = s - ev.sec
                        break
            out.append({"sec": ev.sec, "ticker": m.ticker, "direction": ev.direction,
                        "response": resp, "delay_half": delay})
    return out


def quantile(xs: list[float], q: float) -> float | None:
    if not xs:
        return None
    ys = sorted(xs)
    return ys[min(len(ys) - 1, max(0, int(round(q * (len(ys) - 1)))))]


# --- (c) taker rule --------------------------------------------------------------------

@dataclass
class Bet:
    day: str
    ticker: str
    sec: int
    side: str
    ask: float
    fair: float
    contracts: int
    cost: float
    won: bool
    pnl: float
    depth_ok: bool


def quote_at(m: Market, sec: int, max_age: int) -> dict | None:
    i = bisect_right(m.quote_secs, sec) - 1
    if i < 0 or sec - m.quote_secs[i] > max_age:
        return None
    return m.quotes[i]


def taker_bets(events: list[Event], markets: dict[str, Market], spot: dict[int, float],
               spot_ret: dict[int, float], results: dict[str, str], margin: float,
               max_quote_age: int = 5, min_tte: int = 30) -> list[Bet]:
    bets: list[Bet] = []
    done: set[str] = set()
    for ev in events:
        sig1 = trailing_sigma1(spot_ret, ev.sec)
        px = spot.get(ev.sec)
        if sig1 is None or px is None:
            continue
        sigma_annual = sigma_annual_from_bar_std(sig1 * math.sqrt(60.0))
        for m in markets.values():
            if m.ticker in done or m.ticker not in results or m.strike is None or m.close_ms is None:
                continue
            tte = m.close_ms / 1000.0 - ev.sec
            if tte < min_tte:
                continue
            q = quote_at(m, ev.sec, max_quote_age)
            if q is None:
                continue
            p_yes = p_finish_above(px, m.strike, tte, sigma_annual)
            if p_yes is None:
                continue
            best = None
            for side, fair, ask, qty in (
                ("yes", p_yes, usable(q.get("yes_ask")), q.get("yes_ask_qty")),
                ("no", 1.0 - p_yes, usable(q.get("no_ask")), q.get("no_ask_qty")),
            ):
                if ask is None:
                    continue
                edge = fair - ask - fee_per_contract(ask)
                if edge >= margin and (best is None or edge > best[0]):
                    best = (edge, side, fair, ask, qty)
            if best is None:
                continue
            _, side, fair, ask, qty = best
            c, cost, _fee = size_all_in(ask)
            if c <= 0:
                continue
            won = results[m.ticker] == side
            pnl = (c * 1.0 - cost) if won else -cost
            day = datetime.fromtimestamp(ev.sec, tz=timezone.utc).strftime("%Y-%m-%d")
            bets.append(Bet(day, m.ticker, ev.sec, side, ask, fair, c, cost, won, pnl,
                            depth_ok=qty is not None and qty >= c))
            done.add(m.ticker)
    return bets


def day_block_ci(bets: list[Bet], level: float = 0.95, iters: int = 4000, seed: int = 11) -> tuple[float, float]:
    """Bootstrap over whole UTC days (same as edge_search.py): CI of mean P&L per bet."""
    by_day: dict[str, list[float]] = defaultdict(list)
    for b in bets:
        by_day[b.day].append(b.pnl)
    days = list(by_day)
    if len(days) < 2:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    means = []
    for _ in range(iters):
        tot, n = 0.0, 0
        for _d in days:
            xs = by_day[days[rng.randrange(len(days))]]
            tot += sum(xs)
            n += len(xs)
        if n:
            means.append(tot / n)
    means.sort()
    a = (1.0 - level) / 2.0
    return means[int(a * (len(means) - 1))], means[int((1.0 - a) * (len(means) - 1))]


# --- driver ------------------------------------------------------------------------------

def run(dir: str | Path, days: list[str] | None = None, product: str = "BTC-USD", k: float = 4.0,
        margin: float = 0.02, max_quote_age: int = 5, require_depth: bool = False) -> dict:
    data = load_days(dir, days)
    spot = spot_grid(data["spot"], product)
    spot_ret = log_returns(spot)
    markets = markets_from_book(data["book"])
    results = {r["ticker"]: r["result"] for r in data["settle"]}

    xc = cross_correlation(spot_ret, markets)
    events = find_events(spot, spot_ret, k)
    delays = response_delays(events, markets)
    responded = [d["delay_half"] for d in delays if d["delay_half"] is not None]
    bets = taker_bets(events, markets, spot, spot_ret, results, margin, max_quote_age)
    if require_depth:
        bets = [b for b in bets if b.depth_ok]
    lo, hi = day_block_ci(bets)
    n = len(bets)
    return {
        "days": sorted({datetime.fromtimestamp(s, tz=timezone.utc).strftime("%Y-%m-%d") for s in spot}),
        "spot_seconds": len(spot),
        "markets": len(markets),
        "settled": len(results),
        "xcorr": {str(lag): v for lag, v in xc.items()},
        "peak_lag": peak_lag(xc),
        "events": len(events),
        "event_pairs": len(delays),
        "responded": len(responded),
        "delay_median": quantile(responded, 0.5),
        "delay_p25": quantile(responded, 0.25),
        "delay_p75": quantile(responded, 0.75),
        "bets": n,
        "wins": sum(1 for b in bets if b.won),
        "pnl_total": sum(b.pnl for b in bets),
        "pnl_mean": (sum(b.pnl for b in bets) / n) if n else None,
        "pnl_ci95": [lo, hi],
        "depth_ok": sum(1 for b in bets if b.depth_ok),
        "bet_list": [b.__dict__ for b in bets],
        "params": {"product": product, "k": k, "margin": margin, "max_quote_age": max_quote_age,
                   "require_depth": require_depth, "stake_usd": STAKE_USD},
    }


def _f(x, fmt="{:.3f}") -> str:
    return "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else fmt.format(x)


def report(r: dict) -> str:
    p = r["params"]
    lines = [
        "# Spot -> Kalshi lead-lag study",
        "",
        f"Days {', '.join(r['days']) or 'none'} · {r['spot_seconds']} spot seconds · "
        f"{r['markets']} markets ({r['settled']} settled)",
        "",
        "## (a) Cross-correlation, 1 s spot log-return vs 1 s YES-mid change",
        "",
        f"Peak lag: **{_f(r['peak_lag'], '{:+d}')} s** (positive = Kalshi follows spot)",
        "",
        "| lag s | corr | n |",
        "|---:|---:|---:|",
    ]
    for lag in LAGS:
        v = r["xcorr"][str(lag)]
        lines.append(f"| {lag:+d} | {_f(v['corr'])} | {v['n']} |")
    lines += [
        "",
        f"## (b) Event study: |5 s spot move| >= {p['k']} sigma",
        "",
        f"{r['events']} events · {r['event_pairs']} event x market pairs · "
        f"{r['responded']} with a >= 1c YES-mid response in the spot direction within 30 s",
        f"Seconds from detection to half the response: median {_f(r['delay_median'], '{}')} s, "
        f"p25 {_f(r['delay_p25'], '{}')} s, p75 {_f(r['delay_p75'], '{}')} s",
        "",
        f"## (c) Taker: fair - ask - fee >= {p['margin']} right after the move, hold to settlement",
        "",
        f"{r['bets']} bets · {r['wins']} won · P&L ${r['pnl_total']:.2f} "
        f"(mean ${_f(r['pnl_mean'], '{:.3f}')}/bet, day-block 95% CI "
        f"[{_f(r['pnl_ci95'][0])}, {_f(r['pnl_ci95'][1])}]) · "
        f"top-of-book depth covered {r['depth_ok']}/{r['bets']}",
        "",
        "Fills at the recorded top-of-book ask; no latency, queue or depth-walk model.",
    ]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dir", help="recordings directory (unzipped export)")
    ap.add_argument("--days", nargs="*", help="UTC days (default: all)")
    ap.add_argument("--product", default="BTC-USD")
    ap.add_argument("--k", type=float, default=4.0, help="event threshold in sigma (5 s move)")
    ap.add_argument("--margin", type=float, default=0.02, help="required fair - ask - fee")
    ap.add_argument("--max-quote-age", type=int, default=5)
    ap.add_argument("--require-depth", action="store_true")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    r = run(args.dir, args.days or None, args.product, args.k, args.margin, args.max_quote_age, args.require_depth)
    print(json.dumps(r, indent=2) if args.json else report(r))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
