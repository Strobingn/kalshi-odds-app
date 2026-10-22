# Fast entry model — v1.5.0

This is an experimental local PAPER entry filter, learning whether an executed scalp
will finish with positive net PnL. It does not forecast settlement as a substitute
for executable exit prices. No weights or profitable results are shipped or invented.

The default is shadow learning. Settings → Use validated entry filter requests
filtering for the 35/70/100% allocation books. The 15% books remain unfiltered
controls under policy 3, including the existing rule-based feedback/cooldown.
The adaptive 15% copy is excluded from training to avoid duplicated signals.
The classifier never modifies a position's protective stop, target or exit latency.
Fixed +8¢/−5¢ and a mandatory early liquidation from the research brief were not
adopted: this branch retains its existing adaptive exits and official settlement
fallback for an unfilled position. The monitor still must run to simulate stops.

## Features and labels

Twenty fixed-transform features: time left, entry ask, 10/30s side-bid returns,
30s bid-step RMS, spread, top-three depth imbalance, recovery from the recent low,
four setup indicators, and four optional spot features with separate presence
masks (spot/strike distance, 1m/5m returns, 15m volatility).

Features are frozen at the actual later-book paper fill, not after the result.
Book observations use the existing one-second as-of grid, rejecting history gaps.
The first 30s of continuous history is needed to record a model example; existing
baseline entries can still occur earlier. External spot comes from the app's
existing feed. It must have been fetched at/before the book and within 90s, and is
explicitly an exchange proxy, not CF BRTI. Missing or stale values have zero masks.
No synchronous network call, LLM, tree library or transformer runs in the entry loop.

One completed control trade per setup/side/ticker is retained (first close from
that sequential control book), regardless of quantity. Net label includes all
entry/exit fees and partial fills, or official settlement remainder. Legacy
positions and featureless old results do not become fabricated examples. The
latest 600 examples persist in the ledger and replay checkpoints, separated by
production/demo. Busy windows receive equal aggregate weight during training.

A deterministic 70-step, L2-regularized logistic fit refreshes at most every 15s,
after the quote's exits and fills have run. It trains only after 30 eligible
windows. Labels are eligible only after BOTH the contract close and outcome time,
plus a 90s embargo. Inference uses 21 coefficients; no Android latency guarantee
is inferred from third-party research. Compute is bounded by 600 observations.

## Forward selection tests

Require at least 60 eligible windows. The last 30 are divided chronologically into
three ten-window test blocks; each fit uses only earlier, purged, embargoed windows
and needs at least 30 training windows. No rows from the same ticker cross a split.
No test outcome affects that fold's coefficients, mean win or mean loss.

Selection requires P(net win) >= 0.55 AND estimated net >= 1¢/contract using
training-only average wins and losses. Rejected opportunities contribute zero;
both arms use the same per-window opportunity denominator. The UI compares mean
net cents per contract per window and shows 10+/5–10/<5 minute entry regimes.
The optional filter activates only if 30 test windows, ten selected windows,
and mean minus two standard errors are positive for BOTH selected PnL and its
increment over baseline. It reverts to shadow if this retained-data gate fails.
Missing features also leave baseline selection in place.

These are overlapping rolling selection tests of recorded control trades, not
fresh independent trials or a full counterfactual portfolio replay. Changing
entries changes cash, cooldowns and opportunities; different allocation books
also face different fee rounding and depth. The test does not establish a live
edge, guarantee calibration or justify comparing differently sized books as a
randomized A/B trial. It evaluates only opportunities the controls actually
executed. Selection bias, window correlation and concept drift remain limitations.
Fixed features/hyperparameters are versioned in source; future tuning should use
new untouched windows, not optimize repeatedly on these test blocks.

## Reproducibility and offline use

Qualified entry signals log frozen features, probability (null until trained),
model-fit timestamp, selection, validation gate and requested mode in book events.
Fill-time features/predictions follow positions into final results. All observed
books remain available for replay; mode changes are explicit replay events.
Production engine replay regenerates training/filtering and checks every ledger.

Export the replay ZIP from the paper lab, then:

```
python3 tools/quant/export_examples.py BitcoinEdge-paper-replay.zip observed-examples.csv
```

The CSV contains observed examples only, with ticker groups and all causal
training-cutoff timestamps. Separate sources, purge/embargo by ticker, and replay
the complete execution policy before promoting a different model. No L2 history
was supplied in this chat, so the observed-file replay test remains skipped until
an actual export is available. Unit fixtures test causality and accounting only.

## Official market facts checked 2026-10-22

- Kalshi's crypto help describes the 15m comparison as final-minute average versus
  pre-open-minute average. Specific contract terms govern settlement:
  https://help.kalshi.com/en/articles/13823838-crypto-markets
- Current general fees include series multipliers, with different maker defaults:
  https://kalshi.com/docs/kalshi-fee-schedule.pdf (effective July 7, 2026)
- Fee accumulation and balance precision are described here:
  https://docs.kalshi.com/getting_started/fee_rounding

The paper engine retains its conservative whole-cent fee per walked price level;
it does not claim to reproduce the newer exchange fee accumulator/rebates. Maker
queue position and resting fills are not simulated. Volume/spread medians, market
efficiency, 73% model accuracy and sub-5ms phone inference in the supplied brief
have not been verified on the app's data and are not performance assumptions.
