# Kalshi Odds / Dip Hunter (Android)

Android app for **Dirk Diggler** that shows live Kalshi **crypto** prediction-market odds and fires **analysis-only** signal alerts.

**Default watchlist (Bitcoin-only):**

| Series | Asset | Role |
|--------|--------|------|
| `KXBTC15M` | Bitcoin 15-minute | The only live series |

`KXETH15M`, `KXSOL15M`, and extra tickers are recognized so stored rows can be filtered out of scorecard / signal history / paper P&L. They are never subscribed, polled, scored, alerted, or paper-traded. The live list is `CryptoMarkets.DEFAULT_SERIES`.

**WTI crude (`KXWTI15M`) is deprecated** and, with every other non-crypto market, is excluded from the live app: no toggle, no REST watch, no WebSocket subscription, no scoring, and no alerts.

- **UI:** Jetpack Compose + Material 3
- **Network:** Retrofit REST poll + optional authenticated Kalshi WebSocket
- **Alerts:** local `NotificationCompat` HIGH channel via a foreground WS service
- **Offline:** last successful crypto snapshot cached in DataStore

Package: `com.dirk.kalshiodds.chatgtp` · version **1.3.2 (grokbot)**

## 1.3 (grokbot)

- In-app updates read the rolling tag `grokbot-latest` (stable asset `DipHunter-grokbot.apk`) and the same versionCode base as the build. A downloaded APK is offered only when its package is `com.dirk.kalshiodds.chatgtp` and its signing certificate SHA-256 matches this install.
- The old debug certificate from `app/signing/diphunter-debug.jks` (commits `271856d`, `a558f6c`) is **public**. v1.2+ is signed with the private release key. Installs still on that old certificate must uninstall once before this build will update in place.
- Pushes to `grokbot` fail the release job when `RELEASE_KEYSTORE_*` secrets are missing. Pull-request builds may compile unsigned and never publish.
- From 1.2, install 1.3 once by hand from the grokbot-latest release; later updates arrive in-app.

## 1.3.2

- Refresh logs the shared fair value for Bitcoin, so the scorecard still records rows when the AI percent is hidden. The card edge and the ticket edge use that same number.
- From 1.2, install 1.3 once by hand from the grokbot-latest release; later updates arrive in-app.

## 1.3.1

- Pending orders expire from Kalshi's `close_time` plus 10 minutes. A missing close time is kept. Ticker clocks are `yyMMMddHHmm` in America/New_York.
- Cached ETH/SOL rows are filtered out and purged on load. A hidden AI model is not shown or scored; fair value is used instead.
- A release for another app is reported as skipped, not as up to date.

## 0.3.15

- **Sticky "Window closed" after rollover.** 0.3.14 kept MANUAL / SELL tickets from a closed 15-minute window forever (`preservedManuals` / `voidTickers`) and set `lastError = Window closed` on every refresh. Once a Buy/Sell card was open across a rollover, the error stuck on the fresh window and the dead card never left. A closed window now shows a one-time notice (`That window closed. Nothing was sent.`), voids the ticket so Approve cannot place, then drops it after ~5s / the next refresh and clears `lastError`. `lastOrderError` is recorded once per voided ticket. An in-flight Submitting order is left alone.
- **Buy / Paper UP / Paper DOWN after rollover.** Taps resolve the current live contract via `MarketLifecycle.resolveActionWindow` (`currentOpenWindow` / `resolveLive`). If there is no open window, the UI shows `Next window loading` — never `Window closed` or `Market closed` on a live window.

## 0.3.14

- **Full scorecard empty-state contradiction.** The 0.3.13 screen showed `Picked side: 12/18 correct` and `settled 18` while the bottom read `No settled samples yet`. The empty-state was gated on `ScorecardMetrics.Snapshot.perSeries.isEmpty()` (and the Paparazzi fixture left `perSeries` empty while stuffing honest / window counts). Calibration-sample copy (`the first 20 outcomes unlock calibration`) was a third, unrelated collection. Every count, empty state, and section now comes from `ScorecardCopy` → `ScorecardMetrics.settledScoredPicks` + the same paper P&L as the home line. `No settled picks yet` appears only when that settled list is empty (`ScorecardCopyTest`, `KnownIssuesRegressionTest.fullScorecardEmptyStateUsesSettledPicksNotPerSeriesOrCalibration`).
- **Cleaner layout.** Top summary (W-L, win rate, paper P&L, settled count), 4-hour ET time-of-day buckets, then recent settled picks collapsed by default. Per-coin breakdown is gone (Bitcoin-only). Removed duplicate Today / 7-day / all-time cards, per-series, Brier / policy / adapter / guardrail / Extended-AI debug banners, and fabricated bucket P&L. Empty buckets render `—`, never `0%`. Green/red stay Kalshi UP/DOWN only; P&L and win rate are neutral `+/−` text.
- **Bitcoin-only (KXBTC15M).** Home shows the BTC card plus This window. SOL / ETH cards, watch toggles, extra tickers, WS subscriptions, scoring, alerts, and new paper fills are off. Scorecard and signal history filter stored ETH/SOL rows (DB rows are not deleted). `homeKeeps0312Layout` asserts exactly one BTC card.
- **Rollover cannot leave BTC stuck.** A failed resolve (empty listing or 429) stays in `retrying` and keeps polling — including mid-window and after close-time polling would have ended. `Retry-After` is respected even when longer than the 10s backoff cap. `successor` accepts a re-found open market when `previous == null` even if `close == lastCloseMs`. `btcCardRecoversFromFailedResolveMidWindow` reproduces the 0.3.13 S24 “Next window loading” stuck state and proves recovery.
- **This window sit-out copy.** Home never shows Brier / log-loss. Sit-out reads `NO BET this window. The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative.`
- **Home tiles: $10 profit + larger AI %.** Each UP / DOWN tile shows net profit if the owner bought that side with $10 at the live best ask (`contracts×ask + ceil_cent(0.07×C×P×(1−P)) ≤ $10`, profit = `C×$1 − cost`). Missing ask → `$10 wins —`. Display only — live Approve stays $5 all-in. AI % is headline-sized, still green/red and summing to 100. Paparazzi covers 360dp + fontScale 1.3.
- **Paper UP / Paper DOWN always on the Bitcoin card.** 0.3.13 hid paper Buy once a Kalshi key was saved: keyed Approve became LIVE $ and the only Paper control lived on the ticket confirm sheet, so LIVE $ showed only Buy anyway / LIVE $ and Sell. Both modes now show green **Paper UP** and red **Paper DOWN**. Tap logs a $10 paper fill at the live ask (same math as the tile line) into `PaperBook` — never a real order or portfolio GET. Snackbar confirms contracts / cost / profit-if-win. Open fill reads `Paper: UP 14 @ 70c`. Settles with the window and feeds paper P&L on the home line and scorecard.

## 0.3.11

- **Paper never blocks Live.** `ApproveRouter.Intent.Live` + live positions (`GET /portfolio/positions`). A paper fill on ticker X cannot stop a live order on X (`LiveApprovePaperIsolationTest`). Keyed Approve is Live even when the Paper toggle is ON (`ApproveRouterTest.paperOnWithKeyIsLiveNotPaper`); the Paper button is the only paper path.
- **$5 all-in live size** at the final order-build step (`LiveOrderSizer` + `KalshiTradeClient.enforceLiveCap`). Default **Min profit if win $10**. Tickets below that stay disabled. A leftover $50 win-target cannot resize a LIVE order above $5. Sizing tests at 1¢, 5¢, 10¢, 25¢, 31¢, 50¢, 63¢.
- **Scorecard:** hits and Brier both use 0–1 probability (`ForecastUnits`). 0/5 with Brier 0.003 was a percent-vs-unit mix.
- **UI:** BET UP / BET DOWN / NO BET from the same ticket decision. PAPER vs LIVE $ on buttons. Live confirm sheet says **REAL MONEY** with count, price, fee, total cost, profit if win. Settings opens with **Kalshi API key** first: status is `Live trading ready` / `Key saved - tap Test connection` / `No key - live orders disabled`. Home banner **Add your Kalshi API key to place real bets** (only when no key) opens Settings scrolled to that section. Version `DipHunter v<BuildConfig.VERSION_NAME> (<VERSION_CODE>)` at the top of Settings and the bottom of the home screen.
- Reuses 0.3.10 (PR #20): PEM paste, Test connection, Last order error, consistent quotes, keyed Approve → Live, toolbar insets.

## 0.3.10

- **Live Approve after API key:** Paper trading ON (default) no longer reroutes a keyed Live Approve into the paper book. Paper fills stay on the Paper button. Manual Buy is a fee-inclusive $5 (Settings stake) GTC limit — not resized to the $50 win target.
- **PEM paste:** PKCS#1 `BEGIN RSA PRIVATE KEY` and PKCS#8 `BEGIN PRIVATE KEY` accept CRLF, whitespace, one-line pastes, and missing newlines. Key ID alone is rejected on-screen (`CredentialWriteGuard.REJECT_KEY_ONLY`).
- **Settings → Test connection:** signed `GET /trade-api/v2/portfolio/balance` shows cash or the exact Kalshi body (401 `INCORRECT_API_KEY_SIGNATURE`, clock skew, missing PEM). Copyable Last order error panel.
- **4xx/5xx verbatim:** create-order failures keep the Kalshi JSON on the ticket, snackbar, SQLite ticket attempt, and Settings last-error.
- **Consistent live book:** WS ticker is YES-only; NO bid/ask are derived from the same update (`yes_bid = 1 − no_ask`). Stops the 0.3.9 mixed book (UP 55/63 + DOWN 37/50).

## 0.3.9

- **Chart after kill/reopen:** Bid + spot ticks persist in SQLite (`chart_ticks`, DB v5), trimmed to active/recent 15m windows. A new process restores the current window and gap-fills from Kalshi `GET /series/{series_ticker}/markets/{ticker}/candlesticks` (`start_ts` / `end_ts` Unix seconds, `period_interval=1`, `*_dollars` FixedPointDollars). Spot backfill uses the existing Coinbase 1m candles.
- **One quote source:** Header, chart labels, buttons, and payout multiple all read [MarketQuoteView] from the live book. 0¢ / missing asks render as **—** / **Buy UP|DOWN**.
- **Payout multiple:** `C × $1 / (C×P + orderFee)` at the $5 ticket (`C = floor(stake / P)`). 1¢ @ $5 → **93.46x** (not 99x). Fee is paid on top of the buy. Never divides by zero.

| Control | Code | Test |
|---------|------|------|
| Chart tick persist | `AsyncResultsWriter.enqueueChartTick` | `ChartWindowRestoreTest` |
| Chart restore after process death | `ChartWindowService.restoreWindow` | `ChartWindowRestoreTest.persistAndRestoreTicksAcrossNewRepository` |
| Candlestick backfill | `LiveWindowBackfill.candles` | `ChartWindowRestoreTest.parseDocAccurateCandlestickSample` |
| Header / labels / buttons | `MarketQuoteView.of` | `MarketQuoteViewTest` + `ChartWindowRestoreTest.labelsHeaderButtonsShareOneQuoteSource` |
| Payout multiple | `KalshiQuoteDisplay.multiplier` + `KalshiFee.payoutMultiple` | `MarketQuoteViewTest.oneCentAtFiveDollarsIsHandComputed93_46x` |

## `chat-GTP` separate install

This branch builds **DipHunter GTP** with the independent Android application
ID `com.dirk.kalshiodds.chatgtp` and a separate launcher icon. It installs
alongside the original app and has its own local data and API-key settings.
Each push publishes a versioned prerelease tagged `gtp-v<versionName>-<branch>`
and a rolling tag `<branch>-latest` (on this branch, `grokbot-latest`, asset
`DipHunter-grokbot.apk`). The in-app updater reads only the rolling tag from
`BuildConfig.UPDATE_RELEASE_TAG`. It accepts a download only from this
repository, and only after the APK's package name and signing-certificate
SHA-256 match the installed app.
See [SIGNING.md](SIGNING.md). CI increases the version code for each new run.
Android asks for approval to install an update from this app; confirm it to
update in place when the certificate matches. A certificate change requires
one uninstall. Never install an APK with a different application ID.

The branch-only model workflow publishes `edge-model-chat-GTP` when manually
dispatched on `chat-GTP`. See [the audit](docs/ml-audit-chat-GTP-2026-09-27.md)
for the current evidence and unresolved trading risks.

## 0.3.8

- **Paper Buy fix:** Paper mode Approve / Paper tap fills the paper book with no Kalshi key. Win-target size above paper cash is capped, not blocked. Failures show a reason on the card.
- Weekly edge-model retrain (`edge-model-latest`) + one-tap **Get latest model** (activate only if holdout beats the market; rollback kept).
- Auto-tune edge threshold from settled history; sit out when the model loses to the market (manual override in Settings).
- Optional Supabase sync of History / bets / signals / settings (never the Kalshi key).
- Notifications when a Long-shot or $50 win-target card appears (Approve still required).
- Adaptive launcher icon + per-coin / 4-hour ET scorecard breakdowns.

## Signing

Release APKs are signed from GitHub Actions secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). A push to `grokbot` does not publish when those secrets are missing. The old committed `app/signing/diphunter-debug.jks` and its passwords are public git history and are not used. v1.2+ uses the private release key, so a phone still on that old debug certificate must uninstall once. Details are in [SIGNING.md](SIGNING.md). Back up the Kalshi key from **Data → Back up credentials** before that uninstall. Encrypted credential prefs are excluded from Android Auto Backup.

**0.3.7** puts UP (YES) and DOWN (NO) best bid/ask — in cents, high contrast — on every live market, replaces the sparkline with a two-line Canvas chart (tap for full-screen scrub), and adds a **Data** screen: streamed CSV/JSON import, Kalshi settled-window backfill (WorkManager, resumable), Coinbase spot backfill, optional Supabase restore, and imported logistic weights from `python3 ml/train_edge.py`. A volatility digital-option fair value sits on each card; the imported model blends with the market and only flags an edge past fees + a confidence margin. Scorecard shows model vs market Brier and a “not enough data yet” state under 100 settled signals. Still approve-gated; no unsupervised auto-bets; Heavy ML stays throttled. **0.3.6** stops Live Approve from showing a page-level `No ask to size a limit on <ticker>` when a rolling 15m window has just closed (or one side has no sellers). Expired/closed markets drop or move hunter/manual tickets to the current live window; a missing ask is a disabled ticket card (`Market closed` / `No sellers on YES right now`). Asks come from documented `*_dollars` fields (including deci-cent `"0.0060"`), the opposite-side bid, and the WS book. Approve is still the only path that hits Kalshi; paper stays isolated. **0.3.5** retires the Kalshi **v1 create-order fallback** that produced HTTP 410 `deprecated_v1_order_endpoint` on Live Approve, and adds a visible **paper book** ($100 start / $5 AI fills) on the home screen. **0.3.3 fixes two live 0.3.2 bugs:** (1) light-mode / Extended AI `ConcurrentModificationException` from iterating a live order-book TreeMap (and unsynchronized flicker / flow maps) while WS deltas mutated them — fail-soft snapshots + thread-safe structures; OverlayThrottle and the UP/DOWN hero stay. (2) inverted NO/DOWN recommendations when spot was hundreds of dollars **above** the 15m target and climbing — `delta = fair − mid` was a fade-the-expensive-YES rule that ignored `sign(spot − strike)`. Direction now locks to YES=UP / NO=DOWN for Kalshi crypto 15m “price up?” markets. **0.3.2** added latest-wins overlay throttle + live UP/DOWN hero. **0.3.1 stops remaining mid-session crashes after 0.3.0 Heavy ML** (book-delta scoring flood, tick-thread DataStore rewrites, unsynchronized ensemble, confirmed 256MB `OutOfMemoryError` on Galaxy S24 Ultra SM-S928U) and **persists results to SQLite + `results.log` + CSV export**. Default is **light mode** (0.2.x blend). One OOM immediately persists Heavy ML off; other failures auto-disable after 3. **0.3.0 added on-device heavy ML** (sequence CNN/LSTM, GBM, ensemble, uncertainty gate, continual calibration, policy-eval scorecard) **plus extended AI 10–19**. **0.2.4 stops mid-session crashes** from the 0.2.3 keep-alive path (shared TFLite, live order-book races, specialUse FGS). **0.2.3 keeps live odds alive in the background.** **0.2.2 added approve-gated limit tickets.** There is no unsupervised auto-bet, no background auto-fire, and no order without an in-app **Approve**. The RL sizer is **advisory only**. **Not financial advice. High variance — you can lose the full stake.**

## Closed 15m windows + ask parsing (v0.3.6)

**Bug (user screenshot, 0.3.5):** Live Approve showed a red page-level

`No ask to size a limit on KXBTC15M-26SEP241645-45`

at `Updated 4:45:40 PM` while Recent signals still listed that ticker (AI 95% vs mkt 98%, Lean DOWN / NO).

**Cause:** `KXBTC15M-26SEP241645-45` is the 4:45 PM EDT 15-minute window. At 4:45:40 that contract is past `close_time`. Kalshi GET `/markets?status=open` then lists the next window (`KXBTC15M-26SEP241700-…`, `status=active`). The stale hero/card survived the last REST/cache snapshot; Buy called `TicketBuilder.proposeManual`, `bestAsk` was empty, and `OddsViewModel.buyMarket` `failSoft`’d a generic section error. Manual tickets were also preserved across `replaceProposals` after the ticker left the live set.

**Fix:** `close_time` / `status` (`active` vs `closed`/`determined`/`finalized`) prune and remap tickets to the current live 15m window. Closed markets never size a Kalshi order — the card reads **Market closed**. Open markets parse `yes_ask_dollars` / `no_ask_dollars` (and `yes_ask_size_fp`); `"0.0000"` / `"1.0000"` are not quotes; a one-sided book still implies the ask as `1 − opposite bid` or from the WS book. If that side really has no sellers, the ticket itself says **No sellers on YES right now** and Approve stays off. No more page-level “No ask to size” banner.

**Positions + sell:** Home shows **Your positions** from signed GET `/portfolio/positions` (`market_positions`, `position_fp` >0 YES / <0 NO). Sell opens the same approve-gated V2 GTC path (`side=ask` to sell YES, `side=bid` to sell NO, documented `reduce_only`, never v1 `action` / `/portfolio/orders`). Limit defaults to the best bid; count is capped at held. No bid → **No buyers right now**, Approve off. Buying the opposite side of a hold shows “This will close N of your UP/DOWN shares.” Paper fills have a labeled **Paper sell**. Still approve-gated; paper never hits the order API.

## V2 Live Approve + paper book (v0.3.5)

**Bug (user screenshot, 0.3.4):** tapping Approve on a hunter ticket (`KXBTC15M-26SEP241445-45` YES @ 1¢) showed

`HTTP 410 — {"error":{"code":"deprecated_v1_order_endpoint","message":"Please switch to the V2 endpoints",…}}`

**Cause:** `KalshiTradeClient.createLimit` posted V2 `POST /portfolio/events/orders` first, then on HTTP 404 fell back to legacy **`POST /portfolio/orders`**. Kalshi retired that v1 write (changelog: returns “Please switch to the V2 endpoints”). The 410 body in the screenshot is that fallback — not a successful V2 ack.

**Fix:** Live Approve is **V2 only**. Create `POST /trade-api/v2/portfolio/events/orders` with documented fields (`ticker`, `side` = `bid`/`ask`, `count`, `price`, `time_in_force`, `self_trade_prevention_type`, `client_order_id`). Cancel `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`. Primary host `external-api.kalshi.com`; elections host is V2 fallback only. **`/portfolio/orders` is never called.** 410/404 surface a readable error; the pending card stays so you can retry. Still approve-gated — no unsupervised auto-bets. Auth (Key ID + PEM signing) is unchanged (`timestamp + METHOD + /trade-api/v2/portfolio/events/orders`).

**Paper trading:** 0.3.4 only had an advisory “bankroll” for suggested size — no paper ledger, so AI/LiveCall never recorded simulated fills. Home now shows a **PAPER BOOK** card (start/reset **$100**, **$5** per AI hunter / LiveCall fill). Paper never hits the Kalshi order API. **Paper $5** vs **Live Approve** are labeled separately.

**Checklist contrast:** Pre-trade checklist rows and lower market stats (Spread / Volume / OI / 24h / Liquidity / Time left) used `MaterialTheme.colorScheme.onSurface` on a hardcoded dark card. With a light system theme that is near-black on black — the “No info” screenshot. Those rows now use Material3 `onSurface` / `onSurfaceVariant` against `colorScheme.surface`, with a WCAG-ish fallback so text cannot disappear. Missing stats render as **—**.

## Light-mode CME + direction lock (v0.3.3)

- **CME:** Scoring no longer holds a live `LocalOrderBook`. Imbalance / depth / pulse are copied under `TickBook`’s lock (`BookView`). `LocalOrderBook` mutators and iterators are synchronized. `ExtendedAiRuntime.evaluate` is synchronized and wrapped in `SafeMl` (`label=extended`); flicker uses `ConcurrentHashMap`; rival-flow centroids are synchronized. The banner `Light mode: ConcurrentModificationException: extended` was this race (exception message is null, so the guard used the `"extended"` label).
- **Direction:** For KXBTC/ETH/SOL 15m “price up?” contracts, YES=UP and NO=DOWN. If spot is above the Kalshi `floor_strike` (or parsed target) and rising, the app leans **UP / YES** even when market YES is already expensive. Displayed UP/DOWN percentages are clamped to the same side of 50% as that recommendation.

## Persistence + crash harden (v0.3.1)

Results now survive a process kill:

- **SQLite** (`diphunter_results.db`): scored snapshots, alerts, scorecard / policy-eval rows, Approve-ticket attempts (advisory; never unsupervised).
- **`results.log`**: rolling append-only text in app files.
- **Export results** (Settings or Scorecard): CSV under app Downloads / MediaStore `Download/DipHunter`.
- Cold start reloads recent SQLite rows into Recent signals / Saved results.

**What was killing 0.3.0 mid-session**

Confirmed on a Galaxy S24 Ultra (`SM-S928U`) at 2026-09-23 20:11:27.432-0400: `java.lang.OutOfMemoryError` allocating 32 bytes with ~1.79MB free, **heap growth limit 256MB**, victim thread `CancellableContinuationImpl` / EventLoop / DefaultExecutor. Historical (already partly 0.2.4): `LocalOrderBook` CME, `ForegroundServiceDidNotStopInTimeException`, `ForegroundServiceStartNotAllowedException`.

1. Every Kalshi **order-book delta** launched a scoring coroutine and ran the full Heavy ML + Extended AI stack (CNN/LSTM, 3× MC-dropout, GBM, 48-path Monte Carlo) and then rewrote the entire prediction-log DataStore JSON. Deltas arrive tens of times per second → allocation storm → **OOM** → process death. Book mutations apply on the WS thread; scoring is **latest-wins** (one drain coroutine, not one-per-delta) and throttled to ≥400ms per ticker. DataStore writes are single-in-flight on IO; SQLite/text persist even if DataStore is skipped.
2. Sequence / news / ensemble allocations were unbounded. Caps now: 12 tickers × 80 raw frames, reused CNN/LSTM scratch, RSS ≤48KB / 12 titles, 16 path-sim paths, no stored backbone/tabular, no 3× MC-dropout. Heap ≥80% skips Heavy ML for that tick; ≥90% or any `OutOfMemoryError` latches light mode and persists via SharedPreferences (not DataStore).
3. **`HeavyMlRuntime` was not synchronized.** WS ticks and REST refresh shared `heads` / `stack` / a growing `lastActivation` map → `ConcurrentModificationException` and native-adjacent corruption.
4. A single infer/OOM/TFLite failure could escape. Infer is now fail-soft to the **0.2.x MLP blend**. One OOM persists Heavy ML off across process restarts; other failures trip after 3 (or a recent crash breadcrumb mentioning OOM/TFLite).
5. Compose overlaid every score immediately — book floods hit the main thread. Overlay is a 250ms latest-wins throttle (apply now if the interval elapsed, else one trailing apply). Debounce reset on every WS delta and never fired.

`largeHeap` was **not** added — the 256MB limit is cut by bounding allocations, not by asking for a bigger heap.

**Light mode:** Settings → Light mode (default for new installs and a one-time 0.3.1 reset). Turn Heavy ML back on if the phone can take it; that also clears the persisted OOM latch.

## Heavy ML (v0.3.0)

Analysis + existing approve-gated tickets only. Heavier models use more CPU/battery — turn them off in Settings to force the 0.2.x MLP blend.

1. **Sequence model.** Temporal CNN + TinyLSTM over the last 1–5 min of mid, size, imbalance, aggressor flow, and spot (30 × 10s bins). The 8-feature MLP stays in the stack.
2. **GBM second opinion.** Pure-Kotlin tree ensemble on 16 tabular features, stacked with the neural outputs.
3. **Continual fine-tune + calibration.** Settlement replay updates the YES last layer and ensemble weights. Platt + isotonic per series × TTE regime. Cold start is identity.
4. **Multi-task heads.** P(YES/NO), time-to-move, mid vol, P(fill at limit) — shown on cards and the checklist.
5. **Cross-market backbone.** Shared encoder + BTC/ETH/SOL series embeddings for lead–lag transfer.
6. **Teacher distill.** `python3 ml/train_heavy.py` exports `heavy_ml_student.json` (and optional `diphunter_seq.tflite`). See `ml/DISTILL.md`. Phone stays on the student.
7. **Uncertainty gate.** Ensemble variance or MC-dropout proxy. Alerts and tickets (when gates are on) require uncertainty ≤ Settings threshold.
8. **Microstructure embeddings.** 8→4 autoencoder over book snapshots, fed into GBM / scorer.
9. **Policy eval.** Scorecard counterfactual: simulated ROI / Brier if every alert were taken at stake X (default $5). Not live P&L.
10. **Regime classifier.** Softmax session / weekend / news-shock tags; reweights the ensemble per regime (does not mutate persisted stack weights).
11. **Anomaly / spoof detector.** Cancel storms, quote stuffing, fake depth — downrank or block.
12. **Survival / hazard.** P(YES wins | time left, path); votes into fair after ≥8 ticks.
13. **RL sizer (advisory).** Softmax stake fraction from settlements. Shown on cards / tickets. **Never places an order.** Respects $5 default / $25 hard cap. Ticket stake stays the configured Settings value.
14. **News / social pulse.** 16-d hash embed + bull/bear lexicon for BTC/ETH/SOL. Fail-soft RSS cache.
15. **Rival-flow clustering.** Online centroids (mixed / chase / smart-like); boosts when flow aligns.
16. **Bayesian MM shadow.** Latent fair + inventory from the book; ensemble voter.
17. **Conformal prediction sets.** Coverage-guaranteed {YES}, {NO}, or {YES,NO}. Hide when ambiguous and the bag is ready.
18. **Meta-labeling.** Secondary take/skip on top of the primary side. Cold start always takes.
19. **Synthetic path simulator.** 16×10 Monte Carlo mids; P(edge survives to expiry). Blocks when that probability is <35% and history is warm.

Cold start: if the sequence window is short and the stack has not been fine-tuned, scoring is the **0.2.x blend** (MLP + microstructure). Extended-AI fair voters join only after ≥8 ticks. Conformal / meta never skip while cold. New models drop in as history arrives.

### Settings knobs (v0.3.0)

| Knob | Default | Role |
|------|---------|------|
| Light mode | on (0.3.1) | Heavy ML + Extended AI off — safer on device |
| Heavy ML | **off** | Master switch. Off = 0.2.x blend. One OOM persists off; other failures after 3 |
| Sequence model | on | Temporal CNN / TinyLSTM |
| GBM second opinion | on | Tabular booster |
| Continual fine-tune | on | Last-layer + regime cal from settlements |
| Uncertainty gate | on | Block alerts / tickets when ensemble disagrees |
| Max uncertainty | 0.12 | Stddev in probability units |
| Policy-eval stake | $5 | Scorecard counterfactual size |
| Extended AI | **off** | Master switch for capabilities 10–19 |
| Regime classifier | on | Session / weekend / news-shock reweight |
| Anomaly / spoof gate | on | Block on cancel-storm / stuffing / fake depth |
| Survival / hazard | on | P(YES \| TTE, path) voter |
| RL sizer | on | Advisory stake only — never auto-bets |
| News pulse | on | Cached headline prior; fail-soft offline |
| Rival-flow clustering | on | Smart-like flow boost |
| Bayesian MM shadow | on | Book-implied fair voter |
| Conformal sets | on | Skip when {YES,NO} and bag is ready |
| Meta-label take/skip | on | Precision gate after primary side |
| Path simulator | on | Monte Carlo P(edge survives) |

Heavier models may increase battery and CPU. Disable Heavy ML, Extended AI, or individual pieces if the phone runs hot.

## Background live odds (v0.2.3 / crash-hardened 0.2.4)

Android was treating Dip Hunter as a normal Activity: leaving the app or turning the screen off let the process die, which tore down the WebSocket and scoring loop. Live signals now own a persistent foreground service.

**How to keep it alive**

1. Turn **Live signals** on (home screen card or Settings). Leave it on.
2. Allow the ongoing **“DipHunter live signals”** notification. Do not swipe it away or deny notification permission.
3. Optional on aggressive OEMs: Settings → **Allow background** → set Dip Hunter battery usage to **Unrestricted**.

The service restarts after process death (`START_STICKY`), after swipe-from-recents (`stopWithTask=false` + `onTaskRemoved`), after reboot / app update, and via a 15-minute WorkManager watchdog. Trading is unchanged: tickets still need an in-app **Approve**.

## Approve-gated tickets (v0.2.2)

Human **Approve** is required for **that** ticket before any order is sent.

1. **Stake.** Default **$5 USD**. Settings may lower it. Raising above $5 requires typing `RAISE`. Hard cap **$25** so this cannot become a large auto-bot.
2. **Payout gate.** A ticket is proposed only when max settlement payout for the proposed stake is **≥ $100**.
   - Kalshi binaries settle at **$1.00** per winning contract.
   - `contracts = floor(stakeUsd / conservativeLimitPrice)`
   - `maxSettlementPayout = contracts × $1.00`
   - Propose iff `maxSettlementPayout ≥ $100`
   - At the $5 default that means a conservative limit **≤ $0.05** (5¢) **and** enough visible size at/under that price. If the ask is too high or the book is too thin, no ticket.
3. **Quality gates (default ON).** Skip filter, not muted, and alerts not paused by streak/drawdown. Settings can disable these for tickets only.
4. **Limit only.** GTC limit at the conservative ask. Never a market order. Ticket shows side, ticker, size, estimated fill, max payout, net EV when available.
5. **Auth.** Uses the Key ID + PEM already in Settings (`EncryptedSharedPreferences`). PEM is never logged. Soft-fail with a clear error. Create: `POST /trade-api/v2/portfolio/events/orders` (V2 only — never `/portfolio/orders`). Cancel: `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`.

## Decision support (v0.2.1)

Advisory layer on top of the v0.2.0 predictability stack. Still **never** calls Kalshi trade/order endpoints.

1. **Bankroll & size.** Settings: bankroll, Kelly fraction (default quarter-Kelly, 5% cap) or fixed-fraction. Each card shows **“N contracts max”** from edge, price, bankroll, liquidity, and spread. Advisory size is separate from the $5 ticket stake.
2. **Net EV after fees/spread.** Kalshi-style taker fee `feeRate × P × (1−P)` (default 7%, configurable) plus half-spread. Ranked opportunities and alerts use **net EV** when that toggle is on; raw edge still shows.
3. **Series / regime auto-mute.** Rolling 7-day hit rate below a Settings floor (default 40%, ≥8 samples) mutes that series, regime, or TTE window — no alerts, downranked, **Muted** chip. Disable in Settings.
4. **On-device adapter.** Settlements reweight blend channels (EMA) and fit a slope/intercept on `logit(p)` — not just temperature. Cold start is identity; weights persist in DataStore. No remote training.
5. **Streak / drawdown guard.** Pause alerts after N wrong in a row (default 4) or after a one-contract P&L-proxy drawdown (default $50). Banner: **alerts paused — streak guard**. Resume in Settings or on the next session.
6. **External spot features.** Public Binance (spot + 1m klines + USDT-M funding) with Coinbase REST fallback for BTC/ETH/SOL. Timeouts, cache, fail-soft. Market data only — no exchange orders.
7. **Pre-trade checklist.** Side, suggested size, net EV, confidence, regime, TTE, skip-filter. One-tap copy as plain text.

### Settings knobs (v0.2.1)

| Knob | Default | Role |
|------|---------|------|
| Bankroll (USD) | 1000 | Size suggestions only |
| Kelly / fixed fraction | quarter-Kelly 0.25 | Clip as % of bankroll |
| Max clip | 5% | Hard cap |
| Fee rate | 7% | Kalshi-style `P(1-P)` |
| Rank by net EV | on | Filter/rank after fees+spread |
| Auto-mute | on | Weak series/regimes |
| Hit-rate floor | 40% | Mute threshold |
| Streak N | 4 | Pause after N wrong |
| Drawdown $ | 50 | Pause on proxy DD |
| Resume on new session | on | Clear pause at process start |

## Predictability stack (v0.2.0)

End-to-end analysis upgrades on top of the v0.1.9 crypto WS pipeline. Still **never** calls trade endpoints.

1. **Live settlement feedback + recalibration.** Each watched market stores ticker/series, model P(YES), mid at signal time, predicted side, edge, confidence, regime, TTE bucket. On settle (YES/NO/void) the log is scored. After **20** outcomes a temperature scalar + reliability bins calibrate displayed P(YES) and edge; cold start uses the raw blend.
2. **Richer microstructure.** Aggressor/taker side (trade feed), depth near mid (3¢) vs depth decay (15¢), quote pulls and large cancel spikes from the local book. Folded into fair value with documented weights in `ScoringEngine`.
3. **Cross-asset lead–lag.** Time-aligned BTC ↔ ETH/SOL mids. BTC move over the last ~3s (minus 0.4s) vs follower move over the last 0.4s; reverse when the target is BTC.
4. **Time-to-expiry regimes.** Early window vs last ~3 minutes (`LATE`). Late blend shifts weight from AI/related/lead–lag to velocity, imbalance, depth, and cancels. Cards show `Early window` / `Last 3 min`.
5. **Confidence + skip filter.** Alerts and ranked opportunities require min confidence (default 45%), min liquidity (volume / OI / near-mid depth, default 500), and max spread (default 8¢). Weak/filtered edges are hidden when **Hide weak** is on (Settings).
6. **Regime tags.** Vol spike / quiet / trend / chop from recent mid vol + velocity. Shown on cards; they nudge blend weights and confidence.
7. **Scorecard.** Top-bar assessment icon → daily / rolling 7-day / all-time hit rate, Brier, avg edge when right vs wrong, per-series accuracy, sample count. Calibration status is on that screen.

### How to use the scorecard and new Settings

- Home → **scorecard icon** (or tap the scorecard hint under the HUD).
- **Settings → Skip filter:** min confidence, min liquidity, max spread, hide-weak toggle.
- Defaults live in `app/src/main/assets/default_signal_config.json`.

## Live signals (v0.1.9+)

Event-driven fair-value alerts on crypto ticks.

1. Create a Kalshi API key: [Account → API Keys](https://kalshi.com/account/profile) → Create Key. Save the **Key ID** and the **private key PEM** (RSA or Ed25519). The PEM cannot be downloaded again.
2. In the app: **Settings** → paste Key ID + PEM → **Save key**. The PEM is stored in `EncryptedSharedPreferences` and is **never logged**.
3. Enable **Notifications** (Android 13+ will prompt for `POST_NOTIFICATIONS`) and **Live signals (WS foreground)**.
4. The foreground service opens:

   `wss://external-api-ws.kalshi.com/trade-api/ws/v2`

   (fallback host: `wss://api.elections.kalshi.com/trade-api/ws/v2`)

   Handshake headers (required even for public `ticker`): `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP` (ms), `KALSHI-ACCESS-SIGNATURE` over `timestamp + "GET" + "/trade-api/ws/v2"` using RSA-PSS/SHA-256 or Ed25519.

5. The client subscribes to `ticker`, `orderbook_delta` (snapshot then incremental deltas), and optionally `trade` **only for watched crypto markets**.
6. If keys are missing: fast REST poll continues and the status chip reads **“WS needs API key — using REST”**. Local book imbalance is then unavailable and that blend weight is dropped.

**Notifications:** channel `diphunter_signal_alerts` (HIGH). Foreground ongoing: `diphunter_live_signals` (LOW). Target path is tick-receive → notify in under 1s while the service is running. Killed-app remote push would need FCM later; this release is **local instant alerts** only.

## What the app does

1. Fetches the open **Bitcoin** 15m market (`KXBTC15M` only).
2. Displays each market as a card (AI YES/NO, edge, bid/ask, volume, OI).
3. Probability rule: mid = `(yes_bid + yes_ask) / 2` else `last_price`.
4. Scoring engine (dedicated tick dispatcher):

   `fairValue = blend(ensemble(MLP, TCNN, LSTM, GBM), volume-flow+aggressor, related mid, velocity, imbalance, lead–lag, depth/decay, cancel/pull)`  
   then **calibrate** (global + per series/TTE) when enough settlements exist.  
   `delta = calibratedFair − marketMid` (percentage points)

   Alerts fire only when `|delta|` ≥ threshold, the skip filter passes, **and** uncertainty is below the Settings cap (10s debounce per ticker).

## Dip Hunter AI (TFLite)

On-device YES/NO via the 8-feature **TensorFlow Lite** MLP (`Input(8) → Dense(32) → Dense(16) → Dense(2, softmax)` = `[P(NO), P(YES)]`) **plus** the 0.3.0 sequence/GBM ensemble. Features: see `ml/FEATURES.md`. The historical trainer still includes settled WTI rows; **runtime inference and the live watchlist are crypto-only**.

**Edge hunting:** Dip Hunter edge (AI−market pp), ranked opportunities, stance text (Lean UP/YES or DOWN/NO — no orders). When spot vs strike is known, the recommended side matches that delta; a fade vs a rich YES mid cannot invert it.

## Open in Android Studio

1. Install Android Studio with SDK **35** and JDK 17+.
2. **File → Open** this folder.
3. Run the `app` configuration on API 26+.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/DipHunter-debug.apk
```

## API notes

- Public REST base: `https://api.elections.kalshi.com/trade-api/v2`
- Authenticated trade writes: `https://external-api.kalshi.com/trade-api/v2` (elections as V2-only fallback)
- WS: `wss://external-api-ws.kalshi.com/trade-api/ws/v2` (elections host as alternate)
- Public REST (no auth): `GET /markets?series_ticker=KXBTC15M&status=open`
- Authenticated create (after Live Approve): `POST /portfolio/events/orders` (V2 bid/ask, GTC limit). Cancel: `DELETE /portfolio/events/orders/{order_id}`. Never `POST /portfolio/orders`.
- Example tickers: `KXBTC15M-26SEP231600-00`, `KXETH15M-26SEP231645-45`, `KXSOL15M-26SEP231645-45`

## Project layout

```
app/src/main/java/com/dirk/kalshiodds/
  data/api/          Retrofit KalshiApi + authenticated KalshiTradeApi (create/cancel)
  data/dto/          MarketDto / MarketsResponse
  data/local/        DataStore MarketCache (btc/eth/sol/extra)
  data/repo/         MarketRepository (crypto series only)
  domain/            MarketUiModel, CryptoMarkets (allowlist / WTI reject)
  prediction/        DipHunterModel, FeatureVector, settlement scoring
  signal/
    config/          DataStore prefs + EncryptedSharedPreferences + default JSON
    ws/              KalshiWsAuth, KalshiWsClient, KalshiWsMessages
    engine/          ScoringEngine, TickBook, LocalOrderBook, MarketRegime, SkipFilter
    ml/              Sequence, TCNN, TinyLSTM, GBM, ensemble, uncertainty, fine-tune, policy eval
    feedback/        Calibrator, ScorecardMetrics, OnlineAdapter, Allowlist, Guardrails
    sizing/          PositionSizer, NetExpectedValue (advisory)
    trade/           PayoutGate, TicketBuilder, TicketSession (Live Approve)
    paper/           PaperBook ($100 / $5 AI fills, never Kalshi)
    external/        Public Binance/Coinbase spot · vol · funding
    checklist/       Pre-trade checklist + copy text
    notify/          SignalNotifier (HIGH alerts + ongoing FGS)
    service/         LiveSignalsService + keep-alive / boot / battery prompt
    model/           MarketTick, SignalAlert, WsConnectionState
    SignalHub.kt     tick dispatcher → UI + notifications
  ui/                OddsScreen, SettingsScreen, ScorecardScreen, DataScreen, ViewModels, MarketCard, BidChart
  worker/            MarketRefreshWorker (15 min) + BackfillWorker
  MainActivity.kt
  KalshiOddsApp.kt
  AppContainer.kt
app/src/main/assets/default_signal_config.json
app/src/main/assets/heavy_ml_student.json
ml/README.md             one-command edge trainer
ml/train_edge.py         walk-forward logistic → compact JSON
ml/DISTILL.md            teacher → student weight refresh
ml/train_heavy.py        export student JSON / optional TFLite
```

## Configuration (Settings + DataStore)

- Watch toggles: BTC 15m / ETH 15m / SOL 15m
- Extra **crypto** ticker list (non-crypto values are dropped)
- Edge threshold (pp), notifications on/off, Live signals on/off
- Skip filter: min confidence, min liquidity, max spread, hide weak opportunities
- Bankroll, Kelly / fixed-fraction, fee rate, net-EV ranking
- Auto-mute floor, streak / drawdown guard, resume
- Ticket stake ($5 default, $25 hard cap), quality gates for tickets
- Paper trading toggle (home-screen $100 book / $5 AI fills; default on)
- Heavy ML, sequence, GBM, uncertainty cap, continual fine-tune, policy-eval stake
- Extended AI master + regime / anomaly / survival / RL / news / flow / MM / conformal / meta / path-sim
- Kalshi API Key ID + private key PEM (secure storage; WS + Approve only)

Defaults live in `app/src/main/assets/default_signal_config.json` (`watchBtc/Eth/Sol: true`, threshold 5pp, live signals off, min confidence 45%, min liquidity 500, max spread 8¢).

## Secrets

- **Never** commit Kalshi private keys, PEM files, or API Key IDs.
- Do not embed secrets in BuildConfig.
- This app stores user-pasted keys on-device only.

## Requirements

| Item | Value |
|------|--------|
| minSdk | 26 |
| targetSdk / compileSdk | 35 |
| Kotlin | 2.0.21 |
| AGP | 8.7.2 |
| Compose BOM | 2024.10.01 |

## License / ownership

Built for Dirk Diggler. Prediction-market data © Kalshi; this app is an unofficial client.
