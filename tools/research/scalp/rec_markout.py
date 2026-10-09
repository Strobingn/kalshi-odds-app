"""Context for Part I (DESIGN.md): what the takers in the public tape made.

For every trade, in the taker's own frame (a taker who bought YES at P is long YES at P; one who sold YES
is long NO at 1 - P): the move of the book mid 1/5/10/30/60 s later relative to the price paid, and the
settlement value minus the price paid, weighted by contracts, before and after the taker fee.

Run: python3 rec_markout.py <rec_build out dir>
"""
import json, os, sys, datetime as dt
import numpy as np
H = (1, 5, 10, 30, 60)
SIZES = [(0, 10, "under 10"), (10, 100, "10-99"), (100, 1000, "100-999"), (1000, 1e12, "1,000+")]
def boot(sums, wts, B=3000, seed=3):
    ks = list(sums); s = np.array([sums[k] for k in ks]); w = np.array([wts[k] for k in ks]); r = np.random.default_rng(seed)
    ix = r.integers(0, len(ks), size=(B, len(ks))); m = s[ix].sum(1) / w[ix].sum(1); m.sort()
    return m[int(.025 * B)], m[int(.975 * B)]
if __name__ == "__main__":
    D = sys.argv[1]; W = [w for w in json.load(open(os.path.join(D, "windows.json"))) if w["book_secs"] >= 600]
    acc = {}
    def add(key, day, val, wt):
        a = acc.setdefault(key, ({}, {})); a[0][day] = a[0].get(day, 0.0) + float((val * wt).sum()); a[1][day] = a[1].get(day, 0.0) + float(wt.sum())
    for w in W:
        z = np.load(os.path.join(D, "win", w["ticker"] + ".npz")); day = dt.datetime.fromtimestamp(w["open"], dt.timezone.utc).strftime("%Y-%m-%d")
        T = 900.0 + z["T"]; sec = np.floor(T).astype(int); ok = (sec >= 60) & (sec < 780)
        sec = sec[ok]; P = np.round(z["P"].astype(float), 4)[ok]; C = z["C"].astype(float)[ok]; S = z["S"][ok]
        mid = (z["yb"] + z["ya"]) / 2; res = 1.0 if w["result"] == "yes" else 0.0
        sign = np.where(S == 1, 1.0, -1.0); paid = np.where(S == 1, P, 1.0 - P); fee = 0.07 * P * (1.0 - P)
        band = (P >= 0.10) & (P <= 0.90)
        for name, m0 in (("10c-90c", band), ("tails", ~band)):
            for lo, hi, sz in SIZES + [(0, 1e12, "all sizes")]:
                m = m0 & (C >= lo) & (C < hi)
                if not m.any(): continue
                for h in H:
                    mv = sign[m] * (mid[np.clip(sec[m] + h, 0, 899)] - P[m]); g = ~np.isnan(mv)
                    add((name, sz, f"+{h}s"), day, mv[g] * 100, C[m][g])
                st = sign[m] * (res - P[m]) * 100
                add((name, sz, "settle"), day, st, C[m]); add((name, sz, "settle - fee"), day, st - fee[m] * 100, C[m]); add((name, sz, "fee"), day, fee[m] * 100, C[m])
                add((name, sz, "contracts/day"), day, np.ones(m.sum()), C[m])
    print(f"{len(W)} windows. Cents per contract in the taker's favour, weighted by contracts; [95% resampling days]")
    for name in ("10c-90c", "tails"):
        print(f"\nTrades at {name}:")
        for _, _, sz in SIZES + [(0, 1e12, "all sizes")]:
            parts = []
            for col in [f"+{h}s" for h in H] + ["settle", "fee", "settle - fee"]:
                s, wt = acc.get((name, sz, col), ({}, {}))
                if not wt: continue
                lo, hi = boot(s, wt); parts.append(f"{col} {sum(s.values()) / sum(wt.values()):+.2f}" + (f" [{lo:+.2f},{hi:+.2f}]" if col in ("+10s", "settle", "settle - fee") else ""))
            vol = sum(acc[(name, sz, "fee")][1].values())
            print(f"  {sz:10s} {vol / 1e6:7.1f}M contracts | " + " | ".join(parts))
