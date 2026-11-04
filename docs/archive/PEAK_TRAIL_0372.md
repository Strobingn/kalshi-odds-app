# Kashi 0.3.72: absolute-cent trailing exits

New V2 paper trades default to a 3-cent trailing distance and an independent stop at 20% below actual entry fill price. The trail is active from entry; no net-profit arming threshold applies. Its peak uses fresh bids for the held side (YES/UP or NO/DOWN), starting with the entry-time bid, not a higher pre-entry quote or the ask paid.

- 90¢ bid held steady: the trailing rule holds; trigger is 87¢.
- Bid rises to 95¢: the trigger ratchets up to 92¢ and never moves down.
- 40¢ fill: independent percentage stop triggers at 32¢ before fees. Depth-walking trades use the average actual fill, not the signal price.
- Triggering an exit latches it. Execution still waits for a later fresh book, available bid size and the existing paper latency; partial fills and slippage are real parts of the simulation. A stop is a trigger, not a guaranteed fill price.

Scalp → Scalping settings adds an enabled-by-default peak/percent mode with adjustable cents and percentage. It is independent of the legacy "Use my parameters" switch. Turning peak mode off restores strategy/manual distance stops and net-profit trail arming for new trades. Existing positions retain the policy and numbers saved at their signal; their persisted peak and armed state survive process restart. Optional profit targets, max-hold timers and the final-minute CF evidence check still take priority when they trigger.

Older saved settings decode the newly added mode fields to the requested defaults without resetting settings/history or paper bankroll. Adaptive entry tuning and per-trade feedback remain; the tuner does not override the configured peak distance or percentage stop. Unlimited trade-count defaults remain. Originals retain their own rules.

Validation covers exact-cent thresholds, both held sides, steady highs, upward ratcheting, restart/partial-fill/latched-exit behavior, paused entries, immediate trailing without a profit threshold, percentage stop independent of trail/spread, depth-walk average fills, stale/gapped books, saved custom controls and legacy behavior. Android unit tests, trainer checks and APK/certificate verification run in CI. No profitability claim follows from exit-rule tests.
