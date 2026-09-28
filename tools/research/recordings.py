#!/usr/bin/env python3
"""Loader for the app's second-by-second market-data recordings (stdlib only).

The Android app (data/local/recording/RecordingFormat.kt) writes, per UTC day,
into ``filesDir/recordings/`` (Data -> "Export recordings" zips them under
``recordings/``):

  spot_YYYY-MM-DD.csv.gz    ts_ms,product,price
  book_YYYY-MM-DD.csv.gz    ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,
                            yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty
  trades_YYYY-MM-DD.csv.gz  ts_ms,ticker,yes_price,count,taker_side
  settle_YYYY-MM-DD.csv     ticker,close_ms,strike,result     (plain text)

Header row first, comma-separated, empty field = missing. ``ts_ms`` is the
phone's wall clock (epoch ms) at receipt. Kalshi prices are dollars 0-1,
quantities are contracts, spot is USD. Spot rows: at most one per product per
250 ms (latest print kept). Book rows: on change at most 1/s per ticker plus a
5 s heartbeat. Settle rows go in the file of the market's close day.

The ``.csv.gz`` files are **multi-member gzip** (a new member per app start,
day, or 10 minutes) and the open member is sync-flushed, so a file copied
while recording -- or after a crash -- can end in a member with no trailer.
``read_gzip_tolerant`` concatenates members and salvages a truncated one up to
its last complete line. Plain ``gzip.open`` handles the multi-member part but
raises ``EOFError`` on a truncated tail.

Usage:
    from recordings import load_day, load_days, list_days
    day = load_day("recordings", "2026-09-28")
    day["spot"][0] -> {"ts_ms": ..., "product": "BTC-USD", "price": 65000.5}
"""
from __future__ import annotations

import os
import re
import zlib
from pathlib import Path

GZ_MAGIC = b"\x1f\x8b\x08"

HEADERS = {
    "spot": ["ts_ms", "product", "price"],
    "book": [
        "ts_ms", "ticker", "strike", "close_ms",
        "yes_bid", "yes_bid_qty", "yes_ask", "yes_ask_qty",
        "no_bid", "no_bid_qty", "no_ask", "no_ask_qty",
    ],
    "trades": ["ts_ms", "ticker", "yes_price", "count", "taker_side"],
    "settle": ["ticker", "close_ms", "strike", "result"],
}
KINDS = ("spot", "book", "trades", "settle")

_INT_COLS = {"ts_ms", "close_ms"}
_STR_COLS = {"product", "ticker", "taker_side", "result"}
_FILE_RE = re.compile(r"^(spot|book|trades|settle)_(\d{4}-\d{2}-\d{2})\.csv(\.gz)?$")
_CHUNK = 4096


def file_name(kind: str, day: str) -> str:
    return f"{kind}_{day}.csv" if kind == "settle" else f"{kind}_{day}.csv.gz"


def _member_starts_ok(data: bytes, pos: int) -> bool:
    """True when a gzip member plausibly starts at ``pos`` (header + some clean inflate)."""
    if data[pos:pos + 3] != GZ_MAGIC or len(data) < pos + 10:
        return False
    if data[pos + 3] & 0xE0:  # reserved FLG bits must be zero
        return False
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        d.decompress(data[pos:pos + 65536])
    except zlib.error:
        return False
    return True


def _inflate(data: bytes, start: int, end: int):
    """Inflate one member from data[start:end]. Returns (bytes, eof, next_pos)."""
    d = zlib.decompressobj(16 + zlib.MAX_WBITS)
    chunks: list[bytes] = []
    i = start
    try:
        while i < end:
            piece = data[i:min(end, i + _CHUNK)]
            chunks.append(d.decompress(piece))
            i += len(piece)
            if d.eof:
                break
    except zlib.error:
        return b"".join(chunks), False, end
    if d.eof:
        return b"".join(chunks), True, i - len(d.unused_data)
    return b"".join(chunks), False, end


def read_gzip_tolerant(data: bytes) -> bytes:
    """Concatenate all gzip members; keep a truncated member's complete lines."""
    out: list[bytes] = []
    pos, n = 0, len(data)
    while pos < n:
        if data[pos:pos + 3] != GZ_MAGIC:
            nxt = data.find(GZ_MAGIC, pos + 1)
            if nxt < 0:
                break
            pos = nxt
            continue
        text, eof, nxt = _inflate(data, pos, n)
        if eof:
            out.append(text)
            pos = nxt
            continue
        # Truncated or corrupt member: find where the next real member starts
        # and inflate only up to there, so no bytes of it leak into this one.
        m = data.find(GZ_MAGIC, pos + 10)
        while m >= 0 and not _member_starts_ok(data, m):
            m = data.find(GZ_MAGIC, m + 1)
        end = m if m >= 0 else n
        text, _eof, _ = _inflate(data, pos, end)
        cut = text.rfind(b"\n")
        if cut >= 0:
            out.append(text[:cut + 1])
        if m < 0:
            break
        pos = m
    return b"".join(out)


def _convert(col: str, raw: str):
    raw = raw.strip()
    if raw == "":
        return None
    if col in _STR_COLS:
        return raw
    if col in _INT_COLS:
        return int(raw)
    return float(raw)


def parse_csv(kind: str, text: str) -> list[dict]:
    """Rows as dicts with typed values (None for empty). Skips header / malformed lines."""
    cols = HEADERS[kind]
    header_line = ",".join(cols)
    rows: list[dict] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line == header_line:
            continue
        parts = line.split(",")
        if len(parts) != len(cols):
            continue
        try:
            rows.append({c: _convert(c, v) for c, v in zip(cols, parts)})
        except ValueError:
            continue
    return rows


def read_kind(path: str | os.PathLike, kind: str) -> list[dict]:
    p = Path(path)
    if not p.exists():
        return []
    data = p.read_bytes()
    raw = read_gzip_tolerant(data) if p.name.endswith(".gz") else data
    return parse_csv(kind, raw.decode("utf-8", errors="replace"))


def load_day(dir: str | os.PathLike, day: str) -> dict[str, list[dict]]:
    """``{"spot": [...], "book": [...], "trades": [...], "settle": [...]}`` for one UTC day.

    Missing files give empty lists. Rows keep file order (ascending ts_ms as
    written); ``load_days`` sorts across days.
    """
    base = Path(dir)
    return {kind: read_kind(base / file_name(kind, day), kind) for kind in KINDS}


def list_days(dir: str | os.PathLike) -> list[str]:
    base = Path(dir)
    if not base.is_dir():
        return []
    days = set()
    for f in base.iterdir():
        m = _FILE_RE.match(f.name)
        if m and (m.group(1) == "settle") != bool(m.group(3)):
            days.add(m.group(2))
    return sorted(days)


def load_days(dir: str | os.PathLike, days: list[str] | None = None) -> dict[str, list[dict]]:
    """All (or the given) days merged; time-series kinds sorted by ts_ms, settle de-duplicated."""
    merged: dict[str, list[dict]] = {k: [] for k in KINDS}
    for day in (days if days is not None else list_days(dir)):
        one = load_day(dir, day)
        for k in KINDS:
            merged[k].extend(one[k])
    for k in ("spot", "book", "trades"):
        merged[k].sort(key=lambda r: r["ts_ms"])
    seen: dict[str, dict] = {}
    for r in merged["settle"]:
        if r.get("ticker") and r.get("result") in ("yes", "no"):
            seen[r["ticker"]] = r
    merged["settle"] = list(seen.values())
    return merged


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Summarize a recordings directory")
    ap.add_argument("dir")
    args = ap.parse_args()
    for d in list_days(args.dir):
        one = load_day(args.dir, d)
        print(d, " ".join(f"{k}={len(one[k])}" for k in KINDS))
