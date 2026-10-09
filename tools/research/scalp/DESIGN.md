# Scalping study design — KXBTC15M — fixed 2026-10-09 before any scalp was scored

Scalp = open and close a position inside the same 15-minute window. Nothing is held to settlement
(one "let it ride" variant is marked as such).

Costs on KXBTC15M: taker fee 0.07 x P x (1 - P) per contract every time an order crosses the spread
(entry and exit both pay it); resting orders pay no fee; the spread is about 1 cent.

## Part A — minute level (45 days of Kalshi 1-minute quote closes + Coinbase 1-minute closes)

- Entry at the close of minute m, m = 2..11. Buy the chosen side at its ask.
- Exit k minutes later, k in {1, 2, 3}, by selling at that side's bid.
- P&L per contract = bid_exit - ask_entry - fee(ask_entry) - fee(bid_exit).
- Also shown, as a ceiling nobody can reach: enter at the bid and exit at the ask with no fee,
  assuming both resting orders always fill.
- Prices 5c..95c only at entry.
- Signals (which side to buy):
  - ALWAYS_FAV   the side priced above 50c (baseline: pure cost)
  - SPOT_MOM1 / SPOT_MOM3   side Bitcoin moved toward over the last 1 / 3 minutes
  - KAL_MOM1     side Kalshi's mid moved toward over the last minute
  - KAL_FADE1    opposite of KAL_MOM1, only when the mid moved 5c or more
  - LAG3 / LAG5  spot-implied fair (digital, 30-bar sigma, 60 s average rule) differs from Kalshi's
                 mid by 3c / 5c or more: buy the side the fair favours
  - JUMP_FOLLOW / JUMP_FADE   Bitcoin's last 1-minute move was 1.5 sigma or more: follow / fade
- 9 signals x 3 holds = 27 cells.
- In sample: 2026-08-24..2026-09-22 (30 days). Out of sample: 2026-09-23..2026-10-08 (16 days).
- The cell with the best in-sample cents per trade (at least 300 in-sample trades) is scored once
  out of sample. It counts only if its out-of-sample 95% day-resampled interval is above zero.
  All 27 out-of-sample rows are printed for context and do not count.

## Part B — second level (21 days of every public trade, about 1,990 windows)

Quotes are rebuilt from the tape on a 1-second grid: bid = last print where the taker sold
(taker_side = no, at yes_price), ask = last print where the taker bought (taker_side = yes),
each at most 10 s old. A resting order is filled
  - "through": only when a later print trades strictly through its price (back of the queue), or
  - "at": on any print at its price (front of the queue).
Decision times: every 5 s from 60 s to 780 s after the open. Entry prices 10c..90c.

Families:
- B1 taker in, taker out. Buy at the ask. Sell at the bid when bid >= entry + T (target),
  or bid <= entry - S (stop), or after H seconds. Fee both ways.
  T in {2, 4}, S in {2, 4}, H in {30, 120}.
- B2 resting in, resting out. Join the best bid for up to 20 s. If filled, rest an offer X cents
  above the entry (X in {1, 2}). If the offer has not filled after H seconds, or the bid falls
  S cents below the entry, sell at the bid and pay the fee. H in {30, 120}, S in {2, 4}.
  Scored under both fill rules.
- B3 resting in, let it ride: join the best bid for up to 20 s; if filled, hold to settlement
  (this is the resting-bid study's rule, kept as the reference line).
Signals for the side: NONE (both sides, independently), MOM10 (side Kalshi's price moved toward
over the last 10 s, 2c or more), FADE10 (the opposite), FLOW (side takers bought 3:1 over the last
30 s, 500+ contracts), FLOWFADE (the other side).
- In sample: first 14 days. Out of sample: last 7 days.
- Per family the best in-sample cell (at least 500 in-sample scalps) is scored once out of sample;
  it counts only if the out-of-sample 99% day-resampled interval is above zero (three families).

## What would count as an edge
A cell that is positive out of sample at the stated level, under the "through" fill rule where a
resting order is involved. The "at" rule and the no-fee ceiling are context: they assume a queue
position or a fill a new order does not get.


---

# ML scalping study design — KXBTC15M — fixed 2026-10-09 before any model was scored

Data: every public trade on 1,992 settled windows, 2026-09-18 .. 2026-10-09 (same tape as Part B), plus
Coinbase BTC-USD 1-minute closes (last completed minute only) and each market's start price.

Rows: every 5 s from 60 s to 780 s after the open, once per side (UP frame and DOWN frame).

Split by UTC day, in time order: TRAIN first 11 days, VALID next 4 days, TEST last 7 days
(the same out-of-sample days as Part B). Nothing in TEST is used to fit or to pick anything.

Six things the model is asked to predict (cents per contract; the row's side):
  T_HOLD        buy at the ask, hold to settlement
  T_SCALP       buy at the ask, sell at the bid at +4c / -4c / 120 s (fee both ways)
  R_SCALP_THR   rest at the best bid 20 s; if filled, offer +1c, stop 4c, 120 s; back of queue fill rule
  R_SCALP_AT    same, front of queue fill rule
  R_HOLD_THR    rest at the best bid 20 s; if filled, hold to settlement; back of queue
  R_HOLD_AT     same, front of queue
Resting targets are per order POSTED (an unfilled order is 0).

Features (all known at the decision second, in the row's side frame): bid, ask, mid, spread, time left;
mid change over 5/10/20/30/60/120/300 s; distance from the window's high/low/first mid; mid volatility
(60 s, 300 s); taker-flow imbalance and volume over 5/10/30/60/120/300 s and since the open; trade count;
largest single print per side (30 s, 120 s); seconds since the last bid / ask print; spot distance from the
start price (bp, signed to the side), spot return over 1/3/5 min, 30-bar sigma, z, spot-implied fair minus
mid; hour of day, weekend.

Model: LightGBM regression, fixed settings (num_leaves 31, learning_rate 0.03, min_data_in_leaf 500,
feature_fraction 0.7, bagging_fraction 0.7 every round, lambda_l2 10, up to 2000 rounds, early stopping
100 on VALID). One model per target. No tuning beyond early stopping.

Policy: act on rows whose prediction is at least theta. theta is the VALID value (from a fixed grid of
prediction quantiles: top 50/30/20/10/5/2/1 %) with the highest VALID total P&L and at least 300 VALID
actions. If no theta has a positive VALID total, the policy is "do not trade" and is reported as that.

Score: once on TEST. Cents per action with 95% and 99% day-resampled intervals. Six targets are tried, so
only a 99% interval above zero counts. Also reported as context: TEST P&L by prediction decile and the
"act on everything" baseline.
