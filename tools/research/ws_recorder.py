#!/usr/bin/env python3
"""Tick-level recorder: Kalshi WebSocket (ticker + trade) and Coinbase WebSocket.

The REST recordings poll once a second, so a Kalshi lag under 1 s was
invisible. This records every price update as it arrives, stamped with one
wall clock (time_ns at receipt) on one machine, so Coinbase and Kalshi can be
compared at ~10 ms resolution (see ws_lag.py).

Read-only: it subscribes to market-data channels with the Kalshi API key and
never places or cancels an order. The key is only used to sign the handshake
and is never printed or written to disk.

Rows (gzip CSV, ws_YYYY-MM-DD.csv.gz, new member per flush):
  recv_ns,src,sym,a,b,c,exch_ms
  k   <ticker>   yes_bid  yes_ask  last_price       Kalshi ticker update
  kt  <ticker>   yes_price count   taker_side       Kalshi public trade
  cb  <product>  price    best_bid best_ask         Coinbase ticker

Needs: pip install websockets cryptography.
  KALSHI_KEY_ID=... KALSHI_PRIVATE_KEY="$(cat key.pem)" python3 ws_recorder.py --out ws --minutes 40
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import gzip
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

KALSHI_WS = "wss://api.elections.kalshi.com/trade-api/ws/v2"
KALSHI_WS_PATH = "/trade-api/ws/v2"
COINBASE_WS = "wss://ws-feed.exchange.coinbase.com"
SERIES = ("KXBTC15M", "KXETH15M", "KXSOL15M")
PRODUCTS = ("BTC-USD", "ETH-USD", "SOL-USD")
HEADER = "recv_ns,src,sym,a,b,c,exch_ms\n"
FLUSH_EVERY_S = 30


# --------------------------------------------------------------------------- auth

def normalize_pem(pem: str) -> str:
    """GitHub secrets / env vars often lose newlines or carry literal \\n; rebuild a valid PEM."""
    s = (pem or "").strip().replace("\\n", "\n").replace("\r", "")
    m = re.search(r"-----BEGIN ([A-Z ]*PRIVATE KEY)-----(.*?)-----END \1-----", s, re.S)
    if not m:
        raise ValueError("not a PEM private key")
    body = re.sub(r"\s+", "", m.group(2))
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return f"-----BEGIN {m.group(1)}-----\n" + "\n".join(lines) + f"\n-----END {m.group(1)}-----\n"


def load_key(pem: str):
    from cryptography.hazmat.primitives import serialization

    return serialization.load_pem_private_key(normalize_pem(pem).encode(), password=None)


def sign(key, message: str) -> str:
    """Kalshi signature: RSA-PSS (SHA-256, MGF1, salt = digest length) or Ed25519, base64."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ed25519, padding

    data = message.encode()
    if isinstance(key, ed25519.Ed25519PrivateKey):
        raw = key.sign(data)
    else:
        raw = key.sign(
            data,
            padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=padding.PSS.DIGEST_LENGTH),
            hashes.SHA256(),
        )
    return base64.b64encode(raw).decode()


def handshake_headers(key_id: str, key, now_ms: int | None = None) -> dict:
    ts = str(now_ms if now_ms is not None else int(time.time() * 1000))
    return {
        "KALSHI-ACCESS-KEY": key_id,
        "KALSHI-ACCESS-TIMESTAMP": ts,
        "KALSHI-ACCESS-SIGNATURE": sign(key, ts + "GET" + KALSHI_WS_PATH),
    }


# --------------------------------------------------------------------------- parsing

def _num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def _dollars(m: dict, dollars_key: str, cents_key: str):
    v = _num(m.get(dollars_key))
    if v is not None:
        return v
    c = _num(m.get(cents_key))
    return None if c is None else c / 100.0


def _exch_ms(m: dict):
    v = _num(m.get("ts_ms"))
    if v is not None:
        return int(v)
    v = _num(m.get("ts"))
    return None if v is None else int(v * 1000)


def parse_kalshi(raw: str, recv_ns: int):
    """Row for a ticker / trade message, else None."""
    try:
        d = json.loads(raw)
    except ValueError:
        return None
    t, m = d.get("type"), d.get("msg") or {}
    tk = m.get("market_ticker")
    if not tk:
        return None
    if t == "ticker":
        return [recv_ns, "k", tk, _dollars(m, "yes_bid_dollars", "yes_bid"), _dollars(m, "yes_ask_dollars", "yes_ask"),
                _dollars(m, "price_dollars", "price"), _exch_ms(m)]
    if t == "trade":
        side = (m.get("taker_side") or m.get("taker_outcome_side") or "").lower()
        return [recv_ns, "kt", tk, _dollars(m, "yes_price_dollars", "yes_price"),
                _num(m.get("count_fp", m.get("count"))), side, _exch_ms(m)]
    return None


def parse_coinbase(raw: str, recv_ns: int):
    try:
        d = json.loads(raw)
    except ValueError:
        return None
    if d.get("type") != "ticker" or not d.get("product_id"):
        return None
    ex = None
    if d.get("time"):
        try:
            ex = int(datetime.fromisoformat(d["time"].replace("Z", "+00:00")).timestamp() * 1000)
        except ValueError:
            ex = None
    return [recv_ns, "cb", d["product_id"], _num(d.get("price")), _num(d.get("best_bid")), _num(d.get("best_ask")), ex]


# --------------------------------------------------------------------------- sink

def fmt(v) -> str:
    if v is None:
        return ""
    return f"{v:.6g}" if isinstance(v, float) else str(v)


class Sink:
    def __init__(self, out: Path):
        self.out = out
        out.mkdir(parents=True, exist_ok=True)
        self.rows: list = []
        self.count = {"k": 0, "kt": 0, "cb": 0}

    def add(self, row) -> None:
        if row is None:
            return
        self.rows.append(row)
        self.count[row[1]] = self.count.get(row[1], 0) + 1

    def flush(self) -> None:
        by_day: dict = {}
        for r in self.rows:
            day = datetime.fromtimestamp(r[0] / 1e9, tz=timezone.utc).strftime("%Y-%m-%d")
            by_day.setdefault(day, []).append(r)
        for day, rows in by_day.items():
            path = self.out / f"ws_{day}.csv.gz"
            text = ("" if path.exists() else HEADER) + "".join(",".join(fmt(v) for v in r) + "\n" for r in rows)
            with open(path, "ab") as f:
                f.write(gzip.compress(text.encode()))
        self.rows = []


# --------------------------------------------------------------------------- live loops

def open_tickers(http) -> list:
    from arb_scan import KALSHI
    import urllib.parse

    out = []
    for s in SERIES:
        q = urllib.parse.urlencode({"series_ticker": s, "status": "open", "limit": "50"})
        body = http.get(f"{KALSHI}/markets?{q}") or {}
        out += [m["ticker"] for m in body.get("markets") or [] if m.get("ticker")]
    return out


async def _connect(url: str, headers: dict | None = None):
    import websockets

    kw = {"max_size": None, "ping_interval": 20}
    if headers:
        try:
            return await websockets.connect(url, additional_headers=headers, **kw)
        except TypeError:  # older websockets
            return await websockets.connect(url, extra_headers=headers, **kw)
    return await websockets.connect(url, **kw)


async def coinbase_loop(stop_at: float, sink: Sink, log) -> None:
    backoff = 1.0
    while time.time() < stop_at:
        try:
            ws = await _connect(COINBASE_WS)
            try:
                await ws.send(json.dumps({"type": "subscribe", "product_ids": list(PRODUCTS), "channels": ["ticker"]}))
                backoff = 1.0
                while time.time() < stop_at:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(1.0, stop_at - time.time()))
                    sink.add(parse_coinbase(raw, time.time_ns()))
            finally:
                await ws.close()
        except asyncio.TimeoutError:
            return
        except Exception as e:  # noqa: BLE001 - reconnect on anything
            log(f"coinbase ws: {type(e).__name__}: {e}")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)


async def kalshi_loop(key_id: str, key, stop_at: float, sink: Sink, log) -> None:
    from arb_scan import HttpClient

    http = HttpClient(rps=5.0, retries=2)
    backoff = 1.0
    while time.time() < stop_at:
        try:
            ws = await _connect(KALSHI_WS, handshake_headers(key_id, key))
            subscribed: set = set()
            cmd = 0

            async def discover():
                nonlocal cmd
                while time.time() < stop_at:
                    try:
                        tickers = await asyncio.to_thread(open_tickers, http)
                    except Exception as e:  # noqa: BLE001
                        log(f"kalshi discovery: {e}")
                        tickers = []
                    new = [t for t in tickers if t not in subscribed]
                    if new:
                        cmd += 1
                        await ws.send(json.dumps({"id": cmd, "cmd": "subscribe",
                                                  "params": {"channels": ["ticker", "trade"], "market_tickers": new}}))
                        subscribed.update(new)
                    await asyncio.sleep(15)

            task = asyncio.create_task(discover())
            try:
                backoff = 1.0
                while time.time() < stop_at:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(1.0, stop_at - time.time()))
                    recv = time.time_ns()
                    row = parse_kalshi(raw, recv)
                    if row is None and '"error"' in raw:
                        log(f"kalshi ws error message: {raw[:300]}")
                    sink.add(row)
            finally:
                task.cancel()
                await ws.close()
        except asyncio.TimeoutError:
            return
        except Exception as e:  # noqa: BLE001
            log(f"kalshi ws: {type(e).__name__}: {e}")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)


async def run(out: Path, minutes: float, key_id: str, pem: str, log=print) -> dict:
    key = load_key(pem)
    sink = Sink(out)
    stop_at = time.time() + minutes * 60

    async def flusher():
        while time.time() < stop_at:
            await asyncio.sleep(FLUSH_EVERY_S)
            sink.flush()

    await asyncio.gather(coinbase_loop(stop_at, sink, log), kalshi_loop(key_id, key, stop_at, sink, log), flusher())
    sink.flush()
    return dict(sink.count)


def main(argv: list | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--minutes", type=float, default=40.0)
    a = ap.parse_args(argv)
    key_id = os.environ.get("KALSHI_KEY_ID", "").strip()
    pem = os.environ.get("KALSHI_PRIVATE_KEY", "")
    if not key_id or not pem.strip():
        print("KALSHI_KEY_ID / KALSHI_PRIVATE_KEY not set: nothing recorded.")
        return 0
    try:
        counts = asyncio.run(run(Path(a.out), a.minutes, key_id, pem))
    except ValueError as e:
        print(f"key problem: {e}")  # never the key itself
        return 1
    print("ws recorder rows:", json.dumps(counts))
    return 0 if counts.get("cb") and counts.get("k") else 1


if __name__ == "__main__":
    sys.exit(main())
