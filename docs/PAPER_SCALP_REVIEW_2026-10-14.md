# Autonomous paper scalp lab — source review and implementation

User scope: paper money only. AI chooses timing, entries, profit exits and size. Entries strictly below 50 cents, an immediate two-cent trailing stop on every position, no fixed entry-minute or holding-time restriction. No real orders are placed by this lab.

## Sources inspected

- Grok: `grok-bitcoin` at `472f390f05cda7870675b421a09104d1a50dbd97`, `signal/scalp/ScalpRules.kt`, `ScalpLab.kt`, `ScalpStore.kt`.
- Kashi: `kashi` at `8fb270184250e6a7ab28ca5c9cb413e127881b03`, `decision/ScalpV2.kt`, `Scalp.kt`, `ScalpModels.kt`, `ShockFav.kt`, `ShortHorizonForecaster.kt`.
- Screenshot supplied October 14: total 37,640 round trips, 10,381 wins / 27,079 losses, net -$12,351.81. Leading V2 rows have few trades and several identical outcomes. The phone's database and screenshot build version were not available; these observations cannot establish statistical significance or which source revision generated them.

## What the code establishes

| Area | Grok | Kashi V2 | New Bitcoin Edge paper lab |
| --- | --- | --- | --- |
| Stop | Global adapter cuts non-rising losses at 3 cents and trailing winners at 2 cents; individual trails wait for gains of 5–20 cents | Keeps original stop, often 10/15/25 cents; arms trail only after net gain reaches that stop | Stop immediately at max(entry price, highest bid) minus 2 cents, with a 0.1-cent minimum; ratchets up, never down |
| Timing | Adapter forces exits in final 90 seconds | V2 removes holding limits but copies original signals, including original time filters | Any exchange-open contract time; only data history required by an entry family, fill latency and actual market closure matter |
| Restart | Position peak stored | Peak and armed set held in in-memory maps | High bid, stop, trigger, partial fills and learning records checkpointed locally |
| Funds | A broke book can re-use the $20,000 reference and go negative | V2 costs explicitly excluded from original bankroll usage | Each experiment has its own funded $20,000 paper account; cash and fees must cover each fill |
| Fills | Quantity sized from bankroll without an executable-depth input | Most V2 copies use displayed top level; selected size variants walk depth | All orders consume visible price levels; partial fills, gaps and missing bids retained |
| Liquidity reuse | No depth input | A later quote can offer the displayed level again | Consumed virtual liquidity stays consumed until the displayed book adds size or a level disappears and returns |
| Rollover | `scratch` invents a sale at the old recorded bid | Waits for settlement | Waits for official settlement; no synthetic old-bid exit |
| Learning | Fixed size changes after wins/losses; no fitted forecast demonstrated | Frozen models plus separate original-parameter tuner, not a per-trade V2 forecaster update | Every completed net paper trade updates entry-family/price-bucket feedback and future sizing; open marks never train it |
| Comparison | Twenty named rules, globally overridden exits | Earlier copies could be identical; latest source explicitly diversifies sf01–20; sizing copies deliberately share signals | Five entry families × four explicit sizing ceilings; labeled as correlated sizing experiments |

These are source findings, not proof that the replacements make money. Latest Kashi specs include trails of 1/2/3/4 cents and some maximum asks above 50 cents; the new lab follows the user's stricter uniform policy instead. Its book-forecast fade ports the frozen Kashi fc53 model as an explicitly named comparator. It does not claim to reproduce the screenshot's later fc54 lead-model results or to provide BRTI valuation.

## New lab

Twenty independent paper accounts: under-50 baseline, dip reclaim, book momentum, Kashi fc53 forecast fade and adaptive selector; each has a 15%, 35%, 70% or 100% available-cash ceiling. Feedback can reduce those ceilings. There is no real-money $5 cap, per-trade approval, maximum bet count, fixed entry window, fixed holding period or forced pre-close liquidation. A family may wait for its inputs or choose not to enter; “below 50” is an entry ceiling, not a profitability claim.

Entry decisions wait for a distinct later book and at least 250 ms. This delay is a declared simulation setting, not a measurement. A later book must still offer an affordable ask within the chosen limit. Stops latch at their trigger, then sell into a later book after that same minimum delay, walking bids even below the trigger. Residual positions remain open until further liquidity or official settlement. A missing bid never creates proceeds. Entry and exit fees use the app's conservative quadratic taker estimate, rounded up separately at each simulated price-level fill. No maker fills or maker-fee profitability claims are made.

Public REST order books are a 1-second fallback; authenticated WS books are used when the existing live-data connection is available. Production and demo paper journals and learning are separate. No credentials or trade API are dependencies of the paper engine/runtime. The existing live manual-order feature is unchanged; the previous experimental scalp ticket source is disconnected from live proposals.

Every fully closed round trip, including stops, losses and official settlement, updates a bounded recent feedback set. Entry family and price bucket are separate. Means are shrunk toward zero and stake scale is bounded at 10–100% of each book's configured ceiling. The adaptive selector adds a small exploration score and uses prior completed results only. Winning excursions can inform earlier AI profit exits when the simulated entire exit clears both fees and price action fades; proxy-fair deterioration can also propose a profitable exit. These are simple online heuristics, not an LLM or a calibrated probability model. Repeated trades are grouped by contract window when reporting uncertainty, and the stop is never learned away.

Cash, all-time realized net, fees, wins/losses, no-fills, active stops, partial fills, stale marks, trade count and distinct recent windows are shown for each book. Recent detailed history is bounded at 100 completed trades per account; all-time summary counters remain cumulative. No existing on-phone Kashi or Grok data is modified or imported into the new policy's results.

## Verification

Regression coverage: no live cap / same-book fill; below-50 strictness; near-open and near-close entries; immediate loss stop; persisted ratchet and latched exit after restart; partial exits and static-depth conservation; no-bid and rollover; exact partial-exit settlement accounting; pause with protection continuing; stale/duplicate/invalid books; no free capital; completed-only feedback; fee-aware AI profit exits. CI also runs existing order, sizing and updater regressions and verifies the stable Android signing certificate before publishing the APK.
