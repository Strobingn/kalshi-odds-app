"""Rows for Parts G and H (DESIGN.md, "Real order books"): one per (window, second 60..780, side) where the
book can be joined. Features known at the end of that second; targets from rec_sim.py.

Run: python3 rec_rows.py <rec_build out dir> <rows.npz> [nproc]
"""
import json, os, sys, datetime as dt
import numpy as np
from scipy.special import ndtr
import scalp_b as B, rec_sim as R
NAN = np.nan
SECS = np.arange(60, 781)
PRE = 600
TAPE = ["mid", "spread", "tte", "dm5", "dm10", "dm20", "dm30", "dm60", "vol60", "nchg60", "imb5", "lvol5", "imb10", "lvol10",
        "imb30", "lvol30", "imb60", "lvol60", "lcnt30", "lmaxbuy30", "lmaxsell30", "stale_bid", "stale_ask"]
BOOK = ["b_bid", "b_spread", "b_lbq", "b_laq", "b_imb", "b_micro", "b_dm1", "b_dm2", "b_dm5", "b_bid_age", "b_ask_age",
        "b_bid_dir", "b_bq_rel", "b_minus_tape"]
SPOT = ["s_dist", "s_ret1", "s_ret2", "s_ret5", "s_ret10", "s_ret30", "s_ret60", "s_vol", "s_z", "s_fair_mid", "s_lag5", "s_lag10", "s_stale"]
FEATS = TAPE + BOOK + SPOT
TARGETS = ["book", "volume", "front", "back", "hold_book", "improve_book", "rest30_book", "take22_book", "take33_book",
           "take_m10", "take_m30", "take_hold", "take_m10_next", "take_m30_next", "take_m10_ioc", "take22_next", "take_m5_next", "take_m60_next"]


def rollsum(a, w):
    c = np.concatenate([[0.0], np.cumsum(a)]); i = np.arange(1, len(a) + 1); return c[i] - c[np.maximum(i - w, 0)]


def rollmax(a, w):
    pad = np.concatenate([np.zeros(w - 1), a]); return np.lib.stride_tricks.sliding_window_view(pad, w).max(axis=1)


def since(has):
    n = len(has); idx = np.where(has, np.arange(n), -1); idx = np.maximum.accumulate(idx)
    return np.where(idx >= 0, np.arange(n) - idx, n).astype(np.float64)


def lag(a, k):
    out = np.full(len(a), NAN); out[k:] = a[:-k]; return out


def tape_extras(z):
    T = z["T"]; C = z["C"]; S = z["S"]
    sec = np.floor(900.0 + T).astype(np.int64); ok = (sec >= 0) & (sec < 900); sec = sec[ok]; C = C[ok].astype(np.float64); S = S[ok]
    my = np.zeros(900); mn = np.zeros(900); cnt = np.zeros(900)
    np.maximum.at(my, sec[S == 1], C[S == 1]); np.maximum.at(mn, sec[S == 0], C[S == 0]); np.add.at(cnt, sec, 1.0)
    hasy = np.zeros(900, bool); hasn = np.zeros(900, bool); hasy[sec[S == 1]] = True; hasn[sec[S == 0]] = True
    first = int(sec.min()) if len(sec) else -1
    return my, mn, cnt, since(hasy), since(hasn), first


def tape_feats(g, maxbuy, maxsell, cnt, sb, sa):
    """The shipped model's 23 features on every second (same arithmetic as build_ml.py / PrintGrid.kt)."""
    bid = g["bid"]; ask = g["ask"]; mid = (bid + ask) / 2; f = {}
    f["mid"] = mid; f["spread"] = ask - bid; f["tte"] = 900.0 - np.arange(900)
    for k in (5, 10, 20, 30, 60): f[f"dm{k}"] = mid - lag(mid, k)
    d1 = np.diff(mid, prepend=mid[0]); d1 = np.where(np.isnan(d1), 0.0, d1)
    s1 = rollsum(d1, 60); s2 = rollsum(d1 * d1, 60); f["vol60"] = np.sqrt(np.maximum(s2 / 60 - (s1 / 60) ** 2, 0.0))
    f["nchg60"] = rollsum((d1 != 0).astype(float), 60)
    for w in (5, 10, 30, 60):
        by = rollsum(g["vy"], w); bn = rollsum(g["vn"], w); tot = by + bn
        f[f"imb{w}"] = np.where(tot > 0, (by - bn) / np.maximum(tot, 1e-9), 0.0); f[f"lvol{w}"] = np.log1p(tot)
    f["lcnt30"] = np.log1p(rollsum(cnt, 30)); f["lmaxbuy30"] = np.log1p(rollmax(maxbuy, 30)); f["lmaxsell30"] = np.log1p(rollmax(maxsell, 30))
    f["stale_bid"] = sb; f["stale_ask"] = sa
    return f


def age_dir(px):
    """Seconds since the price last changed (capped 60) and the sign of that change."""
    n = len(px); age = np.zeros(n); d = np.zeros(n); a = 0.0; last = 0.0; prev = NAN
    for i in range(n):
        v = px[i]
        if np.isnan(v) or np.isnan(prev): a = min(a + 1.0, 60.0)
        elif abs(v - prev) > 5e-4: a = 0.0; last = 1.0 if v > prev else -1.0
        else: a = min(a + 1.0, 60.0)
        if not np.isnan(v): prev = v
        age[i] = a; d[i] = last
    return age, d


def book_feats(fr, tape_mid):
    bid, bq, ask, aq = fr.bid, fr.bidq, fr.ask, fr.askq; mid = (bid + ask) / 2; f = {}
    f["b_bid"] = bid; f["b_spread"] = (ask - bid) / 0.01
    f["b_lbq"] = np.log1p(bq); f["b_laq"] = np.log1p(aq)
    with np.errstate(invalid="ignore", divide="ignore"):
        f["b_imb"] = (bq - aq) / (bq + aq); f["b_micro"] = (ask * bq + bid * aq) / (bq + aq) - mid
    for k in (1, 2, 5): f[f"b_dm{k}"] = mid - lag(mid, k)
    f["b_bid_age"], f["b_bid_dir"] = age_dir(bid); f["b_ask_age"], _ = age_dir(ask)
    ok = ~np.isnan(bq); num = rollsum(np.where(ok, bq, 0.0), 60); den = rollsum(ok.astype(float), 60)
    with np.errstate(invalid="ignore", divide="ignore"):
        f["b_bq_rel"] = np.log1p(bq) - np.log1p(num / den)
    f["b_minus_tape"] = mid - tape_mid
    return f, mid


def spot_feats(spot, strike, sign, bmid):
    """spot: seconds -PRE..899. Returns features on seconds 0..899."""
    n = 900; S = spot[PRE:]; ls = np.log(spot); f = {}
    f["s_dist"] = sign * (S / strike - 1.0) * 1e4
    for k in (1, 2, 5, 10, 30, 60): f[f"s_ret{k}"] = sign * (ls[PRE:] - ls[PRE - k:PRE - k + n]) * 1e4
    r = np.diff(ls, prepend=NAN); ok = ~np.isnan(r); rz = np.where(ok, r, 0.0)
    c1 = rollsum(rz, 300); c2 = rollsum(rz * rz, 300); cn = rollsum(ok.astype(float), 300)
    with np.errstate(invalid="ignore", divide="ignore"):
        var = np.maximum(c2 / cn - (c1 / cn) ** 2, 0.0)
    sig = np.where(cn >= 60, np.sqrt(var), NAN)[PRE:]
    f["s_vol"] = sig * 1e4
    tte = 900.0 - np.arange(n)
    with np.errstate(invalid="ignore", divide="ignore"):
        f["s_z"] = sign * np.log(S / strike) / (sig * np.sqrt(tte))
        sd = np.sqrt(sig * sig * (np.maximum(tte, 60.0) - 60.0 + 20.0) + (0.5e-4) ** 2)
        fy = ndtr(np.log(S / strike) / sd)
    fair = fy if sign > 0 else 1.0 - fy
    f["s_fair_mid"] = fair - bmid
    for k in (5, 10): f[f"s_lag{k}"] = (fair - lag(fair, k)) - (bmid - lag(bmid, k))
    ch = np.zeros(len(spot), bool); ch[1:] = (spot[1:] != spot[:-1]) & ~np.isnan(spot[1:])
    f["s_stale"] = np.minimum(since(ch), 30.0)[PRE:]
    return f


def one(args):
    path, w, wi = args
    try:
        z = np.load(path); g0 = B.grid(z["T"], z["P"], z["C"], z["S"]); my, mn, cnt, sy, sn, first = tape_extras(z)
        fy, fn = R.frames(z, w["result"]); out = []
        for fi, (fr, g, ex) in enumerate(((fy, g0, (my, mn, cnt, sn, sy)), (fn, B.mirror(g0), (mn, my, cnt, sy, sn)))):
            lv = R.live(fr, SECS)
            if not lv.any(): continue
            tf = tape_feats(g, *ex); bf, bmid = book_feats(fr, tf["mid"])
            sf = spot_feats(z["spot"], w["strike"], fr.sign, bmid) if w["strike"] else {k: np.full(900, NAN) for k in SPOT}
            allf = {**tf, **bf, **sf}
            X = np.column_stack([allf[k][SECS] for k in FEATS]).astype(np.float32)
            tb = g["bid"][SECS]; ta = g["ask"][SECS]
            with np.errstate(invalid="ignore"):
                tq = (~np.isnan(tb)) & (~np.isnan(ta)) & (tb < ta) & (tb >= 0.10 - 1e-9) & (tb <= 0.90 + 1e-9) & (first >= 0) & (first <= SECS - 60)
            Y = {}
            Y["book"], fs_book, kind = R.rest_scalp(fr, SECS, "book")
            for m in ("volume", "front", "back"): Y[m], _, _ = R.rest_scalp(fr, SECS, m)
            p = fr.bid[SECS]
            Y["hold_book"] = np.where(lv, np.where(fs_book >= 0, fr.res - p, 0.0), NAN)
            Y["improve_book"], _, _ = R.improve_scalp(fr, SECS, "book")
            Y["rest30_book"], _, _ = R.rest_scalp(fr, SECS, "book", H=30)
            Y["take22_book"], _ = R.take_scalp(fr, SECS, "book", 0.02, 0.02, 30)
            Y["take33_book"], _ = R.take_scalp(fr, SECS, "book", 0.03, 0.03, 60)
            Y["take_m10"] = R.take_markout(fr, SECS, 10); Y["take_m30"] = R.take_markout(fr, SECS, 30); Y["take_hold"] = R.take_hold(fr, SECS)
            Y["take_m10_next"] = R.take_markout_next(fr, SECS, 10); Y["take_m30_next"] = R.take_markout_next(fr, SECS, 30)
            Y["take_m5_next"] = R.take_markout_next(fr, SECS, 5); Y["take_m60_next"] = R.take_markout_next(fr, SECS, 60)
            Y["take_m10_ioc"] = R.take_markout_next(fr, SECS, 10, ioc=True); Y["take22_next"] = R.take_scalp_next(fr, SECS, "book", 0.02, 0.02, 30)
            Ym = np.column_stack([Y[k] for k in TARGETS]).astype(np.float32)
            meta = np.column_stack([np.full(len(SECS), wi), SECS, np.full(len(SECS), fi), fs_book, kind, tq.astype(int)])
            out.append((X[lv], Ym[lv], meta[lv], fr.bidq[SECS][lv]))
        if not out: return wi, None
        return wi, tuple(np.concatenate([o[i] for o in out]) for i in range(4))
    except Exception as e:  # noqa: BLE001
        return wi, "ERR " + repr(e)[:300]


if __name__ == "__main__":
    D, OUT = sys.argv[1], sys.argv[2]; NP = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    W = [w for w in json.load(open(os.path.join(D, "windows.json"))) if w["book_secs"] >= 600]
    jobs = [(os.path.join(D, "win", w["ticker"] + ".npz"), w, i) for i, w in enumerate(W)]
    from multiprocessing import Pool
    Xs, Ys, Ms, Qs = [], [], [], []; errs = 0
    with Pool(NP) as pool:
        for k, (wi, r) in enumerate(pool.imap(one, jobs, chunksize=4)):
            if isinstance(r, str): errs += 1; print(wi, r, flush=True); continue
            if r is None: continue
            Xs.append(r[0]); Ys.append(r[1]); Ms.append(r[2]); Qs.append(r[3])
            if (k + 1) % 100 == 0: print(k + 1, flush=True)
    days = np.array([dt.datetime.fromtimestamp(w["open"], dt.timezone.utc).strftime("%Y-%m-%d") for w in W])
    M = np.concatenate(Ms)
    np.savez_compressed(OUT, X=np.concatenate(Xs), Y=np.concatenate(Ys), win=M[:, 0], sec=M[:, 1], frame=M[:, 2], fs=M[:, 3], kind=M[:, 4],
                        tq=M[:, 5], queue=np.concatenate(Qs), wday=days, feats=np.array(FEATS), targets=np.array(TARGETS),
                        tickers=np.array([w["ticker"] for w in W]))
    print("windows", len(W), "rows", len(M), "features", len(FEATS), "errors", errs, flush=True)
