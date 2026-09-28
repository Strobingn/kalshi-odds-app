#!/usr/bin/env python3
"""Fetch what Kalshi and Coinbase say about how 15m crypto markets settle.

Adds files next to the backtest cache (tools/backtest/cache, written by
tools/backtest/fetch.py). fetch.py and its files are not modified.

  settlement.jsonl            one row per settled market: the *raw* Kalshi
                              market object (every field it returns, incl.
                              expiration_value / rules_primary if present)
                              plus {"_ticker", "_via", "_fetched_at"}.
  settlement_series.json      raw GET /series/{series} per series.
  settlement_fields.json      which market fields exist, how often non-null,
                              one example value each.
  settlement_spot_{P}.json    extra Coinbase 1m candles (same row format as
                              spot_{P}.json: [time, low, high, open, close,
                              volume]) for minutes near market open/close that
                              spot_{P}.json does not cover.
  settlement_trades/{T}.json  Coinbase trades [[t_ms, price, size], ...] from
                              close-150s to close+30s for a sample of markets.

Endpoints:
  Kalshi   GET /markets?series_ticker&status=settled  (docs.kalshi.com/api-reference/market/get-markets)
           GET /historical/markets?series_ticker       (docs.kalshi.com/getting_started/historical_data)
           GET /markets/{ticker}, GET /series/{series}
  Coinbase GET /products/{p}/trades?limit=1000&after=<trade_id>  (older than cursor)
           GET /products/{p}/candles?granularity=60

Coinbase public limit is ~10 req/s per IP; we stay near 4 req/s.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "backtest"))

from fetch import (  # noqa: E402
    COINBASE,
    COIN,
    KALSHI,
    PRODUCT,
    SERIES,
    _get,
    append_jsonl,
    cutoff,
    fetch_spot,
    load_jsonl,
    parse_iso,
)

CB_PAUSE = 0.25
TRADE_PRE_S = 150
TRADE_POST_S = 30


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# --- Kalshi -------------------------------------------------------------------

def list_raw(series: str, historical: bool, min_ms: int, max_ms: int, pause: float = 0.08, max_pages: int = 400) -> list[dict]:
    path = "/historical/markets" if historical else "/markets"
    extra = {} if historical else {
        "status": "settled",
        "min_settled_ts": str(int(min_ms / 1000)),
        "max_settled_ts": str(int(max_ms / 1000)),
    }
    out, cursor, pages = [], None, 0
    while pages < max_pages:
        q = {"series_ticker": series, "limit": "200", **extra}
        if cursor:
            q["cursor"] = cursor
        body = _get(f"{KALSHI}{path}?{urllib.parse.urlencode(q)}") or {}
        pages += 1
        for m in body.get("markets") or []:
            close_ms = parse_iso(m.get("close_time")) or parse_iso(m.get("expiration_time"))
            if close_ms is None or close_ms < min_ms - 86_400_000 or close_ms > max_ms + 86_400_000:
                continue
            out.append(m)
        cursor = body.get("cursor") or None
        time.sleep(pause)
        if not cursor:
            break
    return out


def get_market_raw(ticker: str, historical: bool) -> dict | None:
    for path in (("/historical/markets/", "/markets/") if historical else ("/markets/", "/historical/markets/")):
        body = _get(f"{KALSHI}{path}{ticker}")
        if isinstance(body, dict) and isinstance(body.get("market"), dict):
            return body["market"]
    return None


def field_census(rows: list[dict]) -> dict:
    out: dict[str, dict] = {}
    for r in rows:
        for k, v in r.items():
            if k.startswith("_"):
                continue
            e = out.setdefault(k, {"present": 0, "non_null": 0, "example": None})
            e["present"] += 1
            if v not in (None, "", [], {}):
                e["non_null"] += 1
                if e["example"] is None:
                    s = json.dumps(v) if not isinstance(v, str) else v
                    e["example"] = s[:300]
    return {"n": len(rows), "fields": dict(sorted(out.items()))}


def fetch_settlement(cache: Path, days: int, pause: float = 0.08, max_single: int = 3000) -> dict:
    markets = [m for m in load_jsonl(cache / "markets.jsonl") if m.get("ticker")]
    dest = cache / "settlement.jsonl"
    have = {r.get("_ticker") for r in load_jsonl(dest)}
    want = {m["ticker"]: m for m in markets if m["ticker"] not in have}
    print(f"[settle] markets in cache {len(markets)}, settlement rows {len(have)}, missing {len(want)}")
    now_ms = int(time.time() * 1000)
    min_ms = now_ms - days * 86_400_000
    if want:
        min_ms = min(min_ms, min(int(m.get("close_ms") or now_ms) for m in want.values()))
    cut = cutoff()
    cut_ms = parse_iso((cut or {}).get("market_settled_ts")) or 0

    fetched = 0
    for series in SERIES:
        if not any(m.get("series") == series for m in want.values()):
            continue
        raws = list_raw(series, False, max(min_ms, cut_ms), now_ms, pause)
        if min_ms < cut_ms:
            raws += list_raw(series, True, min_ms, cut_ms, pause)
        new = []
        for m in raws:
            t = m.get("ticker")
            if t in want:
                new.append({**m, "_ticker": t, "_via": "list", "_fetched_at": _now_iso()})
                want.pop(t)
        append_jsonl(dest, new)
        fetched += len(new)
        print(f"[settle] {series}: listed {len(raws)}, stored {len(new)}")

    # anything the listings did not return: one GET per ticker (capped)
    rest = list(want.values())[:max_single]
    if rest:
        print(f"[settle] per-ticker GET for {len(rest)} markets")
    buf = []
    for i, m in enumerate(rest):
        hist = bool(cut_ms and (m.get("settled_ms") or 0) < cut_ms)
        raw = get_market_raw(m["ticker"], hist)
        if raw:
            buf.append({**raw, "_ticker": m["ticker"], "_via": "get", "_fetched_at": _now_iso()})
        time.sleep(pause)
        if len(buf) >= 100 or i == len(rest) - 1:
            append_jsonl(dest, buf)
            fetched += len(buf)
            buf = []

    all_rows = load_jsonl(dest)
    census = field_census(all_rows)
    (cache / "settlement_fields.json").write_text(json.dumps(census, indent=1))
    print(f"[settle] settlement rows {len(all_rows)}; fields returned:")
    for k, e in census["fields"].items():
        print(f"[settle]   {k:32s} present {e['present']:6d} non-null {e['non_null']:6d}  e.g. {str(e['example'])[:80]!r}")

    series_meta = {}
    for s in SERIES:
        try:
            series_meta[s] = _get(f"{KALSHI}/series/{s}")
        except Exception as e:  # noqa: BLE001
            series_meta[s] = {"error": str(e)}
        time.sleep(pause)
    (cache / "settlement_series.json").write_text(json.dumps(series_meta, indent=1))
    return {"stored": fetched, "rows": len(all_rows)}


# --- Coinbase 1m gap fill -------------------------------------------------------

def _load_bars(p: Path) -> dict[int, list]:
    if not p.is_file():
        return {}
    try:
        return {int(r[0]): r for r in json.loads(p.read_text()) if isinstance(r, list) and len(r) >= 6}
    except (ValueError, json.JSONDecodeError):
        return {}


def needed_minutes(markets: list[dict], coin: str, pre_min: int = 35, post_min: int = 3) -> set[int]:
    need = set()
    for m in markets:
        if m.get("coin") != coin or not m.get("close_ms") or not m.get("open_ms"):
            continue
        o = int(m["open_ms"]) // 1000
        c = int(m["close_ms"]) // 1000
        for base in (o, c):
            b = base - base % 60
            for k in range(-pre_min, post_min + 1):
                need.add(b + 60 * k)
    return need


def spans(minutes: list[int], join_gap: int = 1800) -> list[tuple[int, int]]:
    out: list[list[int]] = []
    for t in sorted(minutes):
        if out and t - out[-1][1] <= join_gap:
            out[-1][1] = t
        else:
            out.append([t, t])
    return [(a, b) for a, b in out]


def fill_spot(cache: Path) -> dict:
    markets = [m for m in load_jsonl(cache / "markets.jsonl") if m.get("ticker")]
    res = {}
    for series, product in PRODUCT.items():
        coin = COIN[series]
        base = _load_bars(cache / f"spot_{product}.json")
        extra_p = cache / f"settlement_spot_{product}.json"
        extra = _load_bars(extra_p)
        need = needed_minutes(markets, coin)
        now = int(time.time())
        missing = [t for t in need if t not in base and t not in extra and t < now - 120]
        sp = spans(missing)
        print(f"[settle] spot {product}: need {len(need)} min, missing {len(missing)} in {len(sp)} spans")
        for a, b in sp:
            try:
                bars = fetch_spot(product, a * 1000, (b + 60) * 1000)
            except Exception as e:  # noqa: BLE001
                print(f"[settle]   spot fail {a}-{b}: {e}")
                continue
            for r in bars:
                if int(r[0]) not in base:
                    extra[int(r[0])] = r
        extra_p.write_text(json.dumps([extra[k] for k in sorted(extra)], separators=(",", ":")))
        res[product] = {"missing": len(missing), "extra_bars": len(extra)}
    return res


# --- Coinbase trades ------------------------------------------------------------

class TradeClient:
    def __init__(self, product: str, pause: float = CB_PAUSE):
        self.product = product
        self.pause = pause
        self.calls = 0

    def page(self, before_id: int | None = None) -> list[tuple[int, int, float, float]]:
        """Up to 1000 trades with trade_id < before_id (newest first) → [(id, t_ms, px, size)]."""
        q = {"limit": 1000}
        if before_id is not None:
            q["after"] = int(before_id)
        body = _get(f"{COINBASE}/products/{self.product}/trades?{urllib.parse.urlencode(q)}")
        self.calls += 1
        time.sleep(self.pause)
        out = []
        for t in body or []:
            try:
                ts = parse_iso(t["time"])
                if ts is None:
                    continue
                out.append((int(t["trade_id"]), ts, float(t["price"]), float(t["size"])))
            except (KeyError, TypeError, ValueError):
                continue
        return out


def locate_page(client, t_ms: int, head: tuple[int, int], rate: float, max_probes: int = 16):
    """Find a page (ids ≤ guess) whose time span contains t_ms. head = (latest_id, latest_t_ms).

    rate = trades per ms (initial guess). Secant search on (id, time).
    Returns the page or None.
    """
    hi = head
    lo: tuple[int, int] | None = None
    guess = int(hi[0] - rate * (hi[1] - t_ms)) + 200
    for _ in range(max_probes):
        guess = max(1, guess)
        if lo is not None:
            guess = max(lo[0] + 1, guess)
        guess = min(hi[0], guess)
        pg = client.page(guess + 1)
        if not pg:
            lo = (guess, -1)
            guess = (guess + hi[0]) // 2
            continue
        tmin = min(p[1] for p in pg)
        tmax = max(p[1] for p in pg)
        idmin = min(p[0] for p in pg)
        idmax = max(p[0] for p in pg)
        if tmin <= t_ms <= tmax:
            return pg
        if t_ms > tmax:
            lo = (idmax, tmax)
        else:
            hi = (idmin, tmin)
        if lo is not None and lo[1] >= 0 and hi[1] > lo[1]:
            frac = (t_ms - lo[1]) / (hi[1] - lo[1])
            guess = int(lo[0] + frac * (hi[0] - lo[0])) + 200
        elif lo is not None and lo[1] >= 0:
            guess = int(lo[0] + rate * (t_ms - lo[1])) + 200
        else:
            guess = int(hi[0] - rate * (hi[1] - t_ms)) + 200
        if lo is not None and hi[0] - lo[0] <= 1:
            return None
    return None


def window_trades(client, start_ms: int, end_ms: int, head: tuple[int, int], rate: float, max_pages: int = 30):
    """Trades in [start_ms, end_ms] → ([[t_ms, px, size], ...], anchor (id, t_ms) of oldest fetched)."""
    pg = locate_page(client, end_ms, head, rate)
    if pg is None:
        return [], None
    got = {p[0]: p for p in pg}
    pages = 0
    while pages < max_pages and min(p[1] for p in got.values()) > start_ms:
        nxt = client.page(min(got))
        pages += 1
        if not nxt:
            break
        for p in nxt:
            got[p[0]] = p
    rows = sorted((p for p in got.values() if start_ms <= p[1] <= end_ms), key=lambda p: (p[1], p[0]))
    oldest = min(got.values(), key=lambda p: p[0])
    return [[p[1], p[2], p[3]] for p in rows], (oldest[0], oldest[1])


def sample_markets(markets: list[dict], settle_by: dict[str, dict], per_coin: int) -> list[dict]:
    """Stable pseudo-random sample, preferring markets with an expiration value."""
    def key(m):
        has = 0 if (settle_by.get(m["ticker"]) or {}).get("expiration_value") not in (None, "") else 1
        return (has, hashlib.sha1(m["ticker"].encode()).hexdigest())
    out = []
    for coin in COIN.values():
        ms = sorted([m for m in markets if m.get("coin") == coin and m.get("close_ms")], key=key)
        out += ms[:per_coin]
    return out


def fetch_trades(cache: Path, per_coin: int, budget_s: float) -> dict:
    markets = [m for m in load_jsonl(cache / "markets.jsonl") if m.get("ticker")]
    settle_by = {r.get("_ticker"): r for r in load_jsonl(cache / "settlement.jsonl")}
    tdir = cache / "settlement_trades"
    tdir.mkdir(parents=True, exist_ok=True)
    sample = sample_markets(markets, settle_by, per_coin)
    todo = [m for m in sample if not (tdir / f"{m['ticker']}.json").is_file()]
    print(f"[settle] trades: sample {len(sample)}, todo {len(todo)}")
    t0 = time.time()
    stats = {"ok": 0, "empty": 0, "calls": 0}
    for product in PRODUCT.values():
        coin = product.split("-")[0]
        mine = sorted([m for m in todo if m.get("coin") == coin], key=lambda m: -int(m["close_ms"]))
        if not mine:
            continue
        client = TradeClient(product)
        latest = client.page(None)
        if not latest:
            print(f"[settle]   {product}: no trades endpoint response")
            continue
        head = (max(p[0] for p in latest), max(p[1] for p in latest))
        span_ms = max(1, head[1] - min(p[1] for p in latest))
        rate = max(1e-6, (head[0] - min(p[0] for p in latest)) / span_ms)
        for m in mine:
            if time.time() - t0 > budget_s:
                print("[settle]   trade budget exhausted")
                break
            c = int(m["close_ms"])
            try:
                rows, anchor = window_trades(client, c - TRADE_PRE_S * 1000, c + TRADE_POST_S * 1000, head, rate)
            except Exception as e:  # noqa: BLE001
                print(f"[settle]   trades fail {m['ticker']}: {e}")
                continue
            (tdir / f"{m['ticker']}.json").write_text(json.dumps(rows, separators=(",", ":")))
            stats["ok" if rows else "empty"] += 1
            if anchor and anchor[1] < head[1] and anchor[0] < head[0]:
                # markets are processed newest → oldest: the last anchor is a
                # closer upper bracket, and the id/time slope between anchors
                # is a local trade-rate estimate.
                rate = max(1e-6, (head[0] - anchor[0]) / (head[1] - anchor[1]))
                head = anchor
            if (stats["ok"] + stats["empty"]) % 25 == 0:
                print(f"[settle]   trades {stats['ok'] + stats['empty']}/{len(todo)} ({client.calls} calls)")
        stats["calls"] += client.calls
    return stats


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(HERE.parent / "backtest" / "cache"))
    ap.add_argument("--days", type=int, default=28)
    ap.add_argument("--trades-per-coin", type=int, default=100)
    ap.add_argument("--trade-budget-s", type=float, default=1800.0)
    ap.add_argument("--skip-trades", action="store_true")
    args = ap.parse_args()
    cache = Path(args.cache)
    print(fetch_settlement(cache, args.days))
    print(fill_spot(cache))
    if not args.skip_trades:
        print(fetch_trades(cache, args.trades_per_coin, args.trade_budget_s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
