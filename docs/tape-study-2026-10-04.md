# Trade-tape study — Kalshi 15m BTC (KXBTC15M), 2026-10-04

Question: is there any way to bet this market with a positive expectation?

**Answer from this data: no.** Buying at the ask loses about the fee. Resting
orders earn a small amount on average, but only at the front of the queue, and
a new order normally joins behind a few thousand contracts.

This uses Kalshi's public `GET /markets/trades`, which returns every trade with
the taker's side. `docs/maker-research.md` assumed no historical taker-side tape
existed and was waiting on weeks of recordings; this answers the first-order
question now. Tool: `tools/research/tape_study.py`.

## Data

| | |
|---|---|
| Markets | 952 settled windows, 2026-09-24 → 2026-10-04 (11 UTC days) |
| Trades | 33,321,367 |
| Contracts | 2,573M |
| Taker spend | $1,226M |
| Taker fees | $21.6M (0.84¢ per contract) |

All figures are cents per contract, held to settlement. CIs are a
market-cluster bootstrap (952 clusters), 95%.

## 1. Takers lose the fee, makers earn a sliver

| | ¢ per contract | 95% CI |
|---|---:|---|
| Taker before fee | −0.17 | |
| Taker fee | −0.84 | |
| **Taker net** | **−1.01** (−2.1% ROI) | [−1.14, −0.87] |
| **Maker** (fee taken as 0) | **+0.17** | [+0.03, +0.31] |

Takers lost on all 11 days (−0.81¢ to −1.19¢). Makers were positive on 10 of 11.

## 2. Where takers lose most

By time left:

| Time left | Taker net | 95% CI | Taker ROI | Maker | 95% CI |
|---|---:|---|---:|---:|---|
| 10–15 min | −2.48 | [−2.99, −1.98] | −5.2% | +0.96 | [+0.45, +1.46] |
| 6–10 min | −1.27 | [−1.60, −0.93] | −2.7% | +0.13 | [−0.20, +0.47] |
| 3–6 min | −0.64 | [−0.95, −0.35] | −1.3% | −0.14 | [−0.44, +0.17] |
| 1–3 min | −0.11 | [−0.29, +0.04] | −0.2% | −0.29 | [−0.45, −0.13] |
| < 1 min | −0.28 | [−0.44, −0.14] | −0.8% | +0.13 | [+0.00, +0.28] |

The first five minutes are where retail flow pays the most. In the last three
minutes takers are better informed than the resting quotes (makers lose), but
the fee still leaves takers at or below zero.

By trade size:

| Contracts per trade | Taker net | 95% CI |
|---|---:|---|
| < 10 | −1.52 | [−1.70, −1.35] |
| 10–100 | −1.48 | [−1.68, −1.28] |
| 100–1k | −1.19 | [−1.36, −1.02] |
| 1k–10k | −0.51 | [−0.67, −0.34] |
| > 10k | −0.03 | [−0.30, +0.21] |

By price the taker paid: buying the underdog is worse than buying the favorite,
and neither is positive.

| Price paid | Taker net | Taker ROI |
|---|---:|---:|
| 0–5¢ | −0.25 | −25.2% |
| 5–10¢ | −0.82 | −11.3% |
| 10–20¢ | −1.34 | −8.8% |
| 20–30¢ | −2.48 | −10.0% |
| 30–40¢ | −2.72 | −7.8% |
| 40–50¢ | −3.54 | −7.9% |
| 50–60¢ | −0.57 | −1.0% |
| 60–70¢ | −0.77 | −1.2% |
| 70–80¢ | −0.85 | −1.1% |
| 80–90¢ | −0.62 | −0.7% |
| 90–95¢ | −0.45 | −0.5% |
| 95–100¢ | +0.04 [−0.44, +0.47] | +0.0% |

Only the 30–40¢ and 40–50¢ rows have a CI that excludes zero; the price buckets
are noisy because each window has one outcome.

## 3. Resting orders: the edge is the queue position

Simulation: every 30 s, join the best bid on YES and on NO, cancel after 30 s,
hold fills to settlement, no fee. Prices 5–95¢. 40,882 orders per row.

| Queue ahead of the order | Fill % | ¢ per fill | 95% CI |
|---|---:|---:|---|
| None (front of queue) | 98.2 | +0.29 | [+0.24, +0.34] |
| 500 contracts | 92.4 | −0.35 | [−0.47, −0.23] |
| 2,000 contracts | 87.0 | −0.88 | [−1.07, −0.70] |
| 10,000 contracts | 80.8 | −1.52 | [−1.77, −1.28] |
| Filled only when a print trades through | 79.6 | −1.61 | [−1.87, −1.33] |

Live order book, one window sampled every ~2 s on 2026-10-04 17:45–17:54 UTC
(303 mid-priced snapshots):

- size at the best bid: median 3,543 contracts, quartiles 1,589 and 5,283;
- under 500 contracts in 8% of snapshots;
- spread exactly 1¢ in every snapshot, so an order cannot be placed inside it.

A new resting order therefore sits in the −0.9¢ to −1.5¢ rows. The +0.29¢ row
belongs to whoever is already first in line.

## 4. Other checks

- **Hourly ladder, same settlement value.** A window that closes on the hour
  settles on the same 60 s BRTI average as the hourly `KXBTCD` "above K"
  markets, so the two must be consistent. 236 windows, 3,219 one-minute
  snapshots: a gap after taker fees on both legs in 7 snapshots, largest 2.7¢,
  five of them in minutes 13–14.
- **Streaks.** 6,419 windows since 2026-07-29: UP 50.05% (±1.22pp). After UP
  49.55%, after DOWN 50.48%, after DOWN-DOWN 53.19% (±2.46pp). Break-even for
  a taker at a 50¢ ask is 51.75%. No hour of day is off 50% by more than
  chance (max |z| 1.71 over 24 hours).
- **Incentives.** None of the ~5,900 active Kalshi incentive programs is on
  KXBTC15M.

## Limits

- 11 days. Good for the totals and the time/size splits, weak for single price
  buckets.
- Maker fee is taken as 0: the series reports `fee_type: quadratic`,
  `fee_multiplier: 1`, and the standard schedule has no maker fee. Check a real
  fill before relying on it.
- Taker fee is the unrounded `0.07·C·P·(1−P)`; per-fill rounding only makes it
  larger.
- The simulation's bid and ask are the last prints (≤ 10 s old), not the book.
  Queue sizes are assumptions, and cancels ahead of the order are ignored.
- The order-book sample is one window on a Sunday afternoon.

## What this means for the app

1. A ticket that buys at the ask needs the model to beat the market by more
   than the fee. `docs/ml-review-2026-09-27.md` shows no model here beats the
   mid, and this tape shows takers as a group do not either.
2. Entering in the first five minutes is the costliest thing a taker does
   (−5.2% ROI). The entry filter already blocks the first three.
3. A maker strategy has to show it can fill near the front of the queue. Run
   `maker_sim.py` on the recorder's book files and compare its conservative
   model with the table in section 3 before any paper test.

## In the app (1.3)

- The REAL MONEY sheet shows each buy's break-even win rate, fee share and the
  two tables above for its price and time left (`TakerCost`).
- Live buys stop at a daily cap, default $50 (`LiveDailyCap`).

Neither changes the expectation of a bet. They show the cost and bound it.

## Repro

```
pip install numpy pandas tabulate
python3 tools/research/tape_study.py pull --days 10 --dir tape     # ~35k requests, ~20 min
python3 tools/research/tape_study.py report --dir tape
python3 tools/research/tape_study.py makersim --dir tape
python3 tools/research/tape_study.py hourly --dir tape
python3 tools/research/tape_study.py streaks --days 120
```
