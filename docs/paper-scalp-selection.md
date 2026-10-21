# Paper scalp cohort — 2026-10-21

The ten active tracks are a **provisional retrospective cohort**, not validated winners. They were selected from deduplicated v5 paper-sync snapshots available on 2026-10-21. Only settled, non-void fills with finite P&L and positive contracts were included. Each fill's recorded after-fee P&L was divided by contracts; fill values were averaged within each ticker, then ticker averages were averaged equally. This reduces, but does not eliminate, the influence of unusual sizes and repeated fills in one window.

| Rank | Track | Distinct windows | Equal-window USD/contract |
| --- | --- | ---: | ---: |
| 1 | Fair-Value Dislocation | 15 | +0.021957 |
| 2 | Dual-Horizon Reversal | 19 | +0.009092 |
| 3 | 5m Mean Reversion | 20 | +0.005391 |
| 4 | GBM Channel | 19 | -0.001486 |
| 5 | CNN Channel | 6 | -0.005549 |
| 6 | Imported Edge Model | 6 | -0.005549 |
| 7 | Fair Value Follower | 7 | -0.006500 |
| 8 | Momentum Sniper | 19 | -0.007450 |
| 9 | Best-Ask Contrarian | 20 | -0.026351 |
| 10 | Dip Hunter | 19 | -0.033847 |

These windows are few, not independent backtests, and results include simulated fills with varied sizes and snapshot histories. Several selected tracks have only one or two UTC entry days. Multiple candidate testing and correlated trades make the ordering unstable. The cohort is frozen in code, not dynamically trained; use new out-of-sample data before judging it. No guarantee of profit or live execution is implied. The other track identities remain available for historical reporting and managing previously opened paper positions; they no longer create new scalp tickets.
