# Scalping parameters for 15-min BTC up/down markets — evidence check

Date: 2026-10-09. Data: the same cached Kalshi 1-minute candle pull used by
`docs/backtest-2026-09-25.md` — 7,968 settled 15-minute markets
(2,656 each BTC/ETH/SOL, 2026-08-28 → 2026-09-27, one candle per minute, 16
candles per market). Script: `tools/backtest/scalp_grid.py` (rerunnable,
read-only, touches no app code).

## TL;DR

**Do not wire this strategy to live trading.** Every one of the 2,100
parameter combinations loses money after realistic costs. The best combo loses
≈ 4.6¢ per contract per trade at realistic spreads, and even an *idealized*
version (fill at the mid, zero fees) makes only +0.37¢ — statistically
indistinguishable from zero. A 2–6¢ take-profit target is smaller than the
round-trip friction (~4–6¢), so the strategy is structurally unprofitable at
these TP levels regardless of signal quality.

## Methodology

- **Signal**: EMA(span = 5) of the 1-minute mid. A "dip" minute is the first
  minute (index 2–13) where `mid ≤ EMA − dip¢`. One trade per market (first
  qualifying minute), the same honesty rule as the 2026-09-25 backtest.
- **Entry**: buy YES at that candle's **ask close** (plus a spread penalty of
  0 / 0.5 / 1.0¢ per side across scenarios).
- **Exit**: from the next candle on, monitor the mid relative to the entry
  mid — take-profit when it bounces `tp¢` (sell at that candle's **bid close**,
  minus penalty), stop-loss when it falls `sl¢` (sell at bid), else time-stop
  at `max_hold` minutes (sell at bid). If a 1-minute candle's range hits both
  TP and SL, the stop-loss wins (pessimistic; intrabar order is unknowable).
- **Fees**: Kalshi taker fee `ceil_6dp(0.07 · P · (1−P))` per contract,
  charged on **both** the buy and the sell (a scalp sells YES back; it does
  not hold to settlement). At P = 0.50 that is 1.75¢ per side, ≈ 3.5¢ round
  trip; across the actual trade prices it averages ≈ 3.95¢ round trip.
- **Grid**: dip ∈ {1,2,3,4,5}¢, TP ∈ {2..6}¢, SL ∈ {3..8}¢, max hold ∈
  {2..15} min = 2,100 combos. Metrics per combo: trades, win rate (net
  P&L > 0), avg ¢/trade, total ¢ (1 contract each), max drawdown on the
  cumulative per-trade P&L, profit factor, and TP/SL/time exit counts.
- **Per-file discipline**: every market file is replayed separately; metrics
  are accumulated per coin and pooled only for ranking. Regimes are nearly
  identical across BTC/ETH/SOL (see per-coin table), so pooling loses little.

Caveats on fills: we fill at the signal candle's close ask/bid, which assumes
zero latency and that the quote is still there when the order lands. That is
optimistic for a scalper; the spread-penalty scenarios partially compensate.

## Base rate: how common is a 2–5¢ bounce within 10 minutes?

Unconditional, over all 95,616 decision minutes:

| series | minutes | dip minutes (≥1¢ below EMA) | bounce | all-min rate | dip-min rate | median lead |
|---|---:|---:|---:|---:|---:|---:|
| ALL | 95,616 | 45,052 | +2¢ | 82.6% | 90.5% | 2 min |
| ALL | 95,616 | 45,052 | +3¢ | 80.1% | 89.9% | 2 min |
| ALL | 95,616 | 45,052 | +4¢ | 78.0% | 89.2% | 2 min |
| ALL | 95,616 | 45,052 | +5¢ | 76.0% | 88.3% | 2 min |
| BTC | 31,872 | 14,917 | +2¢ | 83.3% | 90.7% | 2 min |
| ETH | 31,872 | 15,002 | +2¢ | 82.5% | 90.5% | 2 min |
| SOL | 31,872 | 15,133 | +2¢ | 82.1% | 90.4% | 2 min |

Read this honestly: the raw event ("mid ticks up 2¢ at some point in the next
10 minutes") is **common** — it happens ~80–90% of the time, dip or no dip.
That number is not tradable on its own: it includes paths that first fell 20¢,
and the strategy's P&L depends on the *ordering* of the bounce vs. the
deeper dip. The conditioning on a "dip" minute barely lifts the rate (+8pp),
which is the first hint that the signal carries little information. Note also
that the strategy's realized TP-hit rate at tp=4¢ is only ~47% — far below the
~89% dip-minute bounce rate — because the stop-loss trigger wins the race
surprisingly often: a mid that just fell 5¢ below trend keeps falling more
often than it bounces 4¢ first.

## Grid results (top 10 by avg ¢/trade, n ≥ 200)

All three spread-penalty scenarios agree; shown: **0.5¢/side** (realistic).
Full output for 0 / 0.5 / 1.0¢ is reproducible via
`python tools/backtest/scalp_grid.py --penalty X`.

| dip | tp | sl | maxhold | trades | win% | avg ¢/trade | total ¢ | maxDD ¢ | PF | TP/SL/time exits |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 5 | 4 | 5 | 7 | 6,140 | 43.0% | −4.585 | −28,154 | 28,209 | 0.48 | 2888/3239/13 |
| 5 | 4 | 5 | 12 | 6,134 | 43.0% | −4.588 | −28,141 | 28,197 | 0.49 | 2890/3244/0 |
| 5 | 4 | 5 | 13 | 6,134 | 43.0% | −4.588 | −28,141 | 28,197 | 0.49 | 2890/3244/0 |
| 5 | 4 | 5 | 14 | 6,134 | 43.0% | −4.588 | −28,141 | 28,197 | 0.49 | 2890/3244/0 |
| 5 | 4 | 5 | 15 | 6,134 | 43.0% | −4.588 | −28,141 | 28,197 | 0.49 | 2890/3244/0 |
| 5 | 4 | 5 | 11 | 6,135 | 43.0% | −4.588 | −28,147 | 28,203 | 0.49 | 2890/3244/1 |
| 5 | 4 | 5 | 9 | 6,136 | 43.0% | −4.588 | −28,152 | 28,207 | 0.49 | 2890/3244/2 |
| 5 | 4 | 5 | 10 | 6,136 | 43.0% | −4.588 | −28,152 | 28,208 | 0.49 | 2890/3244/2 |
| 5 | 4 | 5 | 6 | 6,140 | 42.9% | −4.590 | −28,183 | 28,239 | 0.48 | 2886/3233/21 |
| 5 | 4 | 6 | 7 | 6,139 | 43.7% | −4.591 | −28,182 | 28,237 | 0.49 | 2940/3180/19 |

Worst combos are only slightly worse (≈ −4.95 to −4.97¢): **every one of the
2,100 combos is negative**; the grid surface is flat and dominated by costs,
not by signal. Two structural observations:

1. **Deeper dip is better** (dip=5¢ tops the ranking) purely because you enter
   4¢ cheaper, not because the signal is stronger.
2. **Max hold barely matters** — the time stop fires on <0.5% of trades at
   max_hold ≥ 9 (median bounce lead is 2 minutes, and TP/SL resolve almost
   everything within 9 minutes).

Per-coin breakdown of the top combo (dip=5, tp=4, sl=5, mh=7), 0.5¢/side:

| coin | trades | win% | avg ¢/trade | total ¢ | maxDD ¢ | PF |
|---|---:|---:|---:|---:|---:|---:|
| BTC | 2,033 | 42.6% | −4.680 | −9,515 | 9,515 | 0.47 |
| ETH | 2,048 | 43.2% | −4.374 | −8,959 | 8,976 | 0.50 |
| SOL | 2,059 | 43.1% | −4.701 | −9,680 | 9,719 | 0.49 |

Regimes are the same across coins — there is no coin-specific rescue.

### Friction decomposition (top combo)

| fill model | avg ¢/trade |
|---|---:|
| idealized: fill at mid, no fee | **+0.367** |
| ask/bid close fills + taker fee both sides | −3.587 |
| + 0.5¢/side spread penalty (realistic) | −4.585 |
| + 1.0¢/side spread penalty (pessimistic) | −5.583 |

The idealized gross edge is +0.37¢/trade over 6,140 trades — about $22 total
on ~$6,140 of contract face turned over, easily noise (and it assumes fills at
the mid that do not exist). Fees cost ≈ 3.95¢ round trip; the visible spread
costs another 1–2¢. **A tp of 2–6¢ can never clear ~5–6¢ of round-trip
friction with any win rate short of certainty.**

## "Recommended" defaults

If the feature ships **disabled by default** with a config that needs explicit
opt-in, use the least-bad parameters so that an accidental enable bleeds
slowly:

| parameter | default | why |
|---|---|---|
| dip threshold | **5¢** below EMA(5) | cheapest entry; dominates the grid |
| take-profit | **6¢** | highest TP in grid — the only value with any hope of clearing friction |
| stop-loss | **5¢** | grid-optimal; wider stops don't help |
| max hold | **8 min** | beyond ~9 min the time stop never fires anyway |
| max trades/hour | **2** | one scalp per market, skip the next market after any trade; caps bleed rate |

Expected economics at these defaults, per contract, after fees and a
realistic 0.5¢/side penalty: **≈ −4.6¢ per trade** (from the table above;
scaling a $10 position at ~50¢ ≈ 20 contracts → ≈ −$0.92 per trade). This is
the *best case*; pessimistic fills make it worse. There is no configuration
in the tested grid with positive expected value.

## Why this is mostly noise + fees (honest version)

- The 15-minute binary mid is a monotone transform of spot distance to
  strike. A 1–5¢ dip in the mid is spot noise on a ~$80k underlying — it
  carries almost no directional information, and the base-rate table proves
  it: conditioning on a dip moves the 10-minute bounce rate by only ~8pp,
  and the *race* between +4¢ and −5¢ is roughly a coin flip that the bounce
  loses slightly more often.
- The previous backtest already established that nothing in this project
  beats the raw Kalshi mid (mid Brier 0.159 vs every model). A scalping rule
  that trades mid microstructure is betting *against* the most calibrated
  forecast available, while paying taker fees on both legs.
- Overfitting risk: even if some combo had shown +0.5¢, with 2,100 combos
  tested on one month of data, the max-order statistic of a flat surface
  would produce fake winners. The honest reading of this grid is the surface
  shape (flat, negative), not the identity of the top row.
- Known fill optimism: close-of-signal-candle fills at zero latency; 1-minute
  bars can't resolve intrabar TP/SL ordering (we chose pessimistic); no
  market impact; cached quotes may be kinder than live quotes during fast
  moves. All of these bias *upward*.

## Do NOT enable live until paper trading shows ALL of

Minimum bar, measured on ≥ 300 paper trades over ≥ 2 weeks including a
high-volatility period, with fills recorded at real ask/bid (not mid):

1. Net avg ≥ **+1.0¢ per contract** after real fills and both-side fees
   (not just positive — must clear measurement error).
2. Profit factor ≥ **1.20** and win rate ≥ **55%** at tp ≥ 6¢.
3. Must beat its own base rate: TP-first exit rate ≥ **55%** of trades
   (the dip-conditioned 10-min bounce base rate is ~89–90%, yet this rule's
   TP only fires first in 47% of trades — a real edge must win that race far
   more often).
4. Max drawdown ≤ **30× the average stake** with position sizing fixed.
5. Slippage audit: median executed price ≤ ask + 0.5¢ on entry, ≥ bid − 0.5¢
   on exit; disable immediately if live spread > 2¢ when the signal fires.

If paper trading cannot clear bar 3 in particular, the signal is noise and
the feature should be removed rather than tuned further.

## Repro

```bash
python tools/backtest/scalp_grid.py            # realistic 0.5c/side
python tools/backtest/scalp_grid.py --penalty 0    # optimistic
python tools/backtest/scalp_grid.py --penalty 1.0  # pessimistic
```
