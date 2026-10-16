# Kashi 0.3.73: hard 50-cent scalping buy ceiling

Every original and V2 paper scalper must pay at most $0.50 per contract on the held side (YES or NO). Exactly 50 cents is allowed. This is an entry-price ceiling before fees, not a trade-count limit or a profitability guarantee.

- Original signals and forecast entries reject asks above 50 cents. Shock/Open 50 entries share the same guard; the fixed 49-cent Open 50 maker remains eligible.
- Delayed taker executions recheck the current ask. Original and forecast resting orders saved with limits above 50 cents are cancelled unfilled. Shock maker joins and fills enforce the ceiling.
- V2 saved entry limits, presets, manual settings, and adaptive plans cannot override 50 cents. Tweak-2 presets formerly at 52–55 cents now stop at 50 cents; their other parameters remain intact.
- Each purchased depth level is capped individually; a cheap average cannot hide contracts above 50 cents. Lower strategy/user limits still apply.
- Existing held positions and sell orders keep their protective exits, including selling above 50 cents. Unlimited V2 count settings and per-trade feedback remain in place.

Regression coverage: both sides at 50/51 cents, original and V2 delayed fills, restored high-limit maker/forecast orders, shock maker/taker entries, manual/adaptive bounds, depth-walk tails, and exits for existing positions above the ceiling.
