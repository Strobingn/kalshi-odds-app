#!/usr/bin/env python3
"""Sub-second lead-lag: Coinbase BTC vs the Kalshi KXBTC15M price, from ws_recorder rows.

Earlier REST recordings (1 s polls) found Kalshi reacting "within ~1 s" of
Coinbase, but could not see anything shorter. This uses every update stamped
by one clock on one machine, on a 100 ms grid.

Pre-registered (fixed before any real data):
  (a) cross-correlation of 100 ms Coinbase BTC log-returns with 100 ms changes
      in the Kalshi YES mid, lags -1000..+3000 ms; peak lag in ms. A positive
      lag means Kalshi follows Coinbase.
  (b) event study: |Coinbase 1 s log-return| >= k sigma (k = 3, sigma = std of
      non-overlapping 1 s returns within the market's window), events >= 5 s
      apart. For each, ms until the Kalshi mid moved >= 1c in the same
      direction (searched 10 s). Also the share of events where Kalshi had
      ALREADY moved >= 1c that way in the previous 1 s (Kalshi led).
  Reading: an exploitable lag needs, for k = 3, >= 30 events, median response
  >= 300 ms, and < 30% "Kalshi already moved". Anything under ~150 ms is not
  reachable from a phone. The runner's network position differs from yours, so
  even a lag here is an upper bound on what you could use.

Stdlib only.  python3 ws_lag.py ws
"""
from __future__ import annotations

import argparse
import gzip
import math
import sys
from bisect import bisect_right
from pathlib import Path

GRID_MS = 100
LAGS = list(range(-10, 31))          # grid steps: -1000..+3000 ms
EVENT_BINS = 10                      # 1 s
SEARCH_BINS = 100                    # 10 s
MIN_GAP_BINS = 50                    # 5 s between events
MIN_MOVE = 0.01
K_VALUES = (3.0, 4.0)
SERIES = "KXBTC15M"
PRODUCT = "BTC-USD"


def load_rows(directory: str | Path) -> list:
    rows = []
    for path in sorted(Path(directory).glob("ws_*.csv.gz")):
        with gzip.open(path, "rt") as f:
            for line in f:
                if line.startswith("recv_ns"):
                    continue
                p = line.rstrip("\n").split(",")
                if len(p) < 7:
                    continue
                try:
                    rows.append([int(p[0]), p[1], p[2], p[3], p[4], p[5]])
                except ValueError:
                    continue
    rows.sort(key=lambda r: r[0])
    return rows


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def split(rows: list) -> tuple:
    """(coinbase [(ms, price)], {ticker: [(ms, mid)]}) for BTC."""
    cb, k = [], {}
    for r in rows:
        ms = r[0] // 1_000_000
        if r[1] == "cb" and r[2] == PRODUCT:
            p = _f(r[3])
            if p:
                cb.append((ms, p))
        elif r[1] == "k" and r[2].startswith(SERIES):
            bid, ask = _f(r[3]), _f(r[4])
            if bid is not None and ask is not None and 0 < bid <= ask < 1:
                k.setdefault(r[2], []).append((ms, (bid + ask) / 2.0))
    return cb, k


def grid(points: list, t0: int, n: int) -> list:
    """Forward-filled value at t0 + i*GRID_MS (None before the first point)."""
    times = [p[0] for p in points]
    out = []
    for i in range(n):
        j = bisect_right(times, t0 + i * GRID_MS) - 1
        out.append(points[j][1] if j >= 0 else None)
    return out


def pearson(xs: list, ys: list):
    n = len(xs)
    if n < 30:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx <= 0 or syy <= 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def market_series(cb: list, pts: list):
    """Aligned (price grid, mid grid) over a market's quote window, or None."""
    if len(pts) < 20 or not cb:
        return None
    t0, t1 = pts[0][0], pts[-1][0]
    n = (t1 - t0) // GRID_MS
    if n < 300:
        return None
    price = grid(cb, t0, n)
    mid = grid(pts, t0, n)
    if any(v is None for v in price) or any(v is None for v in mid):
        return None
    return price, mid


def analyze(rows: list) -> dict:
    cb, markets = split(rows)
    xs: dict = {L: ([], []) for L in LAGS}
    delays = {k: [] for k in K_VALUES}
    noresp = {k: 0 for k in K_VALUES}
    led = {k: 0 for k in K_VALUES}
    events = {k: 0 for k in K_VALUES}
    used = 0
    for tk, pts in markets.items():
        ser = market_series(cb, pts)
        if ser is None:
            continue
        used += 1
        price, mid = ser
        n = len(price)
        ret = [0.0] + [math.log(price[i] / price[i - 1]) for i in range(1, n)]
        dmid = [0.0] + [mid[i] - mid[i - 1] for i in range(1, n)]
        for L in LAGS:
            for i in range(max(1, -L), n - max(0, L)):
                xs[L][0].append(ret[i])
                xs[L][1].append(dmid[i + L])
        r1 = [math.log(price[i] / price[i - EVENT_BINS]) for i in range(EVENT_BINS, n, EVENT_BINS)]
        if len(r1) < 20:
            continue
        mean = sum(r1) / len(r1)
        sig = math.sqrt(sum((x - mean) ** 2 for x in r1) / len(r1))
        if sig <= 0:
            continue
        for k in K_VALUES:
            last = -MIN_GAP_BINS
            for i in range(EVENT_BINS, n - SEARCH_BINS):
                if i - last < MIN_GAP_BINS:
                    continue
                move = math.log(price[i] / price[i - EVENT_BINS])
                if abs(move) < k * sig:
                    continue
                last = i
                d = 1.0 if move > 0 else -1.0
                events[k] += 1
                if d * (mid[i] - mid[i - EVENT_BINS]) >= MIN_MOVE - 1e-9:
                    led[k] += 1
                    continue
                m0 = mid[i]
                hit = next((j for j in range(i + 1, i + SEARCH_BINS) if d * (mid[j] - m0) >= MIN_MOVE - 1e-9), None)
                if hit is None:
                    noresp[k] += 1
                else:
                    delays[k].append((hit - i) * GRID_MS)
    corr = {L: pearson(*xs[L]) for L in LAGS}
    best = max((L for L in LAGS if corr[L] is not None), key=lambda L: corr[L], default=None)
    return dict(markets=used, cb_updates=len(cb), kalshi_updates=sum(len(v) for v in markets.values()),
                corr=corr, peak_ms=None if best is None else best * GRID_MS,
                delays=delays, noresp=noresp, led=led, events=events)


def q(xs: list, p: float):
    if not xs:
        return None
    s = sorted(xs)
    return s[min(len(s) - 1, int(p * (len(s) - 1) + 0.5))]


def report(r: dict) -> str:
    L = ["# Sub-second lead-lag: Coinbase BTC vs Kalshi KXBTC15M", "",
         f"Markets used: {r['markets']} · Coinbase updates: {r['cb_updates']} · Kalshi quote updates: {r['kalshi_updates']}", ""]
    if r["markets"] == 0:
        return "\n".join(L + ["No usable data yet."])
    L += ["## (a) cross-correlation, 100 ms grid", "",
          f"Peak lag: **{r['peak_ms']} ms** (positive = Kalshi follows Coinbase)", "",
          "| lag ms | corr |", "|---:|---:|"]
    for lag in LAGS:
        c = r["corr"][lag]
        L.append(f"| {lag * GRID_MS} | {'' if c is None else f'{c:.3f}'} |")
    L += ["", "## (b) event study: Coinbase 1 s move >= k sigma", "",
          "| k | events | Kalshi already moved | no response in 10 s | responded | median ms | p25 | p75 |",
          "|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for k in K_VALUES:
        d = r["delays"][k]
        L.append(f"| {k:g} | {r['events'][k]} | {r['led'][k]} | {r['noresp'][k]} | {len(d)} | "
                 f"{q(d, .5) if d else ''} | {q(d, .25) if d else ''} | {q(d, .75) if d else ''} |")
    d3 = r["delays"][3.0]
    ev = r["events"][3.0]
    ok = bool(d3) and ev >= 30 and q(d3, .5) >= 300 and r["led"][3.0] / max(ev, 1) < 0.30
    L += ["", f"Pre-registered reading (k = 3): {'a response lag of 300 ms+ exists on this sample' if ok else 'no exploitable lag on this sample'}"
              f" ({ev} events). With few days this is exploratory; the runner's network position differs from a phone's."]
    return "\n".join(L)


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dir")
    a = ap.parse_args(argv)
    print(report(analyze(load_rows(a.dir))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
