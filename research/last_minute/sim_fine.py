"""Walk-forward simulation on the second-resolution trade-era dataset built by fine_trades.py.
- Per coin, per fold: fit vol scale k and proxy-noise eta by log loss on TRAIN-day samples only.
- Taker: at each 5 s bucket where a real taker fill printed, the printed price is treated as the available ask
  (YES side: min YES price paid; NO side: min NO price paid). One bet per market per strategy (first qualifying bucket).
  Variant a: EV per $ >= margin (margin picked on TRAIN from a grid); variant b: same + net win profit >= $10 at <= $5.
- Segments: all, time-to-close buckets, open90 (first 90 s), final60 (last 60 s), middle (rest).
- Maker (task 3): resting bid at floor_cent(p - margin) placed at tau0 in {840,600,300,120}s, only if below the current
  ask; filled if a later taker trade prints THROUGH our price (strict) before close; maker fee 0 (series fee_type quadratic).
Writes results/fine_*.json and prints summary tables."""
import numpy as np, pandas as pd, json, math, sys
from common import *
from fine_trades import fair_p
import os
GATE_COINS = tuple(os.environ.get("COINS", "KXBTC15M,KXETH15M,KXSOL15M").split(","))   # 2026-09-27: BTC-only rerun passes COINS=KXBTC15M
KG = [0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.35, 1.5, 1.7]
EG = [0.0, 5e-5, 1e-4, 2e-4, 3e-4, 5e-4, 8e-4]
MG = [0.02, 0.05, 0.10, 0.20, 0.35]
SEGS = {"all": lambda d: pd.Series(np.ones(len(d), bool), index=d.index),
        ">10m": lambda d: d.tau >= 601, "5-10m": lambda d: (d.tau >= 301) & (d.tau <= 600),
        "2-5m": lambda d: (d.tau >= 121) & (d.tau <= 300), "<=2m": lambda d: d.tau <= 120,
        "open90": lambda d: d.since_open < 90, "final60": lambda d: d.tau <= 60,
        "middle": lambda d: (d.since_open >= 90) & (d.tau > 60)}

def fit(sm):
    best = None
    y = sm.y.to_numpy()
    for k in KG:
        for e in EG:
            p = np.clip(fair_p(sm.X, sm.tau, sm.om, sm.sig, k, e), 1e-6, 1 - 1e-6)
            ll = -np.mean(y * np.log(p) + (1 - y) * np.log(1 - p))
            if best is None or ll < best[0]: best = (ll, k, e)
    return best

SZ = {}
def sizing(prices, rate=TAKER_RATE):
    out = np.zeros((len(prices), 2))
    for i, a in enumerate(prices):
        key = (round(float(a), 4), rate)
        if key not in SZ: SZ[key] = size_bet(key[0], rate)
        out[i] = SZ[key]
    return out[:, 0], out[:, 1]

def taker_bets(ev, margin, variant):
    """ev has columns p (win prob for the side), price, C, cost, y_side. Returns first qualifying event per market."""
    evpd = (ev.C * ev.p - ev.cost) / ev.cost.where(ev.cost > 0, np.nan)
    ok = (ev.C > 0) & (evpd >= margin)
    if variant == "b": ok &= (ev.C - ev.cost) >= 10
    q = ev[ok]
    if len(q) == 0: return q
    return q.sort_values(["mid", "since_open"]).groupby("mid", sort=False).head(1)

def to_bets(q):
    pnl = q.C * q.win - q.cost
    return [{"pnl": float(a), "win": int(w), "coin": c, "day": d, "tau": int(t), "cost": float(co), "price": float(pr), "p": float(pp), "mid": int(mi), "since_open": int(so), "C": int(cc)}
            for a, w, c, d, t, co, pr, pp, mi, so, cc in zip(pnl, q.win, q.coin, q.day, q.tau, q.cost, q.price, q.p, q.mid, q.since_open, q.C)]

def main():
    TAG = __import__("os").environ.get("TAG", "")
    ev = pd.read_pickle(f"data/fine_events{TAG}.pkl"); sm = pd.read_pickle(f"data/fine_samples{TAG}.pkl"); mk = pd.read_pickle(f"data/fine_makers{TAG}.pkl")
    ev = ev[(ev.price > 0) & (ev.price < 1)].reset_index(drop=True)
    ev["win"] = np.where(ev.side == 1, ev.y, 1 - ev.y)
    C, cost = sizing(ev.price.to_numpy()); ev["C"] = C; ev["cost"] = cost
    days = sorted(sm.day.unique())
    res_bets = {(s, v): [] for s in SEGS for v in "ab"}
    stale_rows = []; maker_bets = {}; taker_same = {}; fits = []
    for tr, te in folds(days):
        for coin in ev.coin.unique():
            smc = sm[(sm.coin == coin) & sm.day.isin(tr)]
            smc = smc.sample(min(len(smc), 60000), random_state=0)
            ll, k, e = fit(smc); fits.append({"coin": coin, "test": te, "k": k, "eta": e, "ll": ll})
            for part, dd in (("train", tr), ("test", te)):
                pass
            E = ev[(ev.coin == coin) & ev.day.isin(tr + te)].copy()
            pu = fair_p(E.X, E.tau, E.om, E.sig, k, e)
            E["p"] = np.where(E.side == 1, pu, 1 - pu)
            Etr, Ete = E[E.day.isin(tr)], E[E.day.isin(te)]
            # staleness stats on TEST events (all observed fills, not just first per market)
            evpd = (Ete.C * Ete.p - Ete.cost) / Ete.cost.where(Ete.cost > 0, np.nan)
            for s, f in SEGS.items():
                msk = f(Ete).to_numpy() & (Ete.C > 0).to_numpy()
                for thr in (0.05, 0.10, 0.25):
                    sel = msk & (evpd >= thr).to_numpy()
                    stale_rows.append({"coin": coin, "seg": s, "thr": thr, "n_fills": int(msk.sum()), "n_stale": int(sel.sum()),
                                       "pnl_all_stale": float((Ete.C * Ete.win - Ete.cost)[sel].sum()), "cost_all_stale": float(Ete.cost[sel].sum()),
                                       "mean_p": float(Ete.p[sel].mean()) if sel.any() else None, "win": float(Ete.win[sel].mean()) if sel.any() else None})
            for s, f in SEGS.items():
                a_tr, a_te = Etr[f(Etr).to_numpy()], Ete[f(Ete).to_numpy()]
                for v in "ab":
                    best = max(MG, key=lambda m: (lambda q: float((q.C * q.win - q.cost).sum()))(taker_bets(a_tr, m, v)))
                    res_bets[(s, v)] += to_bets(taker_bets(a_te, best, v))
            # maker
            Mk = mk[(mk.coin == coin) & mk.day.isin(tr + te)].copy()
            pu = fair_p(Mk.X, 900 * 0 + Mk.tau0, Mk.om, Mk.sig, k, e)
            Mk["pu"] = pu
            for tau0 in (840, 600, 300, 120):
                Mt = Mk[Mk.tau0 == tau0]
                def maker_run(D, m):
                    out = []
                    for r in D.itertuples():
                        for side in (1, 0):
                            p = r.pu if side == 1 else 1 - r.pu
                            b = math.floor((p - m) * 100 + 1e-9) / 100
                            if b < 0.01: continue
                            cur_ask = r.ask if side == 1 else 1 - r.bid
                            if b >= cur_ask: continue           # would cross -> not a resting order
                            if side == 1: filled = r.minyes_no is not None and r.minyes_no == r.minyes_no and r.minyes_no < b
                            else: filled = r.maxyes_yes is not None and r.maxyes_yes == r.maxyes_yes and (1 - r.maxyes_yes) < b
                            win = r.y if side == 1 else 1 - r.y
                            Cc, co = size_bet(b, MAKER_RATE)
                            out.append({"filled": filled, "win": int(win), "p": p, "b": b, "C": Cc, "cost": co, "coin": r.coin, "day": r.day,
                                        "pnl": (Cc * win - co) if filled else 0.0, "tau": tau0})
                    return out
                def taker_run(D, m):
                    out = []
                    for r in D.itertuples():
                        for side in (1, 0):
                            p = r.pu if side == 1 else 1 - r.pu
                            a = r.ask if side == 1 else 1 - r.bid
                            if not (0 < a < 1): continue
                            Cc, co = size_bet(round(a, 4))
                            if Cc == 0 or (Cc * p - co) / co < m: continue
                            win = r.y if side == 1 else 1 - r.y
                            out.append({"pnl": Cc * win - co, "win": int(win), "coin": r.coin, "day": r.day, "cost": co, "tau": tau0, "p": p, "price": a})
                    return out
                mtr, mte = Mt[Mt.day.isin(tr)], Mt[Mt.day.isin(te)]
                bm = max(MG, key=lambda m: sum(x["pnl"] for x in maker_run(mtr, m)))
                maker_bets.setdefault(tau0, []).extend(maker_run(mte, bm))
                bt = max(MG, key=lambda m: sum(x["pnl"] for x in taker_run(mtr, m)))
                taker_same.setdefault(tau0, []).extend(taker_run(mte, bt))
    if __import__("os").environ.get("SAVEBETS"):   # 2026-09-27: per-bet dump for diagnostics (same bets as the summaries)
        pd.DataFrame([dict(seg=s_, variant=v_, **b_) for (s_, v_), bl in res_bets.items() for b_ in bl]).to_pickle(f"data/fine_bets{TAG}{__import__('os').environ.get('OUTSUFFIX', '')}.pkl")
    out = {"fits": fits, "taker": {}, "stale": stale_rows, "maker": {}, "taker_at_same_times": {}}
    print("fits:", [(f["coin"][:5], f["test"][0], f["k"], f["eta"]) for f in fits])
    for (s, v), b in res_bets.items():
        ok, verdict, summ = gate(b, GATE_COINS)
        per = {c: summarize([x for x in b if x["coin"] == c]) for c in ("KXBTC15M", "KXETH15M", "KXSOL15M")}
        out["taker"][f"{s}|{v}"] = {"verdict": verdict, "summary": summ, "per_coin": per}
        print(f"TAKER {s:8s} {v}: n={summ.get('n')} win={summ.get('win_rate', 0):.3f} pnl={summ.get('pnl', 0):.1f} ci={summ.get('ci95')} dayCI={summ.get('ci95_dayblock')} "
              f"| BTC {per['KXBTC15M'].get('pnl', 0):.1f} ETH {per['KXETH15M'].get('pnl', 0):.1f} SOL {per['KXSOL15M'].get('pnl', 0):.1f} | {verdict}")
    for tau0, b in maker_bets.items():
        f = [x for x in b if x["filled"]]
        ok, verdict, summ = gate(f, GATE_COINS)
        fill_rate = len(f) / max(1, len(b))
        adv = (np.mean([x["p"] for x in f]) - np.mean([x["win"] for x in f])) if f else None
        unf_win = np.mean([x["win"] for x in b if not x["filled"]]) if len(f) < len(b) else None
        unf_p = np.mean([x["p"] for x in b if not x["filled"]]) if len(f) < len(b) else None
        out["maker"][tau0] = {"placed": len(b), "filled": len(f), "fill_rate": fill_rate, "mean_p_filled": float(np.mean([x["p"] for x in f])) if f else None,
                              "win_filled": float(np.mean([x["win"] for x in f])) if f else None, "adverse_selection_p_minus_win": adv,
                              "unfilled_mean_p": unf_p, "unfilled_win": unf_win, "verdict": verdict, "summary": summ,
                              "per_coin": {c: summarize([x for x in f if x["coin"] == c]) for c in ("KXBTC15M", "KXETH15M", "KXSOL15M")}}
        t = taker_same[tau0]; okt, vt, st = gate(t, GATE_COINS)
        out["taker_at_same_times"][tau0] = {"verdict": vt, "summary": st}
        print(f"MAKER tau0={tau0}: placed={len(b)} filled={len(f)} ({fill_rate:.2%}) p_filled={out['maker'][tau0]['mean_p_filled']} win_filled={out['maker'][tau0]['win_filled']} "
              f"pnl={summ.get('pnl', 0):.1f} ci={summ.get('ci95')} {verdict} || TAKER same time: n={st.get('n')} pnl={st.get('pnl', 0):.1f} ci={st.get('ci95')} {vt}")
    st = pd.DataFrame(stale_rows)
    g = st.groupby(["seg", "thr"]).agg(n_fills=("n_fills", "sum"), n_stale=("n_stale", "sum"), pnl=("pnl_all_stale", "sum"), cost=("cost_all_stale", "sum")).reset_index()
    g["share_stale"] = g.n_stale / g.n_fills; g["roi_if_all_bought"] = g.pnl / g.cost
    print(g.to_string())
    out["stale_agg"] = g.to_dict("records")
    json.dump(out, open(f"results/fine_results{TAG}{__import__('os').environ.get('OUTSUFFIX', '')}.json", "w"), indent=1, default=str)   # OUTSUFFIX: e.g. _feecent sensitivity

if __name__ == "__main__":
    main()
