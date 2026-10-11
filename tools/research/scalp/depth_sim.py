"""Part J simulator (DESIGN.md): a resting bid placed below the best bid, followed through the recorded
15 levels a side. Builds on rec_sim.py (same fee, same fill rule, same exits)."""
import numpy as np
import rec_sim as R
NAN = np.nan; EPS = R.EPS; SIZE = R.SIZE; L = 15
LAST_REST = 870          # an order never rests into the last 30 s of the window


def dense(P, Q, n, maxage=5):
    """Size shown at every 0.1c price for each second: 0 where the book shows nothing, NaN where it cannot
    tell (no fresh snapshot, or deeper than the 15 recorded levels). Also the best price per second."""
    out = np.full((900, 1001), NAN, np.float32); best = np.full(900, NAN); last = -10**6
    for s in range(900):
        if n[s] >= 0: last = s
        if s - last > maxage: continue
        k = int(n[last]); row = out[s]; row[:] = 0.0
        if k > 0:
            pi = np.clip(np.rint(P[last, :k].astype(np.float64) * 1000).astype(np.int64), 0, 1000)
            row[pi] = Q[last, :k]; best[s] = round(float(P[last, 0]), 4)
            if k >= L: row[:pi.min()] = NAN        # more levels exist below the last one recorded
    return out, best


class DFrame: pass


def frames(z, result):
    """(UP frame, DOWN frame). `shown_bid[s, i]` = size resting at price i/1000 among this side's bids;
    `shown_off[s, i]` = size already offered at price i/1000 (the other side's bids at 1 - price)."""
    dy, by = dense(z["YP"], z["YQ"], z["YN"]); dn, bn = dense(z["NP"], z["NQ"], z["NN"])
    fake = dict(T=z["T"], P=z["P"], C=z["C"], S=z["S"], yb=by, ybq=np.full(900, NAN), ya=np.round(1.0 - bn, 4), yaq=np.full(900, NAN))
    fy, fn = R.frames(fake, result); out = []
    for fr, own, opp in ((fy, dy, dn), (fn, dn, dy)):
        d = DFrame()
        for a in ("bid", "ask", "VS", "VB", "hitlow", "liftmax", "res", "sign"): setattr(d, a, getattr(fr, a))
        d.shown_bid = own; d.shown_off = opp[:, ::-1]
        out.append(d)
    return out


def patient(fr, D, k, N):
    """Bid 10 contracts k cents below the best bid at the end of second D, rest up to N s.
    Returns dict: live, price, q0 (shown when placed), fs (fill second, -1), ahead (contracts still ahead at
    the start of the fill second, or at expiry), through (filled by a sale below the price)."""
    n = len(D); b = fr.bid[D]; a = fr.ask[D]
    with np.errstate(invalid="ignore"):
        live = (~np.isnan(b)) & (~np.isnan(a)) & (b < a - EPS) & (b <= 0.90 + EPS) & (b - k * 0.01 >= 0.10 - EPS)
    p = np.where(live, np.round(b - k * 0.01, 4), 0.5); pi = np.clip(np.rint(p * 1000).astype(np.int64), 0, 1000)
    q0 = fr.shown_bid[D, pi].astype(np.float64); live &= ~np.isnan(q0)
    need = np.where(live, q0, 0.0) + SIZE
    fs = np.full(n, -1, np.int64); through = np.zeros(n, bool); ahead = np.maximum(need - SIZE, 0.0); open_ = live.copy()
    for w in range(1, N + 1):
        kk = D + w; ok = open_ & (kk <= LAST_REST)
        if not ok.any(): break
        kc = np.clip(kk, 0, 899)
        ahead = np.where(ok, np.maximum(need - SIZE, 0.0), ahead)          # ahead of us at the start of this second
        thr = fr.hitlow[kc] < p - EPS
        need = np.where(ok, need - fr.VS[kc, pi], need)
        hit = ok & (thr | (need <= EPS))
        fs[hit] = kk[hit]; through[hit] = thr[hit]; open_ &= ~hit
        sh = fr.shown_bid[kc, pi].astype(np.float64); upd = open_ & ok & ~np.isnan(sh)
        need = np.where(upd, np.minimum(need, sh + SIZE), need)
    ahead = np.where(open_, np.maximum(need - SIZE, 0.0), ahead)
    return dict(live=live, price=np.where(live, p, NAN), q0=np.where(live, q0, NAN), fs=np.where(live, fs, -1), ahead=np.where(live, ahead, NAN), through=through & live)


def exit_scalp(fr, p, fs, X=0.01, S=0.04, H=120):
    """After a fill at p in second fs: offer at p+X (queue from the recorded levels), stop S below, time-out H."""
    n = len(p); pnl = np.full(n, NAN); kind = np.zeros(n, np.int64); open_ = fs >= 0
    if not open_.any(): return pnl, kind
    p = np.where(open_, p, 0.5); a0 = np.round(p + X, 4); ai = np.clip(np.rint(a0 * 1000).astype(np.int64), 0, 1000)
    sh0 = fr.shown_off[np.clip(fs, 0, 899), ai].astype(np.float64)
    need = np.where(np.isnan(sh0), R.UNKNOWN, sh0) + SIZE
    for h in range(1, H + 31):
        k = fs + h; inw = open_ & (k < 900)
        if not inw.any(): break
        kc = np.clip(k, 0, 899); b = fr.bid[kc]; hasb = ~np.isnan(b)
        if h <= H:
            with np.errstate(invalid="ignore"):
                stop = inw & hasb & (b <= p - S + EPS)
            pnl[stop] = b[stop] - p[stop] - R.fee(b[stop]); kind[stop] = R.T_STOP; open_ &= ~stop; inw &= ~stop
            need = np.where(inw, need - fr.VB[kc, ai], need)
            t = inw & ((fr.liftmax[kc] > a0 + EPS) | (need <= EPS))
            pnl[t] = X; kind[t] = R.T_TARGET; open_ &= ~t; inw &= ~t
            sh = fr.shown_off[kc, ai].astype(np.float64); upd = inw & ~np.isnan(sh)
            need = np.where(upd, np.minimum(need, sh + SIZE), need)
        if h >= H:
            to = inw & hasb
            pnl[to] = b[to] - p[to] - R.fee(b[to]); kind[to] = R.T_TIMEOUT; open_ &= ~to
    pnl[open_] = fr.res - p[open_]; kind[open_] = R.T_SETTLED
    return pnl, kind
