# 0.3.70 — individual paper scalper settings

Open Scalp, select any algorithm card, then expand Signal, Entry, Exits or Execution in Algorithm settings. Sliders and +/− buttons edit a draft. Save changes for new trades persists this algorithm's settings across restarts. Restore model defaults removes its override. Allow new entries pauses future entries after Save; pending/open trades retain their frozen settings and continue to be managed.

Controls include entry price bounds, spread, seconds remaining, minimum profit if it wins, contracts, net profit target, stop, profit-trail distance and arming gain, maximum hold, time to exit before close, fair turn-down, entry/exit delays, expiry, slippage, cooldown and entry count. Applicable signal controls change the price-move/gap trigger, spot-shock threshold/lookback, imbalance threshold, favourite probability or forecast margin. V2 copies share the original's signals and have independent entry/exit/execution controls. Original signal eligibility still constrains candidates; a price filter cannot create a signal the model never generates. ML weights and trained feature coefficients are not sliders.

Manual settings use a shared paper execution policy with delayed book-depth taker fills. Existing maker strategies retain a conservative trade-through entry at their resting price; manual exits sell against subsequent displayed bids. Manual exit controls replace the model's specialized exit rules. Targets/arming use net dollars after recorded entry fees and estimated exit fees; final P&L uses actual simulated partial-fill fees. A gap can overshoot the stop, and missing exit liquidity can leave contracts for settlement.

Every new manual trade stores its full configuration and revision in the persisted trade note. Open positions retain those values even when sliders change, settings reset, or the app restarts. The ledger analyzer separates manual revisions. The in-app all-time algorithm totals still include earlier settings, as stated in the controls card. Parameter choices remain paper experiments; correct execution does not establish a profit improvement.

## Execution and adaptation fixes

- The general fair-gap quote handler now skips Shock/Favourite trades. They were previously eligible for the fair-gap handler's fallback parameters as well as their own family handler.
- Default delayed Shock fills require a later, fresh, ungapped book and reject asks over one cent worse than the signal ask. Default V2 fills also cap slippage at one cent. This avoids optimistic/stale fills and chasing a vanished signal; it is not a measured P&L uplift.
- Adaptive V2 evidence excludes windows where that model still has pending/open trades, bringing in the later fix from the adaptive-exits branch.
- Manual trades run exit management even when entries are disabled or the specialized signal lacks spot/CF input. Clock exits latch through missing/gapped quotes. Actual fills still need newer, fresh depth; a repeated callback cannot refill the same book.

## 40c / 9–11 minutes paper preset

The preset caps entry at 40c and requires 540–660 seconds remaining. That is early in a 15-minute window. It applies to this scalper's signals, not the old main-branch MLP-pick backtest. The combination was not tested by the separate exploratory slices. Minimum profit if it wins is an independent slider and defaults to zero in this preset. It does not guarantee positive expected value; imposing $10 on a 10-contract clip can make every entry impossible. The controls display that impossibility when it follows from clip size and minimum entry price.

## Build and verification

Kashi remains package com.dirk.kalshiodds.kashi with the existing signing key. Version 0.3.70, code 85, installs as a version upgrade over code 84. Pushes run the existing Kashi build workflow: Python tests, Android unit tests, APK assembly and certificate verification. New tests cover configuration completeness, persistence/reset, signal application, entry filters, delay/slippage/gaps, depth and fee accounting, partial fills, duplicate-book protection, restarts, no-liquidity exits and family isolation.

Verification completed: the full Android unit suite, 11 Python analyzer tests, debug APK assembly and signing-certificate verification passed for source commit 0926b93. Dark/light controls previews were inspected. The installable artifact is available from GitHub Actions run 38818404203.
