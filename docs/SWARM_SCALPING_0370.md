# Swarm-style scalping controls — Kashi 0.3.70

Reference: bitcoin-swarm branch, commit 40c19c4. Target: kashi 0144f96. Port its summary-first UI and settings interaction to Kashi's existing V2 paper strategies. Keep app identity, signing key and indexed trade history.

## UI

Scalp opens on Overview: net after fees, wins/losses, count, win rate, today, open P&L, a time-scaled realized-P&L curve, active position cards, and compact per-strategy rows. Switch between V2 and Originals. The curve is the sum of recorded results in the latest 48 windows, rebased to zero for that range; it is not an invented shared bankroll. Tap a strategy for the existing paged trade history. Detailed Algorithms and manual Ladder remain separate tabs.

Scalping settings opens from Scalp and from Signal settings. Controls are drafted and validated before Save. The layout follows Swarm's grouped cards. V2 controls: new-entry master switch, kill switch, adaptive tuning; manual ask cap, contracts, stop, net take-profit, trail distance and net trail-arming threshold; optional hold timer; fee-inclusive stake cap, hourly entry cap, open-position cap and daily realized loss cap; EMA dip filter and feature window; every V2 strategy has an enable switch.

## Unlimited trades and learning

No hourly entry or open-position cap by default; zero means unlimited/off for optional limits. Removed the old 12-entry-per-window quota from the app's primary/shadow signal loop when V2 extras run. Signal requirements, strategy-specific event windows, existing original re-entry cooldowns, executable depth, simulated latency and the final-minute exit check still apply. Unlimited means no artificial trade-count quota, not that every tick creates a bet. The original Open-50 once-at-open signal remains a once-at-open hypothesis.

Every completed V2 trade contributes its final fee-inclusive net/contract to a persisted per-strategy mean and a recency-weighted mean (90% prior, 10% newest). Updates happen after the existing ledger writer accepts the closed trade; repeat delivery of a recent trade ID is ignored. Outcomes and optional hourly entry counters survive ordinary restarts. Entry counters use bounded one-second buckets. Batched ledger and SharedPreferences writes retain their existing crash-durability limitations; no cross-store transaction is claimed.

The strategy note records the feedback count, recent result and exact entry/risk parameters. Losing strategies that qualify under the existing completed-window evidence rule can use the newest trade feedback to reduce profit-trail arming within bounds. Positive/inconclusive strategies keep their base settings. Learning continues when automatic parameter adaptation is off; manual overrides take precedence. This is statistical feedback, not a guarantee of profit.

## Parameter behavior

Defaults leave existing strategy parameters intact. Turn on Use my parameters to override new V2 trade entries and exits. Ask/clip/stop/target/trail/arming/hold are frozen in each pending trade's note. Changing settings cannot widen a held position's stop. Entry enable/kill/strategy switches and optional sizing/entry limits are checked before each fill; pausing never suppresses held-position exits. The optional dip filter adds a requirement to existing signals and can suppress momentum entries. Feature history honors the selected 10–600 second window and uses one observation per second for the EMA. Fee-inclusive book walking accounts for each level's fee rounding.

A positive max hold adds an unconditional exit timer. Net take-profit is measured after both fees. Final-minute and protective exits from 0.3.69 remain active. Daily limit uses V2 realized net across all V2 strategies, midnight Eastern reset, and queued closed results. All changes operate on paper positions; no Swarm live executor was imported.

## Verification

ScalpControlsTest exercises unlimited entries, fee-rounded sizing, actual fill/stop/target/hold wiring, pausing with active exits, learning once per close, and dip/limit boundaries. SavedScalpControlsTest reopens persisted settings, entry counts and feedback and checks duplicate learning. SwarmScalpScreenshotTest renders the new overview and settings on a phone; CI uploads the preview images for inspection. Existing unit and signing checks run in the APK workflow.
