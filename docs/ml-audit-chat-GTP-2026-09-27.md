# ML and trading-logic audit — `chat-GTP`

The September 25 backtest reports that the existing blended forecast scored
worse than the Kalshi midpoint (Brier 0.1610 vs 0.1592 on 103,597 decision
minutes). Its first qualifying app pick lost $226.50 on 1,451 out-of-sample
paper entries using the minute-close ask. These are results reported by the
repository, not a fresh reproduction: the full historical cache is not
committed. In particular, these figures do **not** test this branch's revised
weights or the newly repaired edge trainer. No positive live trading edge is
established.

## Fixed on this branch

1. The offline edge trainer no longer substitutes 240 synthetic examples when
   its public-data fetch fails or yields too few rows. Fixture manifests say
   `synthetic_fixture`; the app rejects synthetic and older unprovenanced
   release manifests. A previously downloaded release with an old manifest is
   not loaded automatically at next app start. Explicit manual JSON imports
   remain user-controlled.
2. The trainer's illustrative P&L now charges a NO pick at `1 − YES mid`.
   Previously it used the YES midpoint for either side, artificially inflating
   a winning NO when YES was cheap. It still assumes midpoint execution and a
   simplified fee; use actual ask fills and order-level fees to evaluate an
   executable strategy.
3. The trainer's `time_of_day` now uses New York local hour **and minute**,
   matching `EdgeFeatures.timeOfDayFrac` on Android. Missing spreads are zero
   in both places.
4. The related-crypto channel no longer inserts another contract's YES
   midpoint into this contract's fair value. The shipped fallback MLP has zero
   weight in that blend pending fresh evidence; its diagnostic output remains
   available. The Python backtest's corresponding inputs are changed too.
   The new blend still needs a full chronological ask-fill rerun.
5. Brier for a NO pick now compares `P(NO)` with the **NO** result. Previously
   it compared `P(NO)` with whether YES settled, making even a correctly
   predicted NO look terribly calibrated. For a binary event, correctly
   complemented NO Brier equals P(YES) Brier. Hit rate and trade P&L evaluate
   the chosen action separately. The prior regression tests encoded the wrong
   mathematical claim; they were corrected and a winning-NO case was added.
6. The Android APK workflow listens for pushes to `chat-GTP` and continues
   uploading a branch-specific debug APK artifact and prerelease.
7. Buy tickets now prefer the current order-book ask to older tick and REST
   quotes; if a live book has no sellers for a side, a stale REST ask cannot
   create a ticket. A regression test covers both sides and the empty side.
8. This app has its own ID (`com.dirk.kalshiodds.chatgtp`), launcher icon,
   local data, exports, and rolling branch APK release. The committed debug
   key and increasing CI version codes allow updates over the same install.
   Its manually dispatched edge-model workflow publishes a separate
   `edge-model-chat-GTP` release so it cannot replace main's model release.

## Highest-priority test of a real edge

The [15-minute BTC contract rule](https://kalshi.com/markets/kx/m/kxbtc15m-26jul251430)
resolves YES when the *simple average* of the final 60 one-second CF
Benchmarks BRTI observations meets the strike. A Coinbase last trade is
neither this benchmark nor the average. [CF Benchmarks](https://www.cfbenchmarks.com/data/indices/BRTI)
describes access and licensing for the live/historical BRTI stream. Check the
exact market rules for each other coin before extending this design.

Proposed signal, **not yet implemented or demonstrated profitable**:

1. Record authorized benchmark observations with exchange timestamps,
   instrument IDs, timestamp/latency quality, Kalshi order-book snapshots and
   deltas, order attempts, acknowledgments, fills, and exact fees. The
   [Kalshi book stream](https://docs.kalshi.com/websockets/orderbook-updates)
   supports snapshots followed by incremental deltas. Reject stale or
   sequence-gapped data.
2. At `k` verified seconds in the final minute, with sum `S_k` and contract
   strike `K`, calculate the future-index-average threshold
   `(60 K − S_k) / (60 − k)`. Model the probability that the *remaining BRTI
   average* crosses this threshold using only observations already published
   and an explicitly measured feed delay. Never insert unpublished future
   seconds. Before the final minute, build a separate calibrated forecast of
   that final average from spot, short-run volatility, and liquidity.
3. For each side calculate conservative expected return per contract:
   `p_side − executable_ask − taker_fee − execution_buffer`. Require positive
   margin for model uncertainty and book movement; skip both sides otherwise.
   Price with actual depth for desired size and the [current fee
   schedule](https://kalshi.com/docs/kalshi-fee-schedule.pdf). Compare maker
   orders separately after tracking fill rate and adverse selection; a lower
   maker fee alone does not show better P&L.
4. Replay chronological markets with no market appearing in both training and
   validation, walk forward by day, and include delays, unfilled orders,
   partial fills, missing feed seconds, and fee rounding. Freeze rules before
   the holdout. Publish net dollars, ROI, drawdown, calibration, number of
   independent markets, and a market-clustered uncertainty interval. Then
   paper trade live for enough independent days to test whether the edge
   persists. Increase stake only if *realized* net P&L and uncertainty justify
   it. A higher accuracy figure or better Brier score alone cannot do that.

No licensed benchmark history/live feed or full book/real-fill archive was
available in this checkout, so this is a research specification rather than
a claimed strategy win. Do not enable automatic live orders on this basis.

## Remaining issues before claiming a tradable advantage

- `DirectionSanity.apply`, `TapeConflict.primaryFromMarket`, and
  `TicketBuilder.resolveSide` can make a favorite the suggested side because
  spot is above its strike or because its market quote is high, even when the
  best available ask plus fee makes the trade negative EV. The app's own
  backtest reports that favorite hit rate roughly tracks the price paid.
  A side picker should compare `pYes − yesAsk − feeYes` against
  `(1 − pYes) − noAsk − feeNo`, and skip if neither clears a prespecified
  margin. It needs end-to-end tests covering the hero, alerts, paper tickets,
  live tickets, and missing or stale asks before deployment.
- The TFLite MLP was trained with a random **row** split across four rows
  from each market. The same market and final outcome can appear in training
  and validation. Its scaler also uses the full dataset before the split.
  The reported 76.8% validation accuracy is therefore not a forward-market
  estimate. The shipped asset includes WTI training rows, whereas live crypto
  inference treats BTC/ETH/SOL as the same series id.
- An edge-model release currently activates when Brier and log-loss beat
  midpoint, even if an executable ask-fill strategy has negative or
  statistically uncertain P&L. The trainer collects only two decision
  minutes per market; live scoring runs on ticks, and its volatility and
  momentum features use different sampling. Build a group-by-market,
  forward-in-time holdout with feature parity and ask/depth/fill accounting.
- The optional Heavy ML and extended AI stack has no demonstrated
  out-of-sample trading edge in the checked-in data; keep it in diagnostic
  mode until live order-book and spot histories can reproduce its inputs.
- The new NO Brier score will often match P(UP) Brier exactly. Showing them
  as two independent quality metrics is redundant. Keep hit rate and net
  realized P&L prominent when evaluating picks.

## Verification

`python3 ml/test_train_edge.py` (6 tests),
`python3 tools/backtest/test_parity.py`, `python3 -m py_compile` and
`git diff --check` passed locally. Android Gradle tests could not run in the
workspace because Gradle 8.9 was not cached and `services.gradle.org` was
unreachable. The push-triggered GitHub Actions APK build is the next Android
compile check. The repository does not contain the full backtest cache, so
the changed strategy weights have not been shown to improve ROI.
