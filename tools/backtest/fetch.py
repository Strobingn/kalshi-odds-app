"""Fetch settled Kalshi 15m markets + 1-minute candles and Coinbase spot.

Docs cited:
  https://docs.kalshi.com/getting_started/historical_data
  https://docs.kalshi.com/api-reference/market/get-markets
  https://docs.kalshi.com/api-reference/market/get-market-candlesticks
  https://docs.kalshi.com/api-reference/historical/get-historical-market-candlesticks
  https://docs.kalshi.com/getting_started/rate_limits
  Coinbase: https://api.exchange.coinbase.com/products/{p}/candles?granularity=60
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
COINBASE = "https://api.exchange.coinbase.com"
SERIES = ("KXBTC15M", "KXETH15M", "KXSOL15M")
PRODUCT = {"KXBTC15M": "BTC-USD", "KXETH15M": "ETH-USD", "KXSOL15M": "SOL-USD"}
COIN = {"KXBTC15M": "BTC", "KXETH15M": "ETH", "KXSOL15M": "SOL"}
UA = {"User-Agent": "DipHunterBacktest/0.3.10 (research; +https://github.com/Strobingn/kalshi-odds-app)"}


def _get(url: str, retries: int = 6) -> object:
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
            return json.loads(raw.decode())
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                return None
            wait = 1.5 * (2 ** min(i, 4))
            if e.code == 429:
                ra = e.headers.get("Retry-After")
                wait = max(wait, float(ra) if ra else 2.0)
            if e.code in (429, 500, 502, 503):
                time.sleep(wait)
                continue
            raise
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            last = e
            time.sleep(1.5 * (2 ** min(i, 4)))
    raise RuntimeError(f"GET failed {url}: {last}")


def parse_iso(raw: str | None) -> int | None:
    if not raw:
        return None
    try:
        return int(datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def cutoff() -> dict:
    """GET /historical/cutoff — https://docs.kalshi.com/getting_started/historical_data"""
    return _get(f"{KALSHI}/historical/cutoff") or {}


def _page_markets(path: str, series: str, cursor: str | None, extra: dict) -> tuple[list[dict], str | None]:
    q = {"series_ticker": series, "limit": "200", **extra}
    if cursor:
        q["cursor"] = cursor
    url = f"{KALSHI}{path}?{urllib.parse.urlencode(q)}"
    body = _get(url)
    if not body:
        return [], None
    return body.get("markets") or [], (body.get("cursor") or None)


def list_settled(series: str, min_settled_ms: int, max_settled_ms: int, historical: bool, pause: float = 0.08) -> list[dict]:
    out = []
    cursor = None
    extra = {}
    if historical:
        path = "/historical/markets"
        # historical listing has no status=settled; filter locally
    else:
        path = "/markets"
        extra = {
            "status": "settled",
            "min_settled_ts": str(int(min_settled_ms / 1000)),
            "max_settled_ts": str(int(max_settled_ms / 1000)),
        }
    pages = 0
    while True:
        markets, cursor = _page_markets(path, series, cursor, extra)
        pages += 1
        for m in markets:
            result = (m.get("result") or "").lower()
            if result not in ("yes", "no"):
                continue
            close_ms = parse_iso(m.get("close_time")) or parse_iso(m.get("expiration_time"))
            settled_ms = parse_iso(m.get("settlement_ts")) or close_ms
            if settled_ms is None:
                continue
            if settled_ms < min_settled_ms or settled_ms > max_settled_ms:
                continue
            open_ms = parse_iso(m.get("open_time")) or (close_ms - 900_000 if close_ms else None)
            strike = m.get("floor_strike")
            if strike is None and isinstance(m.get("custom_strike"), dict):
                strike = m["custom_strike"].get("floor_strike")
            out.append(
                {
                    "ticker": m.get("ticker"),
                    "series": series,
                    "coin": COIN[series],
                    "result": result,
                    "floor_strike": float(strike) if strike is not None else None,
                    "open_ms": open_ms,
                    "close_ms": close_ms,
                    "settled_ms": settled_ms,
                    "volume_fp": _num(m.get("volume_fp")),
                    "open_interest_fp": _num(m.get("open_interest_fp")),
                    "source": "historical" if historical else "live",
                    "title": m.get("title"),
                    "yes_sub_title": m.get("yes_sub_title"),
                }
            )
        time.sleep(pause)
        if not cursor:
            break
        if pages > 400:
            break
    # unique by ticker
    seen = {}
    for m in out:
        seen[m["ticker"]] = m
    return list(seen.values())


def _num(x) -> float | None:
    if x is None:
        return None
    try:
        v = float(x)
        return v if math_finite(v) else None
    except (TypeError, ValueError):
        return None


def math_finite(v: float) -> bool:
    return v == v and v not in (float("inf"), float("-inf"))


def _ohlc(obj: dict | None, dollars: bool) -> dict:
    if not obj:
        return {}
    keys = ("open", "high", "low", "close")
    out = {}
    for k in keys:
        raw = obj.get(f"{k}_dollars") if dollars else obj.get(k)
        if raw is None and dollars:
            raw = obj.get(k)
        v = _num(raw)
        if v is not None:
            # historical integer close is dollars, not cents (LiveWindowBackfill)
            if v > 1.0 and v <= 100.0 and not dollars:
                v = v / 100.0
            out[k] = v
    return out


def fetch_candles(series: str, ticker: str, open_ms: int, close_ms: int, historical: bool, pause: float = 0.07) -> list[dict]:
    start = int(open_ms / 1000) - 60
    end = int(close_ms / 1000) + 60
    q = urllib.parse.urlencode({"start_ts": start, "end_ts": end, "period_interval": 1})
    if historical:
        url = f"{KALSHI}/historical/markets/{ticker}/candlesticks?{q}"
    else:
        url = f"{KALSHI}/series/{series}/markets/{ticker}/candlesticks?{q}"
    body = _get(url)
    time.sleep(pause)
    if not body:
        return []
    rows = []
    for c in body.get("candlesticks") or []:
        end_ts = int(c.get("end_period_ts") or c.get("end_ts") or 0)
        if end_ts <= 0:
            continue
        dollars = "close_dollars" in (c.get("yes_bid") or {}) or "close_dollars" in (c.get("yes_ask") or {})
        yb = _ohlc(c.get("yes_bid"), dollars)
        ya = _ohlc(c.get("yes_ask"), dollars)
        px = _ohlc(c.get("price"), dollars)
        vol = _num(c.get("volume_fp") or c.get("volume")) or 0.0
        oi = _num(c.get("open_interest_fp") or c.get("open_interest"))
        mid = None
        if "close" in yb and "close" in ya:
            mid = 0.5 * (yb["close"] + ya["close"])
        elif "close" in px:
            mid = px["close"]
        elif "close" in yb:
            mid = yb["close"]
        rows.append(
            {
                "end_ts": end_ts,
                "yes_bid": yb,
                "yes_ask": ya,
                "price": px,
                "volume": vol,
                "oi": oi,
                "mid": mid,
            }
        )
    return rows


def fetch_spot(product: str, start_ms: int, end_ms: int, pause: float = 0.12) -> list[list]:
    """Coinbase Exchange 1m candles: [time, low, high, open, close, volume], 300/request."""
    bar_ms = 60_000
    max_span = bar_ms * 280
    out = []
    cursor = start_ms
    while cursor < end_ms:
        chunk_end = min(end_ms, cursor + max_span)
        start = datetime.fromtimestamp(cursor / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        end = datetime.fromtimestamp(chunk_end / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        q = urllib.parse.urlencode({"granularity": 60, "start": start, "end": end})
        url = f"{COINBASE}/products/{product}/candles?{q}"
        body = _get(url)
        if isinstance(body, list):
            out.extend(body)
        time.sleep(pause)
        cursor = chunk_end
    # unique by time
    by = {}
    for row in out:
        if isinstance(row, list) and len(row) >= 6:
            by[int(row[0])] = row
    return [by[k] for k in sorted(by)]


def load_jsonl(path: Path) -> list:
    if not path.is_file():
        return []
    rows = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def append_jsonl(path: Path, rows: list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as f:
        for r in rows:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")


def run_fetch(cache: Path, days: int = 28, pause: float = 0.08) -> dict:
    cache.mkdir(parents=True, exist_ok=True)
    now_ms = int(time.time() * 1000)
    min_ms = now_ms - days * 86_400_000
    cut = cutoff()
    cut_iso = (cut.get("market_settled_ts") or "") if isinstance(cut, dict) else ""
    cut_ms = parse_iso(cut_iso) or 0
    meta = {
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "days": days,
        "min_ms": min_ms,
        "now_ms": now_ms,
        "cutoff": cut,
        "docs": {
            "historical": "https://docs.kalshi.com/getting_started/historical_data",
            "markets": "https://docs.kalshi.com/api-reference/market/get-markets",
            "candles": "https://docs.kalshi.com/api-reference/market/get-market-candlesticks",
            "hist_candles": "https://docs.kalshi.com/api-reference/historical/get-historical-market-candlesticks",
            "rates": "https://docs.kalshi.com/getting_started/rate_limits",
            "coinbase": "https://api.exchange.coinbase.com/products/{product}/candles?granularity=60",
        },
    }
    (cache / "meta.json").write_text(json.dumps(meta, indent=2))

    markets_path = cache / "markets.jsonl"
    existing = {m["ticker"]: m for m in load_jsonl(markets_path)}
    all_markets = list(existing.values())
    for series in SERIES:
        need_live = True
        need_hist = min_ms < cut_ms
        if need_live:
            live_min = max(min_ms, cut_ms)
            print(f"[fetch] live settled {series} from {datetime.fromtimestamp(live_min/1000, tz=timezone.utc)}")
            got = list_settled(series, live_min, now_ms, historical=False, pause=pause)
            new = [m for m in got if m["ticker"] not in existing]
            append_jsonl(markets_path, new)
            for m in new:
                existing[m["ticker"]] = m
            print(f"[fetch]   +{len(new)} live (page total {len(got)})")
        if need_hist:
            print(f"[fetch] historical {series} {datetime.fromtimestamp(min_ms/1000, tz=timezone.utc)} → cutoff")
            got = list_settled(series, min_ms, cut_ms - 1, historical=True, pause=pause)
            new = [m for m in got if m["ticker"] not in existing]
            append_jsonl(markets_path, new)
            for m in new:
                existing[m["ticker"]] = m
            print(f"[fetch]   +{len(new)} historical (page total {len(got)})")
    all_markets = list(existing.values())
    all_markets = [m for m in all_markets if m.get("open_ms") and m.get("close_ms") and m.get("ticker")]
    print(f"[fetch] markets total {len(all_markets)}")

    candles_dir = cache / "candles"
    candles_dir.mkdir(exist_ok=True)
    have = {p.stem for p in candles_dir.glob("*.json")}
    todo = [m for m in all_markets if m["ticker"] not in have]
    print(f"[fetch] candles todo {len(todo)} / {len(all_markets)}")
    lock = threading.Lock()
    done = [0]

    def _one(m: dict) -> None:
        hist = m.get("source") == "historical" or (cut_ms and (m.get("settled_ms") or 0) < cut_ms)
        try:
            rows = fetch_candles(m["series"], m["ticker"], m["open_ms"], m["close_ms"], hist, pause=0.02)
        except Exception as e:
            print(f"[fetch] candle fail {m['ticker']}: {e}")
            rows = []
        (candles_dir / f"{m['ticker']}.json").write_text(json.dumps(rows, separators=(",", ":")))
        with lock:
            done[0] += 1
            if done[0] % 100 == 0:
                print(f"[fetch] candles {done[0]}/{len(todo)}")

    # 3 workers × ~0.15s RTT ≈ 20 reads/s basic cap
    # https://docs.kalshi.com/getting_started/rate_limits
    workers = 3
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futs = [pool.submit(_one, m) for m in todo]
        for fut in as_completed(futs):
            fut.result()

    # spot span
    if all_markets:
        start_ms = min(m["open_ms"] for m in all_markets) - 20 * 60_000
        end_ms = max(m["close_ms"] for m in all_markets) + 60_000
    else:
        start_ms, end_ms = min_ms, now_ms
    for series, product in PRODUCT.items():
        dest = cache / f"spot_{product}.json"
        if dest.is_file() and dest.stat().st_size > 100:
            print(f"[fetch] spot {product} cached")
            continue
        print(f"[fetch] spot {product}")
        bars = fetch_spot(product, start_ms, end_ms)
        dest.write_text(json.dumps(bars, separators=(",", ":")))
        print(f"[fetch]   {len(bars)} bars")

    return {"markets": len(all_markets), "cutoff": cut, "min_ms": min_ms, "now_ms": now_ms}


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser()
    p.add_argument("--cache", default=str(Path(__file__).parent / "cache"))
    p.add_argument("--days", type=int, default=28)
    args = p.parse_args()
    print(run_fetch(Path(args.cache), days=args.days))
