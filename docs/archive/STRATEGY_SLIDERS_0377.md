# Kashi 0.3.77 — per-strategy sliders

Each V2 strategy detail page has a Settings action opening the same style of label/value sliders as Claude's strategy settings. Saves happen when the slider is released. Each V2 model has independent persisted overrides for contracts, minimum/maximum buy price, stop percentage (legacy mode: cents), trailing distance, entry cutoff minute, entry-check cadence, simultaneous open positions per side, net take-profit and maximum hold. The existing strategy switches remain in sync.

Unchanged controls inherit the global/learned plan. Changing one model never changes another. Entry, sizing and exit parameters are recorded when a new order is created, so filled positions keep existing protective exits. Disabling a model blocks new/pending entries but does not stop exits. Entry-check cadence never throttles exits. The 50-cent hard algorithm buy ceiling, mandatory stops and final-minute exit check remain; total trade count has no new limit. Per-side caps limit positions in the same market and do not create new signals or bypass the source strategy.

Original strategy pages keep their original rules; these controls apply to the V2 experiments. Global risk/kill switch, stake and optional daily/hourly limits still apply.
