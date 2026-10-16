"""Smart exit for the ML scalper: sell a filled scalp early when holding on is expected to lose.

The ML scalper (scalper_model.json) rests a bid, then exits on fixed rules: a resting offer 1c up, a sale at
the bid 4c down, or a sale at the bid after 120 s. This trains a second model that looks at the position once a
second while it is open and predicts, in cents per contract, how much better keeping the fixed rules is than
selling at the bid right now (taker fee paid). The app sells when that is below theta_cents.

Rows: every second t a filled scalp is still open after the fixed checks of that second (stop, then target),
with a bid and an ask on the print grid, before the 120 s time-out. Label = what the fixed rules went on to
make - (bid[t] - entry - fee(bid[t])). Features: the 23 compact features at t (PrintGrid.FEATURES, no decision
band) plus EXTRA. Same split as the scalper model (splits.py); train rows are subsampled, theta is the
VALID-best cut, TEST is scored once, on the orders the shipped entry model picks.

  python3 -m exit_ml <tape dir> <shipped scalper_model.json> [nproc]
writes scalper_exit_model.json, scalper_exit_parity.json, exit_gate.md, exit_gate.json in the working folder."""
import datetime as dt
import json
import os
import sys

import numpy as np

import scalp_b as B
from gate_scalper import day_ci, predict
from splits import split

COMPACT = ["mid", "spread", "tte", "dm5", "dm10", "dm20", "dm30", "dm60", "vol60", "nchg60",
           "imb5", "lvol5", "imb10", "lvol10", "imb30", "lvol30", "imb60", "lvol60",
           "lcnt30", "lmaxbuy30", "lmaxsell30", "stale_bid", "stale_ask"]
EXTRA = ["held", "gain_bid", "gain_ask", "entry"]
FEATS = COMPACT + EXTRA
TARGET, STOP, HOLD = 0.01, 0.04, 120
TRAIN_KEEP = 0.2
THETAS = [-3.0, -2.0, -1.5, -1.0, -0.75, -0.5, -0.25, 0.0, 0.25]
MIN_ORDERS = 500


def _rollsum(a, w):
    c = np.concatenate([[0.0], np.cumsum(a)])
    i = np.arange(1, len(a) + 1)
    return c[i] - c[np.maximum(i - w, 0)]


def _rollmax(a, w):
    pad = np.concatenate([np.zeros(w - 1), a])
    return np.lib.stride_tricks.sliding_window_view(pad, w).max(axis=1)


def extras(T, P, C, S):
    """Per-second largest taker-YES / taker-NO print, print count, and which seconds had each (build_ml.extras)."""
    sec = np.floor(900.0 + T).astype(np.int64)
    ok = (sec >= 0) & (sec < 900)
    sec = sec[ok]; C = C[ok].astype(np.float64); S = S[ok]
    my = np.zeros(900); mn = np.zeros(900); cnt = np.zeros(900)
    np.maximum.at(my, sec[S == 1], C[S == 1]); np.maximum.at(mn, sec[S == 0], C[S == 0]); np.add.at(cnt, sec, 1.0)
    hasy = np.zeros(900, bool); hasn = np.zeros(900, bool); hasy[sec[S == 1]] = True; hasn[sec[S == 0]] = True
    return my, mn, cnt, hasy, hasn


def since(has):
    idx = np.where(has, np.arange(900), -1)
    idx = np.maximum.accumulate(idx)
    return np.where(idx >= 0, np.arange(900) - idx, 900).astype(np.float64)


def compact_all(g, ex):
    """The 23 compact features at every second of the window (rows 0..899), NaN as 0, float32.
    Same formulas as build_ml.frame_rows (and PrintGrid.row in the app); rows before 60 s have lags that reach
    before the open and are never used."""
    my, mn, cnt, sb, sa = ex
    bid = g["bid"]; ask = g["ask"]; mid = (bid + ask) / 2
    n = 900; s = np.arange(n)
    f = {"mid": mid, "spread": ask - bid, "tte": (900 - s).astype(float)}
    for k in (5, 10, 20, 30, 60):
        lag = np.full(n, np.nan)
        lag[k:] = mid[:-k]
        f[f"dm{k}"] = mid - lag
    d1 = np.diff(mid, prepend=mid[0])
    d1 = np.where(np.isnan(d1), 0.0, d1)
    s1 = _rollsum(d1, 60); s2 = _rollsum(d1 * d1, 60)
    f["vol60"] = np.sqrt(np.maximum(s2 / 60 - (s1 / 60) ** 2, 0.0))
    f["nchg60"] = _rollsum((d1 != 0).astype(float), 60)
    for w in (5, 10, 30, 60):
        by = _rollsum(g["vy"], w); bn = _rollsum(g["vn"], w); tot = by + bn
        f[f"imb{w}"] = np.where(tot > 0, (by - bn) / np.maximum(tot, 1e-9), 0.0)
        f[f"lvol{w}"] = np.log1p(tot)
    f["lcnt30"] = np.log1p(_rollsum(cnt, 30))
    f["lmaxbuy30"] = np.log1p(_rollmax(my, 30)); f["lmaxsell30"] = np.log1p(_rollmax(mn, 30))
    f["stale_bid"] = sb; f["stale_ask"] = sa
    X = np.column_stack([f[k] for k in COMPACT]).astype(np.float32)
    return np.nan_to_num(X, nan=0.0)


def fixed_exit(g, b0, fs):
    """scalp_b.sim_b2 (resting entry, at-price fills) that also returns the second each scalp closed.
    pnl: dollars per contract, NaN for an unfilled order; a filled scalp never closed settles (caller).
    close: the second the fixed rules closed it, -1 when they never did."""
    n = len(b0); filled = fs >= 0
    pnl = np.full(n, np.nan); close = np.full(n, -1); open_ = filled.copy(); a0 = b0 + TARGET
    for h in range(1, HOLD + 1):
        if not open_.any():
            break
        b = B.at(g["bid"], fs + h); hi = B.at(g["liftmax"], fs + h)
        stop = open_ & (~np.isnan(b)) & (b <= b0 - STOP + 1e-9)
        pnl[stop] = b[stop] - b0[stop] - B.fee(b[stop]); close[stop] = fs[stop] + h; open_ &= ~stop
        t = open_ & (hi >= a0 - 1e-9)
        pnl[t] = TARGET; close[t] = fs[t] + h; open_ &= ~t
        if h == HOLD:
            to = open_ & (~np.isnan(b))
            pnl[to] = b[to] - b0[to] - B.fee(b[to]); close[to] = fs[to] + h; open_ &= ~to
    for h in range(HOLD + 1, HOLD + 31):
        if not open_.any():
            break
        b = B.at(g["bid"], fs + h); to = open_ & (~np.isnan(b))
        pnl[to] = b[to] - b0[to] - B.fee(b[to]); close[to] = fs[to] + h; open_ &= ~to
    return pnl, close


def frame(g, ex, res, entry_model):
    """One side of one window: the decision rows (entry features, fixed result) and the open-position rows."""
    F = compact_all(g, ex)
    DEC = B.DEC
    b0, fs, live = B.entry_rest(g, False)
    pnl, close = fixed_exit(g, b0, fs)
    filled = fs >= 0
    pnl = np.where(filled & np.isnan(pnl), res - b0, pnl)          # filled, never closed: it settles
    pnl = np.where(filled, pnl, 0.0)                               # an unfilled order made nothing
    pick = np.zeros(len(DEC), bool)
    if entry_model is not None and live.any():
        pick[live] = predict(entry_model, F[DEC[live]]) >= entry_model["theta_cents"]
    bid = g["bid"]; ask = g["ask"]
    rows = []
    for i in np.where(live & filled)[0]:
        last = (close[i] if close[i] >= 0 else 900) - 1           # the second before the fixed exit
        t = np.arange(fs[i] + 1, min(last, fs[i] + HOLD - 1, 899) + 1)
        if len(t) == 0:
            continue
        t = t[~np.isnan(bid[t]) & ~np.isnan(ask[t])]
        if len(t) == 0:
            continue
        now = bid[t] - b0[i] - B.fee(bid[t])
        rows.append((i, t, now))
    return dict(b0=b0, fs=fs, live=live, pnl=pnl, pick=pick, F=F, rows=rows)


def position_rows(fr):
    """(order index, second, features, label cents, sell-now cents) for every open-position row of a frame."""
    if not fr["rows"]:
        return None
    idx = np.concatenate([np.full(len(t), i) for i, t, _ in fr["rows"]])
    t = np.concatenate([t for _, t, _ in fr["rows"]])
    now = np.concatenate([nw for _, _, nw in fr["rows"]])
    b0 = fr["b0"][idx]; fs = fr["fs"][idx]
    # held, gain_bid, gain_ask, entry; the grid's bid / ask at t (PrintGrid.bid / ask in the app)
    bid = fr["bid"][t]; ask = fr["ask"][t]
    ext = np.column_stack([(t - fs).astype(float), bid - b0, ask - b0, b0]).astype(np.float32)
    X = np.hstack([fr["F"][t], ext])
    y = (fr["pnl"][idx] - now) * 100.0
    return idx, t, X, y.astype(np.float32), (now * 100.0).astype(np.float32)


_ENTRY = None


def window(args):
    """Every frame of one window, reduced to arrays: orders (posted rows) and open-position rows."""
    path, result, mi, keep_all, seed = args
    try:
        z = np.load(path)
        g0 = B.grid(z["T"], z["P"], z["C"], z["S"])
        my, mn, cnt, hasy, hasn = extras(z["T"], z["P"], z["C"], z["S"])
        sy = since(hasy); sn = since(hasn)
        r = 1.0 if result == "yes" else 0.0
        out_o = []; out_p = []
        rnd = np.random.default_rng(seed)
        for k, (g, ex, res) in enumerate(((g0, (my, mn, cnt, sn, sy), r), (B.mirror(g0), (mn, my, cnt, sy, sn), 1.0 - r))):
            fr = frame(g, ex, res, _ENTRY)
            fr["bid"] = g["bid"]; fr["ask"] = g["ask"]
            live = fr["live"]
            oid = np.where(live)[0]
            # orders: (window, frame, decision index, picked, filled, fixed result cents)
            out_o.append(np.column_stack([np.full(len(oid), mi), np.full(len(oid), k), oid, fr["pick"][oid],
                                          fr["fs"][oid] >= 0, fr["pnl"][oid] * 100.0]))
            pr = position_rows(fr)
            if pr is None:
                continue
            idx, t, X, y, now = pr
            sel = fr["pick"][idx] if keep_all else (rnd.random(len(idx)) < TRAIN_KEEP)
            if not sel.any():
                continue
            out_p.append((np.full(sel.sum(), mi), np.full(sel.sum(), k), idx[sel], t[sel], X[sel], y[sel], now[sel]))
        O = np.vstack(out_o) if out_o else np.zeros((0, 6))
        return mi, O, out_p, None
    except Exception as e:  # one bad file must not stop the run
        return mi, None, None, str(e)[:200]


def _init(model_path):
    global _ENTRY
    _ENTRY = json.load(open(model_path)) if model_path else None


def policy(orders, pos, pred, theta):
    """Cents per order posted when the smart exit sells at the first open second with pred < theta."""
    key = lambda w, f, i: (int(w) << 20) | (int(f) << 19) | int(i)
    res = {key(w, f, i): c for w, f, i, c in zip(orders["w"], orders["f"], orders["i"], orders["c"])}
    sell = pos["now"].astype(np.float64)
    hit = pred < theta
    if hit.any():
        ks = (pos["w"][hit].astype(np.int64) << 20) | (pos["f"][hit].astype(np.int64) << 19) | pos["i"][hit].astype(np.int64)
        ts = pos["t"][hit]; vs = sell[hit]
        o = np.lexsort((ts, ks))                       # by order, then by second
        ks = ks[o]; vs = vs[o]
        first = np.ones(len(ks), bool); first[1:] = ks[1:] != ks[:-1]
        for k, v in zip(ks[first], vs[first]):
            res[int(k)] = float(v)
    return np.array([res[key(w, f, i)] for w, f, i in zip(orders["w"], orders["f"], orders["i"])])


def main():
    tape = sys.argv[1]; shipped = sys.argv[2]; nproc = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    import lightgbm as lgb
    from multiprocessing import Pool
    mk = [m for m in json.load(open(os.path.join(tape, "markets.json")))
          if os.path.exists(os.path.join(tape, "raw", m["ticker"] + ".npz"))]
    mk.sort(key=lambda m: m["open"])
    mday = np.array([dt.datetime.fromtimestamp(m["open"], dt.timezone.utc).strftime("%Y-%m-%d") for m in mk])
    TR, VA, TE = split(sorted(set(mday)))
    jobs = [(os.path.join(tape, "raw", m["ticker"] + ".npz"), m["result"], i, mday[i] not in TR, i) for i, m in enumerate(mk)]
    O = []; P = []; errs = 0
    with Pool(nproc, initializer=_init, initargs=(shipped,)) as pool:
        for k, (mi, o, p, err) in enumerate(pool.imap(window, jobs, chunksize=4)):
            if err is not None:
                errs += 1; print("ERR", mi, err, flush=True); continue
            O.append(o); P.extend(p)
            if (k + 1) % 200 == 0:
                print(k + 1, flush=True)
    O = np.vstack(O)
    orders = dict(w=O[:, 0].astype(np.int64), f=O[:, 1].astype(np.int64), i=O[:, 2].astype(np.int64),
                  pick=O[:, 3] > 0, filled=O[:, 4] > 0, c=O[:, 5])
    pos = dict(w=np.concatenate([p[0] for p in P]).astype(np.int64), f=np.concatenate([p[1] for p in P]).astype(np.int64),
               i=np.concatenate([p[2] for p in P]).astype(np.int64), t=np.concatenate([p[3] for p in P]),
               X=np.vstack([p[4] for p in P]), y=np.concatenate([p[5] for p in P]), now=np.concatenate([p[6] for p in P]))
    oday = mday[orders["w"]]; pday = mday[pos["w"]]
    print("windows", len(mk), "errors", errs, "orders", len(O), "position rows", len(pos["y"]), flush=True)
    a = np.isin(pday, list(TR)); b = np.isin(pday, list(VA)); c = np.isin(pday, list(TE))
    prm = dict(objective="regression", learning_rate=0.05, num_leaves=31, min_data_in_leaf=2000, feature_fraction=0.8,
               bagging_fraction=0.7, bagging_freq=1, lambda_l2=10.0, verbose=-1, seed=7, num_threads=nproc, use_missing=False)
    dtr = lgb.Dataset(pos["X"][a], pos["y"][a], feature_name=FEATS)
    dva = lgb.Dataset(pos["X"][b], pos["y"][b], reference=dtr)
    m = lgb.train(prm, dtr, num_boost_round=1500, valid_sets=[dva], callbacks=[lgb.early_stopping(50, verbose=False)])
    model = export(m)
    pv = predict(model, pos["X"][b]); pt = predict(model, pos["X"][c])

    def picked(days):
        s = orders["pick"] & np.isin(oday, list(days))
        return {k: v[s] for k, v in orders.items()}, oday[s]

    ov, dv = picked(VA)
    posv = {k: v[b] for k, v in pos.items()}
    base_v = ov["c"].mean()
    print(f"VALID fixed exits: {base_v:+.3f}c per order ({len(ov['c'])} orders)")
    best = (base_v, None)
    for th in THETAS:
        v = policy(ov, posv, pv, th).mean()
        print(f"  VALID smart exit, sell below {th:+.2f}c: {v:+.3f}c")
        if v > best[0] + 1e-9:
            best = (v, th)
    theta = best[1]
    ot, dtt = picked(TE)
    post = {k: v[c] for k, v in pos.items()}
    fixed = ot["c"]
    smart = policy(ot, post, pt, theta) if theta is not None else fixed.copy()
    early = int((smart != fixed).sum())
    fx = day_ci(fixed, dtt); sm = day_ci(smart, dtt); df = day_ci(smart - fixed, dtt)
    reasons = []
    if theta is None:
        reasons.append("no cut beat the fixed exits on the validation days")
    if len(fixed) < MIN_ORDERS:
        reasons.append(f"only {len(fixed)} test orders (needs {MIN_ORDERS})")
    if df and not (df["mean"] > 0 and df["lo"] > 0):
        reasons.append("selling early did not beat the fixed exits by more than chance on the test days")
    if sm and not (sm["mean"] > 0 and sm["lo"] > 0):
        reasons.append("the smart exit's orders did not make money by more than chance on the test days")
    promote = not reasons
    model.update(theta_cents=float(theta) if theta is not None else -1e9, trained=f"{min(TR)}..{max(TR)}",
                 valid=f"{min(VA)}..{max(VA)}", test=f"{min(TE)}..{max(TE)}",
                 test_fixed_cents=round(float(fixed.mean()), 4), test_smart_cents=round(float(smart.mean()), 4))
    json.dump(model, open("scalper_exit_model.json", "w"), separators=(",", ":"))
    fmt = lambda r: f"{r['mean']:+.3f}¢ | [{r['lo']:+.2f}, {r['hi']:+.2f}] | {r['up_days']}/{r['days']}"
    md = ["## Smart exit", "",
          f"Test days (fitted and tuned on neither model): {min(TE)} … {max(TE)}. Exit model trained {min(TR)}..{max(TR)}, "
          f"cut tuned on {min(VA)}..{max(VA)}. Orders: the shipped ML scalper's picks, front of the queue, cents per order posted.", "",
          f"Sells when the model expects holding to make less than {theta:+.2f}¢ more than selling now." if theta is not None else
          "No cut beat the fixed exits on the validation days.", "",
          "| Exit | mean | 95% (by day) | days up |", "| --- | ---: | --- | ---: |",
          f"| fixed (+1¢ / −4¢ / 120 s) | {fmt(fx)} |", f"| smart exit | {fmt(sm)} |", f"| smart − fixed | {fmt(df)} |", "",
          f"{len(fixed):,} test orders; the smart exit sold {early:,} of them early.", "",
          f"**Promote: {'yes' if promote else 'no'}**" + ("" if promote else " — " + "; ".join(reasons) + ".")]
    open("exit_gate.md", "w").write("\n".join(md) + "\n")
    json.dump(dict(promote=promote, reasons=reasons, theta=theta, test=sorted(TE), orders=int(len(fixed)), early=early,
                   fixed=fx, smart=sm, diff=df), open("exit_gate.json", "w"), indent=1)
    print("\n".join(md))
    imp = sorted(zip(m.feature_importance("gain"), FEATS), reverse=True)
    print("trees", len(model["trees"]), "gain:", ", ".join(f for _, f in imp[:8]))
    parity(tape, mk, mday, TE, model)


def export(m):
    dump = m.dump_model(num_iteration=m.best_iteration)

    def flat(node, out):
        i = len(out); out.append(None)
        if "leaf_value" in node and "split_feature" not in node:
            out[i] = dict(leaf=node["leaf_value"])
        else:
            assert node["decision_type"] == "<=", node["decision_type"]
            le = flat(node["left_child"], out); ri = flat(node["right_child"], out)
            out[i] = dict(f=node["split_feature"], t=node["threshold"], l=le, r=ri)
        return i

    trees = []
    for t in dump["tree_info"]:
        nodes = []; flat(t["tree_structure"], nodes)
        trees.append(dict(f=[n.get("f", -1) for n in nodes], t=[n.get("t", 0.0) for n in nodes], l=[n.get("l", -1) for n in nodes],
                          r=[n.get("r", -1) for n in nodes], v=[n.get("leaf", 0.0) for n in nodes]))
    return dict(version=1, name="scalper_smart_exit", features=FEATS,
                target="cents per contract: keep the fixed exits (+1c offer, 4c stop, 120 s) minus sell at the bid now (taker fee)",
                trees=trees)


def parity(tape, mk, mday, TE, model):
    """One test window's trades, with open-position rows (features and model output) the app must reproduce."""
    cands = [m for i, m in enumerate(mk) if mday[i] in TE]
    cands = sorted(cands, key=lambda m: os.path.getsize(os.path.join(tape, "raw", m["ticker"] + ".npz")))
    m = cands[len(cands) // 10]
    z = np.load(os.path.join(tape, "raw", m["ticker"] + ".npz"))
    el = 900.0 + z["T"]; keep = (el >= 0) & (el < 900)
    g0 = B.grid(z["T"], z["P"], z["C"], z["S"]); my, mn, cnt, hasy, hasn = extras(z["T"], z["P"], z["C"], z["S"])
    sy = since(hasy); sn = since(hasn)
    rows = []
    for side, g, ex in (("YES", g0, (my, mn, cnt, sn, sy)), ("NO", B.mirror(g0), (mn, my, cnt, sy, sn))):
        fr = frame(g, ex, 1.0, None); fr["bid"] = g["bid"]; fr["ask"] = g["ask"]
        pr = position_rows(fr)
        if pr is None:
            continue
        idx, t, X, _, _ = pr
        pick = np.linspace(0, len(t) - 1, min(len(t), 150)).astype(int)
        p = predict(model, X[pick])
        for j, k in enumerate(pick):
            rows.append(dict(side=side, sec=int(t[k]), entry=float(fr["b0"][idx[k]]), held=int(t[k] - fr["fs"][idx[k]]),
                             x=[float(v) for v in X[k]], pred=float(p[j])))
    fx = dict(ticker=m["ticker"], features=FEATS, rows=rows,
              trades=[[int(np.floor(e * 1000)), float(pp), float(cc), int(ss)] for e, pp, cc, ss in
                      zip(el[keep], np.round(z["P"].astype(np.float64), 4)[keep], z["C"].astype(np.float64)[keep], z["S"][keep].astype(int))])
    json.dump(fx, open("scalper_exit_parity.json", "w"), separators=(",", ":"))
    print("exit parity fixture:", m["ticker"], len(fx["trades"]), "trades", len(rows), "rows")


if __name__ == "__main__":
    main()
