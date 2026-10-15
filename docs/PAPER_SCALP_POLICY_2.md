# Paper scalp policy 2 — Bitcoin Edge 1.3.0

The previous immediate entry-minus-2¢ trail left roughly 1¢ of bid movement after a 1¢ spread. At a 40¢ contract price, a 100-contract taker entry costs approximately 1.68¢/contract in fees, with a similar exit charge. A cheap price alone did not cover those costs. This is a source-level diagnosis; no on-device trading database was available to attribute the user's losses.

## Revised execution

- New positions: initial protective stop 6–10¢ below entry, based on spread and the 80th-percentile observed bid step over the preceding 30 seconds. Trail distance 2–4¢. Both distances freeze at entry, and the actual stop never moves down.
- The profit trail arms only when a sale at the proposed trail level would cover the full entry value, entry fee, projected exit fee, and 1¢ net per original contract. Fill slippage and missing depth can still cause losses.
- Every setup requires an estimated future bid that covers both taker fees plus 1¢/contract. The discount family requires the available fair-value estimate; it cannot buy on price alone. Dip uses the recent range high; momentum extrapolates a recent bid change; fade uses the frozen fc53 hypothesis. These targets are experimental and not independently calibrated forecasts.
- Pending decisions are rechecked on the later book. Actual fill size must also clear the fee hurdle with fee rounding.
- One loss starts a 60-second cooldown within that contract; consecutive losses extend this to at most five minutes. Completed results reduce position sizing. A setup/price bucket with at least eight results across three windows and a negative upper uncertainty estimate is blocked. Correlated sizing books are not pooled as independent evidence.
- Existing positions retain their prior tighter stops, including latched exits. Prior unfilled decisions are canceled. Existing balances, losses and results are retained. Lessons use completed policy-2 trades only.
- No entry-minute or maximum-holding gate; the AI may enter at any time the contract is open when its signal qualifies. Paper cash remains funded and depth-limited, with no live $5 cap. No live order API is invoked.

## Dashboard and measurement

The grayscale native dashboard has selectable family/allocation books, observed equity and drawdown curves with a scrub slider, divergent strategy comparison bars with an all-time/policy-2 switch, a bid/entry/stop rail, and net trade outcome bars. Recent outcome bars include legacy trades with their policy version. All-time totals preserve old results; policy-2 change begins at the first observation after upgrade, including subsequent changes in any legacy positions still held. It is not a reset or clean randomized comparison.

Each book retains 1,440 observed samples (15-second cadence and fills/settlements); no old curve is reconstructed. Samples carry the running equity peak so drawdown survives bounded-history retention. Full-quote maximum drawdown is tracked separately. Marks use the top bid less an estimated fee, so marked equity is not guaranteed executable liquidation value. Stale marks are labeled.

The stop regression includes an adverse 2.1¢ movement that triggered the old rule and remains open under the new initial stop. Other tests cover fee-aware trail arming, restart persistence, legacy migration, fee hurdles, withdrawn signals, loss cooldowns, rejected losing setups, partial depth, settlement, cash conservation and quote freshness. Paparazzi renders real Compose code using synthetic fixtures on normal, tall and large-font narrow phones; fixtures do not demonstrate profitability.

This change does not establish a profitable strategy. There is no historical order-book replay dataset in this checkout, so no claimed historical return improvement. Validate with new paper observations across distinct contract windows, accounting for fees and drawdown.
