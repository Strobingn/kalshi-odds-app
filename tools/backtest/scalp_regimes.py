"""Regime analysis for the scalp method: spot lead-lag, time-of-day, quote regime.

Companion to scalp_grid.py / scalp_maker.py (docs/scalp-advantages.md). Three
angles, all read-only over the same cache:

1. SPOT LEAD-LAG: minute-aligned Kalshi BTC mid returns vs Coinbase BTC-USD
   log returns. Cross-correlation at lags -5..+5 minutes, for three timestamp
   conventions (spot bar-start vs Kalshi bar-end offset by -60/0/+60 s). Then
   the tradable question: if spot moved >= theta bps over the last L minutes,
   does trading the Kalshi mid in spot's direction for the next H minutes clear
   costs? Entry at ask close / exit at bid close (taker both legs, fees).

   LIMITATION: both series are 1-minute bars, so lead-lag INSIDE a minute is
   invisible; a true seconds-scale edge needs tick data (flagged in the doc).

2. TIME-OF-DAY: dip-conditioned vs unconditional +2c/10min bounce rate by UTC
   hour of the candle end timestamp (crypto markets run 24/7, so hours are
   well populated).

3. QUOTE REGIME (creative angle): does the dip signal work better when the
   visible spread is tight (<= 2c) vs wide, and early in the 15-min window
   (minutes 2-7) vs late (minutes 8-13)? Tight-spread minutes are where a
   scalp could actually exit near mid; late-window minutes are where the mid
   is closest to a 0/1 step function (little mean-reversion room).

Run:  python tools/backtest/scalp_regimes.py
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
from scalp_grid import EMA_ALPHA, MAX_TICK, MIN_TICK, fee_per_contract

CACHE = os.path.join(os.path.dirname(__file__), "cache")


# ---------------------------------------------------------------- loading

def load_kalshi_mid_by_minute(coin_prefix, cache_dir):
    """minute end_ts -> mid close, for one coin series (BTC only for lead-lag)."""
    out = {}
    for path in glob.glob(os.path.join(cache_dir, "candles", f"{coin_prefix}*.json")):
        try:
            with open(path) as f:
                rows = json.load(f)
        except Exception:
            continue
        for r in rows:
            m = r.get("mid")
            if m is None or not math.isfinite(m):
                ya, yb = r.get("yes_ask"), r.get("yes_bid")
                if ya and yb and ya.get("close") and yb.get("close"):
                    m = (ya["close"] + yb["close"]) / 2.0
                else:
                    continue
            ts = r["end_ts"]
            # last write wins; overlapping markets are rare and nearly equal
            out[ts] = m
    return out


def load_spot(coin, cache_dir):
    """Coinbase 1-min candles [ts, low, high, open, close, vol] -> ts -> close."""
    with open(os.path.join(cache_dir, f"spot_{coin}.json")) as f:
        rows = json.load(f)
    return {int(r[0]): float(r[4]) for r in rows}


def aligned_returns(kal, spot, shift):
    """Kalshi mid at minute T vs spot close at minute T+shift. Returns two
    aligned 1-min log-return arrays (kal_ret[i], spot_ret[i])."""
    common = sorted(set(kal) & {t + shift for t in spot})
    if len(common) < 100:
        return None, None
    levels_k = np.array([kal[t] for t in common])
    levels_s = np.array([spot[t - shift] for t in common])
    rk = np.diff(np.log(np.clip(levels_k, 1e-6, None)))
    rs = np.diff(np.log(np.clip(levels_s, 1e-6, None)))
    return rk, rs


def leadlag_report(kal, spot):
    print("## Spot (Coinbase BTC-USD) vs Kalshi KXBTC15M mid: 1-min return "
          "cross-correlation\n")
    print("| spot offset (s) | lag 0 | lag +1 (spot leads) | lag +2 | lag +3 "
          "| lag +5 | lag -1 (kalshi leads) | n minutes |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|")
    best = None
    for shift in (-60, 0, 60):
        rk, rs = aligned_returns(kal, spot, shift)
        if rk is None:
            continue
        row = []
        for lag in (0, 1, 2, 3, 5, -1):
            if lag == 0:
                a, b = rk, rs
            elif lag > 0:
                a, b = rk[lag:], rs[:-lag]
            else:
                a, b = rk[:lag], rs[-lag:]
            row.append(np.corrcoef(a, b)[0, 1])
        print(f"| {shift} | {row[0]:.4f} | {row[1]:.4f} | {row[2]:.4f} "
              f"| {row[3]:.4f} | {row[4]:.4f} | {row[5]:.4f} | {len(rk)} |")
        if best is None or abs(row[0]) > abs(best[1][0]):
            best = (shift, row)
    return best


def spot_follow_trade(kal, spot, shift=60):
    """Signal: spot moved >= theta bps over last L min -> trade Kalshi mid in
    that direction for H min, entry ask close / exit bid close, taker fees.
    `kal` maps minute ts -> (mid, ask, bid); spot ts is a bar START, so the
    Kalshi end_ts aligning with the same wall minute is t_spot + 60 (shift)."""
    print("\n## Hypothetical: trade Kalshi in spot's direction after a spot move "
          "(entry ask / exit bid, taker fees both legs)\n")
    print("| L (min) | theta (bps) | H (min) | trades | hit rate | avg gross ¢ "
          "| avg net ¢ | total net ¢ |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|")
    ts_all = sorted(kal)
    ts_set = set(ts_all)
    spot_ts = {t + shift: c for t, c in spot.items()}
    # contiguous run handling: use only minutes present in both
    common = [t for t in ts_all if t in spot_ts]
    for L in (1, 2, 3, 5):
        for theta in (0, 5, 10, 20, 30):
            for H in (1, 2, 3):
                n = hits = 0
                gross = 0.0
                net = 0.0
                for t in common:
                    t0 = t - 60 * L
                    tH = t + 60 * H
                    if t0 not in spot_ts or tH not in kal or t not in kal:
                        continue
                    p0, p1 = spot_ts[t0], spot_ts[t]
                    if p0 <= 0:
                        continue
                    move_bps = (p1 / p0 - 1.0) * 1e4
                    if abs(move_bps) < theta:
                        continue
                    mid, ask, bid = kal[t]
                    midH, _aH, bidH = kal[tH]
                    if ask is None or bidH is None:
                        continue
                    sgn = 1.0 if move_bps > 0 else -1.0
                    g = sgn * (midH - mid) * 100.0          # gross cents on mid
                    epx = min(MAX_TICK, ask)
                    xpx = max(MIN_TICK, bidH)
                    nn = (sgn * (midH - mid) - (fee_vec1(epx) + fee_vec1(xpx))
                          - ((epx - mid) + (midH - xpx))) * 100.0
                    n += 1
                    if g > 0:
                        hits += 1
                    gross += g
                    net += nn
                if n >= 200:
                    print(f"| {L} | {theta} | {H} | {n} | {hits / n * 100:.1f}% "
                          f"| {gross / n:+.3f} | {net / n:+.3f} | {net:+.0f} |")


def fee_vec1(p):
    p = min(0.999, max(0.001, p))
    return math.ceil(0.07 * p * (1 - p) * 1e6) / 1e6


def load_kalshi_triplet(coin_prefix, cache_dir):
    """minute ts -> (mid, ask_close, bid_close) merged across all markets."""
    kal = {}
    for path in glob.glob(os.path.join(cache_dir, "candles", f"{coin_prefix}*.json")):
        try:
            with open(path) as f:
                rows = json.load(f)
        except Exception:
            continue
        for r in rows:
            m = r.get("mid")
            ya, yb = r.get("yes_ask"), r.get("yes_bid")
            ask = ya.get("close") if ya else None
            bid = yb.get("close") if yb else None
            if m is None and ask and bid:
                m = (ask + bid) / 2.0
            if m is None:
                continue
            kal[r["end_ts"]] = (m, ask, bid)
    return kal


# ------------------------------------------------------- time of day / regime

def load_all_candles(cache_dir):
    for path in sorted(glob.glob(os.path.join(cache_dir, "candles", "*.json"))):
        try:
            with open(path) as f:
                rows = json.load(f)
        except Exception:
            continue
        if len(rows) >= 12:
            yield rows


def ema_list(vals, alpha):
    out = []
    e = None
    for v in vals:
        e = v if e is None else alpha * v + (1 - alpha) * e
        out.append(e)
    return out


def time_of_day(cache_dir):
    hours = defaultdict(lambda: dict(n=0, dip=0, hit=0, dip_hit=0))
    for rows in load_all_candles(cache_dir):
        n = len(rows)
        mids = [r.get("mid") for r in rows]
        emas = ema_list([m if m is not None else 0.5 for m in mids], EMA_ALPHA)
        for i in range(2, min(13, n - 2) + 1):
            m = mids[i]
            if m is None:
                continue
            horizon = min(i + 10, n - 1)
            if horizon <= i:
                continue
            hr = int((rows[i]["end_ts"] % 86400) // 3600)
            dip = m <= emas[i] - 0.01
            hit = any(mids[u] is not None and mids[u] - m >= 0.02
                      for u in range(i + 1, horizon + 1))
            h = hours[hr]
            h["n"] += 1
            h["hit"] += hit
            if dip:
                h["dip"] += 1
                h["dip_hit"] += hit
    print("\n## +2c bounce within 10 min by UTC hour (all coins, all minutes "
          "vs dip minutes)\n")
    print("| UTC hour | minutes | all-min rate | dip-min rate | dip share | lift |")
    print("|---:|---:|---:|---:|---:|---:|")
    for hr in range(24):
        h = hours[hr]
        if h["n"] == 0:
            continue
        ar = h["hit"] / h["n"] * 100
        dr = h["dip_hit"] / h["dip"] * 100 if h["dip"] else 0
        print(f"| {hr:02d}:00 | {h['n']} | {ar:.1f}% | {dr:.1f}% "
              f"| {h['dip'] / h['n'] * 100:.0f}% | {dr - ar:+.1f}pp |")


def regime_analysis(cache_dir):
    """Spread regime (tight <=2c vs wide >2c) and window position (early vs
    late): dip-conditioned bounce rate and best-taker-combo avg PnL."""
    reg = defaultdict(lambda: dict(n=0, dip=0, hit=0, dip_hit=0,
                                   pnls=[], dip_pnls=[]))
    for rows in load_all_candles(cache_dir):
        n = len(rows)
        mids, asks, bids = [], [], []
        for r in rows:
            mids.append(r.get("mid"))
            ya, yb = r.get("yes_ask"), r.get("yes_bid")
            asks.append(ya.get("close") if ya else None)
            bids.append(yb.get("close") if yb else None)
        emas = ema_list([m if m is not None else 0.5 for m in mids], EMA_ALPHA)
        # best combo rerun: dip5,tp4,sl5,mh7 (needs dip=5 first trigger)
        t5 = None
        for i in range(2, min(13, n - 2) + 1):
            if mids[i] is not None and mids[i] <= emas[i] - 0.05:
                t5 = i
                break
        for i in range(2, min(13, n - 2) + 1):
            m = mids[i]
            if m is None:
                continue
            horizon = min(i + 10, n - 1)
            if horizon <= i:
                continue
            spread = None
            if asks[i] is not None and bids[i] is not None:
                spread = (asks[i] - bids[i]) * 100.0
            early = i <= 7
            dims = []
            dims.append("tight" if (spread is not None and spread <= 2.0) else "wide/n.a.")
            dims.append("early(2-7)" if early else "late(8-13)")
            hit = any(mids[u] is not None and mids[u] - m >= 0.02
                      for u in range(i + 1, horizon + 1))
            dip = m <= emas[i] - 0.01
            for dname in dims:
                g = reg[dname]
                g["n"] += 1
                g["hit"] += hit
                if dip:
                    g["dip"] += 1
                    g["dip_hit"] += hit
        # one best-combo trade per market, bucketed by entry-minute regime
        if t5 is not None and asks[t5] is not None and bids[t5] is not None:
            base = mids[t5]
            horizon = min(t5 + 7, n - 1)
            ex, kind = horizon, "time"
            for u in range(t5 + 1, horizon + 1):
                if mids[u] is None:
                    continue
                dc = (mids[u] - base) * 100.0
                if dc >= 4.0:
                    ex, kind = u, "tp"
                    break
                if dc <= -5.0:
                    ex, kind = u, "sl"
                    break
            if bids[ex] is not None:
                spread5 = (asks[t5] - bids[t5]) * 100.0
                epx = min(MAX_TICK, asks[t5] + 0.005)
                xpx = max(MIN_TICK, bids[ex] - 0.005)
                pnl = (xpx - fee_vec1(xpx) - epx - fee_vec1(epx)) * 100.0
                for dname in (("tight" if spread5 <= 2.0 else "wide/n.a."),
                              "early(2-7)" if t5 <= 7 else "late(8-13)"):
                    reg[dname]["pnls"].append(pnl)
    print("\n## Regime breakdown: dip-conditioned bounce rate + best-combo "
          "(dip5,tp4,sl5,mh7) avg PnL\n")
    print("| regime | minutes | all-min bounce | dip-min bounce | dip share "
          "| trades | avg ¢/trade |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    order = ["tight", "wide/n.a.", "early(2-7)", "late(8-13)"]
    for name in order:
        g = reg[name]
        if g["n"] == 0:
            continue
        ar = g["hit"] / g["n"] * 100
        dr = g["dip_hit"] / g["dip"] * 100 if g["dip"] else 0
        if g["pnls"]:
            pnls = np.asarray(g["pnls"])
            tr = f"{len(pnls)}"
            avg = f"{pnls.mean():+.3f}"
        else:
            tr, avg = "0", "-"
        print(f"| {name} | {g['n']} | {ar:.1f}% | {dr:.1f}% "
              f"| {g['dip'] / g['n'] * 100:.0f}% | {tr} | {avg} |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=CACHE)
    args = ap.parse_args()

    kal = load_kalshi_mid_by_minute("KXBTC15M", args.cache)
    spot = load_spot("BTC-USD", args.cache)
    print(f"kalshi minutes: {len(kal)}, spot minutes: {len(spot)}", file=sys.stderr)
    leadlag_report(kal, spot)

    kalt = load_kalshi_triplet("KXBTC15M", args.cache)
    spot_follow_trade(kalt, spot)

    time_of_day(args.cache)
    regime_analysis(args.cache)


if __name__ == "__main__":
    main()
