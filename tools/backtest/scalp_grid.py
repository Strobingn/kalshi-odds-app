"""Scalping grid backtest: dip-buy YES on the Kalshi 15m mid, exit on bounce.

Replays every cached 1-minute candle file in tools/backtest/cache/candles and
simulates: when the mid dips >= `dip` cents below a short EMA(span=5) of the
mid, buy YES at that candle's ask close (plus a configurable spread penalty).
Hold until the mid bounces >= `tp` cents from the entry mid (exit at that
candle's bid close, minus penalty), the mid falls >= `sl` cents (exit at bid,
stop-loss), or `max_hold` minutes elapse (exit at bid, time stop). One trade
per market (first qualifying minute), matching the honesty rules of
docs/backtest-2026-09-25.md.

Fees: Kalshi taker fee ceil_6dp(0.07 * P * (1 - P)) per contract, charged on
both the buy and the sell (a scalp sells YES back, it does not hold to
settlement). P&L is reported per contract in cents.

Also reports the unconditional base rate: for every decision minute, how often
does the mid rise 2-5 cents within the next 10 minutes, with and without
conditioning on a dip.

Read-only: touches no app code, writes nothing. Run:
    python tools/backtest/scalp_grid.py
    python tools/backtest/scalp_grid.py --penalty 1.0   # pessimistic, cents
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys
from collections import defaultdict

MIN_TICK = 0.001
MAX_TICK = 0.999
FEE_RATE = 0.07
EMA_SPAN = 5  # "short EMA" of the mid
EMA_ALPHA = 2.0 / (EMA_SPAN + 1)

DIPS = [1, 2, 3, 4, 5]          # cents below EMA
TPS = [2, 3, 4, 5, 6]           # bounce take-profit, cents
SLS = [3, 4, 5, 6, 7, 8]        # stop-loss, cents
MAX_HOLDS = list(range(2, 16))  # minutes
BOUNCE_WINDOWS = [2, 3, 4, 5]   # base-rate bounce sizes, cents
N_TP, N_SL, N_MH = len(TPS), len(SLS), len(MAX_HOLDS)

CACHE = os.path.join(os.path.dirname(__file__), "cache", "candles")


def usable(p) -> bool:
    return p is not None and math.isfinite(p) and MIN_TICK - 1e-12 <= p <= MAX_TICK + 1e-12


def fee_per_contract(price: float) -> float:
    """KalshiFee model fee, dollars per contract: ceil_6dp(0.07*P*(1-P))."""
    p = min(0.999, max(0.001, price))
    return math.ceil(FEE_RATE * p * (1.0 - p) * 1e6) / 1e6


def load_all(cache_dir):
    """Load every candle file once: list of (coin, start_ts, mids, asks, bids)."""
    series = []
    for path in glob.glob(os.path.join(cache_dir, "*.json")):
        name = os.path.basename(path)
        coin = {"KXBTC": "BTC", "KXETH": "ETH", "KXSOL": "SOL"}.get(name[:5], "?")
        try:
            with open(path) as f:
                rows = json.load(f)
        except Exception:
            continue
        n = len(rows)
        if n < 6:
            continue
        mids = [None] * n
        asks = [None] * n
        bids = [None] * n
        for i, r in enumerate(rows):
            m = r.get("mid")
            if not usable(m):
                yb, ya = r.get("yes_bid"), r.get("yes_ask")
                if yb and ya and usable(yb.get("close")) and usable(ya.get("close")):
                    m = (yb["close"] + ya["close"]) / 2.0
            mids[i] = m
            ya, yb = r.get("yes_ask"), r.get("yes_bid")
            if ya and usable(ya.get("close")):
                asks[i] = ya["close"]
            if yb and usable(yb.get("close")):
                bids[i] = yb["close"]
        series.append((coin, rows[0]["end_ts"], mids, asks, bids))
    series.sort(key=lambda s: s[1])
    return series


def ema(values, alpha):
    out = []
    e = None
    for v in values:
        e = v if e is None else alpha * v + (1 - alpha) * e
        out.append(e)
    return out


class Acc:
    """Flat accumulators indexed [combo_id][coin_idx]."""

    def __init__(self, n_combos, n_coins):
        self.n = [[0] * n_coins for _ in range(n_combos)]
        self.wins = [[0] * n_coins for _ in range(n_combos)]
        self.gw = [[0.0] * n_coins for _ in range(n_combos)]
        self.gl = [[0.0] * n_coins for _ in range(n_combos)]
        self.eq = [[0.0] * n_coins for _ in range(n_combos)]
        self.peak = [[0.0] * n_coins for _ in range(n_combos)]
        self.dd = [[0.0] * n_coins for _ in range(n_combos)]
        self.tp = [[0] * n_coins for _ in range(n_combos)]
        self.sl = [[0] * n_coins for _ in range(n_combos)]
        self.tm = [[0] * n_coins for _ in range(n_combos)]

    def add(self, cid, coidx, pnl, reason):
        self.n[cid][coidx] += 1
        if pnl > 0:
            self.wins[cid][coidx] += 1
            self.gw[cid][coidx] += pnl
        else:
            self.gl[cid][coidx] += -pnl
        e = self.eq[cid][coidx] + pnl
        self.eq[cid][coidx] = e
        if e > self.peak[cid][coidx]:
            self.peak[cid][coidx] = e
        d = self.peak[cid][coidx] - e
        if d > self.dd[cid][coidx]:
            self.dd[cid][coidx] = d
        if reason == 0:
            self.tp[cid][coidx] += 1
        elif reason == 1:
            self.sl[cid][coidx] += 1
        else:
            self.tm[cid][coidx] += 1


COINS = ("BTC", "ETH", "SOL")
COINS_ALL = ("ALL", "BTC", "ETH", "SOL")


def run_grid(series, penalty_cents, with_fees=True):
    """Single detection pass. Returns Acc plus combo metadata."""
    pen = penalty_cents / 100.0
    combos = [(d, tp, sl, mh)
              for d in DIPS for tp in TPS for sl in SLS for mh in MAX_HOLDS]
    acc = Acc(len(combos), 4)  # col 0 = ALL, 1..3 = BTC/ETH/SOL
    coin_idx = {c: i + 1 for i, c in enumerate(COINS)}

    for coin, _start, mids, asks, bids in series:
        n = len(mids)
        emas = ema([m if m is not None else 0.5 for m in mids], EMA_ALPHA)
        last = n - 1
        coidx = coin_idx.get(coin, 0)

        # One entry per dip value: first trigger minute.
        entries = []
        for d in DIPS:
            thr = d / 100.0
            t = -1
            for i in range(2, min(13, last - 1) + 1):
                if (mids[i] is not None and emas[i] is not None
                        and mids[i] <= emas[i] - thr):
                    t = i
                    break
            if t < 0 or asks[t] is None:
                continue
            base = mids[t]
            # Forward scan: first minute where mid-base >= tp cents (tge) or
            # <= -sl cents (tle).
            tge = [None] * N_TP
            tle = [None] * N_SL
            for u in range(t + 1, n):
                mu = mids[u]
                if mu is None:
                    continue
                dc = (mu - base) * 100.0
                for a in range(N_TP):
                    if tge[a] is None and dc >= TPS[a]:
                        tge[a] = u
                for b in range(N_SL):
                    if tle[b] is None and dc <= -SLS[b]:
                        tle[b] = u
            entries.append((d, t, tge, tle))

        if not entries:
            continue

        entry_ask = {}
        for d, t, _tge, _tle in entries:
            entry_ask[d] = min(MAX_TICK, asks[t] + pen)

        for ci, (d, tp, sl, mh) in enumerate(combos):
            ent = None
            for (dd, t, tge, tle) in entries:
                if dd == d:
                    ent = (t, tge, tle)
                    break
            if ent is None:
                continue
            t, tge, tle = ent
            horizon = t + mh
            if horizon > last:
                horizon = last
            a = b = None
            for k in range(N_TP):
                if TPS[k] == tp:
                    a = tge[k]
            for k in range(N_SL):
                if SLS[k] == sl:
                    b = tle[k]
            ex, reason = horizon, 2  # time stop
            if a is not None and a <= horizon and (b is None or a < b):
                ex, reason = a, 0
            elif b is not None and b <= horizon:
                ex, reason = b, 1
            if bids[ex] is None:
                continue
            exit_px = max(MIN_TICK, bids[ex] - pen)
            entry_px = entry_ask[d]
            if not with_fees:
                # idealized: fill at the mid both ways, no taker fee
                if mids[ex] is None:
                    continue
                exit_px = mids[ex]
                entry_px = mids[t]
            pnl = (exit_px - (fee_per_contract(exit_px) if with_fees else 0.0)
                   - entry_px - (fee_per_contract(entry_px) if with_fees else 0.0)) * 100.0
            acc.add(ci, 0, pnl, reason)
            acc.add(ci, coidx, pnl, reason)
    return combos, acc


def run_base_rate(series):
    stats = {c: {"n": 0, "dip_n": 0,
                 "hit": defaultdict(int), "dip_hit": defaultdict(int),
                 "tmin": defaultdict(list)} for c in COINS_ALL}
    for coin, _s, mids, _a, _b in series:
        n = len(mids)
        emas = ema([m if m is not None else 0.5 for m in mids], EMA_ALPHA)
        for t in range(2, min(13, n - 2) + 1):
            if mids[t] is None:
                continue
            horizon = min(t + 10, n - 1)
            if horizon <= t:
                continue
            is_dip = mids[t] is not None and emas[t] is not None and mids[t] <= emas[t] - 0.01
            for tgt in (0, COINS.index(coin) + 1) if coin in COINS else (0,):
                s = stats[COINS_ALL[tgt]]
                s["n"] += 1
                if is_dip:
                    s["dip_n"] += 1
                for k in BOUNCE_WINDOWS:
                    lead = None
                    for u in range(t + 1, horizon + 1):
                        if mids[u] is not None and mids[u] - mids[t] >= k / 100.0:
                            lead = u - t
                            break
                    if lead is not None:
                        s["hit"][k] += 1
                        s["tmin"][k].append(lead)
                        if is_dip:
                            s["dip_hit"][k] += 1
    return stats


def combo_row(acc, ci, coidx):
    n = acc.n[ci][coidx]
    if n == 0:
        return None
    avg = (acc.gw[ci][coidx] - acc.gl[ci][coidx]) / n
    total = acc.gw[ci][coidx] - acc.gl[ci][coidx]
    pf = acc.gw[ci][coidx] / acc.gl[ci][coidx] if acc.gl[ci][coidx] > 0 else float("inf")
    return (n, acc.wins[ci][coidx] / n, avg, total, acc.dd[ci][coidx], pf,
            acc.tp[ci][coidx], acc.sl[ci][coidx], acc.tm[ci][coidx])


def table(rows, title):
    lines = [f"### {title}", "",
             "| dip | tp | sl | maxhold | trades | win% | avg ¢/trade | total ¢ | maxDD ¢ | PF | TP/SL/time exits |",
             "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for (d, tp, sl, mh), r in rows:
        n, wr, avg, total, dd, pf, tpx, slx, tmx = r
        pfs = f"{pf:.2f}" if pf != float("inf") else "inf"
        lines.append(f"| {d} | {tp} | {sl} | {mh} | {n} | {wr * 100:.1f}% "
                     f"| {avg:+.3f} | {total:+.0f} | {dd:.0f} | {pfs} "
                     f"| {tpx}/{slx}/{tmx} |")
    lines.append("")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--penalty", type=float, default=0.5,
                    help="spread penalty in cents on each side (test 0 / 0.5 / 1)")
    ap.add_argument("--cache", default=CACHE)
    args = ap.parse_args()

    series = load_all(args.cache)
    print(f"markets loaded: {len(series)}", file=sys.stderr)

    stats = run_base_rate(series)
    print("## Base rate: P(mid rises +k cents within 10 min of minute t)\n")
    print("| series | minutes | dip minutes (>=1c below EMA) | bounce | all-min rate | dip-min rate | median lead (min) |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for name in COINS_ALL:
        s = stats[name]
        for k in BOUNCE_WINDOWS:
            rate = s["hit"][k] / s["n"] * 100 if s["n"] else 0
            drate = s["dip_hit"][k] / s["dip_n"] * 100 if s["dip_n"] else 0
            tm = sorted(s["tmin"][k])
            med = tm[len(tm) // 2] if tm else 0
            print(f"| {name} | {s['n']} | {s['dip_n']} | +{k}¢ "
                  f"| {rate:.1f}% | {drate:.1f}% | {med} |")
    print()

    combos, acc = run_grid(series, args.penalty)

    def rank_key(ci):
        n = acc.n[ci][0]
        if n < 200:
            return 1e18
        return -(acc.gw[ci][0] - acc.gl[ci][0]) / n

    order = sorted(range(len(combos)), key=rank_key)
    print(f"## Grid top 10 by avg cents/trade (n>=200), penalty {args.penalty}c/side\n")
    print(table([(combos[ci], combo_row(acc, ci, 0)) for ci in order[:10]],
                f"penalty {args.penalty}c per side, pooled"))
    print(table([(combos[ci], combo_row(acc, ci, 0)) for ci in order[-3:]],
                "worst 3 (context)"))

    best = order[0]
    bd, btp, bsl, bmh = combos[best]
    print(f"### Per-coin breakdown of top combo (dip={bd}, tp={btp}, sl={bsl}, mh={bmh})\n")
    print(table([((bd, btp, bsl, bmh), combo_row(acc, best, i))
                 for i in (1, 2, 3)], "by coin"))

    # Friction decomposition for the top combo: idealized mid fills without
    # fees -> ask/bid close fills with taker fee both sides, no penalty ->
    # plus the spread penalty. Shows where the money goes.
    combos_nf, acc_nf = run_grid(series, 0.0, with_fees=False)
    combos_f0, acc_f0 = run_grid(series, 0.0, with_fees=True)
    ci = combos.index(combos[best])
    r_nf = combo_row(acc_nf, ci, 0)
    r_f0 = combo_row(acc_f0, ci, 0)
    r_pn = combo_row(acc, best, 0)
    print("### Friction decomposition for top combo, cents per trade\n")
    print(f"- idealized mid fills, no fee:                 {r_nf[2]:+.3f} over {r_nf[0]} trades")
    print(f"- ask/bid close fills + taker fee both sides:  {r_f0[2]:+.3f} over {r_f0[0]} trades")
    print(f"- plus {args.penalty}c/side spread penalty:                {r_pn[2]:+.3f} over {r_pn[0]} trades")


if __name__ == "__main__":
    main()
