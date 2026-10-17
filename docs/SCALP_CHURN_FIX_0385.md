# Kashi 0.3.85 — forecast/scalper churn fix (paper)

## Root cause (0.3.83, PR #117)
1. `ScalpRisk` added a shared 1% stop measured from the **fill price (the ask paid)**: `stop = fill × 0.99`.
   At a 31¢ fill the trigger is 30.69¢, but the sellable bid right after a taker entry is already 30¢ (1¢ spread).
   `applyProtectiveStops` therefore latched a stop on the very first fresh book after every entry, before any
   strategy exit could run. The stop fired on the spread itself, not on a real move.
2. The forecast lane (`onForecastQuote`, fcride/fcfade and their v2 copies) never had a re-entry cooldown. Before
   0.3.83 that didn't matter because a trade lived until its 600 s horizon or the 3¢ stop. Once trades closed within
   about 1 s, the lane re-entered on the next quote with a positive forecast. The result was about 170 round trips in
   35 minutes, mostly fees. The v2 spawn and shock lanes had no exit cooldown either.
3. The 0.3.83 migration forced every user's global cap to 1%, which also tightened every per-strategy slider (min).

## Fix
- Default stop is 4% (`ScalpRisk.DEFAULT_PERCENT`, policy v2 migration). A stored 0.3.83 forced 1% becomes 4%.
  A different value the user chose deliberately is kept, and manual sliders still override.
- Stop distance = max(percent × entry bid, entry spread + round-trip taker fees + 1¢, 3¢). It is measured from the
  **bid at entry** (`riskRefBid`/`riskSpread`, recorded at fill), so the entry spread can never trigger it. Applied in
  the shared guard and in the v2 peak-rule stop.
- `ScalpRisk.entryBlocked` covers every entry path (originals, forecast, shock/sf, v2 spawn, g14 copies). It is per strategy
  and per market: 60 s cooldown after a stop exit, 15 s (the pre-0.3.83 value) after any other exit, and at most
  4 round trips per strategy per rolling 15 minutes.
- Regression: `ScalpChurnGuardTest` covers noisy prices, the floor, measuring from the entry bid, the cooldown and the cap.
