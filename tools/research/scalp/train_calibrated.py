"""The app's scalper model: a depth-capped LightGBM classifier with a calibrated probability, on the 25 features that
add the most after-fee profit. Run in the working folder after build_ml:  python3 -m train_calibrated

Target: R_SCALP_AT (rest at the best bid 20 s, offer +1c when filled, 4c stop, 120 s; cents per order posted, front
of the queue, after fees). Label: the order made money (> 0c). Same split as train_compact (splits.py): TRAIN fits,
VALID picks the features, calibrates and sets the threshold, TEST is scored once at the end.

1. Candidates: every feature build_ml computes that the app can rebuild from the prints (PrintGrid.CANDIDATES);
   the Coinbase spot features are left out (the app prices from Kalshi only).
2. Feature selection ("the money makers"): fit on every candidate, then drop the features whose shuffling on VALID
   costs the least after-fee profit (the VALID total of the orders the model picks at its own cut), refit, and
   repeat until SCALP_N_FEATURES (25) remain.
3. Final model: binary LightGBM, max_depth SCALP_MAX_DEPTH (5), early stopping on VALID log-loss.
4. Calibration: isotonic regression of the outcome on the raw probability, fitted on VALID (probability = chance
   the order makes money). Expected cents: isotonic regression of the cents on the calibrated probability, also on
   VALID, so the threshold and the queue penalty stay in cents. Threshold: the VALID quantile cut with the highest
   VALID total, as train_compact.
5. Export scalper_model.json (kind "classifier", trees with every node's value for the Explain button, both
   curves, a report the app charts) plus compact_pred_test.npy / compact_meta.json for queue_ml and
   export_app_model.
"""
import json
import os
import random

import lightgbm as lgb
import numpy as np
from sklearn.isotonic import IsotonicRegression

from splits import split

N_KEEP = int(os.environ.get("SCALP_N_FEATURES", "25"))
MAX_DEPTH = int(os.environ.get("SCALP_MAX_DEPTH", "5"))
SEED = 7

# Must match PrintGrid.CANDIDATE_LOOKBACK (the app rebuilds exactly these, by name).
CANDIDATES = (
    ["ask", "bid", "mid", "spread", "tte", "dist50", "hour", "weekend"]
    + [f"dm{k}" for k in (5, 10, 20, 30, 60, 120, 180, 300)]
    + ["from_hi", "from_lo", "from_first", "imb_all", "lvol_all", "hi60", "lo60"]
    + [f"vol{w}" for w in (30, 60, 120, 300)]
    + [f"nchg{w}" for w in (30, 60, 120)]
    + [f"{p}{w}" for w in (5, 10, 30, 60, 120, 300) for p in ("imb", "lvol")]
    + [f"lcnt{w}" for w in (30, 60, 120)]
    + [f"{p}{w}" for w in (30, 120) for p in ("lmaxbuy", "lmaxsell")]
    + ["stale_bid", "stale_ask"]
)

PARAMS = dict(
    objective="binary", learning_rate=0.05, max_depth=MAX_DEPTH, num_leaves=min(2 ** MAX_DEPTH, 24),
    min_data_in_leaf=500, feature_fraction=0.8, bagging_fraction=0.8, bagging_freq=1, lambda_l2=10.0,
    verbose=-1, seed=SEED, num_threads=int(os.environ.get("SCALP_THREADS", "2")), use_missing=False,
)
MAX_ROUNDS = 400
QUANTILES = (0.0, 0.15, 0.30, 0.50, 0.70, 0.80, 0.90)


def load():
    d = np.load("ml_data.npz", allow_pickle=True)
    allf = [str(f) for f in d["feats"]]
    missing = [f for f in CANDIDATES if f not in allf]
    assert not missing, f"build_ml does not compute {missing}"
    X = np.nan_to_num(d["X"][:, [allf.index(f) for f in CANDIDATES]].astype(np.float32), nan=0.0)
    y = d["Y"].astype(np.float64)[:, 4] * 100.0
    mkt = d["mkt"].astype(int)
    day = d["mday"][mkt]
    days = sorted(set(d["mday"]))
    tr, va, te = split(days)
    ok = ~np.isnan(y)
    a = np.isin(day, list(tr)) & ok
    b = np.isin(day, list(va)) & ok
    c = np.isin(day, list(te)) & ok
    return X, y, day, a, b, c, tr, va, te


def fit(X, y, a, b, cols):
    lab = (y > 0).astype(np.float64)
    dtr = lgb.Dataset(X[a][:, cols], lab[a], feature_name=[CANDIDATES[i] for i in cols], free_raw_data=False)
    dva = lgb.Dataset(X[b][:, cols], lab[b], reference=dtr)
    return lgb.train(PARAMS, dtr, num_boost_round=MAX_ROUNDS, valid_sets=[dva],
                     callbacks=[lgb.early_stopping(40, verbose=False)])


def best_cut(score, cents):
    """The quantile cut of [score] with the highest total [cents] (train_compact's rule): (total, q, threshold)."""
    best = None
    for q in QUANTILES:
        th = float(np.quantile(score, q)) if q > 0 else -1e9
        tot = float(cents[score >= th].sum())
        if best is None or tot > best[0]:
            best = (tot, q, th)
    return best


def select(X, y, a, b, log):
    cols = list(range(len(CANDIDATES)))
    rng = np.random.default_rng(SEED)
    rounds = []
    while True:
        m = fit(X, y, a, b, cols)
        Xb = X[b][:, cols]
        yb = y[b]
        base = m.predict(Xb, num_iteration=m.best_iteration)
        tot, q, th = best_cut(base, yb)
        drops = {}
        for j, ci in enumerate(cols):
            loss = 0.0
            for _ in range(3):
                Xp = Xb.copy()
                Xp[:, j] = Xp[rng.permutation(len(Xp)), j]
                p = m.predict(Xp, num_iteration=m.best_iteration)
                loss += tot - float(yb[p >= th].sum())
            drops[ci] = loss / 3.0 / 100.0  # dollars a contract-lot
        gains = dict(zip(cols, m.feature_importance("gain")))
        ranked = sorted(cols, key=lambda ci: (drops[ci], gains[ci]), reverse=True)
        rounds.append(dict(n=len(cols), valid_total_usd=round(tot / 100.0, 2), trees=int(m.best_iteration),
                           drop_usd={CANDIDATES[ci]: round(drops[ci], 2) for ci in ranked}))
        log(f"  {len(cols):2d} features: VALID total ${tot / 100.0:+.1f} with {m.best_iteration} trees; "
            f"most profit lost when shuffled: " + ", ".join(f"{CANDIDATES[ci]} {drops[ci]:+.1f}" for ci in ranked[:6]))
        if len(cols) <= N_KEEP:
            return [CANDIDATES[ci] for ci in ranked], rounds
        n_next = max(N_KEEP, len(cols) - max(1, (len(cols) - N_KEEP + 1) // 2))
        cols = sorted(ranked[:n_next])


def iso_knots(x, y):
    iso = IsotonicRegression(increasing=True, out_of_bounds="clip", y_min=None, y_max=None).fit(x, y)
    xs = np.asarray(iso.X_thresholds_, dtype=np.float64)
    ys = np.asarray(iso.y_thresholds_, dtype=np.float64)
    return xs, ys


def thin(xs, ys, max_knots=200):
    """At most [max_knots] knots, keeping both ends; np.interp through the result stays monotone."""
    if len(xs) <= max_knots:
        return xs, ys
    keep = np.unique(np.round(np.linspace(0, len(xs) - 1, max_knots)).astype(int))
    return xs[keep], ys[keep]


def day_ci(vals, days, B=4000):
    by = {}
    for v, x in zip(vals, days):
        z = by.setdefault(x, [0.0, 0])
        z[0] += v
        z[1] += 1
    ks = list(by)
    if not ks:
        return None
    rnd = random.Random(5)
    o = []
    for _ in range(B):
        s = n = 0.0
        for k in rnd.choices(ks, k=len(ks)):
            s += by[k][0]
            n += by[k][1]
        o.append(s / n)
    o.sort()
    return dict(lo=o[int(.025 * B)], hi=o[int(.975 * B)], up_days=sum(1 for k in ks if by[k][0] > 0), days=len(ks))


def flatten(node, out):
    i = len(out)
    out.append(None)
    if "split_feature" not in node:
        out[i] = dict(leaf=node["leaf_value"])
    else:
        assert node["decision_type"] == "<=", node["decision_type"]
        le = flatten(node["left_child"], out)
        ri = flatten(node["right_child"], out)
        out[i] = dict(f=node["split_feature"], t=node["threshold"], l=le, r=ri, iv=node["internal_value"])
    return i


def tree_sum(trees, x):
    s = 0.0
    for t in trees:
        i = 0
        while t["f"][i] >= 0:
            i = t["l"][i] if float(np.float32(x[t["f"][i]])) <= t["t"][i] else t["r"][i]
        s += t["v"][i]
    return s


def main():
    lines = []

    def log(s):
        print(s, flush=True)
        lines.append(s)

    X, y, day, a, b, c, TR, VA, TE = load()
    log(f"rows: train {a.sum():,}, valid {b.sum():,}, test {c.sum():,}; candidates {len(CANDIDATES)}; "
        f"label: made money (> 0c), {100 * (y[a] > 0).mean():.1f}% of train rows")
    log("feature selection on VALID (profit lost when a feature is shuffled):")
    feats, rounds = select(X, y, a, b, log)
    cols = [CANDIDATES.index(f) for f in feats]
    m = fit(X, y, a, b, cols)
    it = m.best_iteration

    Xb, Xc = X[b][:, cols], X[c][:, cols]
    lab_b, lab_c = (y[b] > 0).astype(float), (y[c] > 0).astype(float)
    raw_b = m.predict(Xb, num_iteration=it)
    raw_c = m.predict(Xc, num_iteration=it)
    cx, cy = thin(*iso_knots(raw_b, lab_b))
    cal_b = np.clip(np.interp(raw_b, cx, cy), 0.0, 1.0)
    cal_c = np.clip(np.interp(raw_c, cx, cy), 0.0, 1.0)
    kx, ky = thin(*iso_knots(cal_b, y[b]))
    cents_b = np.interp(cal_b, kx, ky)
    cents_c = np.interp(cal_c, kx, ky)
    tot, q, theta = best_cut(cents_b, y[b])
    yt = y[c]
    sel = cents_c >= theta

    log(f"final: {len(feats)} features, {it} trees, max depth {MAX_DEPTH}")
    for qq in QUANTILES:
        th = float(np.quantile(cents_b, qq)) if qq > 0 else -1e9
        s = cents_b >= th
        log(f"  VALID top {int(round((1 - qq) * 100)):3d}%: n={int(s.sum()):6d} mean {y[b][s].mean():+.3f}c total {y[b][s].sum() / 100:+9.1f}$")
    ci = day_ci(yt[sel], day[c][sel]) or dict(lo=0, hi=0, up_days=0, days=0)
    log(f"theta {theta:+.4f}c (top {int(round((1 - q) * 100))}% of VALID)")
    log(f"TEST: take everything {yt.mean():+.3f}c (n={len(yt)}); model picks n={int(sel.sum())} ({100 * sel.mean():.1f}%) "
        f"{yt[sel].mean():+.3f}c 95% [{ci['lo']:+.2f},{ci['hi']:+.2f}] days+ {ci['up_days']}/{ci['days']}")

    def brier(p, lab):
        return float(np.mean((p - lab) ** 2))

    edges = np.quantile(cal_c, np.linspace(0, 1, 11))
    bins = []
    for k in range(10):
        lo, hi = edges[k], edges[k + 1]
        s = (cal_c >= lo) & ((cal_c <= hi) if k == 9 else (cal_c < hi))
        if s.sum() == 0:
            continue
        bins.append(dict(p=round(float(cal_c[s].mean()), 4), won=round(float(lab_c[s].mean()), 4),
                         cents_pred=round(float(cents_c[s].mean()), 3), cents=round(float(yt[s].mean()), 3), n=int(s.sum())))
    ece = sum(abs(bn["p"] - bn["won"]) * bn["n"] for bn in bins) / max(1, sum(bn["n"] for bn in bins))
    log(f"TEST calibration: Brier raw {brier(raw_c, lab_c):.4f} -> calibrated {brier(cal_c, lab_c):.4f}; "
        f"expected calibration error {ece:.4f}")
    log("TEST deciles (calibrated chance -> won / cents): " + "; ".join(f"{bn['p']:.2f}->{bn['won']:.2f}/{bn['cents']:+.2f}c" for bn in bins))
    gain = sorted(zip(m.feature_importance("gain", iteration=it), feats), reverse=True)
    log("gain: " + ", ".join(f for _, f in gain[:10]))

    dump = m.dump_model(num_iteration=it)
    trees = []
    for t in dump["tree_info"]:
        nodes = []
        flatten(t["tree_structure"], nodes)
        trees.append(dict(
            f=[n.get("f", -1) for n in nodes], t=[n.get("t", 0.0) for n in nodes],
            l=[n.get("l", -1) for n in nodes], r=[n.get("r", -1) for n in nodes],
            v=[n.get("leaf", 0.0) for n in nodes],
            iv=[n["iv"] if "iv" in n else n["leaf"] for n in nodes],
        ))
    depth = 0
    for t in trees:
        def dep(i):
            return 0 if t["f"][i] < 0 else 1 + max(dep(t["l"][i]), dep(t["r"][i]))
        depth = max(depth, dep(0))
    assert depth <= MAX_DEPTH, depth

    report = dict(
        test=dict(n=int(sel.sum()), every=int(len(yt)), mean_cents=round(float(yt[sel].mean()), 4),
                  every_cents=round(float(yt.mean()), 4), lo=round(ci["lo"], 4), hi=round(ci["hi"], 4),
                  up_days=ci["up_days"], days=ci["days"], brier_raw=round(brier(raw_c, lab_c), 5),
                  brier=round(brier(cal_c, lab_c), 5), ece=round(ece, 5)),
        calibration_bins=bins,
        gain=[[f, round(float(g), 1)] for g, f in gain],
        selection=[dict(n=r["n"], valid_total_usd=r["valid_total_usd"], trees=r["trees"]) for r in rounds],
        money_makers=[[f, rounds[-1]["drop_usd"][f]] for f in feats],
        candidates=len(CANDIDATES), max_depth=MAX_DEPTH, trees=len(trees),
    )
    model = dict(
        version=2, kind="classifier", name="scalper_rest_plus1c_calibrated", features=feats, theta_cents=theta,
        target="chance and cents per order posted: rest at the best bid 20 s, offer +1c, stop 4c, 120 s",
        trained=f"{min(TR)}..{max(TR)}", valid=f"{min(VA)}..{max(VA)}", test=f"{min(TE)}..{max(TE)}",
        test_front_of_queue_cents=round(float(yt[sel].mean()), 4), test_take_everything_cents=round(float(yt.mean()), 4),
        calibration=dict(x=[float(v) for v in cx], y=[float(v) for v in cy]),
        cents_by_chance=dict(x=[float(v) for v in kx], y=[float(v) for v in ky]),
        report=report, trees=trees,
    )
    json.dump(model, open("scalper_model.json", "w"), separators=(",", ":"))

    # The exported trees must give LightGBM's raw score, and the node values must add up to it (Explain).
    idx = np.where(c)[0][::997][:300]
    raw = m.predict(X[idx][:, cols], num_iteration=it, raw_score=True)
    mx = max(abs(tree_sum(trees, X[i][cols]) - r) for i, r in zip(idx, raw))
    log(f"exported trees {len(trees)}, nodes {sum(len(t['f']) for t in trees)}, max |trees - lightgbm raw| on 300 rows: {mx:.2e}")
    assert mx < 1e-6, mx
    bias = sum(t["iv"][0] for t in trees)
    x0 = X[idx[0]][cols]
    contrib = np.zeros(len(feats))
    for t in trees:
        i = 0
        while t["f"][i] >= 0:
            nxt = t["l"][i] if float(np.float32(x0[t["f"][i]])) <= t["t"][i] else t["r"][i]
            contrib[t["f"][i]] += t["iv"][nxt] - t["iv"][i]
            i = nxt
    assert abs(bias + contrib.sum() - tree_sum(trees, x0)) < 1e-9

    np.save("compact_pred_test.npy", np.column_stack([cents_c, yt]))
    json.dump(dict(theta=theta, feats=feats), open("compact_meta.json", "w"))
    open("train_report.txt", "w").write("\n".join(lines) + "\n")
    print("model json bytes", os.path.getsize("scalper_model.json"))


if __name__ == "__main__":
    main()
