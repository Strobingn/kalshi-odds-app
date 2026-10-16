# BTC 15-minute paper scalping execution audit

Date: 2026-10-16  
Branch: `dot/adaptive-stops-fix-20261016`  
Scope: paper-only BTC 15-minute strategies and their entry/exit simulation. No live order, account, purchase, or credential action was performed.

## Executive result

The highest-confidence improvements were execution and ledger correctness fixes, not a new alpha formula:

1. A trailing stop is now profit protection only. A small move from `$0.400` to `$0.402` cannot authorize a `$0.394` below-entry exit. A bounded trend-risk exit is allowed only near close after three fresh monotonic declines of at least 0.2 cents each; otherwise below-entry exits require the explicit max-hold or pre-settlement fallback. There is no calibrated recovery model in this app, so the code does not claim one.
2. Partial paper exits now create unique realized-slice identities while retaining the residual's immutable open identity. Peak updates only touch open rows. This preserves holdings, realized P&L, and future peak tracking.
3. Exit quotes and local books carry ingest timestamps and monotonic revisions. Stale quotes are rejected before peak mutation; an empty held-side book does not fall back to an old quote; unknown depth is not treated as unlimited; and one depth snapshot cannot be consumed repeatedly beyond its displayed size.
4. Automatic paper entries share the adaptive pause, adaptive stake cap, close-time propagation, and hard `$0.50` entry ceiling. Existing paper positions continue to be risk-managed when automatic paper entry is toggled off.
5. Alert-created paper entries now use the same strict fresh-source and known-ask-depth path as automatic tickets and honor the adaptive stake cap rather than a fixed `$5` clip. Cached market reads preserve their original observation timestamp instead of being stamped fresh on re-read.

These changes improve the validity of measured paper results. They do not establish a profitable strategy.

## Branch and implementation audit

The repository history was inspected before editing. The relevant branch set included `main`, `dot/scalping-research-20261016`, and the dedicated implementation branch `dot/adaptive-stops-fix-20261016`; no existing branch was modified or force-pushed. The implementation audited here is the current paper-stop/ticket path on the dedicated branch, not an assumed historical `kalashi`, Grok V2, or chat branch.

Key paths:

- `app/src/main/java/com/dirk/kalshiodds/signal/trade/TrailingStop.kt`: entry-relative trail distance, activation, stop-loss delay, quote-age gate, and time/settlement fallback.
- `app/src/main/java/com/dirk/kalshiodds/signal/trade/StopOrderManager.kt`: paper stop evaluation, executable held-side quote, depth clipping, and per-snapshot consumption.
- `app/src/main/java/com/dirk/kalshiodds/signal/paper/PaperBook.kt`: entry/exit ledger, fees, partial exits, residual positions, and persisted peaks.
- `app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt`: paper quote construction, alert-created entries, automatic paper entries, and stop evaluation scheduling.
- `app/src/main/java/com/dirk/kalshiodds/signal/trade/TicketBuilder.kt`: side-aware executable asks/bids, adaptive paper context, stale-source handling, and entry gates.
- `app/src/main/java/com/dirk/kalshiodds/signal/engine/LocalOrderBook.kt` and `TickBook.kt`: immutable book snapshots, observation timestamps, and revisions.

## Prioritized findings and disposition

### P0: below-entry trailing semantics were unsafe — fixed

The prior calculation activated the trail after a small favorable move and then treated `current <= peak - distance` as a hit even when the current executable bid was below entry. With a `$0.400` entry, `$0.402` peak, and 2% entry-relative distance, `$0.394` could be classified as a trailing exit. The delayed stop loss also became a loss exit solely because a timer elapsed.

The implementation now separates profit trailing from risk fallback. Profit trailing requires a peak above entry and a current held-side bid at or above entry. Before the emergency fallback, a below-entry exit requires a known close within 120 seconds plus three fresh observations within 60 seconds that decline monotonically by at least 0.2 cents per step. Any rebound breaks the rule. This is a transparent trend heuristic, not a calibrated recovery estimate, and is deliberately not presented as one.

### P0: partial-exit identity corrupted the ledger — fixed

The prior partial exit copied the open fill into both the realized slice and residual with the same ID. Filtering by ID could remove the wrong rows, and peak updates could update both a settled slice and an open residual. Realized contracts and open holdings were therefore not conserved.

Realized partial slices now receive collision-safe immutable IDs; the residual retains the original open ID. `updatePeak` only updates unsettled rows. Tests cover two sequential partial exits, residual peak retention, unique IDs, contract conservation, and realized-P&L conservation.

### P0: stale and empty executable quotes could look fillable — fixed

The old paper path stamped every `PaperExitQuote` with the current read time. A cached book/tick/REST value could therefore appear fresh indefinitely. An empty held-side book could fall back to an older quote, and null depth was interpreted as all held contracts.

Book and tick observations now retain ingest timestamps. Strict paper contexts reject unstamped/stale sources. A present book with no relevant side is treated as no executable quote. Unknown depth does not fill. This is intentionally conservative and may produce fewer paper fills.

### P0: the same displayed depth could be consumed repeatedly — fixed

The stop manager now keys consumed depth by fill and immutable source snapshot revision. Rechecking an unchanged two-contract snapshot cannot sell the same two contracts repeatedly; a new book revision is required for new displayed liquidity.

The key is market ticker + held side + snapshot revision, so a newly opened position cannot reuse liquidity already consumed by a prior position while the source snapshot is unchanged.

### P1: adaptive entry controls had bypasses — fixed narrowly

Automatic paper tickets from hunter/value/configured paths were still proposed while the adaptive pause was false, and the adaptive stake output did not reach the automatic paper sizing path. Alert-created fills also bypassed the common `$0.50` cap and omitted market close time.

Automatic non-manual ticket construction now honors the adaptive pause, adaptive max-ask and reduced stake cap. Alert-created entries apply the same hard price cap, adaptive eligibility, and authoritative market close time. Explicit manual paper actions remain user-controlled. Existing open positions are evaluated independently of the automatic paper-entry switch.

Strict paper alert/ticket contexts require a fresh source timestamp and whole-contract displayed ask depth. Cached/offline DTO re-reads preserve the cache fetch timestamp; unknown timestamps remain ineligible for strict paper execution.

### P1: simulated exit is still not a resting exchange order — remaining limitation

The app still performs a bounded, immediate paper sell when a stop condition is observed. It clips to the current executable held-side bid and displayed touch depth, but it does not model a resting stop-limit order lifecycle, queue position, cancel/replace, partial fills over time, adverse selection while waiting, or exchange acknowledgement latency. The report and code do not describe this as an exchange stop order. A future research step should add an explicit order-state simulator before comparing stop variants.

## Entry/exit audit checklist

- Entry price: automatic paper entries are capped at `$0.50` by the common paper boundary; manual paper actions remain separate.
- Executability: entries and exits use side-aware asks/bids. Exits use the held-side bid rather than a midpoint or inverted display price.
- Fees: paper entry and exit fees are included in cash/P&L accounting and partial-slice allocation.
- Spread/slippage: current code uses the observed executable touch and does not claim midpoint fills. It does not yet model queue slippage or walking multiple exit levels.
- Depth: exit size is capped by known displayed touch depth, with zero/unknown distinction and per-snapshot consumption.
- Latency/staleness: strict paper quote paths carry ingest times and reject stale/unstamped observations before peak updates.
- Peaks: favorable executable bids are persisted on open fills and are not rewritten onto settled slices.
- Settlement: max-hold and close-time fallback avoid blindly holding to settlement when a close timestamp is known. Alert fills now retain that close timestamp.
- Recovery heuristic: only recent fresh monotonic held-side bid declines near close authorize the bounded early risk exit; a transient rebound does not.
- Leakage: this patch adds no future market data or model calibration. Any backtest must use only information available at the decision timestamp.

## Reproducible evaluation protocol

Before changing thresholds or claiming an edge:

1. Export raw event-time inputs for each BTC 15-minute window: market open/close, every book snapshot/delta, trades/ticks, receive time, exchange time when present, fees, and the strategy decision.
2. Freeze a pre-declared strategy/configuration and entry/exit rules. The model, threshold, stop, and sizing choice must be selected on a training period only.
3. Replay chronologically with event-time joins. Do not use a later snapshot, settlement result, or a quote read after the decision timestamp to fill an earlier decision.
4. For each entry, simulate the executable ask and known depth. For each exit, use the held-side executable bid, quote age, displayed depth, fees, and one-time consumption of each source snapshot. Record no-fill when depth or freshness is unknown.
5. Split by contiguous time, not random rows: development, a locked validation period, and a final untouched out-of-sample period. Include separate regimes for quiet, volatile, thin-book, reconnect, and near-settlement windows.
6. Report trade count, no-fill rate, fill delay, entry/exit spread, fee drag, slippage assumptions, gross and net P&L, median/quantile P&L, maximum drawdown, exposure, turnover, calibration/Brier metrics where applicable, and confidence intervals. Compare against no-trade, hold-to-settlement, and a simple executable-price baseline.
7. Run a paper forward test with the exact build and immutable event logs. Reconcile every entry, partial exit, residual, fee, and settlement against the ledger before interpreting results.

No profitability conclusion is justified until this protocol produces locked out-of-sample and forward-test evidence. The existing unit tests validate mechanics, not economic performance.

## Tests and known limits

Focused tests run on this branch include:

- `TrailingStopTest` and `PaperBookTest`: stop semantics, time fallback, unique partial identities, peak retention, fee-aware exits, stale handling, and repeated-depth protection.
- `TradeTicketTest`, `PositionSellTest`, `TickBookMathTest`, `KalshiFeeAndWinTargetTest`, and `AdaptiveStrategyTest`: executable side pricing, book/tick behavior, fees, ticket sizing, and adaptive controls.

The full repository suite has known environment-sensitive Robolectric/Android-all download failures and several pre-existing substantive failures from the earlier branch baseline; those are not presented as proof of this patch. CI must remain the release build authority.

Remaining economic limitations are queue position, resting-order lifecycle, multi-level exit walking, exchange matching uncertainty, and lack of measured out-of-sample profitability. The entire change remains paper-only.
