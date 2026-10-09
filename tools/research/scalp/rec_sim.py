"""Real-order-book scalp simulator (DESIGN.md, "Real order books").

A window file from rec_build.py becomes two frames (the UP side and the DOWN side). Everything in a
frame is in that side's own prices: `bid` is the best bid to buy that side, a "sell" print is a taker
selling that side.
"""
import math
import numpy as np
NAN = np.nan
EPS = 1e-9
SIZE = 10
UNKNOWN = 3500.0
MODES = ("front", "back", "volume", "book")
T_STOP, T_TARGET, T_TIMEOUT, T_SETTLED = 1, 2, 3, 4


def fee(p, size=SIZE):
    """Taker fee per contract, rounded up to the cent on the whole order as Kalshi does."""
    p = np.asarray(p, dtype=np.float64)
    return np.ceil(np.round(0.07 * size * p * (1.0 - p) * 100.0, 6)) / 100.0 / size


def tick(p):
    p = np.asarray(p, dtype=np.float64)
    return np.where((p >= 0.10 - EPS) & (p < 0.90 - EPS), 0.01, 0.001)


class Frame:
    __slots__ = ("bid", "bidq", "ask", "askq", "VS", "VB", "hitlow", "liftmax", "res", "sign")


def frames(z, result):
    """(UP frame, DOWN frame) from a window npz. `result` is "yes" / "no"."""
    T = z["T"]; P = np.round(z["P"].astype(np.float64), 4); C = z["C"].astype(np.float64); S = z["S"]
    sec = np.floor(900.0 + T).astype(np.int64); ok = (sec >= 0) & (sec < 900)
    sec, P, C, S = sec[ok], P[ok], C[ok], S[ok]
    out = []
    for yes in (True, False):
        f = Frame()
        px = P if yes else np.round(1.0 - P, 4)
        sell = (S == 0) if yes else (S == 1)          # a taker selling this side
        pi = np.clip(np.rint(px * 1000).astype(np.int64), 0, 1000)
        f.VS = np.zeros((900, 1001), dtype=np.float32); np.add.at(f.VS, (sec[sell], pi[sell]), C[sell])
        f.VB = np.zeros((900, 1001), dtype=np.float32); np.add.at(f.VB, (sec[~sell], pi[~sell]), C[~sell])
        f.hitlow = np.full(900, np.inf); np.minimum.at(f.hitlow, sec[sell], px[sell])
        f.liftmax = np.full(900, -np.inf); np.maximum.at(f.liftmax, sec[~sell], px[~sell])
        if yes:
            f.bid, f.bidq, f.ask, f.askq = z["yb"], z["ybq"], z["ya"], z["yaq"]
            f.res = 1.0 if result == "yes" else 0.0; f.sign = 1.0
        else:
            f.bid, f.bidq, f.ask, f.askq = np.round(1.0 - z["ya"], 4), z["yaq"], np.round(1.0 - z["yb"], 4), z["ybq"]
            f.res = 0.0 if result == "yes" else 1.0; f.sign = -1.0
        out.append(f)
    return out


def entry(fr, D, p, q0, wait, mode):
    """Rest a bid at p (queue q0 ahead) from the end of second D. Returns the fill second, -1 if none."""
    n = len(D); fs = np.full(n, -1, dtype=np.int64)
    open_ = ~np.isnan(p); pi = np.clip(np.rint(np.nan_to_num(p) * 1000).astype(np.int64), 0, 1000)
    # contracts that must still trade at our price before our whole order is filled
    need = np.where(np.isnan(q0), UNKNOWN, q0).astype(np.float64) + SIZE
    for w in range(1, wait + 1):
        k = D + w; ok = open_ & (k < 900)
        if not ok.any(): break
        kk = np.clip(k, 0, 899); lo = fr.hitlow[kk]; through = lo < p - EPS
        if mode == "back":
            hit = through
        elif mode == "front":
            hit = lo <= p + EPS
        else:
            need = need - fr.VS[kk, pi]
            hit = through | (need <= EPS)
            if mode == "book":
                b = fr.bid[kk]; bq = fr.bidq[kk]
                with np.errstate(invalid="ignore"):
                    same = (np.abs(b - p) < 5e-4) & ~np.isnan(bq)
                    need = np.where(same, np.minimum(need, bq + SIZE), need)
                    need = np.where(b < p - 5e-4, np.minimum(need, SIZE), need)
        hit &= ok; fs[hit] = k[hit]; open_ &= ~hit
    return fs


def exit_(fr, p, fs, X, S, H, mode, entry_fee=None):
    """A position bought at p, held from second fs: offer at p+X, stop S below, time-out H.
    Returns (pnl per contract, exit kind); NaN / 0 where fs < 0."""
    n = len(p); pnl = np.full(n, NAN); kind = np.zeros(n, dtype=np.int64)
    open_ = fs >= 0
    if not open_.any(): return pnl, kind
    p = np.where(open_, p, 0.5)
    ef = np.zeros(n) if entry_fee is None else entry_fee
    a0 = np.round(p + X, 4); ai = np.clip(np.rint(a0 * 1000).astype(np.int64), 0, 1000)
    f0 = np.clip(fs, 0, 899); a = fr.ask[f0]; aq = fr.askq[f0]
    with np.errstate(invalid="ignore"):
        qa = np.where(np.abs(a - a0) < 5e-4, aq, np.where(a > a0 + 5e-4, 0.0, UNKNOWN))
    need = np.where(np.isnan(qa), UNKNOWN, qa) + SIZE
    for h in range(1, H + 31):
        k = fs + h; inw = open_ & (k < 900)
        if not inw.any(): break
        kk = np.clip(k, 0, 899); b = fr.bid[kk]; hasb = ~np.isnan(b)
        if h <= H:
            with np.errstate(invalid="ignore"):
                stop = inw & hasb & (b <= p - S + EPS)
            pnl[stop] = b[stop] - p[stop] - fee(b[stop]) - ef[stop]; kind[stop] = T_STOP
            open_ &= ~stop; inw &= ~stop
            hi = fr.liftmax[kk]
            if mode == "back":
                t = hi > a0 + EPS
            elif mode == "front":
                t = hi >= a0 - EPS
            else:
                need = need - fr.VB[kk, ai]
                t = (hi > a0 + EPS) | (need <= EPS)
                if mode == "book":
                    ak = fr.ask[kk]; akq = fr.askq[kk]
                    with np.errstate(invalid="ignore"):
                        same = (np.abs(ak - a0) < 5e-4) & ~np.isnan(akq)
                        need = np.where(same, np.minimum(need, akq + SIZE), need)
                        need = np.where(ak > a0 + 5e-4, np.minimum(need, SIZE), need)
            t &= inw
            pnl[t] = X - ef[t]; kind[t] = T_TARGET; open_ &= ~t; inw &= ~t
        if h >= H:
            to = inw & hasb
            pnl[to] = b[to] - p[to] - fee(b[to]) - ef[to]; kind[to] = T_TIMEOUT; open_ &= ~to
    pnl[open_] = fr.res - p[open_] - ef[open_]; kind[open_] = T_SETTLED
    return pnl, kind


def live(fr, D):
    """Rows where the book can be joined: fresh snapshot, bid < ask, bid 10c..90c."""
    b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        return (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (b >= 0.10 - EPS) & (b <= 0.90 + EPS)


def rest_scalp(fr, D, mode, X=0.01, S=0.04, H=120, wait=20):
    """Join the book's best bid. Returns (pnl per order posted [0 if unfilled, NaN if not live], fill second, kind)."""
    lv = live(fr, D); p = np.where(lv, fr.bid[D], NAN); q0 = np.where(lv, fr.bidq[D], NAN)
    fs = entry(fr, D, p, q0, wait, mode)
    pnl, kind = exit_(fr, p, fs, X, S, H, mode)
    return np.where(lv, np.where(fs >= 0, pnl, 0.0), NAN), fs, kind


def improve_scalp(fr, D, mode, S=0.04, H=120, wait=20):
    """Post one tick above the best bid when the spread is at least two ticks: nothing is ahead."""
    lv = live(fr, D); b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        tk = tick(np.nan_to_num(b, nan=0.5)); ok = lv & (a - b >= 2 * tk - EPS)
    p = np.where(ok, np.round(b + tk, 4), NAN)
    fs = entry(fr, D, p, np.zeros(len(D)), wait, "front" if mode != "back" else "back")
    X = np.where(ok, tick(np.nan_to_num(p, nan=0.5)), 0.01)
    pnl = np.full(len(D), NAN); kind = np.zeros(len(D), dtype=np.int64)
    for x in np.unique(X[ok]) if ok.any() else []:
        m = ok & (X == x); pp, kk = exit_(fr, np.where(m, p, NAN), np.where(m, fs, -1), float(x), S, H, mode)
        pnl[m] = pp[m]; kind[m] = kk[m]
    return np.where(ok, np.where(fs >= 0, pnl, 0.0), NAN), fs, kind


def take_scalp(fr, D, mode, X, S, H):
    """Buy at the book's ask now (taker fee), then the same exit."""
    b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        lv = (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (a >= 0.10 - EPS) & (a <= 0.90 + EPS)
    p = np.where(lv, a, NAN)
    pnl, kind = exit_(fr, p, np.where(lv, D, -1), X, S, H, mode, entry_fee=np.where(lv, fee(np.nan_to_num(p, nan=0.5)), 0.0))
    return np.where(lv, pnl, NAN), kind


def take_markout(fr, D, K):
    """Buy at the book ask now, sell at the book bid K seconds later (first fresh bid from then), fee both ways."""
    b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        lv = (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (a >= 0.10 - EPS) & (a <= 0.90 + EPS)
    out = np.full(len(D), NAN); open_ = lv.copy(); p = np.where(lv, a, 0.5)
    for h in range(K, K + 31):
        k = D + h; inw = open_ & (k < 900)
        if not inw.any(): break
        x = fr.bid[np.clip(k, 0, 899)]; hit = inw & ~np.isnan(x)
        out[hit] = x[hit] - p[hit] - fee(x[hit]) - fee(p[hit]); open_ &= ~hit
    out[open_] = fr.res - p[open_] - fee(p[open_])
    return out


def take_hold(fr, D):
    """Buy at the book ask now and hold to settlement: one fee."""
    b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        lv = (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (a >= 0.10 - EPS) & (a <= 0.90 + EPS)
    p = np.where(lv, a, 0.5)
    return np.where(lv, fr.res - p - fee(p), NAN)


def _take_entry(fr, D, delay, ioc):
    """Entry price of a buy sent at the end of second D that reaches the book `delay` seconds later.
    The snapshot of second D can be up to a second older than the prints of that second, so buying at
    its ask is not possible; the order meets the book of the next snapshot. ioc=True: a limit at the
    ask that was seen, cancelled if the ask has moved above it (no fill)."""
    b = fr.bid[D]; a = fr.ask[D]; k = np.clip(D + delay, 0, 899); a1 = fr.ask[k]; b1 = fr.bid[k]
    with np.errstate(invalid="ignore"):
        lv = (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (a >= 0.10 - EPS) & (a <= 0.90 + EPS) & (D + delay < 900)
        got = lv & (~np.isnan(a1)) & (~np.isnan(b1)) & (b1 < a1 - EPS) & (a1 > 0.0) & (a1 < 1.0)
        if ioc: got &= a1 <= a + EPS
    return lv, got, np.where(got, a1, 0.5)


def take_markout_next(fr, D, K, delay=1, ioc=False):
    """Buy at the ask one snapshot after the decision, sell at the book bid K s after that. Fees both ways.
    Not live -> NaN; no fill (ioc, or no book) -> 0."""
    lv, got, p = _take_entry(fr, D, delay, ioc)
    out = np.full(len(D), NAN); open_ = got.copy(); D1 = D + delay
    for h in range(K, K + 31):
        k = D1 + h; inw = open_ & (k < 900)
        if not inw.any(): break
        x = fr.bid[np.clip(k, 0, 899)]; hit = inw & ~np.isnan(x)
        out[hit] = x[hit] - p[hit] - fee(x[hit]) - fee(p[hit]); open_ &= ~hit
    out[open_] = fr.res - p[open_] - fee(p[open_])
    return np.where(lv, np.where(got, out, 0.0), NAN)


def take_scalp_next(fr, D, mode, X, S, H, delay=1, ioc=False):
    """Buy at the ask one snapshot after the decision, then offer X higher / stop S / time-out H."""
    lv, got, p = _take_entry(fr, D, delay, ioc)
    pnl, kind = exit_(fr, np.where(got, p, NAN), np.where(got, D + delay, -1), X, S, H, mode, entry_fee=np.where(got, fee(p), 0.0))
    return np.where(lv, np.where(got, pnl, 0.0), NAN)
