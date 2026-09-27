"""Measure how much final60 paper/backtest P&L came from sub-3¢ buys.

Reads research pickles if present (`data/fine_events*.pkl`). Prints counts
and P&L with and without price < 0.03. No data files are committed here.
"""
import os, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

def summarize(bets, label):
    n = len(bets)
    if n == 0:
        print(f"{label}: 0 bets")
        return
    pnl = sum(b["pnl"] for b in bets)
    wins = sum(1 for b in bets if b.get("win"))
    print(f"{label}: n={n} wins={wins} pnl={pnl:.2f} per={pnl/n:.4f}")

def from_pickle():
    import pandas as pd
    import numpy as np
    from common import size_bet, TAKER_RATE
    from fine_trades import fair_p
    tag = os.environ.get("TAG", "")
    ev_path = ROOT / f"data/fine_events{tag}.pkl"
    if not ev_path.is_file():
        ev_path = ROOT / "data" / f"fine_events{tag}.pkl"
    if not ev_path.is_file():
        print("NO_DATA")
        return 2
    ev = pd.read_pickle(ev_path)
    ev = ev[(ev.price > 0) & (ev.price < 1) & (ev.tau <= 60)].copy()
    pu = fair_p(ev.X, ev.tau, ev.om, ev.sig, 1.1, 0.0001)
    ev["p"] = np.where(ev.side == 1, pu, 1 - pu)
    sized = [size_bet(float(p), TAKER_RATE, float(os.environ.get("MAX_STAKE", "10"))) for p in ev.price]
    ev["C"] = [c for c, _ in sized]
    ev["cost"] = [co for _, co in sized]
    ev["evpd"] = (ev.C * ev.p - ev.cost) / ev.cost.replace(0, np.nan)
    q = ev[(ev.C > 0) & (ev.evpd >= 0.35)].sort_values(["mid", "since_open"]).groupby("mid", sort=False).head(1)
    q = q.copy()
    q["win"] = np.where(q.side == 1, q.y, 1 - q.y)
    q["pnl"] = q.C * q.win - q.cost
    bets = q.to_dict("records")
    cheap = [b for b in bets if float(b["price"]) < 0.03 - 1e-12]
    rest = [b for b in bets if float(b["price"]) >= 0.03 - 1e-12]
    summarize(bets, "final60_all")
    summarize(cheap, "final60_sub3c")
    summarize(rest, "final60_ge3c")
    return 0

if __name__ == "__main__":
    raise SystemExit(from_pickle())
