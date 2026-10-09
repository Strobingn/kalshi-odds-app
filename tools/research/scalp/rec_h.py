"""Part H (DESIGN.md, "Real order books"): a model that sees the book and Bitcoin, leave-one-day-out.

Run: python3 rec_h.py <rows.npz> <out.json> [target] [feature set: all|tape|tape+book|tape+spot] [step seconds]
"""
import json, sys
import numpy as np, lightgbm as lgb
from rec_g import ci
from rec_rows import TAPE, BOOK, SPOT
QS = (0.0, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98, 0.99)
P = dict(objective="regression", learning_rate=0.03, num_leaves=31, min_data_in_leaf=300, feature_fraction=0.7, bagging_fraction=0.7,
         bagging_freq=1, lambda_l2=10.0, verbose=-1, seed=7, num_threads=2)


def run(d, target="book", fset="all", step=2, log=print):
    feats = [str(f) for f in d["feats"]]; T = [str(t) for t in d["targets"]]
    use = {"all": TAPE + BOOK + SPOT, "tape": TAPE, "tape+book": TAPE + BOOK, "tape+spot": TAPE + SPOT}[fset]
    y = d["Y"][:, T.index(target)].astype(np.float64) * 100.0
    sec = d["sec"].astype(int); win = d["win"].astype(int); day = d["wday"][win]
    ok = (~np.isnan(y)) & (sec % step == 0)
    X = d["X"][:, [feats.index(f) for f in use]][ok]; y = y[ok]; day = day[ok]; win = win[ok]
    days = sorted(set(day)); pred = np.full(len(y), np.nan); pick = np.zeros(len(y), bool); folds = []
    for te in days:
        tr_days = [x for x in days if x != te]; va = tr_days[-1]
        a = np.isin(day, tr_days[:-1]) if len(tr_days) > 1 else day == va; b = day == va; c = day == te
        dtr = lgb.Dataset(X[a], y[a], feature_name=use); dva = lgb.Dataset(X[b], y[b], reference=dtr)
        m = lgb.train(P, dtr, num_boost_round=1500, valid_sets=[dva], callbacks=[lgb.early_stopping(100, verbose=False)])
        pv = m.predict(X[b], num_iteration=m.best_iteration); pt = m.predict(X[c], num_iteration=m.best_iteration)
        best = None
        for q in QS:
            th = np.quantile(pv, q) if q > 0 else -1e18; s = pv >= th
            if s.sum() >= 200:
                tot = y[b][s].sum()
                if tot > 0 and (best is None or tot > best[0]): best = (tot, q, float(th))
        pred[c] = pt
        if best: pick[c] = pt >= best[2]
        folds.append(dict(test=te, valid=va, trees=int(m.best_iteration), q=best[1] if best else None, theta=best[2] if best else None,
                          valid_mean=float(y[b][pv >= best[2]].mean()) if best else None,
                          n=int(c.sum()), picks=int(pick[c].sum()), all_mean=float(y[c].mean()),
                          pick_mean=float(y[c][pick[c]].mean()) if pick[c].any() else None))
        f = folds[-1]
        log(f"  held out {te}: {f['trees']} trees, " + ("no trade" if not best else f"top {round((1 - f['q']) * 100)}% (theta {f['theta']:+.2f}c, inner {f['valid_mean']:+.2f}c)") +
            f"; all {f['all_mean']:+.2f}c n={f['n']}" + (f"; picks {f['pick_mean']:+.2f}c n={f['picks']}" if f["picks"] else "; no picks"))
    res = dict(target=target, features=fset, step=step, folds=folds, n=int(len(y)), all_mean=float(y.mean()), picks=int(pick.sum()))
    if pick.sum() >= 30:
        v = y[pick]; lo, hi, lo9, hi9, pd_, nd = ci(v, day[pick]); wlo, whi, *_ = ci(v, win[pick], seed=6)
        res.update(pick_mean=float(v.mean()), ci95=[lo, hi], ci99=[lo9, hi9], win95=[wlo, whi], posdays=[pd_, nd], filled=float((v != 0).mean()))
        log(f"{target} / {fset}: out-of-fold picks n={int(pick.sum())} ({100 * pick.mean():.1f}% of rows) {v.mean():+.3f}c per order, 95% days [{lo:+.2f},{hi:+.2f}] "
            f"99% [{lo9:+.2f},{hi9:+.2f}], windows [{wlo:+.2f},{whi:+.2f}], days+ {pd_}/{nd}, {100 * (v != 0).mean():.0f}% filled; every row {y.mean():+.3f}c")
    else:
        log(f"{target} / {fset}: the model picks nothing ({int(pick.sum())} rows); every row {y.mean():+.3f}c")
    o = np.argsort(pred); res["deciles"] = [float(y[ix].mean()) for ix in np.array_split(o, 10)]
    res["top1pct"] = float(y[o[-max(len(o) // 100, 1):]].mean()); res["top01pct"] = float(y[o[-max(len(o) // 1000, 1):]].mean())
    log("  out-of-fold deciles by prediction: " + " ".join(f"{x:+.2f}" for x in res["deciles"]) + f" | top 1% {res['top1pct']:+.2f}c | top 0.1% {res['top01pct']:+.2f}c")
    return res, pred, ok


if __name__ == "__main__":
    d = np.load(sys.argv[1], allow_pickle=True); out = sys.argv[2]
    target = sys.argv[3] if len(sys.argv) > 3 else "book"; fset = sys.argv[4] if len(sys.argv) > 4 else "all"; step = int(sys.argv[5]) if len(sys.argv) > 5 else 2
    res, pred, ok = run(d, target, fset, step, log=lambda s: print(s, flush=True))
    json.dump(res, open(out, "w"), indent=1); np.save(out.replace(".json", "_pred.npy"), pred)
