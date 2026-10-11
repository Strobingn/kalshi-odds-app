"""Part J (DESIGN.md): patient orders, a bid placed k cents below the best bid and left to wait.

Run: python3 rec_j.py <depth_build out dir> [out.json]
"""
import json, os, sys, datetime as dt
import numpy as np
import depth_sim as DS
DEC = np.arange(60, 721, 15)
KS = (0, 1, 2, 3, 4, 5); NS = (60, 300)


def boot(vals, groups, B=4000, seed=7):
    by = {}
    for v, g in zip(vals, groups):
        z = by.setdefault(g, [0.0, 0]); z[0] += v; z[1] += 1
    ks = list(by)
    if len(ks) < 5: return (float("nan"),) * 4
    s = np.array([by[k][0] for k in ks]); n = np.array([by[k][1] for k in ks]); r = np.random.default_rng(seed)
    ix = r.integers(0, len(ks), size=(B, len(ks))); m = np.sort(s[ix].sum(1) / n[ix].sum(1))
    return float(m[int(.025 * B)]), float(m[int(.975 * B)]), float(m[int(.005 * B)]), float(m[int(.995 * B)])


if __name__ == "__main__":
    Dr = sys.argv[1]; W = [w for w in json.load(open(os.path.join(Dr, "windows.json"))) if w["depth_secs"] >= 600]
    rows = {(k, N): [] for k in KS for N in NS}
    for wi, w in enumerate(W):
        z = np.load(os.path.join(Dr, "win", w["ticker"] + ".npz")); day = dt.datetime.fromtimestamp(w["open"], dt.timezone.utc).strftime("%Y-%m-%d")
        for fr in DS.frames(z, w["result"]):
            for k in KS:
                for N in NS:
                    r = DS.patient(fr, DEC, k, N); lv = r["live"]
                    if not lv.any(): continue
                    sc, _ = DS.exit_scalp(fr, np.where(r["fs"] >= 0, r["price"], np.nan), r["fs"])
                    filled = r["fs"] >= 0
                    scalp = np.where(filled, sc, 0.0); hold = np.where(filled, fr.res - r["price"], 0.0)
                    for i in np.where(lv)[0]:
                        rows[(k, N)].append((wi, day, bool(filled[i]), bool(r["through"][i]), r["q0"][i], r["ahead"][i], scalp[i] * 100, hold[i] * 100,
                                             (r["fs"][i] - DEC[i]) if filled[i] else -1))
    days = sorted({dt.datetime.fromtimestamp(w["open"], dt.timezone.utc).strftime("%Y-%m-%d") for w in W})
    print(f"{len(W)} windows, {len(days)} UTC days ({', '.join(f'{d}: ' + str(sum(1 for w in W if dt.datetime.fromtimestamp(w['open'], dt.timezone.utc).strftime('%Y-%m-%d') == d)) for d in days)}). "
          f"Decisions every 15 s, both sides. Cents per order sent (unfilled = 0); intervals resample windows.")
    out = {}
    for N in NS:
        print(f"\nRest up to {N} s")
        for k in KS:
            R = rows[(k, N)]
            if len(R) < 50: print(f"  k={k}: too few orders ({len(R)})"); continue
            wi = np.array([r[0] for r in R]); dy = np.array([r[1] for r in R]); f = np.array([r[2] for r in R]); thr = np.array([r[3] for r in R])
            q0 = np.array([r[4] for r in R]); ah = np.array([r[5] for r in R]); wait = np.array([r[8] for r in R])
            head = (f"  {'join the best bid (context)' if k == 0 else str(k) + 'c below the best bid'}: {len(R)} orders, {100 * f.mean():.0f}% filled"
                    + (f" (median wait {np.median(wait[f]):.0f} s, {100 * thr[f].mean():.0f}% by a sale through the price)" if f.any() else ""))
            print(head)
            print(f"      size at the price when placed: median {np.median(q0):,.0f}; still ahead when it filled: median {np.median(ah[f]) if f.any() else float('nan'):,.0f}"
                  f" ({100 * (ah[f] <= 100).mean() if f.any() else 0:.0f}% of fills with 100 or fewer ahead); still ahead at expiry, unfilled: median {np.median(ah[~f]) if (~f).any() else float('nan'):,.0f}")
            for name, col in (("scalp", 6), ("hold", 7)):
                v = np.array([r[col] for r in R]); lo, hi, lo9, hi9 = boot(v, wi)
                per_day = {d: float(v[dy == d].mean()) for d in days if (dy == d).any()}
                ok = (lo9 > 0) and all(x > 0 for x in per_day.values()) and len(per_day) == len(days)
                print(f"      {name:5s} {v.mean():+.2f}c per order [95% {lo:+.2f},{hi:+.2f}] [99% {lo9:+.2f},{hi9:+.2f}] · {v[f].mean() if f.any() else 0:+.2f}c per fill · by day "
                      + ", ".join(f"{d[5:]} {x:+.2f}" for d, x in per_day.items()) + ("" if k == 0 else (" · PASSES the bar" if ok else " · fails the bar")))
                out[f"k{k}|N{N}|{name}"] = dict(n=len(R), filled=float(f.mean()), mean=float(v.mean()), ci95=[lo, hi], ci99=[lo9, hi9], per_fill=float(v[f].mean()) if f.any() else None,
                                                 per_day=per_day, passes=bool(ok) if k else None, q0_median=float(np.median(q0)),
                                                 ahead_fill_median=float(np.median(ah[f])) if f.any() else None, through_share=float(thr[f].mean()) if f.any() else None)
    if len(sys.argv) > 2: json.dump(out, open(sys.argv[2], "w"), indent=1)
