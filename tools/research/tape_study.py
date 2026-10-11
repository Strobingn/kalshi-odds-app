#!/usr/bin/env python3
"""Trade-tape study of Kalshi KXBTC15M: who makes money, takers or resting orders?

Kalshi's public GET /markets/trades returns every trade with the taker's side,
so maker/taker P&L can be measured on history today, without waiting weeks for
1-second recordings (docs/maker-research.md).

Sub-commands (all public market data, no API key):

  pull      download every trade of the last N days of settled markets
            (one compressed .npz per market under --dir)
  report    taker vs maker P&L held to settlement, by price paid, time left
            and trade size, with a market-cluster bootstrap 95% CI
  makersim  resting-order simulation: join the best bid on YES and on NO every
            30 s, cancel after 30 s, hold fills to settlement, for several
            queue positions (contracts that must trade at our price first)
  favmaker  the same resting-order simulation, split by which side the bid is
            on: favourite side (our price 50-90c, i.e. the other side of a
            cheap-side buyer) against underdog side. Days 2026-09-24..10-04
            formed the idea, so the verdict reads only the other days and uses
            a day-block bootstrap (docs/queue-maker-2026-10-08.md)
  hourly    same-settlement check against the hourly KXBTCD "above K" ladder
            for windows that close on the hour
  streaks   UP rate after UP / DOWN runs and by hour of day

Conventions:
  * taker price paid q = yes_price if the taker is long YES, else 1 - yes_price
  * taker fee = 0.07 * C * q * (1 - q)   (series fee_type "quadratic", multiplier 1)
  * maker P&L = minus the taker's gross P&L; maker fee taken as 0. VERIFY the
    maker fee on your own fills before trusting any maker number.
  * best bid/ask in makersim are proxies from the last prints (taker-NO print
    = bid, taker-YES print = ask, each at most 10 s old). Queue sizes are
    assumptions; calibrate them with the recorder's book files.

Needs numpy and pandas.
  python3 tools/research/tape_study.py pull --days 10 --dir tape
  python3 tools/research/tape_study.py report --dir tape
  python3 tools/research/tape_study.py makersim --dir tape
"""
from __future__ import annotations

import argparse
import json
import os
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

import numpy as np
import pandas as pd

API = "https://api.elections.kalshi.com/trade-api/v2"
SERIES = "KXBTC15M"
TAKER_FEE = 0.07
PRICE_EDGES = np.array([0, .05, .10, .20, .30, .40, .50, .60, .70, .80, .90, .95, 1.0001])
PRICE_LABELS = ["0-5", "5-10", "10-20", "20-30", "30-40", "40-50", "50-60", "60-70", "70-80", "80-90", "90-95", "95-100"]
TIME_EDGES = np.array([0, 60, 180, 360, 600, 900.01])
TIME_LABELS = ["<1m", "1-3m", "3-6m", "6-10m", "10-15m"]
SIZE_EDGES = np.array([0, 10, 100, 1000, 10000, 1e12])
SIZE_LABELS = ["<10", "10-100", "100-1k", "1k-10k", ">10k"]
QUEUES = [0, 500, 2000, 10000, -1]
QUEUE_LABELS = {0: "front of queue", 500: "500 ahead", 2000: "2,000 ahead", 10000: "10,000 ahead",
                -1: "only when a print trades through"}


# ---------------------------------------------------------------- http
class Http:
    def __init__(self, rate: float):
        self.gap = 1.0 / rate
        self.lock = threading.Lock()
        self.next = 0.0

    def get(self, url: str) -> dict:
        for attempt in range(8):
            with self.lock:
                at = max(time.time(), self.next)
                self.next = at + self.gap
            wait = at - time.time()
            if wait > 0:
                time.sleep(wait)
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "tape-study/1.0", "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=40) as r:
                    return json.load(r)
            except urllib.error.HTTPError as e:
                time.sleep(min(30.0, 1.5 * (attempt + 1)) if e.code == 429 else 1 + attempt)
            except Exception:
                time.sleep(1 + attempt)
        raise RuntimeError(f"giving up on {url}")


def _ts(s: str) -> float:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def settled_markets(http: Http, days: float) -> list[dict]:
    lo = int(time.time() - days * 86400)
    out, cur = [], None
    while True:
        url = f"{API}/markets?series_ticker={SERIES}&status=settled&limit=1000&min_close_ts={lo}" + (f"&cursor={cur}" if cur else "")
        d = http.get(url)
        ms = d.get("markets") or []
        for m in ms:
            if m.get("result") in ("yes", "no"):
                out.append({"ticker": m["ticker"], "open": _ts(m["open_time"]), "close": _ts(m["close_time"]),
                            "result": m["result"], "strike": m.get("floor_strike"), "exp": m.get("expiration_value")})
        cur = d.get("cursor")
        if not cur or not ms:
            return out


# ---------------------------------------------------------------- pull
def market_day(m: dict) -> str:
    return datetime.fromtimestamp(m["open"], timezone.utc).strftime("%Y-%m-%d")


def pull_subset(markets: list[dict], shard: str = "0/1", skip_days: str = "") -> list[dict]:
    """Markets this run downloads: drop UTC open days in `A:B` (inclusive), then keep shard `i/n`."""
    if skip_days:
        lo, hi = skip_days.split(":")
        markets = [m for m in markets if not (lo <= market_day(m) <= hi)]
    i, n = (int(x) for x in shard.split("/"))
    if not (0 <= i < n):
        raise ValueError(f"bad shard {shard}")
    return [m for k, m in enumerate(markets) if k % n == i]


def cmd_pull(a) -> None:
    os.makedirs(os.path.join(a.dir, "raw"), exist_ok=True)
    http = Http(a.rate)
    mpath = os.path.join(a.dir, "markets.json")
    known = {m["ticker"]: m for m in json.load(open(mpath))} if os.path.exists(mpath) else {}
    for m in settled_markets(http, a.days):
        known[m["ticker"]] = m
    markets = sorted(known.values(), key=lambda m: -m["close"])
    json.dump(markets, open(mpath, "w"))
    print(f"{len(markets)} settled markets", flush=True)
    markets = pull_subset(markets, a.shard, a.skip_days)
    print(f"{len(markets)} to pull (shard {a.shard}, skipping days {a.skip_days or 'none'})", flush=True)
    failed: list[str] = []
    done = [0]
    lock = threading.Lock()

    def pull(m: dict) -> None:
        path = os.path.join(a.dir, "raw", m["ticker"] + ".npz")
        if os.path.exists(path):
            return
        T, P, C, S, cur = [], [], [], [], None
        while True:
            d = http.get(f"{API}/markets/trades?ticker={m['ticker']}&limit=1000" + (f"&cursor={cur}" if cur else ""))
            tr = d.get("trades") or []
            for x in tr:
                T.append(_ts(x["created_time"]) - m["close"])          # seconds relative to close (negative)
                P.append(float(x["yes_price_dollars"]))
                C.append(float(x["count_fp"]))
                S.append(1 if x.get("taker_outcome_side", x.get("taker_side")) == "yes" else 0)
            cur = d.get("cursor")
            if not cur or not tr:
                break
        tmp = path + ".tmp.npz"
        np.savez_compressed(tmp, t=np.array(T, np.float32), p=np.array(P, np.float32),
                            c=np.array(C, np.float32), s=np.array(S, np.int8))
        os.replace(tmp, path)
        with lock:
            done[0] += 1
            if done[0] % 25 == 0:
                print(f"  {done[0]} markets pulled", flush=True)

    def safe(m: dict) -> None:
        try:
            pull(m)
        except Exception as e:  # one market that keeps failing must not lose the rest
            with lock:
                failed.append(m["ticker"])
            print(f"  skipped {m['ticker']}: {str(e)[:80]}", flush=True)

    with ThreadPoolExecutor(a.workers) as ex:
        list(ex.map(safe, markets))
    print(f"pull finished: {done[0]} new, {len(failed)} skipped", flush=True)


def _load(dirname: str):
    markets = {m["ticker"]: m for m in json.load(open(os.path.join(dirname, "markets.json")))}
    raw = os.path.join(dirname, "raw")
    for f in sorted(os.listdir(raw)):
        if not f.endswith(".npz") or ".tmp" in f:
            continue
        m = markets.get(f[:-4])
        if not m:
            continue
        d = np.load(os.path.join(raw, f))
        order = np.argsort(d["t"], kind="stable")
        left = -d["t"][order].astype(np.float64)                      # seconds left at the trade
        p = d["p"][order].astype(np.float64)
        c = d["c"][order].astype(np.float64)
        s = d["s"][order]
        ok = (left >= 0) & (left <= 900) & (p > 0) & (p < 1)
        yield m, left[ok], p[ok], c[ok], s[ok]


# ---------------------------------------------------------------- report
def _cells(dirname: str) -> pd.DataFrame:
    rows = []
    for m, left, p, c, s in _load(dirname):
        y = 1.0 if m["result"] == "yes" else 0.0
        q = np.where(s == 1, p, 1 - p)
        win = np.where(s == 1, y, 1 - y)
        fee = TAKER_FEE * c * q * (1 - q)
        key = (np.digitize(q, PRICE_EDGES) - 1) * 100 + (np.digitize(left, TIME_EDGES) - 1) * 10 + (np.digitize(c, SIZE_EDGES) - 1)
        day = datetime.fromtimestamp(m["open"], timezone.utc).strftime("%Y-%m-%d")
        for k in np.unique(key):
            mm = key == k
            rows.append((m["ticker"], day, int(k // 100), int(k % 100 // 10), int(k % 10), int(mm.sum()),
                         c[mm].sum(), (c[mm] * q[mm]).sum(), (c[mm] * win[mm]).sum(), fee[mm].sum()))
    return pd.DataFrame(rows, columns=["tk", "day", "pb", "tb", "sb", "n", "contracts", "cost", "payout", "fee"])


def _summary(g: pd.DataFrame, rng, iters: int) -> dict:
    pm = g.groupby("tk").agg(C=("contracts", "sum"), cost=("cost", "sum"), pay=("payout", "sum"), fee=("fee", "sum"))
    C, gross, fee = pm.C.values, (pm["pay"] - pm["cost"]).values, pm.fee.values
    idx = rng.integers(0, len(pm), size=(iters, len(pm)))
    bC = C[idx].sum(1)
    maker = -gross[idx].sum(1) / bC * 100
    taker = (gross[idx].sum(1) - fee[idx].sum(1)) / bC * 100
    tot = C.sum()
    return {"markets": len(pm), "contracts_M": tot / 1e6, "avg_price_c": pm.cost.sum() / tot * 100,
            "taker_win_%": pm["pay"].sum() / tot * 100, "fee_c": fee.sum() / tot * 100,
            "taker_net_c": (gross.sum() - fee.sum()) / tot * 100,
            "taker_95%": "[%+.2f, %+.2f]" % tuple(np.percentile(taker, [2.5, 97.5])),
            "taker_ROI_%": (gross.sum() - fee.sum()) / pm.cost.sum() * 100,
            "maker_c": -gross.sum() / tot * 100,
            "maker_95%": "[%+.2f, %+.2f]" % tuple(np.percentile(maker, [2.5, 97.5]))}


def _table(df: pd.DataFrame, col: str, labels: list[str], rng, iters: int) -> str:
    r = pd.DataFrame({labels[k]: _summary(g, rng, iters) for k, g in df.groupby(col)}).T
    return r.to_markdown(floatfmt=".2f") if _has_tabulate() else r.to_string(float_format=lambda x: f"{x:,.2f}")


def _has_tabulate() -> bool:
    try:
        import tabulate  # noqa: F401
        return True
    except ImportError:
        return False


def cmd_report(a) -> None:
    df = _cells(a.dir)
    rng = np.random.default_rng(7)
    print(f"# Trade-tape report: {SERIES}\n")
    print(f"{df.tk.nunique()} markets, {df.day.min()} to {df.day.max()}, {int(df.n.sum()):,} trades, "
          f"{df.contracts.sum() / 1e6:,.0f}M contracts, ${df.cost.sum() / 1e6:,.0f}M taker spend, "
          f"${df.fee.sum() / 1e6:,.1f}M taker fees.\n\nCents per contract, held to settlement.\n")
    print("## All trades\n")
    print(pd.Series(_summary(df, rng, a.iters)).to_string(), "\n")
    for title, col, labels in (("By price the taker paid (cents)", "pb", PRICE_LABELS),
                               ("By time left", "tb", TIME_LABELS), ("By trade size (contracts)", "sb", SIZE_LABELS)):
        print(f"## {title}\n\n{_table(df, col, labels, rng, a.iters)}\n")
    print("## Mid-priced (30-70c) by time left\n\n" + _table(df[(df.pb >= 4) & (df.pb <= 7)], "tb", TIME_LABELS, rng, a.iters) + "\n")
    print("## By day\n")
    for day, g in df.groupby("day"):
        C, gross = g.contracts.sum(), g.payout.sum() - g.cost.sum()
        print(f"- {day}: {g.tk.nunique()} markets, maker {-gross / C * 100:+.2f}c, taker net {(gross - g.fee.sum()) / C * 100:+.2f}c")


# ---------------------------------------------------------------- makersim
def _maker_frame(a) -> pd.DataFrame:
    """One row per resting order and queue position: tk, day, tau, Q, cost, pnl (NaN = no fill)."""
    rows = []
    for m, left, p, c, s in _load(a.dir):
        day = datetime.fromtimestamp(m["open"], timezone.utc).strftime("%Y-%m-%d")
        t = 900.0 - left
        o = np.argsort(t, kind="stable")
        t, p, c, s = t[o], p[o], c[o], s[o]
        y = 1.0 if m["result"] == "yes" else 0.0
        ty, py, cy = t[s == 1], p[s == 1], c[s == 1]      # taker bought YES: prints at the ask
        tn, pn, cn = t[s == 0], p[s == 0], c[s == 0]      # taker bought NO:  prints at the bid
        if len(ty) < 50 or len(tn) < 50:
            continue
        for tau in range(30, 841, 30):
            i, j = np.searchsorted(ty, tau) - 1, np.searchsorted(tn, tau) - 1
            if i < 0 or j < 0 or tau - ty[i] > 10 or tau - tn[j] > 10:
                continue
            ask, bid = py[i], pn[j]
            if not (0.05 <= bid < ask <= 0.95) or ask - bid > 0.03:
                continue
            for side, tt, pp, cc in (("YES", tn, pn, cn), ("NO", ty, py, cy)):
                lo, hi = np.searchsorted(tt, tau, side="right"), np.searchsorted(tt, tau + a.cancel, side="right")
                sp, sc = pp[lo:hi], cc[lo:hi]
                if side == "YES":       # our YES bid at `bid`
                    at, through, pnl, cost = sp <= bid + 1e-9, sp < bid - 1e-9, y - bid, bid
                else:                   # our NO bid at 1 - ask
                    at, through, pnl, cost = sp >= ask - 1e-9, sp > ask + 1e-9, ask - y, 1 - ask
                cum = np.cumsum(np.where(at, sc, 0.0))
                k_through = int(np.argmax(through)) if through.any() else None
                for Q in QUEUES:
                    reached = cum > (Q if Q >= 0 else np.inf)
                    k_queue = int(np.argmax(reached)) if reached.any() else None
                    filled = k_through is not None or k_queue is not None
                    rows.append((m["ticker"], day, tau, Q, cost, pnl if filled else np.nan))
    return pd.DataFrame(rows, columns=["tk", "day", "tau", "Q", "cost", "pnl"])


def cmd_makersim(a) -> None:
    df = _maker_frame(a)
    df["phase"] = pd.cut(df.tau, [0, 300, 600, 900], labels=["first 5 min", "minutes 5-10", "minutes 10-14"])
    rng = np.random.default_rng(3)

    def ci(g):
        pm = g.dropna(subset=["pnl"]).groupby("tk").pnl.agg(["sum", "count"])
        if len(pm) < 30:
            return "n/a"
        idx = rng.integers(0, len(pm), size=(a.iters, len(pm)))
        v = pm["sum"].values[idx].sum(1) / pm["count"].values[idx].sum(1) * 100
        return "[%+.2f, %+.2f]" % tuple(np.percentile(v, [2.5, 97.5]))

    print(f"# Resting-order simulation: {SERIES}\n\n{df.tk.nunique()} markets. Join the best bid on each side every 30 s, "
          f"cancel after {a.cancel} s, hold to settlement, maker fee 0. Cents per filled contract.\n")
    print("| Phase | Queue position | Orders | Fill % | P&L c/fill | 95% CI |\n|---|---|---:|---:|---:|---|")
    for (phase, Q), g in list(df.groupby(["phase", "Q"], observed=True)) + [(("all", Q), g) for Q, g in df.groupby("Q")]:
        print(f"| {phase} | {QUEUE_LABELS[Q]} | {len(g)} | {g.pnl.notna().mean() * 100:.1f} | {g.pnl.mean() * 100:+.2f} | {ci(g)} |")


# ---------------------------------------------------------------- favmaker
# Fixed on 2026-10-08 before the fresh days were scored.
IDEA_DAYS = ("2026-09-24", "2026-10-04")     # the tape study's days: they formed the idea
FAV_PRICE = (0.50, 0.90)                     # our bid price on the favourite side
DOG_PRICE = (0.10, 0.50)                     # control: our bid on the underdog side
EARLY_S = 300
DECISION_Q = 2000                            # a new order normally sits behind about this many


def day_block_ci(by_day: pd.DataFrame, level: float, rng, iters: int) -> tuple[float, float]:
    """CI of cents per fill with whole UTC days resampled. by_day: columns sum, count."""
    if len(by_day) < 2 or by_day["count"].sum() == 0:
        return float("nan"), float("nan")
    idx = rng.integers(0, len(by_day), size=(iters, len(by_day)))
    num, den = by_day["sum"].values[idx].sum(1), by_day["count"].values[idx].sum(1)
    v = num[den > 0] / den[den > 0] * 100
    a = (1 - level) / 2 * 100
    return tuple(np.percentile(v, [a, 100 - a]))


def fav_cell(g: pd.DataFrame, rng, iters: int) -> dict:
    """Summary of one cell (one queue position): fills, cents per fill, day-block CIs."""
    f = g.dropna(subset=["pnl"])
    out = {"orders": len(g), "fills": len(f)}
    if not len(f):
        return out
    bd = f.groupby("day").pnl.agg(["sum", "count"])
    out.update(cents=f.pnl.mean() * 100, days=len(bd), pos_days=int((bd["sum"] > 0).sum()),
               ci95=day_block_ci(bd, 0.95, rng, iters), ci99=day_block_ci(bd, 0.99, rng, iters))
    return out


def _ci_text(ci) -> str:
    return "n/a" if any(np.isnan(x) for x in ci) else f"[{ci[0]:+.2f}, {ci[1]:+.2f}]"


def fav_verdict(c: dict, min_days: int = 5) -> str:
    if c.get("days", 0) < min_days:
        return f"NOT EVALUABLE ({c.get('days', 0)} fresh day(s); need >= {min_days})"
    lo, hi = c["ci99"]
    if lo > 0:
        return "PASS - 99% day-block CI above 0 on fresh days. Paper trade it next; it is not proven live"
    if hi < 0:
        return "FAIL - 99% day-block CI below 0 on fresh days"
    return "NO EDGE SHOWN - 99% day-block CI includes 0 on fresh days"


def fav_masks(df: pd.DataFrame) -> dict:
    fav = (df.cost >= FAV_PRICE[0] - 1e-9) & (df.cost <= FAV_PRICE[1] + 1e-9)
    dog = (df.cost >= DOG_PRICE[0] - 1e-9) & (df.cost < DOG_PRICE[1] - 1e-9)
    early = df.tau <= EARLY_S
    return {"all orders": pd.Series(True, index=df.index), "favourite side (50-90c)": fav,
            "favourite side, first 5 min": fav & early, "underdog side (10-50c)": dog,
            "underdog side, first 5 min": dog & early}


def cmd_favmaker(a) -> None:
    df = _maker_frame(a)
    rng = np.random.default_rng(11)
    idea = (df.day >= IDEA_DAYS[0]) & (df.day <= IDEA_DAYS[1])
    samples = (("Fresh days (not used to form the idea)", ~idea), (f"Idea days {IDEA_DAYS[0]} to {IDEA_DAYS[1]}", idea),
               ("All days", pd.Series(True, index=df.index)))
    print(f"# Favourite-side resting bids: {SERIES}\n\n{df.tk.nunique()} markets, {df.day.nunique()} days "
          f"({df.day.min()} to {df.day.max()}). Join the best bid on each side every 30 s, cancel after {a.cancel} s, "
          f"hold to settlement, maker fee 0. Cents per filled contract; CIs resample whole UTC days.\n")
    masks = fav_masks(df)
    decision = None
    for title, sm in samples:
        d = df[sm]
        print(f"## {title}: {d.tk.nunique()} markets, {d.day.nunique()} days\n")
        print("| Cell | Queue position | Orders | Fill % | c/fill | 95% day CI | 99% day CI | Days + |\n|---|---|---:|---:|---:|---|---|---:|")
        for name, mk in masks.items():
            for Q in QUEUES:
                g = d[mk[sm] & (d.Q == Q)]
                c = fav_cell(g, rng, a.iters)
                if not c["fills"]:
                    print(f"| {name} | {QUEUE_LABELS[Q]} | {c['orders']} | 0.0 | n/a | n/a | n/a | n/a |")
                    continue
                print(f"| {name} | {QUEUE_LABELS[Q]} | {c['orders']} | {c['fills'] / c['orders'] * 100:.1f} | {c['cents']:+.2f} "
                      f"| {_ci_text(c['ci95'])} | {_ci_text(c['ci99'])} | {c['pos_days']}/{c['days']} |")
                if title.startswith("Fresh") and name.startswith("favourite side (") and Q == DECISION_Q:
                    decision = c
        print()
    print(f"Decision (favourite side, {QUEUE_LABELS[DECISION_Q]}, fresh days): "
          + (fav_verdict(decision) if decision else "NOT EVALUABLE (no fresh-day fills)"))


# ---------------------------------------------------------------- hourly
def cmd_hourly(a) -> None:
    http = Http(a.rate)
    markets = [m for m in json.load(open(os.path.join(a.dir, "markets.json"))) if int(m["close"]) % 3600 == 0 and m["strike"]]
    fee = lambda p: TAKER_FEE * p * (1 - p)  # noqa: E731

    def quote(x):
        try:
            v = float(x)
            return v if 0 < v < 1 else None
        except (TypeError, ValueError):
            return None

    def candles(series, ticker, start, end):
        d = http.get(f"{API}/series/{series}/markets/{ticker}/candlesticks?start_ts={start}&end_ts={end}&period_interval=1")
        return {int(c["end_period_ts"]): (quote((c.get("yes_bid") or {}).get("close_dollars")),
                                          quote((c.get("yes_ask") or {}).get("close_dollars")))
                for c in d.get("candlesticks") or []}

    snaps = hits = 0
    best = 0.0
    windows = set()
    for m in markets:
        close, strike = int(m["close"]), float(m["strike"])
        d = http.get(f"{API}/markets?series_ticker=KXBTCD&min_close_ts={close - 1}&max_close_ts={close + 1}&limit=1000")
        ladder = [x for x in d.get("markets") or [] if x.get("strike_type") == "greater" and x.get("floor_strike") is not None]
        above = [x for x in ladder if x["floor_strike"] >= strike]
        below = [x for x in ladder if x["floor_strike"] < strike]
        if not above or not below:
            continue
        hi, lo = min(above, key=lambda x: x["floor_strike"]), max(below, key=lambda x: x["floor_strike"])
        c15 = candles(SERIES, m["ticker"], close - 900, close)
        chi, clo = candles("KXBTCD", hi["ticker"], close - 900, close), candles("KXBTCD", lo["ticker"], close - 900, close)
        for ts, (b15, a15) in c15.items():
            if b15 is None or a15 is None:
                continue
            snaps += 1
            bid_hi, ask_lo = chi.get(ts, (None, None))[0], clo.get(ts, (None, None))[1]
            # hourly YES(K>=S) implies 15m YES: buy 15m YES, sell hourly YES
            n1 = bid_hi - a15 - fee(a15) - fee(bid_hi) if bid_hi is not None else -1
            # 15m YES implies hourly YES(K<S): buy hourly YES, buy 15m NO
            n2 = b15 - ask_lo - fee(b15) - fee(ask_lo) if ask_lo is not None else -1
            if max(n1, n2) > 0:
                hits += 1
                windows.add(m["ticker"])
                best = max(best, n1, n2)
    print(f"# 15m vs hourly ladder (same settlement value)\n\n{len(markets)} hour-ending windows, {snaps} one-minute snapshots. "
          f"Gap after taker fees on both legs: {hits} snapshots in {len(windows)} windows, largest {best * 100:.1f}c.")


# ---------------------------------------------------------------- streaks
def cmd_streaks(a) -> None:
    ms = sorted(settled_markets(Http(a.rate), a.days), key=lambda m: m["close"])
    df = pd.DataFrame(ms)
    df["y"] = (df.result == "yes").astype(int)
    consecutive = df.close.diff() == 900

    def line(name, mask):
        g = df[mask]
        q = g.y.mean()
        print(f"- {name}: n={len(g)}, UP {q * 100:.2f}% (±{1.96 * np.sqrt(q * (1 - q) / len(g)) * 100:.2f}pp)")

    print(f"# Streaks: {SERIES}, {len(df)} windows\n")
    line("all windows", df.y >= 0)
    p1, p2 = df.y.shift(1), df.y.shift(2)
    line("after UP", consecutive & (p1 == 1))
    line("after DOWN", consecutive & (p1 == 0))
    line("after UP, UP", consecutive & consecutive.shift(1, fill_value=False) & (p1 == 1) & (p2 == 1))
    line("after DOWN, DOWN", consecutive & consecutive.shift(1, fill_value=False) & (p1 == 0) & (p2 == 0))
    print(f"\nBreak-even win rate buying a 50c ask as a taker: {(0.50 + TAKER_FEE * 0.25) * 100:.2f}%")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("pull", cmd_pull), ("report", cmd_report), ("makersim", cmd_makersim), ("favmaker", cmd_favmaker), ("hourly", cmd_hourly), ("streaks", cmd_streaks)):
        p = sub.add_parser(name)
        p.set_defaults(fn=fn)
        p.add_argument("--dir", default="tape")
        p.add_argument("--days", type=float, default=10)
        p.add_argument("--rate", type=float, default=12.0, help="requests per second")
        p.add_argument("--workers", type=int, default=8)
        p.add_argument("--iters", type=int, default=2000, help="bootstrap iterations")
        p.add_argument("--cancel", type=int, default=30, help="makersim: seconds before cancel")
        p.add_argument("--shard", default="0/1", help="pull: download only part i of n, e.g. 1/3")
        p.add_argument("--skip-days", default="", help="pull: skip UTC open days A:B (inclusive), e.g. 2026-09-24:2026-10-04")
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
