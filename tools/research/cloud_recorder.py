#!/usr/bin/env python3
"""Cloud recorder: the app's 1 s market-data recordings, made on a GitHub runner.

Writes the same files as the phone (see recordings.py / RecordingFormat.kt), so
lag_study.py and maker_sim.py run on either:

  spot_YYYY-MM-DD.csv.gz    ts_ms,product,price          (Coinbase REST ticker, ~1/s per coin)
  book_YYYY-MM-DD.csv.gz    ts_ms,ticker,strike,close_ms,yes_bid,...,no_ask_qty  (Kalshi REST, ~1/s per open market)
  trades_YYYY-MM-DD.csv.gz  ts_ms,ticker,yes_price,count,taker_side             (ts_ms = Kalshi created_time)
  settle_YYYY-MM-DD.csv     ticker,close_ms,strike,result

ts_ms is the runner's clock at receipt (trades: exchange time). Polling, not
websockets: the timing is ~1 s granular, which is what the studies use. Each
flush appends a gzip member, so files can be copied or extended across runs.

Stdlib only.  python3 cloud_recorder.py --out rec --minutes 330
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "backtest"))

from arb_scan import KALSHI, HttpClient, parse_orderbook  # noqa: E402
from recordings import HEADERS  # noqa: E402

COINBASE = "https://api.exchange.coinbase.com"
SERIES = {"KXBTC15M": "BTC-USD", "KXETH15M": "ETH-USD", "KXSOL15M": "SOL-USD"}
LIST_EVERY_S = 30
TRADES_EVERY_S = 5
SETTLE_EVERY_S = 30
SETTLE_GIVE_UP_S = 3 * 3600
FLUSH_EVERY_S = 60


def iso_ms(s: str | None) -> int | None:
    if not s:
        return None
    try:
        return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def day_of(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def num(x) -> str:
    if x is None:
        return ""
    return f"{x:.6g}" if isinstance(x, float) else str(x)


def dollars(m: dict, key: str) -> float | None:
    """Kalshi price field as dollars: `<key>_dollars` (string) or legacy cents."""
    v = m.get(f"{key}_dollars")
    if v not in (None, ""):
        try:
            return float(v)
        except (TypeError, ValueError):
            return None
    v = m.get(key)
    if v is None:
        return None
    try:
        return float(v) / 100.0
    except (TypeError, ValueError):
        return None


def book_row(ts_ms: int, ticker: str, strike, close_ms, body) -> list | None:
    b = parse_orderbook(body)
    if b is None:
        return None
    yb = b.yes_bids[0] if b.yes_bids else (None, None)
    nb = b.no_bids[0] if b.no_bids else (None, None)
    ya = (round(1.0 - nb[0], 4), nb[1]) if nb[0] is not None else (None, None)
    na = (round(1.0 - yb[0], 4), yb[1]) if yb[0] is not None else (None, None)
    return [ts_ms, ticker, strike, close_ms, yb[0], yb[1], ya[0], ya[1], nb[0], nb[1], na[0], na[1]]


def trade_row(t: dict) -> list | None:
    ts = iso_ms(t.get("created_time"))
    tk = t.get("ticker")
    if ts is None or not tk:
        return None
    price = dollars(t, "yes_price")
    cnt = t.get("count_fp", t.get("count"))
    try:
        cnt = int(float(cnt))
    except (TypeError, ValueError):
        return None
    side = (t.get("taker_side") or "").lower()
    return [ts, tk, price, cnt, side if side in ("yes", "no") else ""]


class Sink:
    """Buffers rows per (kind, day) and appends one gzip member per flush."""

    def __init__(self, out: Path):
        self.out = out
        self.buf: dict = {}
        out.mkdir(parents=True, exist_ok=True)

    def add(self, kind: str, ts_ms: int, row: list) -> None:
        self.buf.setdefault((kind, day_of(ts_ms)), []).append(row)

    def flush(self) -> None:
        for (kind, day), rows in self.buf.items():
            if not rows:
                continue
            gz = kind != "settle"
            path = self.out / (f"{kind}_{day}.csv.gz" if gz else f"{kind}_{day}.csv")
            new = not path.exists()
            text = ("" if not new else ",".join(HEADERS[kind]) + "\n") + "".join(
                ",".join(num(v) for v in r) + "\n" for r in rows)
            if gz:
                with open(path, "ab") as f:
                    f.write(gzip.compress(text.encode()))
            else:
                with open(path, "a") as f:
                    f.write(text)
        self.buf = {}


def run(out: Path, minutes: float, log=print) -> dict:
    http = HttpClient(rps=20.0, retries=2)
    sink = Sink(out)
    markets: dict = {}        # ticker -> {"series", "strike", "close_ms"}
    pending_file = out / "pending_settle.json"
    try:
        pending: dict = json.loads(pending_file.read_text())  # carried over from the last run
    except (OSError, ValueError):
        pending = {}            # ticker -> {"close_ms", "strike"} awaiting result
    seen_trades: set = set()
    trade_since: dict = {}
    stats = dict(spot=0, book=0, trades=0, settle=0, errors=0)
    t_end = time.time() + minutes * 60
    last = dict(list=0.0, trades=0.0, settle=0.0, flush=time.time())

    def get(url: str):
        try:
            return http.get(url)
        except Exception as e:  # noqa: BLE001 - one bad poll never stops a recording
            stats["errors"] += 1
            if stats["errors"] % 50 == 1:
                log(f"poll error ({stats['errors']}): {e}")
            return None

    while time.time() < t_end:
        tick = time.time()
        now_ms = int(tick * 1000)
        if tick - last["list"] >= LIST_EVERY_S:
            last["list"] = tick
            for series in SERIES:
                q = urllib.parse.urlencode({"series_ticker": series, "status": "open", "limit": "50"})
                body = get(f"{KALSHI}/markets?{q}") or {}
                for m in body.get("markets") or []:
                    tk, close_ms = m.get("ticker"), iso_ms(m.get("close_time"))
                    if tk and close_ms and close_ms > now_ms:
                        strike = m.get("floor_strike")
                        markets[tk] = dict(series=series, strike=float(strike) if strike is not None else None,
                                           close_ms=close_ms)
        for tk in [t for t, m in markets.items() if m["close_ms"] <= now_ms]:
            m = markets.pop(tk)
            pending[tk] = dict(close_ms=m["close_ms"], strike=m["strike"])

        for product in SERIES.values():
            body = get(f"{COINBASE}/products/{product}/ticker")
            if isinstance(body, dict) and body.get("price"):
                ts = int(time.time() * 1000)
                sink.add("spot", ts, [ts, product, float(body["price"])])
                stats["spot"] += 1

        # Only the market closing next in each series (the one the app trades).
        live = {}
        for tk, m in markets.items():
            cur = live.get(m["series"])
            if cur is None or m["close_ms"] < markets[cur]["close_ms"]:
                live[m["series"]] = tk
        for tk in live.values():
            m = markets[tk]
            body = get(f"{KALSHI}/markets/{urllib.parse.quote(tk)}/orderbook")
            ts = int(time.time() * 1000)
            row = book_row(ts, tk, m["strike"], m["close_ms"], body)
            if row:
                sink.add("book", ts, row)
                stats["book"] += 1

        if tick - last["trades"] >= TRADES_EVERY_S:
            last["trades"] = tick
            for tk in live.values():
                since = trade_since.get(tk, int(tick) - 60)
                q = urllib.parse.urlencode({"ticker": tk, "min_ts": str(since), "limit": "1000"})
                body = get(f"{KALSHI}/markets/trades?{q}") or {}
                for t in body.get("trades") or []:
                    tid = t.get("trade_id") or json.dumps(t, sort_keys=True)
                    if tid in seen_trades:
                        continue
                    seen_trades.add(tid)
                    row = trade_row(t)
                    if row:
                        sink.add("trades", row[0], row)
                        stats["trades"] += 1
                trade_since[tk] = int(tick) - 10

        if tick - last["settle"] >= SETTLE_EVERY_S:
            last["settle"] = tick
            for tk, p in list(pending.items()):
                if now_ms - p["close_ms"] > SETTLE_GIVE_UP_S * 1000 * 8:
                    pending.pop(tk)
                    continue
                if now_ms - p["close_ms"] < 30_000:
                    continue
                body = get(f"{KALSHI}/markets/{urllib.parse.quote(tk)}") or {}
                m = body.get("market") or {}
                res = (m.get("result") or "").lower()
                if res in ("yes", "no"):
                    strike = m.get("floor_strike", p["strike"])
                    sink.add("settle", p["close_ms"], [tk, p["close_ms"], float(strike) if strike is not None else None, res])
                    stats["settle"] += 1
                    pending.pop(tk)

        if tick - last["flush"] >= FLUSH_EVERY_S:
            last["flush"] = tick
            sink.flush()
            if len(seen_trades) > 200_000:
                seen_trades.clear()
        spare = 1.0 - (time.time() - tick)
        if spare > 0:
            time.sleep(spare)
    sink.flush()
    pending_file.write_text(json.dumps(pending))
    stats["unsettled_at_end"] = len(pending)
    return stats


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--minutes", type=float, default=330.0)
    a = ap.parse_args(argv)
    stats = run(Path(a.out), a.minutes)
    print("recorder stats:", json.dumps(stats))
    return 0 if stats["book"] > 0 and stats["spot"] > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
