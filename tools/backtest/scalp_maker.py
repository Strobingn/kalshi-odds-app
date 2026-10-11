"""Maker-side scalp backtest: enter with resting bids during dips, exit maker or taker.

Motivation (docs/scalp-advantages.md): Kalshi's published fee schedule charges
the 0.07 * C * P * (1-P) fee ONLY to orders immediately matched (takers);
resting orders that fill as makers pay no trading fee on the general schedule
(verified 2026-10 against https://kalshi.com/docs/kalshi-fee-schedule.pdf;
KXBTC15M is reported by third-party measurements as maker-fee-free — verify the
series multiplier live before shipping). This script re-runs the dip-buy scalp
with a maker entry to see if the economics flip.

Fill model on 1-minute bars (honest limits):
- At the dip minute t (same EMA(5) dip rule as scalp_grid.py), place a RESTING
  bid at mid_t - k cents. It is maker-valid only if that price is below the
  current ask close (otherwise it would take). The order rests up to W minutes.
- It fills at minute u iff that minute's bar LOW touches the bid
  (mid_low[u] = (yes_bid.low + yes_ask.low)/2 <= bid). This assumes the mid
  traded down THROUGH our price — optimistic on intrabar ordering (the bar
  could have hit our bid only in its final second, or hit it and kept falling).
  Adverse-selection markouts after the fill quantify part of this bias; the
  rest is flagged as a 1-minute-bar limitation in the doc.
- Exit mode A (maker): rest an ask at fill_mid + m cents for up to T minutes;
  if the bar HIGH touches it, exit at the ask with ZERO fee. Stop-loss (if
  enabled) crosses the spread immediately (taker fee). Timeout -> taker exit
  at bid close (fee).
- Exit mode B (taker): same TP/SL/timeout rules as scalp_grid.py, but the
  entry leg was a maker fill (zero entry fee; only the exit leg pays taker).

Grid: dip {1..5}, k {1..4}, m {2..6}, SL {none,3,5}, W {2,3,5}, T {3,5,8}.
Also reports: fill rate, maker-exit rate, adverse selection (mid markout after
fill), entry savings vs the scalp_grid taker entry (ask[t] - fill), the cached
mid price distribution + taker fee drag by price band (fee geography), and a
restricted re-run of the best taker combo (dip5,tp4,sl5,mh7) to entry mids
<= 15c / >= 85c.

The core loop is numpy-vectorized across all ~8k markets (16 candles each).
Read-only: touches no app code. Run:
    python tools/backtest/scalp_maker.py
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys
from collections import defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scalp_grid import EMA_ALPHA, MIN_TICK, MAX_TICK, fee_per_contract

CACHE = os.path.join(os.path.dirname(__file__), "cache", "candles")

DIPS = [1, 2, 3, 4, 5]          # cents below EMA(5), same as scalp_grid
OFFSETS = [1, 2, 3, 4]          # k: resting bid at mid - k cents
TPS = [2, 3, 4, 5, 6]           # m: maker ask at fill_mid + m cents
STOPS = [None, 3, 5]            # stop-loss cents (None = no stop)
ENTRY_WAITS = [2, 3, 5]         # W: minutes a resting bid lives
EXIT_TIMEOUTS = [3, 5, 8]       # T: minutes a resting ask lives
MODES = ("maker", "taker")


def load_arrays(cache_dir):
    """All markets -> (coin, end_ts, mids, asks, bids, mlow, mhigh) ndarrays,
    NaN for missing."""
    coins, starts = [], []
    rows_all = []
    for path in glob.glob(os.path.join(cache_dir, "*.json")):
        name = os.path.basename(path)
        coin = {"KXBTC": "BTC", "KXETH": "ETH", "KXSOL": "SOL"}.get(name[:5], "?")
        try:
            with open(path) as f:
                rows = json.load(f)
        except Exception:
            continue
        if len(rows) < 6:
            continue
        coins.append(coin)
        starts.append(rows[0]["end_ts"])
        rows_all.append(rows)
    order = np.argsort(starts)
    coins = [coins[i] for i in order]
    starts = [starts[i] for i in order]
    rows_all = [rows_all[i] for i in order]

    M, n = len(rows_all), max(len(r) for r in rows_all)
    mids = np.full((M, n), np.nan)
    asks = np.full((M, n), np.nan)
    bids = np.full((M, n), np.nan)
    mlow = np.full((M, n), np.nan)
    mhigh = np.full((M, n), np.nan)
    for i, rows in enumerate(rows_all):
        for j, r in enumerate(rows):
            ya, yb = r.get("yes_ask"), r.get("yes_bid")
            m = r.get("mid")
            if not (m is not None and math.isfinite(m)):
                m = np.nan
                if ya and yb:
                    ybc, yac = yb.get("close"), ya.get("close")
                    if ybc is not None and yac is not None:
                        m = (ybc + yac) / 2.0
            mids[i, j] = m
            if ya and ya.get("close") is not None:
                asks[i, j] = ya["close"]
            if yb and yb.get("close") is not None:
                bids[i, j] = yb["close"]
            if (ya and yb and ya.get("low") is not None
                    and yb.get("low") is not None):
                mlow[i, j] = (yb["low"] + ya["low"]) / 2.0
            if (ya and yb and ya.get("high") is not None
                    and yb.get("high") is not None):
                mhigh[i, j] = (yb["high"] + ya["high"]) / 2.0
    return coins, np.asarray(starts), mids, asks, bids, mlow, mhigh


def clip_arr(a):
    return np.minimum(MAX_TICK, np.maximum(MIN_TICK, a))


def ema_rows(x, alpha):
    out = np.full_like(x, np.nan)
    e = None
    for j in range(x.shape[1]):
        col = x[:, j]
        fill = np.where(np.isnan(col), 0.5, col)
        e = fill if e is None else alpha * fill + (1 - alpha) * e
        out[:, j] = e
    return out


def dip_minutes(mids, emas):
    """First dip minute per dip value -> dict d -> (M,) int index or -1."""
    out = {}
    n = mids.shape[1]
    valid = ~np.isnan(mids)
    for d in DIPS:
        cond = valid & (mids <= emas - d / 100.0)
        cond[:, :2] = False
        cond[:, min(13, n - 2) + 1:] = False
        hit = np.where(cond.any(axis=1), cond.argmax(axis=1), -1)
        out[d] = hit
    return out


def fee_vec(px):
    """Vectorized ceil_6dp(0.07*P*(1-P)) fee, dollars per contract."""
    p = np.minimum(0.999, np.maximum(0.001, px))
    return np.ceil(0.07 * p * (1.0 - p) * 1e6) / 1e6


class GridResult:
    def __init__(self):
        self.stats = {}   # (mode, combo) -> dict of scalars
        self.markout = {}  # (d,k,w) -> (mo1_mean, mo2_mean, saved_mean, n)
        self.fill = {}    # (d,k,w) -> (placed, filled)


def run_maker_grid(coins, starts, mids, asks, bids, mlow, mhigh):
    M, n = mids.shape
    last = n - 1
    rows_idx = np.arange(M)
    emas = ema_rows(mids, EMA_ALPHA)
    dips = dip_minutes(mids, emas)
    res = GridResult()

    for d in DIPS:
        t = dips[d]
        has = t >= 0
        for k in OFFSETS:
            ep = clip_arr(mids[rows_idx, np.maximum(t, 0)] - k / 100.0)
            ep = np.where(has, ep, np.nan)
            ask_t = asks[rows_idx, np.maximum(t, 0)]
            maker_ok = has & (ep < ask_t - 1e-9) & ~np.isnan(ep) & ~np.isnan(ask_t)
            for w in ENTRY_WAITS:
                placed = int(maker_ok.sum())
                # resting fill scan: first u in t+1..t+w with bar low <= ep
                fu = np.full(M, -1)
                for uoff in range(1, w + 1):
                    ui = np.minimum(t + uoff, last)
                    touch = (mlow[rows_idx, ui] <= ep) & ~np.isnan(mids[rows_idx, ui])
                    newly = maker_ok & (fu < 0) & touch
                    fu[newly] = ui[newly]
                filled = fu >= 0
                nf = int(filled.sum())
                res.fill[(d, k, w)] = (placed, nf)
                if nf == 0:
                    continue
                fmask = filled
                fmid = mids[rows_idx, np.maximum(fu, 0)]
                # markouts + entry savings (independent of exit combo)
                u1 = np.minimum(fu + 1, last)
                mo1 = (mids[rows_idx, u1] - fmid) * 100.0
                u2 = np.minimum(fu + 2, last)
                mo2a = (mids[rows_idx, u1] - fmid) * 100.0
                mo2b = (mids[rows_idx, u2] - fmid) * 100.0
                mo2 = np.fmin(mo2a, mo2b)
                saved = (ask_t - ep) * 100.0
                res.markout[(d, k, w)] = (float(np.nanmean(mo1)), float(np.nanmean(mo2)),
                                          float(np.nanmean(saved)), nf)
                bid_fu = bids[rows_idx, np.maximum(fu, 0)]

                for m in TPS:
                    for sl in STOPS:
                        for tt in EXIT_TIMEOUTS:
                            # exit scan: first SL touch vs first TP touch in
                            # v = fu+1 .. fu+tt (SL wins ties, pessimistic)
                            sl_min = np.full(M, 999)
                            tp_min = np.full(M, 999)
                            for voff in range(1, tt + 1):
                                vi = np.minimum(fu + voff, last)
                                inrange = (fu + voff) <= last
                                slv = mlow[rows_idx, vi] if sl else np.full(M, np.nan)
                                tpv = mhigh[rows_idx, vi]
                                if sl:
                                    cond = inrange & (slv <= fmid - sl / 100.0)
                                    sl_min = np.where(cond & (sl_min == 999), vi, sl_min)
                                cond = inrange & (tpv >= fmid + m / 100.0)
                                tp_min = np.where(cond & (tp_min == 999), vi, tp_min)
                            is_sl = (sl_min < 999) & ((tp_min == 999) | (sl_min < tp_min))
                            is_tp = (tp_min < 999) & ~is_sl
                            to_min = np.minimum(fu + tt, last)
                            ex = np.where(is_sl, sl_min, np.where(is_tp, tp_min, to_min))
                            bid_ex = bids[rows_idx, ex]
                            ok = fmask & ~np.isnan(bid_ex)
                            for mode in MODES:
                                ask_px = clip_arr(fmid + m / 100.0)
                                mk_ok = ok & (ask_px > bid_fu + 1e-9)
                                if mode == "maker":
                                    px = np.where(is_tp & mk_ok, ask_px, bid_ex)
                                    fee_x = np.where(is_tp & mk_ok, 0.0, fee_vec(bid_ex))
                                else:
                                    px = bid_ex
                                    fee_x = fee_vec(bid_ex)
                                pnl = (px - fee_x - ep) * 100.0  # entry fee = 0 (maker)
                                pnl_ok = pnl[ok]
                                combo = (d, k, m, sl, w, tt)
                                key = (mode, combo)
                                s = res.stats.setdefault(key, dict(
                                    placed=0, n=0, wins=0, gw=0.0, gl=0.0,
                                    maker_x=0, taker_x=0, sl_x=0, to_x=0))
                                s["placed"] += placed
                                s["n"] += int(ok.sum())
                                s["wins"] += int((pnl_ok > 0).sum())
                                s["gw"] += float(pnl_ok[pnl_ok > 0].sum())
                                s["gl"] += float(-pnl_ok[pnl_ok <= 0].sum())
                                if mode == "maker":
                                    s["maker_x"] += int((is_tp & mk_ok & ok).sum())
                                    s["taker_x"] += int((ok & ~(is_tp & mk_ok)).sum())
                                else:
                                    s["taker_x"] += int(ok.sum())
                                s["sl_x"] += int((is_sl & ok).sum())
                                s["to_x"] += int((~is_sl & ~is_tp & ok).sum())
    return res


def combo_row(s):
    n = s["n"]
    if n == 0:
        return None
    avg = (s["gw"] - s["gl"]) / n
    pf = s["gw"] / s["gl"] if s["gl"] > 0 else float("inf")
    return dict(n=n, win=s["wins"] / n, avg=avg, total=s["gw"] - s["gl"], pf=pf,
                maker_x=s["maker_x"], taker_x=s["taker_x"],
                sl_x=s["sl_x"], to_x=s["to_x"])


def mid_distribution(mids):
    bands = [(0.0, 0.15, "<=15c"), (0.15, 0.35, "15-35c"), (0.35, 0.65, "35-65c"),
             (0.65, 0.85, "65-85c"), (0.85, 1.01, ">=85c")]
    v = mids[~np.isnan(mids)]
    out = []
    for lo, hi, label in bands:
        sel = v[(v >= lo) & (v < hi)]
        if len(sel) == 0:
            out.append((label, 0, 0.0, 0.0))
            continue
        p2 = np.minimum(MAX_TICK, np.maximum(MIN_TICK, sel + 0.05))
        rt_fee = (fee_vec(sel) + fee_vec(p2)) * 100.0
        out.append((label, len(sel), len(sel) / len(v) * 100.0, float(rt_fee.mean())))
    return out, len(v)


def restricted_taker_rerun(mids, asks, bids, lo=None, hi=None, penalty=0.5):
    """Best taker combo (dip5,tp4,sl5,mh7) restricted to entry mids in band."""
    emas = ema_rows(mids, EMA_ALPHA)
    t = dip_minutes(mids, emas)[5]
    M, n = mids.shape
    rows_idx = np.arange(M)
    valid = (t >= 0)
    mid_t = mids[rows_idx, np.maximum(t, 0)]
    if lo is not None:
        valid &= mid_t >= lo
    if hi is not None:
        valid &= mid_t < hi
    valid &= ~np.isnan(mid_t)
    ask_t = asks[rows_idx, np.maximum(t, 0)]
    valid &= ~np.isnan(ask_t)
    pnls = []
    for i in np.where(valid)[0]:
        ti = t[i]
        base = mids[i, ti]
        horizon = min(ti + 7, n - 1)
        ex, kind = horizon, "time"
        for u in range(ti + 1, horizon + 1):
            mu = mids[i, u]
            if np.isnan(mu):
                continue
            dc = (mu - base) * 100.0
            if dc >= 4.0:
                ex, kind = u, "tp"
                break
            if dc <= -5.0:
                ex, kind = u, "sl"
                break
        bx = bids[i, ex]
        if np.isnan(bx):
            continue
        epx = min(MAX_TICK, ask_t[i] + penalty / 100.0)
        xpx = max(MIN_TICK, bx - penalty / 100.0)
        pnl = (xpx - fee_per_contract(xpx) - epx - fee_per_contract(epx)) * 100.0
        pnls.append(pnl)
    if not pnls:
        return None
    a = np.asarray(pnls)
    return len(a), float((a > 0).mean()), float(a.mean()), float(a.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=CACHE)
    args = ap.parse_args()

    coins, starts, mids, asks, bids, mlow, mhigh = load_arrays(args.cache)
    print(f"markets loaded: {mids.shape[0]} x {mids.shape[1]} candles",
          file=sys.stderr)

    res = run_maker_grid(coins, starts, mids, asks, bids, mlow, mhigh)

    print("## Resting-bid fill rates (placed during dip minutes)\n")
    print("| dip | k | W (min) | placed | filled | fill rate | avg markout +1min ¢ | avg worst 2min ¢ | avg entry saved ¢ |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for d in DIPS:
        for k in OFFSETS:
            for w in ENTRY_WAITS:
                p, f = res.fill[(d, k, w)]
                mo = res.markout.get((d, k, w))
                if mo:
                    print(f"| {d} | {k} | {w} | {p} | {f} | {f / p * 100:.1f}% "
                          f"| {mo[0]:+.2f} | {mo[1]:+.2f} | {mo[2]:+.2f} |")
                else:
                    print(f"| {d} | {k} | {w} | {p} | {f} | {f / p * 100:.1f}% | - | - | - |")

    for mode in MODES:
        rows = []
        for (md, combo), s in res.stats.items():
            if md != mode:
                continue
            r = combo_row(s)
            if r is None or r["n"] < 200:
                continue
            rows.append((combo, r))
        rows.sort(key=lambda cr: -cr[1]["avg"])
        print(f"\n## Exit mode: {mode.upper()} — top 10 by avg cents/trade "
              f"(filled >= 200)\n")
        print("| dip | k | m | sl | W | T | filled | win% | avg ¢ | total ¢ | PF | makerX | takerX | SL exits | TO exits |")
        print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for (d, k, m, sl, w, tt), r in rows[:10]:
            sls = "-" if sl is None else sl
            print(f"| {d} | {k} | {m} | {sls} | {w} | {tt} "
                  f"| {r['n']} | {r['win'] * 100:.1f}% | {r['avg']:+.3f} "
                  f"| {r['total']:+.0f} | {r['pf']:.2f} | {r['maker_x']} "
                  f"| {r['taker_x']} | {r['sl_x']} | {r['to_x']} |")
        pos = [cr for cr in rows if cr[1]["avg"] > 0]
        best = rows[0][1]["avg"] if rows else float("nan")
        print(f"\npositive combos (mode={mode}): {len(pos)} / {len(rows)} "
              f"with filled>=200; best avg {best:+.3f} ¢/trade")

    dist, total = mid_distribution(mids)
    print("\n## Mid price distribution + round-trip taker fee by band\n")
    print("| mid band | minutes | share | avg RT taker fee ¢ |")
    print("|---|---:|---:|---:|")
    for label, cnt, share, fee in dist:
        print(f"| {label} | {cnt} | {share:.1f}% | {fee:.2f} |")

    print("\n## Best taker combo (dip5,tp4,sl5,mh7) restricted by entry mid "
          "(0.5c/side penalty, taker fees both legs)\n")
    print("| restriction | trades | win% | avg ¢/trade | total ¢ |")
    print("|---|---:|---:|---:|---:|")
    for label, lo, hi in (("unrestricted", None, None),
                          ("mid <= 15c", 0.0, 0.15),
                          ("mid >= 85c", 0.85, None),
                          ("mid in 15-85c", 0.15, 0.85)):
        r = restricted_taker_rerun(mids, asks, bids, lo, hi)
        if r is None:
            print(f"| {label} | 0 | - | - | - |")
        else:
            n, wr, avg, tot = r
            print(f"| {label} | {n} | {wr * 100:.1f}% | {avg:+.3f} | {tot:+.0f} |")


if __name__ == "__main__":
    main()
