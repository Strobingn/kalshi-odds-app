#!/usr/bin/env python3
"""Regenerate the tiny synthetic maker_sim fixture (one UTC day, two markets).

  A  KXBTC15M-FIXA  00:45→01:00  strike 59990  spot ≈ 60000 → YES favoured, settles yes.
     Book: YES 0.50 (qty 10) / 0.53. Taker-NO trades at 0.50: 15 lots at 00:47:10.
  B  KXBTC15M-FIXB  01:00→01:15  strike 60300  spot ≈ 60000 → NO favoured, settles no.
     Book: NO 0.80 (qty 8) / 0.84. Taker-YES trades at yes 0.20: 20 lots at 01:02:20,
     plus a taker-NO print (wrong side for a NO buy) at 01:02:05.

spot file is written as two gzip members to exercise the multi-member reader.
"""
import gzip
from pathlib import Path

HERE = Path(__file__).resolve().parent
DAY = "2026-09-01"
T0 = 1788220800000  # 2026-09-01T00:00:00Z


def ms(h, m, s=0):
    return T0 + ((h * 60 + m) * 60 + s) * 1000


def main():
    spot = ["ts_ms,product,price"]
    for i in range(0, 76 * 60, 5):
        zig = (20.0 if (i // 60) % 2 else -20.0) + (i % 60) / 6.0
        spot.append(f"{T0 + i * 1000},BTC-USD,{60000.0 + zig:.2f}")
    half = len(spot) // 2
    with open(HERE / f"spot_{DAY}.csv.gz", "wb") as f:
        f.write(gzip.compress(("\n".join(spot[:half]) + "\n").encode(), mtime=0))
        f.write(gzip.compress(("\n".join(spot[half:]) + "\n").encode(), mtime=0))

    book = ["ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty"]
    for tk, strike, open_ms, yb, ybq, ya, yaq in (
        ("KXBTC15M-FIXA", 59990, ms(0, 45), 0.50, 10, 0.53, 7),
        ("KXBTC15M-FIXB", 60300, ms(1, 0), 0.16, 9, 0.20, 8),
    ):
        close = open_ms + 900_000
        for t in range(open_ms, close + 1, 5000):
            book.append(f"{t},{tk},{strike},{close},{yb:.2f},{ybq},{ya:.2f},{yaq},{1 - ya:.2f},{yaq},{1 - yb:.2f},{ybq}")
    (HERE / f"book_{DAY}.csv.gz").write_bytes(gzip.compress(("\n".join(book) + "\n").encode(), mtime=0))

    trades = ["ts_ms,ticker,yes_price,count,taker_side",
              f"{ms(0, 47, 10)},KXBTC15M-FIXA,0.50,15,no",
              f"{ms(1, 2, 5)},KXBTC15M-FIXB,0.16,4,no",
              f"{ms(1, 2, 20)},KXBTC15M-FIXB,0.20,20,yes",
              f"{ms(1, 2, 30)},KXBTC15M-FIXB,0.20,5,"]
    (HERE / f"trades_{DAY}.csv.gz").write_bytes(gzip.compress(("\n".join(trades) + "\n").encode(), mtime=0))

    (HERE / f"settle_{DAY}.csv").write_text(
        "ticker,close_ms,strike,result\n"
        f"KXBTC15M-FIXA,{ms(1, 0)},59990,yes\n"
        f"KXBTC15M-FIXB,{ms(1, 15)},60300,no\n"
    )


if __name__ == "__main__":
    main()
