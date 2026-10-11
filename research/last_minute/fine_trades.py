"""Second-resolution study on the trade-complete era (2026-06-29 .. 2026-07-26; 28 contiguous days, 3 coins).
Spot proxy: Binance 1-second klines (BTC/ETH/SOL-USDT). Model features are returns relative to the proxy's own 60 s
average before the window open, so the USDT/USD basis cancels to first order.
Kalshi prices: actual taker trades aggregated to 5 s buckets (a taker buying YES at p proves a YES ask of p existed then;
a taker buying NO at q proves a NO ask of q). Decision uses spot up to the START of the bucket only (no lookahead).
Outputs: results/fine_taker.json, results/fine_maker.json, printed tables.
Usage: python fine_trades.py"""
import sqlite3, zipfile, io, json, math, sys, collections, os, glob
import numpy as np, pandas as pd
from scipy.stats import norm
from common import *
# Env overrides for the full-history rerun (defaults reproduce the reported trade-era study):
#   DAYS0/DAYS1 = inclusive UTC day range; SPOT = binance (1-s klines) | cf (real CF RTI 1-s values from fetch_cf_history.py);
#   TAG = suffix for output files so the original results are not overwritten.
DAYS0, DAYS1 = os.environ.get("DAYS0", "2026-06-29"), os.environ.get("DAYS1", "2026-07-26")
SPOT = os.environ.get("SPOT", "binance"); TAG = os.environ.get("TAG", "")
CF_IDX = {"BTC": "BRTI", "ETH": "ETHUSD_RTI", "SOL": "SOLUSD_RTI"}
COINS = {"KXBTC15M": "BTC", "KXETH15M": "ETH", "KXSOL15M": "SOL"}
# 2026-09-27 full-history rerun env: COINS=KXBTC15M restricts series; KW=<kalshi_work db> (e.g. data/kalshi_work_full.sqlite,
# re-extracted with the WAL tail). Event/sample generation is vectorised per market (same formulas) so ~9M BTC buckets fit in RAM.
if os.environ.get("COINS"): COINS = {k: v for k, v in COINS.items() if k in os.environ["COINS"].split(",")}
KW = os.environ.get("KW", "data/kalshi_work.sqlite")
LAG = int(os.environ.get("LAG", "0"))   # latency sensitivity only; 0 = original
def day_of(ts): return pd.Timestamp(ts, unit="s", tz="UTC").strftime("%Y-%m-%d")

def _series_to_feats(t, c):
    t0 = t.min(); n = t.max() - t0 + 1
    full = np.full(n, np.nan); full[t - t0] = c
    s = pd.Series(full).ffill().to_numpy()
    lc = np.log(s)
    lm = lc[::60]; r = np.diff(lm, prepend=lm[0])
    rvm = np.sqrt(pd.Series(r ** 2).rolling(60, min_periods=30).mean().shift(1).to_numpy())  # uses minutes strictly before
    return t0, lc, rvm

def load_cf(sym):
    """Real CF Benchmarks RTI whole-second values (settlement source), saved by fetch_cf_history.py."""
    T, V = [], []
    for d in pd.date_range(pd.Timestamp(DAYS0) - pd.Timedelta(days=1), DAYS1):
        fn = f"data/cf_rti/{CF_IDX[sym]}/{d:%Y-%m-%d}.npz"
        if not os.path.exists(fn): print("missing", fn); continue
        z = np.load(fn); T.append(z["t_sec"]); V.append(z["v"])
    return _series_to_feats(np.concatenate(T), np.concatenate(V))

def load_binance(sym):
    if SPOT == "cf": return load_cf(sym)
    parts = []
    for d in pd.date_range(pd.Timestamp(DAYS0) - pd.Timedelta(days=1), DAYS1):
        fn = f"data/binance1s/{sym}USDT-1s-{d:%Y-%m-%d}.zip"
        with zipfile.ZipFile(fn) as z:
            a = np.loadtxt(io.TextIOWrapper(z.open(z.namelist()[0])), delimiter=",", usecols=(0, 4))
        parts.append(a)
    a = np.concatenate(parts); t = (a[:, 0] // 1_000_000).astype(np.int64); c = a[:, 1]
    t0 = t.min(); n = t.max() - t0 + 1
    full = np.full(n, np.nan); full[t - t0] = c
    s = pd.Series(full).ffill().to_numpy()
    lc = np.log(s)
    # per-minute realized vol from 1-min returns over prior 60 minutes, indexed by minute
    lm = lc[::60]; r = np.diff(lm, prepend=lm[0])
    rvm = np.sqrt(pd.Series(r ** 2).rolling(60, min_periods=30).mean().shift(1).to_numpy())  # uses minutes strictly before
    return t0, lc, rvm

def fair_p(X, tau, obs_mean, sig_s, k, eta):
    """P(final 60s average >= open 60s average). X: log spot now vs open-avg; tau: s to close; obs_mean: mean log level
    of already-observed seconds of the final minute (used when tau<60); sig_s: per-second vol."""
    X = np.asarray(X, float); tau = np.asarray(tau, float); sig = k * np.asarray(sig_s, float)
    late = tau < 60
    nobs = np.where(late, 60 - tau, 0.0)
    mean = np.where(late, (nobs * obs_mean + tau * X) / 60.0, X)
    var = np.where(late, (tau / 60.0) ** 2 * sig ** 2 * np.maximum(tau, 1) / 3.0, sig ** 2 * (tau - 40.0))
    return norm.cdf(mean / np.sqrt(var + eta ** 2))

def main():
    ta = sqlite3.connect("data/trades_agg.sqlite"); kw = sqlite3.connect(KW)
    ser = tuple(COINS)
    qm = ",".join("?" * len(ser))
    mk = pd.read_sql_query(f"select id, ticker, series, open_ts, close_ts from done_markets where series in ({qm})", ta, params=ser)
    meta = pd.read_sql_query("select id, floor_strike, expiration_value, result from markets", kw)
    mk = mk.merge(meta, on="id")
    mk["day"] = [day_of(c - 1) for c in mk.close_ts]
    mk = mk[(mk.day >= DAYS0) & (mk.day <= DAYS1) & (mk.close_ts - mk.open_ts == 900) & mk.result.isin(["yes", "no"])]
    have = set(r[0] for r in ta.execute("select distinct market_id from tagg"))
    print("markets in era", len(mk), "with trade aggregates", mk.id.isin(have).sum(), flush=True)
    mk = mk[mk.id.isin(have)]
    ids = set(mk.id)
    tg = pd.read_sql_query(f"select market_id, b5, taker_side, contracts, min_yes, max_yes from tagg where market_id in "
                           f"(select id from done_markets where series in ({qm}))", ta, params=ser)
    tg = tg[tg.market_id.isin(ids)]
    cand = pd.read_sql_query(f"select c.market_id, c.end_ts, c.yes_bid_close bid, c.yes_ask_close ask from candles c join markets m on m.id=c.market_id "
                             f"where m.series in ({qm})", kw, params=ser)
    cand = cand[cand.market_id.isin(ids)]
    print("tagg rows", len(tg), "candle rows", len(cand), flush=True)
    EV = collections.defaultdict(list); SM = collections.defaultdict(list); makers = []
    proxy_check = collections.Counter(); skipped = collections.Counter()
    for series, sym in COINS.items():
        t0, lc, rvm = load_binance(sym)
        M = mk[mk.series == series]
        tgs = dict(tuple(tg[tg.market_id.isin(set(M.id))].groupby("market_id")))
        cds = dict(tuple(cand[cand.market_id.isin(set(M.id))].groupby("market_id")))
        for m in M.itertuples():
            O, C = m.open_ts, m.close_ts
            if O - 60 - t0 < 0 or C - t0 >= len(lc): skipped["no spot range"] += 1; continue
            a_open = lc[O - 60 - t0: O - t0].mean()
            a_close = lc[C - 60 - t0: C - t0].mean()
            y = 1 if m.result == "yes" else 0
            proxy_check[(series, int((a_close >= a_open) == bool(y)))] += 1
            cum = np.cumsum(lc[C - 60 - t0: C - t0] - a_open)  # for observed-mean in final minute
            def vfeats(T):
                T = np.asarray(T, np.int64) - LAG   # LAG>0 (latency test): features as seen LAG seconds before the print bucket; tau = model's time to close
                X = lc[T - 1 - t0] - a_open
                sig_s = rvm[(T - t0) // 60] / math.sqrt(60)
                tau = C - T
                late = (tau > 0) & (tau < 60)
                om = np.where(late, cum[np.clip(60 - tau - 1, 0, 59)] / np.where(late, 60 - tau, 1), 0.0)
                return X, tau, om, sig_s
            def feats(T):
                X, tau, om, s = vfeats([T]); return X[0], int(tau[0]), om[0], s[0]
            # model-fit samples every 15 s
            Ts = np.arange(O + 15, C, 15)
            X, tau, om, s = vfeats(Ts); ok = ~np.isnan(s)
            if ok.any():
                SM["coin"].append(np.full(ok.sum(), series, object)); SM["day"].append(np.full(ok.sum(), m.day, object))
                SM["X"].append(X[ok]); SM["tau"].append(tau[ok]); SM["om"].append(om[ok]); SM["sig"].append(s[ok]); SM["y"].append(np.full(ok.sum(), y, np.int8))
            # taker events from trades
            g = tgs.get(m.id)
            if g is not None:
                T = g.b5.to_numpy(np.int64) * 5
                inw = (T >= O) & (T < C)
                if inw.any():
                    gs = g[inw]; T = T[inw]
                    X, tau, om, s = vfeats(T); ok = ~np.isnan(s)
                    side = gs.taker_side.to_numpy()
                    price = np.where(side == 1, gs.min_yes.to_numpy(), 1 - gs.max_yes.to_numpy())
                    n = int(ok.sum())
                    EV["coin"].append(np.full(n, series, object)); EV["mid"].append(np.full(n, m.id, np.int64)); EV["day"].append(np.full(n, m.day, object))
                    EV["since_open"].append((T - O)[ok]); EV["tau"].append(tau[ok]); EV["X"].append(X[ok]); EV["om"].append(om[ok]); EV["sig"].append(s[ok])
                    EV["side"].append(side[ok].astype(np.int8)); EV["price"].append(price[ok]); EV["y"].append(np.full(n, y, np.int8))
            # maker placements at minute marks
            cd = cds.get(m.id)
            for tau0 in (840, 600, 300, 120):
                T0 = C - tau0
                X, tau, om, s = feats(T0)
                if s != s: continue
                ask = bid = None
                if cd is not None:
                    c2 = cd[cd.end_ts <= T0]
                    if len(c2): ask, bid = float(c2.ask.iloc[-1]), float(c2.bid.iloc[-1])
                if ask is None: continue
                # future through-prices
                minyes_no = maxyes_yes = None; minyes_no_eq = None
                if g is not None:
                    fut = g[(g.b5 * 5 >= T0) & (g.b5 * 5 < C)]
                    f0 = fut[fut.taker_side == 0]; f1 = fut[fut.taker_side == 1]
                    minyes_no = float(f0.min_yes.min()) if len(f0) else None
                    maxyes_yes = float(f1.max_yes.max()) if len(f1) else None
                makers.append((series, m.id, m.day, tau0, X, om, s, bid, ask, minyes_no, maxyes_yes, y))
        del lc, rvm
    cat = lambda D, k: np.concatenate(D[k]) if D[k] else np.array([])
    ev = pd.DataFrame({k: cat(EV, k) for k in ["coin", "mid", "day", "since_open", "tau", "X", "om", "sig", "side", "price", "y"]})
    sm = pd.DataFrame({k: cat(SM, k) for k in ["coin", "day", "X", "tau", "om", "sig", "y"]})
    for D in (ev, sm):
        D["coin"] = D["coin"].astype("category"); D["day"] = D["day"].astype(str)
    mkr = pd.DataFrame(makers, columns=["coin", "mid", "day", "tau0", "X", "om", "sig", "bid", "ask", "minyes_no", "maxyes_yes", "y"])
    ev.to_pickle(f"data/fine_events{TAG}.pkl"); sm.to_pickle(f"data/fine_samples{TAG}.pkl"); mkr.to_pickle(f"data/fine_makers{TAG}.pkl")
    print(f"proxy sign agreement ({SPOT} 60s avgs vs Kalshi result):", {k: v for k, v in sorted(proxy_check.items())}, "skipped:", dict(skipped))
    print("markets used", len(set(ev.mid)), "events", len(ev), "samples", len(sm), "maker placements", len(mkr), "days", ev.day.min(), ev.day.max())

if __name__ == "__main__":
    main()
