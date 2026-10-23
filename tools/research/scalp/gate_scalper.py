"""Promotion gate for a retrained scalper model (the weekly retrain, .github/workflows/train-scalper-model.yml).

Scores the new model and the model the app ships on the same TEST rows (the newest days, which neither model
was fitted or tuned on) and promotes the new one only when its picks
  * number at least MIN_PICKS,
  * made money front-of-queue: mean > 0 and the day-bootstrap 95 % interval above 0,
  * were up on at least half the test days,
  * did at least as well per order as the shipped model's picks on those same rows
    (skipped, with a note, when the shipped model was trained on any of the test days).

  python3 -m gate_scalper ml_data.npz scalper_model.json <repo>/app/src/main/assets/scalper_model.json gate.md gate.json
"""
import json
import random
import sys

import numpy as np

from splits import split

MIN_PICKS = 500


def predict(model, X):
    """The app's evaluator (ScalperModel.predict), vectorised: expected cents a contract. A regression file's trees
    give the cents directly; a classifier's trees give log-odds, turned into the calibrated chance and then cents
    through the file's two curves (np.interp, as ScalperModel.Curve)."""
    m = margin(model, X)
    if model.get("kind") != "classifier":
        return m
    return cents_of_margin(model, m)


def chance_of_margin(model, m):
    raw = 1.0 / (1.0 + np.exp(-np.asarray(m, dtype=np.float64)))
    cal = model.get("calibration")
    p = np.interp(raw, cal["x"], cal["y"]) if cal else raw
    return np.clip(p, 0.0, 1.0)


def cents_of_margin(model, m):
    c = model["cents_by_chance"]
    return np.interp(chance_of_margin(model, m), c["x"], c["y"])


def margin(model, X):
    """The trees' sum: float32 features compared as doubles, `<=` goes left."""
    X = np.asarray(X, dtype=np.float32).astype(np.float64)
    out = np.zeros(len(X))
    rows = np.arange(len(X))
    for t in model["trees"]:
        f = np.asarray(t["f"]); th = np.asarray(t["t"], dtype=np.float64)
        le = np.asarray(t["l"]); ri = np.asarray(t["r"]); v = np.asarray(t["v"], dtype=np.float64)
        node = np.zeros(len(X), dtype=np.int64)
        live = f[node] >= 0
        while live.any():
            i = node[live]
            go_left = X[rows[live], f[i]] <= th[i]
            node[live] = np.where(go_left, le[i], ri[i])
            live = f[node] >= 0
        out += v[node]
    return out


def day_ci(values, days, iters=4000, seed=5):
    """Mean per order and its 95 % interval, resampling whole days; plus the number of days that were up."""
    by = {}
    for v, d in zip(values, days):
        z = by.setdefault(d, [0.0, 0])
        z[0] += float(v)
        z[1] += 1
    keys = sorted(by)
    if not keys:
        return None
    rnd = random.Random(seed)
    means = []
    for _ in range(iters):
        s = n = 0.0
        for k in rnd.choices(keys, k=len(keys)):
            s += by[k][0]
            n += by[k][1]
        means.append(s / n)
    means.sort()
    total = sum(by[k][0] for k in keys) / sum(by[k][1] for k in keys)
    return dict(mean=total, lo=means[int(0.025 * iters)], hi=means[int(0.975 * iters)],
                up_days=sum(1 for k in keys if by[k][0] > 0), days=len(keys), n=int(sum(by[k][1] for k in keys)))


def theta_text(theta):
    """The threshold as the table shows it; train_compact uses −1e9 for "take every order"."""
    return "takes every order" if theta < -1e6 else f"θ {theta:+.3f}¢"


def decide(new, old, old_saw_test):
    """The promotion rule. `new` / `old` are day_ci results for each model's picks (old may be None)."""
    reasons = []
    if new is None or new["n"] < MIN_PICKS:
        reasons.append(f"fewer than {MIN_PICKS} picks on the test days")
    else:
        if new["mean"] <= 0:
            reasons.append("its picks lost money on the test days")
        if new["lo"] <= 0:
            reasons.append("the 95% interval of its picks reaches 0")
        if new["up_days"] * 2 < new["days"]:
            reasons.append("its picks were up on fewer than half the test days")
        if not old_saw_test and old is not None and new["mean"] < old["mean"]:
            reasons.append("the shipped model's picks did better on the same days")
    return len(reasons) == 0, reasons


def main(data, new_path, old_path, md_out, json_out):
    d = np.load(data, allow_pickle=True)
    feats_all = [str(f) for f in d["feats"]]
    new = json.load(open(new_path))
    old = json.load(open(old_path))

    def cols(model):
        return np.nan_to_num(d["X"][:, [feats_all.index(f) for f in model["features"]]].astype(np.float32), nan=0.0)

    X_new, X_old = cols(new), cols(old)
    Y = d["Y"].astype(np.float64)[:, 4] * 100.0
    mkt = d["mkt"].astype(int)
    day = d["mday"][mkt]
    days = sorted(set(d["mday"]))
    _, _, TE = split(days)
    c = np.isin(day, list(TE)) & ~np.isnan(Y)
    yt, dt = Y[c], day[c]
    p_new = predict(new, X_new[c])
    p_old = predict(old, X_old[c])
    pick_new = p_new >= new["theta_cents"]
    pick_old = p_old >= old["theta_cents"]
    r_new = day_ci(yt[pick_new], dt[pick_new])
    r_old = day_ci(yt[pick_old], dt[pick_old])
    r_all = day_ci(yt, dt)
    old_train_end = str(old.get("valid", old.get("trained", "..")).split("..")[-1])
    old_saw_test = bool(old_train_end) and old_train_end >= min(TE)
    ok, reasons = decide(r_new, r_old, old_saw_test)

    def row(name, r):
        if r is None:
            return f"| {name} | 0 | – | – | – |"
        return f"| {name} | {r['n']:,} | {r['mean']:+.3f}¢ | [{r['lo']:+.2f}, {r['hi']:+.2f}] | {r['up_days']}/{r['days']} |"

    lines = [
        "## Scalper model retrain",
        "",
        f"Test days (fitted and tuned on neither model): {min(TE)} … {max(TE)}. New model: trained {new['trained']}, "
        f"threshold tuned on {new['valid']}. Shipped model: trained {old.get('trained')}, tuned on {old.get('valid')}.",
        "",
        "Cents per order posted, front of the queue (rest at the bid 20 s, +1¢ offer, 4¢ stop, 120 s):",
        "",
        "| Orders | n | mean | 95% (by day) | days up |",
        "| --- | ---: | ---: | --- | ---: |",
        row("every order", r_all),
        row(f"shipped model's picks ({theta_text(old['theta_cents'])})", r_old),
        row(f"new model's picks ({theta_text(new['theta_cents'])})", r_new),
        "",
    ]
    if old_saw_test:
        lines += [f"The shipped model was tuned up to {old_train_end}, inside the test days, so it is not compared.", ""]
    lines += ["**Promote: yes.**" if ok else "**Promote: no** — " + "; ".join(reasons) + ". The app keeps the shipped model.", ""]
    open(md_out, "w").write("\n".join(lines))
    json.dump(dict(promote=ok, reasons=reasons, new=r_new, old=r_old, every=r_all,
                   test=[min(TE), max(TE)], old_saw_test=old_saw_test), open(json_out, "w"), indent=1)
    print("\n".join(lines))


if __name__ == "__main__":
    main(*sys.argv[1:6])
