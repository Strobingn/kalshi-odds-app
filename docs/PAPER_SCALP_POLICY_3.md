# Bitcoin Edge 1.4.0: performance first, paper rules 3

## What the audit establishes

Rules 2 used the opposite sign of the fc53 forecast, extrapolated the last three packets regardless of elapsed time, treated a settlement probability as a future executable sale price, and accepted as little as 1¢ net while allowing a 6–10¢ gross stop. Those are observable implementation choices, not measured explanations of every loss on the phone.

The earlier saved backtest report (`docs/backtest-2026-09-25.md`) contains contrary evidence to easy edge claims: cheap-side betting lost after fees, and the model's calibration did not beat market mid. Its raw cache is absent here, and its one-minute candles lack historical L2 depth. It cannot validate the new sub-second scalp engine. No profitable historical replay is claimed.

## New signals and execution

| Family | Trigger and target hypothesis |
|---|---|
| Range recovery | Reclaim at least 1.5¢ from a dip, then target the observed 60-second median minus 0.5¢. Requires 30-second context and acceptable book pressure. |
| Sweep reclaim | At least 8¢ observed sweep, a 2¢ recovery and supporting depth; target the pre-sweep observed bid minus 1¢. |
| Trend continuation | 10s and 30s moves agree with book pressure; discount projected continuation by 25%. |
| Forecast follow | Follow the signed fc53 predicted YES-mid move, with the side complement handled correctly. Discount by 25% and half-spread; choose an available forecast horizon of at most 480s that fits before close. |
| Adaptive mix | Select among qualifying signals using planned reward/risk and prior completed net outcomes. |

These are experimental price hypotheses, not validated expectations. Inputs use one observation per wall-clock second, fixed time anchors and continuous book history. Missing feed periods cannot become momentum. A settlement-probability number alone cannot produce an entry or a new-rule profit exit.

Every new entry must be below 50¢, have a spread at most 1.5¢, and project at least 2¢ net **and** 1.25 times its planned fee-inclusive stop loss. The later fill snapshot must still qualify, and actual quantity/rounded fees are checked again. Buying is limited to 25% of displayed bid depth within 1¢ of the best bid, as well as affordable visible asks. That is a simulation participation assumption, not an exchange fill guarantee.

The initial stop is 4–8¢ based on spread and observed 1s bid steps; trail distance is 2–4¢. Both freeze at entry. The stop never moves down. The profit trail arms only when its proposed stop would earn at least 2¢ per original contract and 75% of planned risk after fees. A target exit requires its original target and at least 1.25 times planned risk net; no automatic exit on a single profitable fading quote. Actual later fills can be worse than planned because of latency, gaps and depth.

New setups use 5% of their configured allocation ceiling while evidence is sparse. At least eight completed trades across three contract windows are needed to leave that stage. Further scaling uses the lower uncertainty estimate; an adequately sampled setup with a negative upper estimate is blocked. Results are not pooled across correlated allocation copies. Loss cooldowns remain 1–5 minutes within that contract. These thresholds are design choices, not confidence guarantees from a large validation study.

Legacy positions keep rules-1/rules-2 stop behavior; old pending entries are canceled. Prior balances, losses and charts remain. New-rule measurement starts at the actual balance after upgrade and includes legacy positions still held; it is not a clean randomized comparison. No live orders or credentials are used by the paper engine.

## Native Android dashboard

Performance is the first tab: a large multi-strategy chart, actual dollar/time axes, line-pattern legend, selectable books and 1H/6H/recorded periods. Tapping or dragging inspects available observations without using a future point; double-tap returns to live. Gaps over 60 seconds split paths. A rule-change marker separates earlier behavior. The new-rule filter starts at the recorded upgrade balance. Positions and the detailed trade ledger are separate tabs. Allocation settings and replay export are in the menu. Colors remain grayscale.

All-time ledger totals differ from bounded observed chart history. Net equity includes estimated exit fees at the current bid, without assuming all held size can exit at that bid. Fee/trade totals are all-time or current-rule totals, not a claim that a selected one-hour view contains every trade. Profit factor and average win/loss use only retained closes (up to 100), with the sample count shown.

## Recording and replay

The monitor writes bounded local JSONL segments (about 32 MB per production/demo feed, four roughly 8 MB segments). Each segment starts with the virtual ledger, pending orders, consumed liquidity and one-second lookback books. Quote, pause, expiry and official-settlement events contain resulting balances, equity, fees and trade counters. The recorder stores no API keys or real portfolio data. Recording errors are visible; after an error a fresh checkpoint starts a new segment.

Menu → **Export recorded books & trades** saves a ZIP through the Android document picker. Export captures complete file prefixes and releases the recording lock before writing to the document provider, so a slow destination does not hold up the paper engine. Each segment can be replayed by `PaperScalpReplay.run` through the same engine, checking every recorded balance. Do not sum cumulative totals across segments or combine production and demo. A recording gap is missing evidence. Replay parity verifies accounting and reproducibility, not a profitable signal.

On a machine with the Android project configured, inspect an exported JSONL segment with:

```bash
PAPER_REPLAY_PATH=/absolute/path/segment.jsonl ./gradlew :app:testDebugUnitTest --tests 'com.dirk.kalshiodds.signal.scalp.PaperScalpReplayTest.externalRecordedSegmentsCanBeCheckedWithoutInventingData'
```

The source regressions use synthetic adverse/noise/recovery/trend scenarios and validate execution, costs, signal direction, quote-rate behavior, missing data, stop persistence, migration and replay tamper detection. Android storage tests check ZIP export/replay, production/demo separation, visible recording errors, recovery checkpoints and empty exports. Native Paparazzi renders cover normal/narrow large-font phones, performance, feed gaps, positions and trades. Screenshots use synthetic data; their displayed returns are not performance evidence.
