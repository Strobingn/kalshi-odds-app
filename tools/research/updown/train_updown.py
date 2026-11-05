#!/usr/bin/env python3
"""Walk-forward test of up/down models on build_updown.py rows, then the final fit.

Every day from the 4th on is predicted by models trained only on the days before it, so every number in the report
is out of sample. Rows inside one window are strongly correlated (one result each), so confidence intervals are
bootstrapped over windows, never over rows.

Compared on the same rows:
  market  the YES mid of the book, taken as the chance of UP
  fair    the app's math (ScalpFair: settlement-average digital on Coinbase spot)
  lr2     logistic regression on logit(mid) and logit(fair) only (a calibrated blend)
  lr      logistic regression on every feature (with time-left interactions)
  gbm     LightGBM, small

Paper betting replay, one look a minute (like the Paper AI): buy the side whose model chance beats its ask by more than
the cut plus the taker fee, at the ask, 1 contract, asks 10..85c. No depth, no queue: a best case for a 1-lot.

Usage: train_updown.py rows.npz report.json [final_model.json]
"""
from __future__ import annotations

import json
import math
import sys

import numpy as np

FEE = 0.07
LO_ASK, HI_ASK = 0.10, 0.85
SEED = 7


def ll(p, y):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p)))


def brier(p, y):
    return float(np.mean((p - y) ** 2))


def ece(p, y, bins=10):
    e, n = 0.0, len(p)
    edges = np.linspace(0, 1, bins + 1)
    out = []
    for a, b in zip(edges[:-1], edges[1:]):
        m = (p >= a) & (p < b) if b < 1 else (p >= a) & (p <= b)
        if m.sum() == 0:
            continue
        e += m.sum() / n * abs(p[m].mean() - y[m].mean())
        out.append([round(float(p[m].mean()), 4), round(float(y[m].mean()), 4), int(m.sum())])
    return float(e), out


COMPACT = ["logit_mid", "logit_mid*sqrt_tleft", "logit_mid*tleft", "edge_logit", "edge_logit*sqrt_tleft", "imb",
           "r30", "r60", "dmid30", "dmid60", "spread"]


def design(X, names, kind):
    i = {n: k for k, n in enumerate(names)}
    if kind == "compact":
        t = X[:, i["tleft"]]
        st = np.sqrt(t)
        lm, el = X[:, i["logit_mid"]], X[:, i["edge_logit"]]
        return np.column_stack([lm, lm * st, lm * t, el, el * st, X[:, i["imb"]], X[:, i["r30"]], X[:, i["r60"]],
                                X[:, i["dmid30"]], X[:, i["dmid60"]], X[:, i["spread"]]])
    if kind == "lr2":
        cols = [X[:, i["logit_mid"]], X[:, i["logit_fair"]]]
    else:
        t = X[:, i["tleft"]]
        st = np.sqrt(t)
        base = [X[:, k] for k in range(X.shape[1]) if names[k] not in ("mid", "fair")]
        inter = [X[:, i["logit_mid"]] * st, X[:, i["logit_fair"]] * st, X[:, i["edge_logit"]] * st,
                 X[:, i["imb"]] * st, X[:, i["r30"]] * st, X[:, i["r60"]] * st, X[:, i["dmid30"]] * st]
        cols = base + inter
    return np.column_stack(cols)


DESIGN_LR = None  # filled by design_names


def design_names(names):
    base = [n for n in names if n not in ("mid", "fair")]
    return base + ["logit_mid*sqrt_tleft", "logit_fair*sqrt_tleft", "edge_logit*sqrt_tleft", "imb*sqrt_tleft",
                   "r30*sqrt_tleft", "r60*sqrt_tleft", "dmid30*sqrt_tleft"]


def fit_lr(Z, y, C):
    from sklearn.linear_model import LogisticRegression
    mu, sd = Z.mean(0), Z.std(0) + 1e-9
    m = LogisticRegression(C=C, max_iter=2000)
    m.fit((Z - mu) / sd, y)
    w = m.coef_[0] / sd
    b = float(m.intercept_[0] - np.sum(m.coef_[0] * mu / sd))
    return w, b


def pred_lr(Z, w, b):
    return 1.0 / (1.0 + np.exp(-(Z @ w + b)))


def fit_gbm(X, y, params):
    import lightgbm as lgb
    m = lgb.LGBMClassifier(**params, random_state=SEED, verbose=-1)
    m.fit(X, y)
    return m


C_COMPACT = 1.0
GBM = dict(n_estimators=300, learning_rate=0.03, num_leaves=15, min_child_samples=400, subsample=0.8,
           subsample_freq=1, colsample_bytree=0.8, reg_lambda=5.0)


def boot_ci(per_window, stat, n=2000):
    rng = np.random.default_rng(SEED)
    keys = list(per_window)
    vals = []
    for _ in range(n):
        pick = rng.integers(0, len(keys), len(keys))
        vals.append(stat([per_window[keys[k]] for k in pick]))
    return [round(float(np.percentile(vals, 2.5)), 4), round(float(np.percentile(vals, 97.5)), 4)]


def metric_block(p, y, win):
    e, cal = ece(p, y)
    by = {}
    for w_, pi, yi in zip(win, p, y):
        by.setdefault(w_, []).append((pi, yi))
    def ll_of(groups):
        a = np.array([q for g in groups for q in g])
        return ll(a[:, 0], a[:, 1])
    return dict(logloss=round(ll(p, y), 4), brier=round(brier(p, y), 4), accuracy=round(float(np.mean((p > 0.5) == y)), 4),
                ece=round(e, 4), calibration=cal, logloss_ci95=boot_ci(by, ll_of, 500))


def bets(p, d, cut, rows):
    """One look a minute. Returns per-window lists of (pnl per contract after fee)."""
    out = {}
    for k in rows:
        ya, na = d["yes_ask"][k], d["no_ask"][k]
        pk = p[k]
        best = None
        for side, ask, ch in (("YES", ya, pk), ("NO", na, 1 - pk)):
            if not (LO_ASK <= ask <= HI_ASK):
                continue
            fee = math.ceil(FEE * ask * (1 - ask) * 100 - 1e-9) / 100.0
            edge = ch - ask - fee
            if edge > cut and (best is None or edge > best[0]):
                best = (edge, side, ask, fee)
        if best is None:
            continue
        _, side, ask, fee = best
        won = (d["y"][k] == 1) == (side == "YES")
        out.setdefault(d["window"][k], []).append((1.0 - ask if won else -ask) - fee)
    return out


def bet_block(per_window):
    allv = [v for g in per_window.values() for v in g]
    if not allv:
        return dict(bets=0)
    days = {}
    return dict(bets=len(allv), windows=len(per_window), win_rate=round(float(np.mean([v > 0 for v in allv])), 4),
                cents_per_contract=round(100 * float(np.mean(allv)), 3), net_usd_1lot=round(float(np.sum(allv)), 2),
                cents_ci95=[round(100 * x, 3) for x in boot_ci(per_window, lambda gs: float(np.mean([v for g in gs for v in g])) if gs else 0.0)])


def _date(day: int) -> str:
    import datetime
    return (datetime.date(1970, 1, 1) + datetime.timedelta(days=int(day))).isoformat()


def main():
    d = dict(np.load(sys.argv[1], allow_pickle=False))
    names = [str(n) for n in d["names"]]
    X, y = d["X"], d["y"].astype(float)
    days = sorted(set(d["day"].tolist()))
    oos = {k: np.full(len(y), np.nan) for k in ("lr2", "compact", "lr", "gbm")}
    tested = np.zeros(len(y), bool)
    for i, dd in enumerate(days):
        if i < 3:
            continue
        tr, te = d["day"] < dd, d["day"] == dd
        if te.sum() == 0:
            continue
        tested |= te
        Z2 = design(X, names, "lr2"); w, b = fit_lr(Z2[tr], y[tr], 1.0); oos["lr2"][te] = pred_lr(Z2[te], w, b)
        Zc = design(X, names, "compact"); w, b = fit_lr(Zc[tr], y[tr], C_COMPACT); oos["compact"][te] = pred_lr(Zc[te], w, b)
        Z = design(X, names, "lr"); w, b = fit_lr(Z[tr], y[tr], 0.1); oos["lr"][te] = pred_lr(Z[te], w, b)
        g = fit_gbm(X[tr], y[tr], GBM); oos["gbm"][te] = g.predict_proba(X[te])[:, 1]
        print(f"day {dd}: train {int(tr.sum())} rows, test {int(te.sum())}", flush=True)
    m = tested
    preds = {"market": np.clip(d["mid"], 0.001, 0.999), "fair": d["fair"], **oos}
    report = {"rows_tested": int(m.sum()), "windows_tested": len(set(d["window"][m].tolist())),
              "days_tested": len(set(d["day"][m].tolist())), "metrics": {}, "by_time_left": {}, "paper_bets": {}}
    for k, p in preds.items():
        report["metrics"][k] = metric_block(p[m], y[m], d["window"][m])
    sec = d["sec"]
    for lo, hi in ((20, 300), (300, 600), (600, 780), (780, 880)):
        mm = m & (sec >= lo) & (sec <= hi)
        report["by_time_left"][f"{900 - hi}-{900 - lo}s left"] = {k: round(ll(p[mm], y[mm]), 4) for k, p in preds.items()}
    minute_rows = np.where(m & ((sec - 20) % 60 == 0))[0]
    for k in ("fair", "lr2", "compact", "lr", "gbm"):
        report["paper_bets"][k] = {f"cut_{int(c * 100)}c": bet_block(bets(preds[k], d, c, minute_rows)) for c in (0.0, 0.02, 0.05)}
    if len(sys.argv) > 3:
        Zc = design(X, names, "compact")
        w, b = fit_lr(Zc, y, C_COMPACT)
        report["final"] = dict(kind="logistic", features=COMPACT, weights=[float(v) for v in w], bias=b,
                               trained_rows=int(len(y)), trained_windows=len(set(d["window"].tolist())),
                               trained_days=len(days), first_day=_date(days[0]), last_day=_date(days[-1]))
        mt = report["metrics"]
        report["final"]["walk_forward"] = dict(
            note="Each day from the 4th on predicted by a model trained only on the days before it.",
            rows=report["rows_tested"], windows=report["windows_tested"], days=report["days_tested"],
            model={k: mt["compact"][k] for k in ("logloss", "brier", "accuracy", "ece", "logloss_ci95")},
            market={k: mt["market"][k] for k in ("logloss", "brier", "accuracy", "ece", "logloss_ci95")},
            fair={k: mt["fair"][k] for k in ("logloss", "brier", "accuracy", "ece", "logloss_ci95")},
            calibration=mt["compact"]["calibration"], market_calibration=mt["market"]["calibration"],
            by_time_left={k: {kk: v[kk] for kk in ("compact", "market", "fair")} for k, v in report["by_time_left"].items()},
            paper_replay=report["paper_bets"]["compact"])
        json.dump(report["final"], open(sys.argv[3], "w"), indent=1)
    json.dump(report, open(sys.argv[2], "w"), indent=1)
    print(json.dumps({k: {kk: v[kk] for kk in ("logloss", "brier", "accuracy", "ece", "logloss_ci95")} for k, v in report["metrics"].items()}, indent=1))
    print(json.dumps(report["by_time_left"], indent=1))
    print(json.dumps(report["paper_bets"], indent=1))


if __name__ == "__main__":
    main()
