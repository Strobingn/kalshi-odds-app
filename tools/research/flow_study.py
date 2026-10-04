#!/usr/bin/env python3
"""Order-flow study on the KXBTC15M trade tape: fade one-sided taker flow.

Needs the tape pulled by tape_study.py (python3 tools/research/tape_study.py pull --dir tape).

Rule under test (fixed; the app's paper tracker FlowFadeRule mirrors it):
  every 15 s from 60 s to 870 s into the window, look at taker contracts in the
  last 30 s. If total >= 500 and |yes - no| / (yes + no) >= 0.5, buy the side
  takers are NOT buying, at its ask, and hold to settlement. First trigger per
  market only. Asks 10-90c. Taker fee 0.07 * q * (1 - q) per contract.

Result on 2,088 markets (2026-09-12 to 10-04): that rule is flat (-0.0c), it
only looked good on the 11 days it was found on. The second table tests the
resting version the app's paper tracker now runs: on the same signal, join the
best bid on the side takers are not buying (no fee), cancel after 30 s, fill
only after Q contracts have traded at that price or a print trades through.

Ask proxies come from the last prints (a taker-YES print is the YES ask, a
taker-NO print the YES bid, each at most 10 s old), not from the book.
CIs are a market-cluster bootstrap. Needs numpy and pandas.
  python3 tools/research/flow_study.py --dir tape
"""
from __future__ import annotations

import argparse

import numpy as np
import pandas as pd

from tape_study import TAKER_FEE, _load

LOOK, STEP, MIN_VOL = 30, 15, 500.0


def decisions(dirname: str) -> pd.DataFrame:
    rows = []
    for m, left, p, c, s in _load(dirname):
        t = 900.0 - left
        o = np.argsort(t, kind="stable")
        t, p, c, s = t[o], p[o], c[o], s[o]
        ty, py, cy = t[s == 1], p[s == 1], c[s == 1]
        tn, pn, cn = t[s == 0], p[s == 0], c[s == 0]
        if len(ty) < 50 or len(tn) < 50:
            continue
        cumy, cumn = np.concatenate([[0.0], np.cumsum(cy)]), np.concatenate([[0.0], np.cumsum(cn)])
        y = 1.0 if m["result"] == "yes" else 0.0
        day = pd.Timestamp(m["open"], unit="s").strftime("%Y-%m-%d")
        for tau in range(60, 871, STEP):
            i, j = np.searchsorted(ty, tau) - 1, np.searchsorted(tn, tau) - 1
            if i < 0 or j < 0 or tau - ty[i] > 10 or tau - tn[j] > 10:
                continue
            ask, bid = py[i], pn[j]
            if not bid < ask or ask - bid > 0.03:
                continue
            vy = cumy[i + 1] - cumy[np.searchsorted(ty, tau - LOOK)]
            vn = cumn[j + 1] - cumn[np.searchsorted(tn, tau - LOOK)]
            rows.append((m["ticker"], day, tau, y, ask, bid, vy, vn))
    df = pd.DataFrame(rows, columns=["tk", "day", "tau", "y", "ask", "bid", "vy", "vn"])
    df["imb"] = (df.vy - df.vn) / (df.vy + df.vn).where(df.vy + df.vn > 0)
    return df


def bets(df: pd.DataFrame, threshold: float, fade: bool = True, delay: int = 0, first: bool = True) -> pd.DataFrame:
    sig = df[(df.vy + df.vn >= MIN_VOL) & (df.imb.abs() >= threshold)].sort_values("tau")
    if first:
        sig = sig.groupby("tk").head(1)
    if delay:
        later = df.set_index(["tk", "tau"])[["ask", "bid"]]
        sig = sig.drop(columns=["ask", "bid"]).join(later, on=["tk", sig.tau + delay]).dropna(subset=["ask", "bid"])
    buy_yes = (sig.imb < 0) if fade else (sig.imb > 0)
    q = np.where(buy_yes, sig.ask, 1 - sig.bid)
    win = np.where(buy_yes, sig.y, 1 - sig.y)
    out = sig.assign(q=q, win=win, net=win - q - TAKER_FEE * q * (1 - q),
                     worse=win - (q + 0.01) - TAKER_FEE * (q + 0.01) * (0.99 - q))
    return out[(out.q >= 0.10) & (out.q <= 0.90)]


def resting(dirname: str, cancel: int = 30, queues=(0, 2000, 5000, 10000)) -> pd.DataFrame:
    """One row per (decision point, side, queue): P&L per contract if filled, else NaN."""
    rows = []
    for m, left, p, c, s in _load(dirname):
        t = 900.0 - left
        o = np.argsort(t, kind="stable")
        t, p, c, s = t[o], p[o], c[o], s[o]
        ty, py, cy = t[s == 1], p[s == 1], c[s == 1]
        tn, pn, cn = t[s == 0], p[s == 0], c[s == 0]
        if len(ty) < 50 or len(tn) < 50:
            continue
        cumy, cumn = np.concatenate([[0.0], np.cumsum(cy)]), np.concatenate([[0.0], np.cumsum(cn)])
        y = 1.0 if m["result"] == "yes" else 0.0
        day = pd.Timestamp(m["open"], unit="s").strftime("%Y-%m-%d")
        for tau in range(60, 841, STEP):
            i, j = np.searchsorted(ty, tau) - 1, np.searchsorted(tn, tau) - 1
            if i < 0 or j < 0 or tau - ty[i] > 10 or tau - tn[j] > 10:
                continue
            ask, bid = py[i], pn[j]
            if not bid < ask or ask - bid > 0.03:
                continue
            vy = cumy[i + 1] - cumy[np.searchsorted(ty, tau - LOOK)]
            vn = cumn[j + 1] - cumn[np.searchsorted(tn, tau - LOOK)]
            if vy + vn < MIN_VOL:
                continue
            imb = (vy - vn) / (vy + vn)
            for absorb in (True, False):
                # absorb: rest on the side takers are not buying, so their flow fills us
                if (imb > 0) == absorb:     # NO bid at 1 - ask, hit by taker-YES prints at >= ask
                    lo, hi = np.searchsorted(ty, tau, side="right"), np.searchsorted(ty, tau + cancel, side="right")
                    sp, sc = py[lo:hi], cy[lo:hi]
                    at, through, pnl, cost = sp >= ask - 1e-9, sp > ask + 1e-9, ask - y, 1 - ask
                else:                       # YES bid at bid, hit by taker-NO prints at <= bid
                    lo, hi = np.searchsorted(tn, tau, side="right"), np.searchsorted(tn, tau + cancel, side="right")
                    sp, sc = pn[lo:hi], cn[lo:hi]
                    at, through, pnl, cost = sp <= bid + 1e-9, sp < bid - 1e-9, y - bid, bid
                if not 0.10 <= cost <= 0.90:
                    continue
                cum = np.cumsum(np.where(at, sc, 0.0))
                for q in queues:
                    filled = through.any() or (cum > q).any()
                    rows.append((m["ticker"], day, abs(imb) >= 0.5, absorb, q, pnl if filled else np.nan))
    return pd.DataFrame(rows, columns=["tk", "day", "signal", "absorb", "queue", "pnl"])


def resting_line(name: str, g: pd.DataFrame, rng, iters: int) -> str:
    pm = g.dropna(subset=["pnl"]).groupby("tk").pnl.agg(["sum", "count"])
    idx = rng.integers(0, len(pm), size=(iters, len(pm)))
    boot = pm["sum"].values[idx].sum(1) / pm["count"].values[idx].sum(1) * 100
    lo, hi = np.percentile(boot, [2.5, 97.5])
    return f"| {name} | {len(g)} | {g.pnl.notna().mean() * 100:.0f}% | {g.pnl.mean() * 100:+.2f} | [{lo:+.2f}, {hi:+.2f}] |"


def line(name: str, b: pd.DataFrame, rng, iters: int) -> str:
    if len(b) < 30:
        return f"| {name} | {len(b)} | | | | | | |"
    g = b.groupby("tk").net.agg(["sum", "count"])
    idx = rng.integers(0, len(g), size=(iters, len(g)))
    boot = g["sum"].values[idx].sum(1) / g["count"].values[idx].sum(1) * 100
    lo95, hi95, lo99, hi99 = np.percentile(boot, [2.5, 97.5, 0.5, 99.5])
    return (f"| {name} | {len(b)} | {b.q.mean() * 100:.1f} | {b.win.mean() * 100:.1f}% | {b.net.mean() * 100:+.2f} | "
            f"{b.worse.mean() * 100:+.2f} | [{lo95:+.2f}, {hi95:+.2f}] | [{lo99:+.2f}, {hi99:+.2f}] |")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default="tape")
    ap.add_argument("--iters", type=int, default=3000)
    a = ap.parse_args()
    df = decisions(a.dir)
    rng = np.random.default_rng(5)
    days = sorted(df.day.unique())
    print(f"# Flow-fade study: {df.tk.nunique()} markets, {days[0]} to {days[-1]}, {len(df)} decision points\n")
    print("Cents per contract after the taker fee, held to settlement.\n")
    print("| Rule | Bets | Avg ask c | Win | Net c | Net at +1c | 95% CI | 99% CI |\n|---|---:|---:|---:|---:|---:|---|---|")
    print(line("FADE, first trigger per market (the rule)", bets(df, 0.5), rng, a.iters))
    print(line("fade, every trigger", bets(df, 0.5, first=False), rng, a.iters))
    print(line("FOLLOW the flow instead, every trigger", bets(df, 0.5, fade=False, first=False), rng, a.iters))
    for th in (0.4, 0.6, 0.7):
        print(line(f"fade, threshold {th}", bets(df, th), rng, a.iters))
    for delay in (15, 30):
        print(line(f"fade, entry {delay} s late", bets(df, 0.5, delay=delay), rng, a.iters))
    b = bets(df, 0.5)
    early = days[:len(days) // 2 + len(days) % 2]
    for name, part in ((f"{early[0]} to {early[-1]}", b[b.day.isin(early)]), (f"after {early[-1]}", b[~b.day.isin(early)])):
        print(line(f"fade, {name}", part, rng, a.iters))
    print("\n## By day\n")
    for day, g in b.groupby("day"):
        print(f"- {day}: {len(g)} bets, {g.net.mean() * 100:+.2f}c")
    r = resting(a.dir)
    print("\n## Resting bid instead of buying at the ask\n\nCents per filled contract, maker fee 0, cancel after 30 s.\n")
    print("| Order | Orders | Filled | c per fill | 95% CI |\n|---|---:|---:|---:|---|")
    for name, part in (("signal, rest on the side takers are not buying", r[r.signal & r.absorb]),
                       ("signal, rest on the side takers are buying", r[r.signal & ~r.absorb]),
                       ("no signal, either side", r[~r.signal])):
        for q in sorted(part.queue.unique()):
            print(resting_line(f"{name}, {q:,} ahead", part[part.queue == q], rng, a.iters))
    sig = r[r.signal & r.absorb]
    for name, part in ((f"{early[0]} to {early[-1]}", sig[sig.day.isin(early)]), (f"after {early[-1]}", sig[~sig.day.isin(early)])):
        for q in (2000, 5000):
            print(resting_line(f"signal, not-buying side, {name}, {q:,} ahead", part[part.queue == q], rng, a.iters))


if __name__ == "__main__":
    main()
