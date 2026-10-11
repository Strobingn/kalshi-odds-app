"""Recompute the MOST RECENT walk-forward fold of the TAG=_full_cf_btc run (train 2026-09-09..09-22) exactly as sim_fine.py does:
k/eta by log loss on train samples (60k sample, random_state=0), margin for final60 a/b picked on train P&L; for $5 and $10 max stake.
Writes shadow/params.json."""
import os, sys, json, numpy as np, pandas as pd
sys.path.insert(0, "/workspace/edge-research"); os.chdir("/workspace/edge-research")
import common
from common import folds, size_bet, TAKER_RATE
import sim_fine as SF
from fine_trades import fair_p
ev = pd.read_pickle("data/fine_events_full_cf_btc.pkl"); sm = pd.read_pickle("data/fine_samples_full_cf_btc.pkl")
ev = ev[(ev.price > 0) & (ev.price < 1)].reset_index(drop=True); ev["win"] = np.where(ev.side == 1, ev.y, 1 - ev.y)
days = sorted(sm.day.unique()); tr, te = list(folds(days))[-1]
print("last fold train", tr[0], tr[-1], "test", te)
smc = sm[(sm.coin == "KXBTC15M") & sm.day.isin(tr)]; smc = smc.sample(min(len(smc), 60000), random_state=0)
ll, k, e = SF.fit(smc); print("k", k, "eta", e, "ll", ll)
E = ev[ev.day.isin(tr) & (ev.tau <= 60)].copy()
pu = fair_p(E.X, E.tau, E.om, E.sig, k, e); E["p"] = np.where(E.side == 1, pu, 1 - pu)
out = {"train": [tr[0], tr[-1]], "test_of_that_fold": te, "k": k, "eta": e}
for stake in (5.0, 10.0):
    sz = {}
    def s(p):
        kk = round(float(p), 4)
        if kk not in sz: sz[kk] = size_bet(kk, TAKER_RATE, stake)
        return sz[kk]
    E["C"] = [s(p)[0] for p in E.price]; E["cost"] = [s(p)[1] for p in E.price]
    for v in "ab":
        res = {m: float((lambda q: (q.C * q.win - q.cost).sum())(SF.taker_bets(E, m, v))) for m in SF.MG}
        best = max(SF.MG, key=lambda m: res[m])
        out[f"margin_final60_{v}_stake{int(stake)}"] = best; out[f"train_pnl_by_margin_{v}_stake{int(stake)}"] = res
        print(stake, v, best, res)
json.dump(out, open("shadow/params.json", "w"), indent=1)
