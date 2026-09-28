# Looking for a way to make money — 2026-09-28

Question: is there a strategy on Kalshi 15-minute BTC / ETH / SOL markets that
makes money **on days it was not tuned on**? Data: the backtest cache
(7,968 settled markets, 2026-08-31 → 09-28, Kalshi 1-minute candles, Coinbase
1-minute spot). Fills at the candle's close ask, Kalshi taker fee, $10 all-in.
CIs are bootstrap; "day-block" resamples whole days.

**Answer: nothing tested passes.** The market is well calibrated at every
price and every time to close; taker losses are roughly spread + fee.

## 1. Market calibration, model-free

Every side's close ask, bucketed by price and seconds to close. Win rate tracks
the ask within ~1¢ everywhere. Buying final-minute longshots loses:

| 60 s before close | Bets | Win | Avg ask | P&L |
|---|---:|---:|---:|---:|
| ≤ 1¢ | 5,361 | 0.30% | 0.35¢ | −$6,604 |
| 2¢ | 436 | 2.98% | 1.95¢ | +$1,793 (day-block CI includes 0) |
| 5–7¢ | 302 | 4.30% | 5.82¢ | −$888 |
| 50–74¢ | 643 | 59.4% | 62.4¢ | −$484 (CI below 0) |
| 90–99¢ | 5,093 | 98.5% | 98.7¢ | −$182 |

One of ~45 cells had a day-block CI above zero (8–12¢ asks at 120 s:
549 bets, +$1,746). Its neighbours at 60 s and 180 s lose; with this many
cells a false positive or two is expected. Not trusted.

## 2. Last-minute rule (60 s settlement average), 3 coins, walk-forward

`fair_p` from `research/last_minute`, evaluated at the candle 60 s / 120 s
before close; k and margin chosen on the previous 14 days, tested on the next
3, rolled (test days 09-14 → 09-28). Control = same thing but pricing a
single-print settlement instead of the 60 s average.

| Model | τ | Bets | Win | Avg ask | P&L | +days | $/bet 95% CI |
|---|---|---:|---:|---:|---:|---|---|
| 60 s average | 60 s | 1,564 | 8.6% | 8.6¢ | +$19,625 | 3/15 | [−2.74, +32.93] |
| point (control) | 60 s | 1,736 | 4.7% | 5.4¢ | +$16,958 | 3/15 | [−3.91, +27.83] |
| 60 s average | 120 s | 2,071 | 7.1% | 6.6¢ | +$736 | 4/15 | [−3.78, +6.22] |
| point (control) | 120 s | 2,396 | 6.3% | 5.7¢ | −$1,617 | 3/15 | [−4.22, +4.55] |

Profit comes from a few cheap tickets on 3 of 15 days, the CI includes zero,
and the naive control does almost as well — so at 1-minute resolution the
averaging insight is not what earns it. The original research's positive
result used 1-second BRTI and trade prints, which this cache cannot
reproduce. **Unverified, not disproven.** It stays in the app, BTC only, and
now paper-trades with Kelly sizing so it builds a live record.

## 3. Passive market making (limit orders, no maker fee on these series)

Rest a 1-contract bid at the best bid (or 1¢ better) each minute; count it
filled only if trades go *through* the bid in the next minute.

| | Fills | ¢ per contract | Day-block CI (total) |
|---|---:|---:|---|
| Join bid, in-sample | 15,897 | −1.45 | [−$278, −$182] |
| Join bid, out-of-sample | 7,286 | −1.65 | [−$172, −$67] |
| Improve 1¢, out-of-sample | 8,027 | −1.02 | [−$109, −$55] |

Adverse selection beats the spread: bids fill when price is falling through
them. (Fills at the touch without a trade-through would do better, but
candles cannot show queue position.)

## 4. Models (from `ml-changes-2026-09-27.md`)

The market-offset edge model on 103k decision minutes: held-out log-loss
0.4634 vs market 0.4620 (gain CI [−0.0027, +0.0001]); more regularization
converges to the market with no trades.

## What would be needed

- **Sub-second data.** The only plausible structural edges here (latency after
  spot crosses the strike; the settlement-average mechanics in the final
  minute) live below 1-minute resolution. Record 1-second Kalshi book +
  trades + BRTI-proxy spot for several weeks, then re-test sections 2 and 3.
- **Real queue data** for any maker strategy.
- Until something passes out of sample with a CI above zero, the paper book
  (Kelly-sized) is the honest way to watch a strategy live.
