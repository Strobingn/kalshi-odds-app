"""Part G (DESIGN.md, "Real order books"): the shipped model and rules with the real displayed queue.

Run: python3 rec_g.py <rows.npz> <scalper_model.json>
"""
import json, sys, random
import numpy as np
BUCKETS = [(0, 100, "0-100"), (100, 250, "101-250"), (250, 1000, "251-1,000"), (1000, 3000, "1,001-3,000"), (3000, 1e12, "over 3,000")]


def predict(model, X):
    """Vectorised evaluation of the exported trees (same comparisons as the app: float32 feature <= threshold)."""
    out = np.zeros(len(X)); rows = np.arange(len(X))
    for t in model["trees"]:
        f = np.array(t["f"]); th = np.array(t["t"]); l = np.array(t["l"]); r = np.array(t["r"]); v = np.array(t["v"])
        i = np.zeros(len(X), dtype=np.int64)
        while True:
            fi = f[i]; act = fi >= 0
            if not act.any(): break
            x = X[rows, np.where(act, fi, 0)]
            i = np.where(act, np.where(x <= th[i], l[i], r[i]), i)
        out += v[i]
    return out


def penalty(model, q):
    k = np.array([a for a, _ in model["queue_penalty"]]); p = np.array([b for _, b in model["queue_penalty"]])
    return np.interp(q, k, p)


def ci(vals, groups, B=3000, seed=5):
    """Mean with a 95% interval resampling whole groups (days or windows)."""
    by = {}
    for v, g in zip(vals, groups):
        z = by.setdefault(g, [0.0, 0]); z[0] += v; z[1] += 1
    ks = list(by)
    if len(ks) < 2: return NAN_CI
    s = np.array([by[k][0] for k in ks]); n = np.array([by[k][1] for k in ks]); rnd = np.random.default_rng(seed)
    ix = rnd.integers(0, len(ks), size=(B, len(ks))); m = s[ix].sum(1) / n[ix].sum(1); m.sort()
    return float(m[int(.025 * B)]), float(m[int(.975 * B)]), float(m[int(.005 * B)]), float(m[int(.995 * B)]), int((s > 0).sum()), len(ks)


NAN_CI = (float("nan"),) * 4 + (0, 0)


def line(name, y, sel, cols, day, win):
    n = int(sel.sum())
    if n < 30: return f"  {name:42s} n={n:6d}  (too few)"
    parts = []
    for c in cols:
        v = y[c][sel] * 100; filled = v != 0
        parts.append(f"{c} {v.mean():+.2f}c ({100 * filled.mean():3.0f}% filled, {v[filled].mean() if filled.any() else 0:+.2f}c each)")
    v = y["book"][sel] * 100; lo, hi, lo9, hi9, pd_, nd = ci(v, day[sel]); wlo, whi, *_ = ci(v, win[sel], seed=6)
    return f"  {name:42s} n={n:6d}  " + " | ".join(parts) + f" | BOOK 95% days [{lo:+.2f},{hi:+.2f}] windows [{wlo:+.2f},{whi:+.2f}] days+ {pd_}/{nd}"


if __name__ == "__main__":
    d = np.load(sys.argv[1], allow_pickle=True); model = json.load(open(sys.argv[2]))
    feats = [str(f) for f in d["feats"]]; T = [str(t) for t in d["targets"]]
    X = np.nan_to_num(d["X"][:, [feats.index(f) for f in model["features"]]].astype(np.float32), nan=0.0)
    y = {t: d["Y"][:, i].astype(np.float64) for i, t in enumerate(T)}
    win = d["win"].astype(int); sec = d["sec"].astype(int); day = d["wday"][win]; q = d["queue"]; tq = d["tq"] == 1
    pred = predict(model, X); theta = model["theta_cents"]
    base = tq & (sec % 5 == 0)
    pick = base & (pred >= theta); aware = base & (pred - penalty(model, np.nan_to_num(q, nan=3500.0)) >= 0)
    cols = ["front", "back", "volume", "book"]
    print(f"{len(set(win))} windows, {len(set(day))} days {min(day)}..{max(day)}; rows every 5 s: {int(base.sum())}")
    print("displayed size at the best bid on those rows: " + ", ".join(f"{p}% {np.nanpercentile(q[base], p):,.0f}" for p in (5, 10, 25, 50, 75, 90)))
    print("share of rows by size: " + ", ".join(f"{n} {100 * ((q[base] > lo) & (q[base] <= hi)).mean():.1f}%" for lo, hi, n in BUCKETS).replace("0-100", "0-100"))
    print("\nResting scalp, cents per order posted (join the book's best bid 20 s, offer +1c, stop 4c, 120 s):")
    print(line("every row", y, base, cols, day, win))
    print(line("shipped model picks", y, pick, cols, day, win))
    print(line("shipped model, queue-aware picks", y, aware, cols, day, win))
    for lo, hi, name in BUCKETS:
        b = (q > lo if lo > 0 else q >= 0) & (q <= hi)
        print(line(f"every row, {name} ahead", y, base & b, cols, day, win))
        print(line(f"model picks, {name} ahead", y, pick & b, cols, day, win))
    print("\nOther entries under BOOK (cents per order posted):")
    for name, sel, t in (("improve by one tick (spread >= 2c), all", base & ~np.isnan(y["improve_book"]), "improve_book"),
                         ("rest and hold to settlement, all", base, "hold_book"),
                         ("rest and hold, model picks", pick, "hold_book")):
        v = y[t][sel] * 100; lo_, hi_, *_r = ci(v, day[sel]); f = v != 0
        print(f"  {name:42s} n={int(sel.sum()):6d}  {v.mean():+.2f}c [{lo_:+.2f},{hi_:+.2f}]  {100 * f.mean():.0f}% filled")
    print("\nFast signals, rows every 2 s, BOOK rule:")
    fast = tq & (sec % 2 == 0); dm10 = d["X"][:, feats.index("dm10")]; dm30 = d["X"][:, feats.index("dm30")]
    with np.errstate(invalid="ignore"):
        sig = {"dip (fell 2c in 10 s)": dm10 <= -0.02 + 1e-6, "momentum (rose 2c in 10 s)": dm10 >= 0.02 - 1e-6, "extreme (fell 6c in 30 s)": dm30 <= -0.06 + 1e-6}
    for name, s in sig.items():
        for t in ("rest30_book", "take33_book" if name.startswith("extreme") else "take22_book"):
            sel = fast & s & ~np.isnan(y[t]); v = y[t][sel] * 100
            if sel.sum() < 30: continue
            lo_, hi_, _a, _b, pd_, nd = ci(v, day[sel]); f = v != 0
            print(f"  {name:28s} {t:12s} n={int(sel.sum()):6d}  {v.mean():+.2f}c [{lo_:+.2f},{hi_:+.2f}]  {100 * f.mean():3.0f}% filled  days+ {pd_}/{nd}")
