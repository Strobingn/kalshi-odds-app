# A testable edge for the 15-minute crypto app

**Status: research, not a profitable strategy yet.** The existing September 25 replay lost $226.50 over 1,451 out-of-sample app picks, and the blended forecast was less accurate than the market midpoint. The underlying historical cache is absent from this checkout, so those figures cannot be independently rerun here. Do not infer that this change recovers the loss.

## The specific hypothesis

The BTC contract settles against the final-minute average of the CF Benchmarks BRTI, not the last Coinbase trade. Kalshi's authenticated `cfbenchmarks_value` WebSocket reports the running quarter-hour final-minute average, its count, and boundaries. If that number changes the likelihood of YES/NO before the book reprices, an executable quote might occasionally be favorable. The same feed is available to other traders, so a useful quote lag must be **measured**, not assumed. Verify the precise contract rule and benchmark ID on every supported series.

The REST passthrough documentation says benchmark history requires an account entitlement. Kalshi's crypto help article says RTI data is available to all users. Check actual account access and streaming output; these two statements do not establish that the REST history endpoint will work for this account.

## Measurement before trading

1. Record each received benchmark update with local receipt time, upstream timestamp, index ID, quarter-hour window boundaries, running average and `window_size`. Record order-book snapshots and deltas with sequence numbers, source/receipt times, available quantity, both asks, fills, and charged fees. Ignore gaps, stale data, and `window_size=60` because the market may already be closed. Avoid a simulated fill at the same instant as a quote that preceded the signal.
2. At count `k<60`, define `S_k=k×running_average`. For strike `K`, the mean of the remaining `60−k` observations must exceed `(60K−S_k)/(60−k)` to settle above the unrounded strike. Apply that market's documented rounding and equality rules. A probabilistic model still needs to estimate the *unknown remaining average*; the current running average is not a final outcome.
3. For each available side and desired size, compute `p(side)−average_executable_ask−actual_order_fee/filled_contracts−latency/slippage_buffer`. Use displayed depth, not a midpoint or a stale cheapest ask. Compare taker and resting-maker approaches on *realistic fills* separately. Include quotes that disappear and orders that never fill.
4. Walk forward by day with complete markets isolated between training and validation. Measure realized dollars after fees, fill rate, calibration, worst drawdown and a confidence interval clustered by market/day. Freeze entry rules before the final holdout. Live-paper test with receipt-time data before placing money at risk. A positive backtest with a confidence interval crossing zero does not establish an edge.

## Change in this branch

Automatic hunter and configured ticket suggestions now compare YES and NO against their respective buy asks and the fee of the actual $5 clip. They abstain unless modeled net value is **more than 3 percentage points per contract**. This provisional 3-point buffer is a safety margin, not a measured optimum and not proof that the current model is right. The ticket's expected-value display is recomputed at the ticket price; previously it could show an earlier score calculated at another price. Manual tickets remain possible by explicit user action.

This does not yet capture benchmark data, measure real latency, prove calibration, guarantee execution, or justify automatic live trading. Next engineering work is a timestamped feed/book recorder and chronological replay. Do not optimize a larger AI model against today's midpoint-only replay and call its accuracy profit.

Sources: [Kalshi crypto settlement explanation](https://help.kalshi.com/en/articles/13823838-crypto-markets), [CF WebSocket fields and averaging semantics](https://docs.kalshi.com/websockets/cfbenchmarks-value), [REST passthrough access/history](https://docs.kalshi.com/cfbenchmarks/rest-passthrough), [fee schedule](https://kalshi.com/docs/kalshi-fee-schedule.pdf), [repo backtest](backtest-2026-09-25.md).
