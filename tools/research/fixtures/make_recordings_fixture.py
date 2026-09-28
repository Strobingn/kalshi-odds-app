#!/usr/bin/env python3
"""Regenerate the tiny synthetic recording in fixtures/recordings/ (deterministic).

Two UTC days, one KXBTC15M market each, 240 s of data from 12:00:00Z:
  * BTC spot: seeded 1 s random walk (sigma 3 bp) with a 0.4 % jump at
    t = 100 s (up on day 1, down on day 2), one print per second.
  * Kalshi book: YES mid = digital fair of spot **3 s earlier**, rounded to
    1c, bid/ask = mid -/+ 1c; rows on change at most 1/s plus a 5 s
    heartbeat, like the app.
  * Trades: a few prints. Settle: day 1 yes, day 2 no.
The day-1 spot file is written as the app can leave it after a crash: a
sync-flushed member with no trailer, then a new complete member.

    python3 tools/research/fixtures/make_recordings_fixture.py
"""
from __future__ import annotations

import gzip
import math
import random
import sys
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "backtest"))
from pipeline import p_finish_above, sigma_annual_from_bar_std  # noqa: E402

OUT = HERE / "recordings"
LAG_S = 3
SIGMA_1S = 0.0003
SECONDS = 240
JUMP_AT = 100
DAYS = [
    # day, epoch s of 12:00Z, ticker, jump sign, result
    ("2026-09-26", 1790424000, "KXBTC15M-26SEP260815-15", +1, "yes"),
    ("2026-09-27", 1790510400, "KXBTC15M-26SEP270815-15", -1, "no"),
]
SPOT_HEADER = "ts_ms,product,price"
BOOK_HEADER = ("ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,"
               "no_bid,no_bid_qty,no_ask,no_ask_qty")
TRADES_HEADER = "ts_ms,ticker,yes_price,count,taker_side"
SETTLE_HEADER = "ticker,close_ms,strike,result"


def num(x: float) -> str:
    s = f"{x:.6f}".rstrip("0").rstrip(".")
    return s if s else "0"


def spot_path(t0: int, sign: int, rng: random.Random) -> list[float]:
    px = [65000.0]
    for s in range(1, SECONDS):
        r = rng.gauss(0.0, SIGMA_1S)
        if s == JUMP_AT:
            r += sign * 0.004
        px.append(px[-1] * math.exp(r))
    return px


def gz(text: str) -> bytes:
    return gzip.compress(text.encode(), mtime=0)


def gz_truncated(text: str) -> bytes:
    """Gzip header + sync-flushed deflate data, no final block / trailer."""
    c = zlib.compressobj(6, zlib.DEFLATED, 16 + zlib.MAX_WBITS)
    return c.compress(text.encode()) + c.flush(zlib.Z_SYNC_FLUSH)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*"):
        old.unlink()
    rng = random.Random(7)
    sigma_annual = sigma_annual_from_bar_std(SIGMA_1S * math.sqrt(60.0))
    for day, t0, ticker, sign, result in DAYS:
        close_ms = (t0 + 900) * 1000
        strike = 65000.0
        px = spot_path(t0, sign, rng)
        spot_lines = [f"{(t0 + s) * 1000 + 37},BTC-USD,{num(round(p, 2))}" for s, p in enumerate(px)]
        book_lines: list[str] = []
        last_top = None
        last_ts = None
        for s in range(SECONDS):
            src = px[max(0, s - LAG_S)]
            fair = p_finish_above(src, strike, 900.0 - s, sigma_annual)
            mid = min(0.97, max(0.03, round(fair, 2)))
            bid, ask = round(mid - 0.01, 2), round(mid + 0.01, 2)
            top = (bid, ask)
            ts = (t0 + s) * 1000 + 500
            if last_top is None or (top != last_top and ts - last_ts >= 1000) or ts - last_ts >= 5000:
                book_lines.append(
                    f"{ts},{ticker},{num(strike)},{close_ms},{num(bid)},25,{num(ask)},12,"
                    f"{num(round(1 - ask, 2))},12,{num(round(1 - bid, 2))},25"
                )
                last_top, last_ts = top, ts
        trade_lines = [
            f"{(t0 + 50) * 1000 + 120},{ticker},0.5,3,yes",
            f"{(t0 + JUMP_AT + 2) * 1000 + 800},{ticker},{'0.55' if sign > 0 else '0.45'},10,{'yes' if sign > 0 else 'no'}",
            f"{(t0 + 200) * 1000 + 5},{ticker},0.6,1,",
        ]
        if day == DAYS[0][0]:
            half = len(spot_lines) // 2
            data = (gz_truncated(SPOT_HEADER + "\n" + "\n".join(spot_lines[:half]) + "\n")
                    + gz("\n".join(spot_lines[half:]) + "\n"))
        else:
            data = gz(SPOT_HEADER + "\n" + "\n".join(spot_lines) + "\n")
        (OUT / f"spot_{day}.csv.gz").write_bytes(data)
        # Book: two complete members (an app restart mid-day).
        cut = len(book_lines) // 3
        (OUT / f"book_{day}.csv.gz").write_bytes(
            gz(BOOK_HEADER + "\n" + "\n".join(book_lines[:cut]) + "\n") + gz("\n".join(book_lines[cut:]) + "\n")
        )
        (OUT / f"trades_{day}.csv.gz").write_bytes(gz(TRADES_HEADER + "\n" + "\n".join(trade_lines) + "\n"))
        (OUT / f"settle_{day}.csv").write_text(f"{SETTLE_HEADER}\n{ticker},{close_ms},{num(strike)},{result}\n")
    print(f"wrote {sorted(p.name for p in OUT.iterdir())}")


if __name__ == "__main__":
    main()
