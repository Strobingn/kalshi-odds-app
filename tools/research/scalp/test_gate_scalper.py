"""Checks for the scalper retrain: the rolling split, the vectorised tree evaluator and the promotion rule."""
import os
import random

import numpy as np

import gate_scalper as G
from splits import split


def scalar(model, x):
    s = 0.0
    for t in model["trees"]:
        i = 0
        while t["f"][i] >= 0:
            i = t["l"][i] if float(np.float32(x[t["f"][i]])) <= t["t"][i] else t["r"][i]
        s += t["v"][i]
    return s


def test_split():
    days = [f"2026-10-{d:02d}" for d in range(1, 29)]
    os.environ.pop("SCALP_TEST_DAYS", None)
    tr, va, te = split(days)
    assert len(tr) == 11 and len(va) == 4 and len(te) == 13, "default: the original 11 / 4 / rest"
    os.environ["SCALP_TEST_DAYS"] = "7"
    tr, va, te = split(days)
    assert sorted(te) == days[-7:] and sorted(va) == days[-11:-7] and sorted(tr) == days[:-11]
    assert not (tr & va or va & te or tr & te)
    try:
        split(days[:10])
        raise AssertionError("too few days must stop the retrain")
    except SystemExit:
        pass
    os.environ.pop("SCALP_TEST_DAYS")


def test_predict_matches_the_app_evaluator():
    rnd = random.Random(3)
    trees = []
    for _ in range(6):
        # a full depth-3 tree: nodes 0..6 split, 7..14 leaves, children of i at 2i+1 / 2i+2
        f, t, l, r, v = [], [], [], [], []
        for i in range(15):
            if i < 7:
                f.append(rnd.randrange(3)); t.append(rnd.uniform(-1, 1)); l.append(2 * i + 1); r.append(2 * i + 2); v.append(0.0)
            else:
                f.append(-1); t.append(0.0); l.append(-1); r.append(-1); v.append(rnd.uniform(-2, 2))
        trees.append(dict(f=f, t=t, l=l, r=r, v=v))
    model = dict(trees=trees)
    X = np.random.default_rng(1).uniform(-1.2, 1.2, size=(400, 3)).astype(np.float32)
    X[0, 0] = np.float32(trees[0]["t"][0])  # a value exactly on a threshold goes left
    p = G.predict(model, X)
    for i in range(len(X)):
        assert abs(p[i] - scalar(model, X[i])) < 1e-12


def test_day_ci_and_decide():
    vals = [1.0] * 300 + [0.5] * 300
    days = ["a"] * 300 + ["b"] * 300
    r = G.day_ci(vals, days, iters=500)
    assert abs(r["mean"] - 0.75) < 1e-12 and r["up_days"] == 2 and r["n"] == 600
    assert r["lo"] > 0
    assert G.decide(r, None, old_saw_test=False)[0], "600 picks, both days up, interval above 0"
    few = G.day_ci(vals[:100], days[:100], iters=200)
    ok, why = G.decide(few, None, old_saw_test=False)
    assert not ok and "fewer than" in why[0]
    big = dict(mean=0.4, lo=0.1, hi=0.7, up_days=5, days=7, n=5000)
    assert G.decide(big, dict(mean=0.3), False)[0]
    ok, why = G.decide(big, dict(mean=0.5), False)
    assert not ok and "shipped model" in why[0]
    assert G.decide(big, dict(mean=0.5), True)[0], "not compared when the shipped model saw the test days"
    assert not G.decide(dict(big, lo=-0.01), None, False)[0]
    assert not G.decide(dict(big, up_days=3), None, False)[0]
    assert not G.decide(dict(big, mean=-0.1, lo=-0.3), None, False)[0]
    assert not G.decide(None, None, False)[0]
    assert G.theta_text(-1e9) == "takes every order" and G.theta_text(0.25) == "θ +0.250¢"


def test_classifier_predict_goes_through_both_curves():
    model = dict(kind="classifier", features=["a"], theta_cents=0.0,
                 calibration=dict(x=[0.0, 0.5, 1.0], y=[0.0, 0.4, 1.0]),
                 cents_by_chance=dict(x=[0.0, 1.0], y=[-4.0, 1.0]),
                 trees=[dict(f=[0, -1, -1], t=[0.5, 0, 0], l=[1, -1, -1], r=[2, -1, -1], v=[0, -0.4, 0.6])])
    X = np.array([[0.4], [0.6]], dtype=np.float32)
    m = G.margin(model, X)
    assert np.allclose(m, [-0.4, 0.6])
    raw = 1 / (1 + np.exp(-m))
    cal = np.interp(raw, [0.0, 0.5, 1.0], [0.0, 0.4, 1.0])
    assert np.allclose(G.predict(model, X), -4.0 + 5.0 * cal)
    reg = dict(model, kind="regression")
    assert np.allclose(G.predict(reg, X), m)


if __name__ == "__main__":
    test_split()
    test_predict_matches_the_app_evaluator()
    test_day_ci_and_decide()
    test_classifier_predict_goes_through_both_curves()
    print("ok gate_scalper")
