# BTC 15-minute paper scalping audit (Kashi baseline)

Date: 2026-10-16
Branch: `dot/scalping-research-20261016`
Baseline: `origin/kashi` at `25c24b292397ed10fcb6f26abaa22fe4b366cd3b` (`0.3.74`)

## Executive conclusion

The Kashi implementation already has several defensible safety properties: the
50c entry ceiling is inclusive and enforced through the V2 depth walk, fresh
executable quotes are required for paper fills, exits use executable bids, and
displayed depth is bounded. These are useful correctness guards, not evidence
of an edge. The repository's own historical reports still show negative
out-of-sample results, so this audit makes no profitability claim.

The highest-value work is execution and measurement quality before changing
signals: centralize the fee ledger, record decision/order/fill timing and book
sequence, replace optimistic passive-fill assumptions with bounded queue models,
and model settlement using the contract's actual reference windows. Then test
time-aware exits on identical entries. A 50c cap is a risk/budget constraint;
being below 50c does not by itself create positive expectancy.

This branch is research-only. It does not add live orders, credentials, or a
new strategy, and it does not change existing branches.

## Baseline and code path

Branch inspection found multiple divergent implementation lines. The relevant
completed Kashi line is `origin/kashi`; the live-chart side branch is older and
divergent. This report intentionally does not compare or transplant other
strategy lines.

The paper path is:

1. `OddsViewModel.runScalp` receives one BTC 15-minute market's websocket book,
   settlement-feed context, and timing data.
2. `ScalpBook.onQuote` updates history, advances open trades, and evaluates
   entries. `CoinEvalConflator` can coalesce events at 150ms and discard stale
   intermediate ticks.
3. `Scalp.kt`/`ScalpV2.kt` create paper entry and exit state. Entries wait for a
   later book and a delay; exits wait for a later fresh book and may be partial.
4. `ScalpBook.settle` closes unresolved positions at the contract outcome.

Important implementation locations (line numbers refer to this baseline):

| Concern | Location | Audit result |
|---|---|---|
| Entry ceiling | `decision/Scalp.kt:47-84,215-240`; `decision/ScalpV2.kt:14-22,147-148`; `decision/ScalpControls.kt:22-29,50-64` | Inclusive 50c cap is enforced for signal, V2, manual controls, and every walked level. |
| Taker fill timing | `decision/Scalp.kt:1219-1275`; `decision/ScalpV2.kt:1467-1575` | Later-book, freshness, delay, executable ask/bid, displayed depth, and partial-fill checks exist. |
| Passive fills | `decision/Scalp.kt:1238-1261`; `signal/paper/PaperLimitOrders.kt:12-22,190-232` | Touch/depth fills are explicitly documented as optimistic versus real queue priority. |
| Fees | `decision/Scalp.kt:96-100`; `signal/trade/KalshiFee.kt:9-49,77-83,142-151` | Older scalp helper rounds cents per batch; newer fee model is order-level and accumulative. |
| Peak trailing exit | `decision/ScalpV2.kt:63-76`; `decision/ScalpControls.kt:14-20` | Uses held-side fresh bid and a 3c configured peak trail; it is active from entry in peak mode. |
| Exit state machine | `decision/Scalp.kt:1188-1215`; `decision/ScalpV2.kt:1467-1575` | Trigger and later execution are distinct; stale/gapped books defer execution. |
| Settlement context | `signal/ws/CfBenchmarks.kt:12-15,173-180`; `decision/Scalp.kt:1284+` | Final-minute average and terminal spot must not be conflated; contract terms need to be logged. |

## Findings and prioritized improvements

### P0 — Keep the 50c rule, but label it correctly

The current rule is `price <= 0.50` (inclusive), and V2 also caps every depth
level rather than allowing a cheap VWAP to hide a level above the ceiling.
The 50c value is a maximum, not a target: a trade may enter below 50c. For
each completed or partial trade, performance analysis should calculate the
realized volume-weighted filled entry from its actual fills and quantities,
then separately report the highest level purchased, displayed depth, and
unfilled quantity. That is a correctness improvement worth retaining. It is
not a prediction or profitability rule. A future report should always
distinguish:

- entry budget/cap: no newly purchased level above 50c;
- executable price: the ask for entry and bid for exit;
- expected value: executable proceeds minus both fees, spread, slippage, and
  non-fill risk.

### P0 — Make fee accounting one order-level ledger

`ScalpRule.orderFee` applies a cent ceiling to each call. V2 depth walking and
partial fills can call this helper for separate levels or fragments. The newer
`KalshiFee` model instead carries raw fee precision and aligns the debit at the
account/order boundary. These paths should not silently disagree in a backtest.

The included diagnostic found a concrete rounding difference for one synthetic
multi-level order: `3@0.21 + 7@0.49` is `$0.17` under the legacy per-batch helper
versus `$0.16` under a raw/6dp-then-once accumulator. This is a fee-model
diagnostic, not a P&L result and not proof that every runtime fill is wrong.

Recommended implementation/testing sequence:

1. Resolve the series/event fee coefficient and member precision from the actual
   contract metadata; do not hard-code a universal maker exemption.
2. Represent one logical order with fills, raw fee precision, fee rounding, and
   any rebate/accumulator state. Preserve every fill; report VWAP for realized
   entry analytics, but do not replace nonlinear fee calculation with a VWAP
   shortcut.
3. Add tests for 1, 10, and 100 contracts, single-level and multi-level depth,
   partial fills, cancellation, and settlement/void paths.
4. Re-run all historical comparisons with the same fee engine and publish the
   delta separately from signal changes.

### P0 — Instrument quote truth, latency, and stale/gapped books

The code has useful freshness checks (legacy 10s, V2 3s), a 2s V2 entry delay,
later-book requirements, a 250ms exit delay, and sequence-gap invalidation in
`TickBook`. However, local receive age is not the same as exchange quote age;
the 150ms conflator and 1Hz open-trade timer also affect decisions. A robust
paper record should include, for every decision and fill:

- exchange and local receive timestamps, decision/order/cancel timestamps;
- book sequence, snapshot/delta status, gap/resnapshot status, and age;
- side-specific best bid/ask, displayed sizes, depth walked, spread, and the
  exact executable price used;
- trigger time versus fill time, expected versus actual price, partial quantity,
  and the reason for cancellation or non-fill.

This makes latency sensitivity measurable instead of assuming a quote remains
available because it was observed locally.

### P0 — Bound passive-fill optimism

`PaperLimitOrders` fills a resting order when the quote touches/crosses and up
to displayed depth is available. `ScalpBook` has a more detailed maker queue
estimate, but a cross or level wipe can still produce a full modeled fill
without replaying trades or proving queue priority. A touched quote is not
evidence of a passive fill.

Use three reported bounds before trusting maker results:

- optimistic: current touch/depth behavior;
- conservative: no passive fill without observed trade-through or modeled queue
  consumption ahead of the order;
- calibrated: fill probability by queue-ahead, time-at-level, cancellations,
  and trade flow, only after out-of-sample data is available.

Do not count both a trade print and an order-book delta as independent consumed
volume unless the data model proves they are distinct.

### P1 — Make exits time-aware and forecast-aware, without holding blindly to close

The requested behavior is not “hold every position to settlement while it is
falling.” An exit policy should use time remaining, executable market state,
and the forecast/context feature available at the decision time. The current
code has deterministic fair-value/CF context and strategy signals; it does not
have an LLM authority that should be treated as a guaranteed prediction. Any
forecast must be timestamped and frozen at the decision so later information
cannot leak into the label.

The 1–4% exit request still needs an explicit definition before implementation:
percentage of what (entry, peak, markout, or contract probability), and percent
versus cents? This branch deliberately does not choose units or alter runtime
behavior.

Most importantly, a stop trigger and a sell limit are different controls:

- a stop/trigger says “the exit condition is now active”;
- a sell limit says “do not accept below this price.”

If price falls through a sell limit, it can remain unfilled. Therefore a
limit-only exit can reduce slippage on a favorable move but increase exposure,
miss settlement, and leave the position falling. The paper engine should record
triggered-but-unfilled states and their counterfactual executable bid, rather
than treating the requested limit as a guaranteed sale. A hard risk/time
fallback may be needed, but its semantics must be explicitly paper-tested and
approved before implementation.

The pending policy boundary is equally important: do not automatically sell
solely because the mark dipped below entry, and do not promise capital
preservation. Compare the calibrated probability of a rebound before the
deadline and its continuation value with deeper-loss risk, remaining time, and
the executable exit. Breakeven is fee-adjusted, so it is not necessarily the
entry price. Do not widen a stop arbitrarily after a loss, and do not force a
hold to settlement when the time/risk test says to exit; the decision must have
an explicit time budget and fallback.

Evaluate candidate exits on identical entries and identical information:

1. fixed stop plus executable taker exit;
2. current peak-bid trail (3c configured default) with its trigger latency;
3. time-to-expiry and volatility-aware trail/stop;
4. forecast-conditioned continuation value versus immediate executable bid;
5. limit exit with explicit non-fill, timeout, and fallback outcomes.

For each candidate, compare net proceeds after both fees, spread, slippage,
and partial/non-fill outcomes. A useful decision quantity is the expected value
of waiting versus selling now, conditioned on time remaining and the information
available then—not a terminal-spot mark. Keep a maximum-loss and end-of-window
policy so “forecast-aware” cannot silently become indefinite holding.

The current exit code has a specific risk worth measuring: entry spread is
bounded, but stop/trail execution does not require the spread to remain under
the same 2c threshold. A transient widened bid can trigger a stop and later
execute at a poor bid. Do not simply disable stops; log spread/age/sequence at
trigger and execution, then compare confirmation and immediate-trigger variants
on the same entries.

### P1 — Model settlement using the actual contract terms

The current feed (`signal/ws/CfBenchmarks.kt`) uses a current CF tick outside the
final minute and a `finalMinuteAverage` path near expiry. Official settlement documentation makes
the opening reference, minute-before-close average, minute-before-open average,
and contract-specific terms material. The terminal spot is not automatically
the settlement value. The ledger should retain the average window, sample count,
partial/final status, source age, and the exact contract/event terms used.

Also verify void/refund handling and fee treatment against the specific contract
terms before calling it a bug: `ScalpBook.settle` pays 1/0 for open positions and
has a separate void path. The report should not universalize one settlement or
fee rule across series.

### P1 — Preserve the data-leakage safeguards and strengthen the protocol

The existing adaptive evidence excludes unfinished windows, which is good, but
selection can still become optimistic if many variants are tried and only the
winner is reported. Use chronological whole-contract/day-block splits, purge
overlapping label horizons, freeze strategy and calibration choices before an
untouched forward paper period, and record every tried/discarded variant. Use
15-minute-window or day-clustered confidence intervals; ordinary fill-level
independence is false.

The repository's prior reports are an important negative control: the cited BTC
V2 out-of-sample estimate is approximately -2.93 cents per contract with a
confidence interval below zero, and the longer fair-gap history is also
negative. These results are not a universal impossibility theorem, but they
mean no strategy change should be described as profitable without new,
pre-registered out-of-sample and forward-paper evidence.

## Economics sanity check

For a one-contract taker entry at 45c and taker exits at 47c and 48c, the
fee-inclusive examples supplied for the current official fee model are
approximately:

| Entry → exit | 1 contract | 10 contracts | 100 contracts |
|---|---:|---:|---:|
| 45c → 47c | -$0.02 | -$0.16 | -$1.49 |
| 45c → 48c | -$0.01 | -$0.06 | -$0.49 |

With a zero-fee maker entry and taker exit at 48c, the corresponding examples
are approximately +$0.01, +$0.12, and +$1.25 before adverse selection and
non-fill risk. These are illustrations of fee sensitivity, not expected
returns. The exact coefficient, precision, maker eligibility, and contract
terms must be loaded for the series/event being tested.

## Reproducible research protocol

1. **Freeze inputs.** Record source commit, series/event, contract terms, fee
   metadata, timezone, code version, and all selected parameters.
2. **Reconstruct executable books.** Use chronological snapshots/deltas with
   sequence-gap handling. Keep bid/ask, size, depth, local/exchange time, and
   no-lookahead settlement features.
3. **Replay orders.** Apply signal-to-order delay, later-book requirement,
   cancellation latency, depth walking, partial fills, fee ledger, and separate
   maker queue assumptions. Produce optimistic/conservative bounds.
4. **Split by time.** Train/calibrate only on earlier complete windows, tune on
   later development blocks, and evaluate once on untouched whole-contract/day
   blocks. Purge overlapping horizons and cluster uncertainty by window/day.
5. **Compare exits on paired entries.** Report trigger rate, fill rate, time to
   exit, remaining exposure, net cents/contract, fee cents, adverse markout,
   settlement/void outcomes, and confidence intervals. Include triggered-but-
   unfilled limit orders as exposure, not as successful exits.
6. **Forward paper test.** Freeze the strategy and calibration, run paper-only
   with no live credentials or orders, and publish all variants and stopping
   rules before looking at results.

Minimum regression tests should cover the inclusive 50c boundary, a 50.01c
rejection, depth with an over-cap tail, stale/gapped quote rejection, delayed
executable entry/exit, partial depth fills, passive non-fill, fee rounding,
settlement-window selection, and triggered-but-unfilled limit exits.

## Reproducible evidence in this branch

The dependency-free audit can be run from the repository root:

```text
python tools/scalp_audit/audit.py
```

It checks the source invariants listed above and prints a fee-rounding
diagnostic. Current output includes PASS for the inclusive cap, V2 cap, peak-bid
trail, 3s V2 freshness, manual max-ask clamp, explicit passive-fill caveat,
and displayed-depth usage. The synthetic `3@0.21 + 7@0.49` case reports
legacy `$0.17` versus accumulated `$0.16`.

Targeted existing Gradle tests also pass under the installed JDK 21:

```text
:app:testDebugUnitTest
  ScalpPeakTrailTest
  ScalpEntryCeilingTest
  ScalpControlsTest
  ScalpV2AdaptiveTest
  ReleaseGate0343PaperLimitTest
BUILD SUCCESSFUL in 16s
```

The first attempt with the environment's JDK 25 was incompatible with this
Gradle/Kotlin version; rerunning with JDK 21 completed successfully. The SDK
XML-version warning is environmental and did not fail the tests.

## Limits and blockers

- This audit does not contain a calibrated passive-fill probability, queue
  position, or exchange-arrival model.
- The repository does not provide enough raw order-book/trade/CF final-average
  data here to establish a new edge.
- The 1–4% exit reference and units are unresolved; no implementation should
  assume them.
- “AI forecast” must be defined as a timestamped, available-at-decision feature
  with a validated calibration; it cannot be used as hindsight or a guarantee.
- No profitability, live-trading readiness, or financial recommendation is
  claimed. All proposed work remains paper-only until separately authorized.
