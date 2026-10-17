# Kashi 0.3.80 — 1% stops, strategy audit and background blocking fixes

The default global price stop is 1% below each trade's actual fill/VWAP. The application sets the global cap to the requested 1% once, retaining per-model overrides beneath that cap and all other settings; later intentional global changes persist. A shared guard covers every original and V2 strategy, including retired-model positions still being unwound. It runs without spot, strike, volatility, forecasts or entry permission. Lowering the global cap tightens current positions; raising it cannot loosen a position's saved cap. Additional original exits and V2's default 3¢ peak trail remain active.

At a 55¢ fill the trigger is 54.45¢, not 54¢ or 1¢ below entry. A 54¢ best bid already crosses the trigger. The price threshold is not rounded to a cent or computed from account balance. This is a trigger, not a guaranteed realized loss: spread, rounded fees, gaps, partial liquidity and later executable bids can make the net loss larger. Stops latch, cancel resting maker exits, and sell only on a later fresh, non-gapped book after 250ms. Missing liquidity leaves an exit pending; recovery in price cannot cancel it. Saved risk, order baseline and last consumed sell snapshot survive restart and note sync.

## Confirmed execution/math fixes

- Shock/maker/opening trades no longer also run through the generic fair-gap engine, which could fill maker bids as taker asks and use the wrong timing/exits.
- Previously signalled shock orders can fill from fresh book data without waiting for optional spot/strike/volatility initialization. Price/time exits and simple-dip signals work if those feeds are absent; held-side exits also work on one-sided books. No fabricated fair value is used for entries.
- Forecast fills require a newer, fresh, complete-sequence snapshot. NO forecast values are stored in the NO probability coordinate.
- Partial sells across every lane record the consumed book timestamp, preventing repeated callbacks/timers from selling the same displayed quantity twice. SF maker fills/profit exits respect displayed depth.
- Forecast entry fees use ten-contract fee rounding and the predicted executable exit price, bounded by the $1 payout; forecast exit and shock/net-target fee calculations use actual quantities. The optional dip EMA compares historical asks with the current ask on either side.
- The normal-CDF leading coefficient is corrected to 1.061405429. Fee arithmetic is independently checked against decimal calculations over all whole-cent prices and multiple order sizes. Actual P&L still subtracts actual entry and each partial-exit fee exactly once.

## Background responsiveness

- Keepalive flag changes update an immediate memory mirror and a single coalesced IO writer. Service lifecycle callbacks no longer commit preference files synchronously or wait on DataStore with runBlocking. An explicit stop keeps the foreground component alive while its background job durably saves both settings, then stops it; a write failure retries without blocking Main.
- Scalp persistence has a separate serialized writer. The quote lane and burst queue no longer wait for SQLite flushes while processing prices or holding the quote lock. Flush retries preserve newer rows and learning transitions. Shared risk is evaluated once per quote across the three callbacks, and risk-token regexes are cached instead of recompiled per trade.
- Existing 0.3.79 retirement and service-start guards remain. These are fixes for confirmed blocking paths, not proof of the cause of a specific phone freeze; the app's Android exit/ANR report is still needed to distinguish a remaining ANR, OS kill or feed stall.

## Validation

All model IDs map to their actual execution variants in a mirrored UP/DOWN regression matrix, covering exact 1% triggers, missing spot, delayed/partial exits, replayed snapshots, rebound, pause and restart. Additional tests cover fee rounding, CDF symmetry, fresh forecast fills, maker routing/depth, migration of stored settings, blocked preference IO and blocked SQLite IO. The existing full Android suite, UI renders, release/model tooling, APK assembly and certificate checks run in CI before updater publication.

References used to verify platform/fee assumptions:
- https://kalshi.com/docs/kalshi-fee-schedule.pdf
- https://developer.android.com/reference/android/content/SharedPreferences.Editor
