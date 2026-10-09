#!/usr/bin/env python3
"""Does "bet the way Bitcoin is going" make money on Kalshi's 15-minute BTC market?

The study behind the app's clear-lead rule (docs/clear-lead-2026-10-09.md,
app: signal/trend/ClearLead.kt). Public data only, no API key:

  pull    settled KXBTC15M markets + their 1-minute quote candles (one request
          per market) and Coinbase BTC-USD 1-minute closes, into --dir
  report  the tables in the write-up

Conventions
  * At minute m of a window only what has already printed is used: the Kalshi
    candle that ended at open + 60*m and the Coinbase bar that ended then.
  * "Side Bitcoin is on": UP when the Coinbase close is above the market's
    floor_strike (the window's start price), DOWN when below.
  * Buy price = that side's ask (YES ask, or 1 - YES bid for DOWN).
  * Fee = 0.07 * P * (1 - P) per contract, unrounded (the kindest version).
  * Intervals are 95%, resampling whole UTC days.

  python3 tools/research/clear_lead_study.py pull --days 45 --dir lead
  python3 tools/research/clear_lead_study.py report --dir lead
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import os
import random
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "https://api.elections.kalshi.com/trade-api/v2"
SERIES = "KXBTC15M"
MIN_BP, MAX_BP = 10.0, 20.0          # ClearLeadRule.MIN_BP / MAX_BP
MIN_MINUTE, MAX_MINUTE = 3, 10       # ClearLeadRule time band


def get(url: str):
    for a in range(40):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "clear-lead-study/1.0", "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(0.1 + random.random() * 0.2 if e.code == 429 else 0.5 + 0.3 * a)
        except Exception:
            time.sleep(0.5 + 0.3 * a)
    raise RuntimeError("giving up on " + url)


def _ts(s: str) -> float:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def _f(x):
    return None if x in (None, "") else float(x)


def cmd_pull(a) -> None:
    os.makedirs(a.dir, exist_ok=True)
    lo = int(time.time() - a.days * 86400)
    markets, cur = [], None
    while True:
        d = get(f"{API}/markets?series_ticker={SERIES}&status=settled&limit=1000&min_close_ts={lo}" + (f"&cursor={cur}" if cur else ""))
        ms = (d or {}).get("markets") or []
        for m in ms:
            if m.get("result") in ("yes", "no") and m.get("floor_strike") is not None:
                markets.append({"ticker": m["ticker"], "open": _ts(m["open_time"]), "close": _ts(m["close_time"]),
                                "result": m["result"], "strike": float(m["floor_strike"])})
        cur = (d or {}).get("cursor")
        if not cur or not ms:
            break
    json.dump(markets, open(os.path.join(a.dir, "markets.json"), "w"))
    print(len(markets), "settled markets", flush=True)

    path = os.path.join(a.dir, "candles.jsonl")
    done = set()
    if os.path.exists(path):
        for line in open(path):
            try:
                done.add(json.loads(line)["t"])
            except Exception:
                pass
    out, lock, n = open(path, "a"), threading.Lock(), [0]

    def one(m):
        d = get(f"{API}/series/{SERIES}/markets/{m['ticker']}/candlesticks?start_ts={int(m['open']) - 60}"
                f"&end_ts={int(m['close']) + 60}&period_interval=1")
        rows = [(c["end_period_ts"], _f((c.get("yes_bid") or {}).get("close_dollars")),
                 _f((c.get("yes_ask") or {}).get("close_dollars"))) for c in (d or {}).get("candlesticks") or []]
        with lock:
            out.write(json.dumps({"t": m["ticker"], "c": rows}) + "\n")
            n[0] += 1
            if n[0] % 500 == 0:
                out.flush()
                print(n[0], flush=True)

    with ThreadPoolExecutor(a.workers) as ex:
        list(ex.map(one, [m for m in markets if m["ticker"] not in done]))
    out.close()

    end = int(time.time()) // 60 * 60
    start = int(min(m["open"] for m in markets)) - 3600
    spot, t = {}, start
    iso = lambda x: dt.datetime.fromtimestamp(x, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    while t < end:
        e = min(t + 300 * 60, end)
        for row in get(f"https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=60&start={iso(t)}&end={iso(e)}") or []:
            spot[int(row[0])] = float(row[4])          # bar start -> close
        t = e
        time.sleep(0.12)
    json.dump(sorted(spot.items()), open(os.path.join(a.dir, "spot.json"), "w"))
    print("spot bars", len(spot))


def fee(p: float) -> float:
    return 0.07 * p * (1 - p)


def load(d: str) -> list[dict]:
    mk = {m["ticker"]: m for m in json.load(open(os.path.join(d, "markets.json")))}
    cb = dict((int(a), b) for a, b in json.load(open(os.path.join(d, "spot.json"))))
    rows, seen = [], set()
    for line in open(os.path.join(d, "candles.jsonl")):
        o = json.loads(line)
        t = o["t"]
        if t in seen or t not in mk:
            continue
        seen.add(t)
        m = mk[t]
        op = int(m["open"])
        day = dt.datetime.fromtimestamp(op, dt.timezone.utc).strftime("%Y-%m-%d")
        q = {int(e): (yb, ya) for e, yb, ya in o["c"]}
        for mnt in range(1, 15):
            now = op + 60 * mnt
            if now not in q:
                continue
            yb, ya = q[now]
            s = cb.get(now - 60)                       # the bar that ended at `now`
            if yb is None or ya is None or s is None or not (0 < yb < 1 and 0 < ya < 1) or ya < yb:
                continue
            side_up = s > m["strike"]
            ask = ya if side_up else 1 - yb
            if not 0.01 <= ask <= 0.99:
                continue
            win = 1 if (m["result"] == "yes") == side_up else 0
            rows.append({"t": t, "day": day, "m": mnt, "bp": abs(s / m["strike"] - 1) * 1e4,
                         "win": win, "ask": ask, "net": win - ask - fee(ask), "s": s,
                         "s1": cb.get(now - 120), "s3": cb.get(now - 240), "s5": cb.get(now - 360),
                         "send": cb.get(int(m["close"]) - 60)})
    return rows


def summarize(bets: list[dict], label: str, iters: int = 3000) -> None:
    if len(bets) < 50:
        print(f"{label:62s} n={len(bets)} (too few)")
        return
    by = collections.defaultdict(list)
    for b in bets:
        by[b["day"]].append(b["net"])
    days = list(by)
    rnd = random.Random(3)
    v = []
    for _ in range(iters):
        s = [x for d in rnd.choices(days, k=len(days)) for x in by[d]]
        v.append(100 * sum(s) / len(s))
    v.sort()
    n = len(bets)
    per5 = sum(5.0 / (b["ask"] + fee(b["ask"])) * b["net"] for b in bets) / n
    print(f"{label:62s} n={n:5d} wins {100 * sum(b['win'] for b in bets) / n:5.1f}%  paid {100 * sum(b['ask'] for b in bets) / n:5.1f}c"
          f"  net {100 * sum(b['net'] for b in bets) / n:+5.2f}c [{v[int(.025 * iters)]:+.2f},{v[int(.975 * iters) - 1]:+.2f}]  per $5 bet ${per5:+.3f}")


def first_per_window(rows: list[dict], pred) -> list[dict]:
    out = {}
    for r in sorted(rows, key=lambda r: (r["t"], r["m"])):
        if r["t"] not in out and pred(r):
            out[r["t"]] = r
    return list(out.values())


def cmd_report(a) -> None:
    rows = load(a.dir)
    days = sorted({r["day"] for r in rows})
    half = days[len(days) // 2]
    print(f"windows {len({r['t'] for r in rows})}, days {len(days)} ({days[0]}..{days[-1]}), second half starts {half}\n")

    print("== 1. Bet the side Bitcoin is on, by minute ==")
    for mnt in (3, 5, 8, 10, 12):
        summarize([r for r in rows if r["m"] == mnt], f"minute {mnt:2d}")
    summarize([r for r in rows if 3 <= r["m"] <= 12], "minutes 3-12 pooled")

    print("\n== 2. Does Bitcoin keep going the way it was going? (no Kalshi prices) ==")
    for k in (1, 3, 5):
        key = f"s{k}"
        xs = [r for r in rows if r["m"] in (3, 5, 8, 10, 12) and r[key] and r["send"] and r["s"] != r[key] and r["send"] != r["s"]]
        same = sum(1 for r in xs if (r["s"] > r[key]) == (r["send"] > r["s"]))
        print(f"  after a {k}-minute move, the rest of the window went the same way {100 * same / len(xs):.1f}% of the time (n={len(xs)})")

    print("\n== 3. By how far ahead Bitcoin is, minute 8 ==")
    for lo, hi in ((0, 2), (2, 5), (5, 10), (10, 20), (20, 1e9)):
        summarize([r for r in rows if r["m"] == 8 and lo <= r["bp"] < hi], f"{lo}-{'+' if hi > 1e8 else hi} bp from the start price")

    print(f"\n== 4. The app's rule: first time {MIN_BP:.0f}-{MAX_BP:.0f} bp ahead in minutes {MIN_MINUTE}-{MAX_MINUTE} ==")
    rule = lambda r: MIN_MINUTE <= r["m"] <= MAX_MINUTE and MIN_BP <= r["bp"] < MAX_BP
    summarize(first_per_window([r for r in rows if r["day"] < half], rule), "first half of the days")
    summarize(first_per_window([r for r in rows if r["day"] >= half], rule), "second half of the days")
    summarize(first_per_window(rows, rule), "all days")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("pull", cmd_pull), ("report", cmd_report)):
        p = sub.add_parser(name)
        p.add_argument("--dir", default="lead")
        p.add_argument("--days", type=float, default=45)
        p.add_argument("--workers", type=int, default=4)
        p.set_defaults(fn=fn)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
