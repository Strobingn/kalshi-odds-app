# KIMI-Bitcoin branch

Working branch created from `main` for review findings and improvements.
**All work stays on this branch — main and other branches are untouched.**

## Code changes vs main

### Reliability

- `worker/MarketRefreshWorker.kt`, `worker/SyncWorker.kt`,
  `worker/BackfillWorker.kt`: cap WorkManager retries at 3 attempts
  (`MAX_RUN_ATTEMPTS`). All three previously returned `Result.retry()` on
  any exception, so a permanent failure (bad credentials, revoked Supabase
  project, API schema change) retried with backoff forever and looked
  "fine" while silently doing nothing. `BackfillWorker` also now surfaces
  the failure message in its output data.

### Rebrand: "Bitcoin Kimi"

- `res/values/strings.xml`: `app_name` = **Bitcoin Kimi**; notification
  channel/title strings renamed to match.
- New launcher logo: `drawable/ic_launcher_bitcoin_kimi_foreground.xml`
  (orange #F7931A coin with a stylized ₿ mark, sized to the adaptive-icon
  safe zone) + `ic_launcher_bitcoin_kimi_monochrome.xml`; both
  `mipmap-anydpi-v26` adaptive icons point at them; launcher background is
  now dark `#101418`.
- `ui/AppVersion.kt`: version label reads "Bitcoin Kimi v...".

### In-app updates

- `ui/AppUpdater.kt`: now polls the rolling release tag
  **`kimi-v1.0-KIMI-Bitcoin`** and accepts `BitcoinKimi-*.apk` assets
  (previously the `gtp-v1.0-chat-GTP` tag and `DipHunter-GTP-*` assets).
  `AppUpdaterTest` updated to match. Version codes stay monotonic
  (1,000,000 + CI run number), so each new CI build offers itself as an
  in-app update over the previous install.

### Branch-scoped edge model

- `signal/config/SignalConstants.kt`: `EDGE_MODEL_RELEASE_TAG` is now
  `edge-model-KIMI-Bitcoin`, so **Data → Get latest model** reads this
  branch's model release, not chat-GTP's.
- `ml/train_edge.py`:
  - **BTC-only training.** The app trades `KXBTC15M` exclusively
    (`CryptoMarkets.DEFAULT_SERIES`), but the trainer used to dilute its
    sample budget across ETH/SOL. All 180 default markets are now BTC.
  - **Honest ask-fill simulation.** The trainer's diagnostic P&L used to
    fill at the YES *midpoint*. The 2026-09-25 backtest proved midpoint
    fills flatter every strategy (app picks: −$226 at close-ask fills vs
    −$843 at worse fills). The sim now fills at the candle's `yes_ask`
    (or mid + a fixed 0.5¢ half-spread when the ask print is missing) and
    prices NO at the derived NO ask.
  - Manifest tag `edge-model-KIMI-Bitcoin`; trainer UA `BitcoinKimiTrainer`.
- `ml/test_train_edge.py`: 8 tests, all passing — including new checks
  that the sim never fills at the bare midpoint, BTC-only series, and the
  new manifest tag. (Verified by executing the suite locally.)
- `ml/README.md`, `ui/DataScreen.kt`: copy updated to the new release.
- **The activation gate is unchanged on purpose**: `ModelActivation` still
  refuses to activate any model whose verified holdout doesn't beat the
  market mid on Brier + log-loss. If a trained model doesn't beat the
  market, the app keeps the previous one. That's the correct behavior —
  do not weaken it to force a model live.

## Two workflow edits still needed (token lacks `workflow` scope)

The GitHub token available to the assistant gets a 403 on anything under
`.github/workflows/`. Apply these two edits manually (web editor works)
**on the KIMI-Bitcoin branch**:

### 1. `.github/workflows/build-apk.yml` — build + publish the APK

1. Trigger list: `branches: [Claude, main, chat-GTP]` →
   `branches: [KIMI-Bitcoin]`.
2. Rename APK step: `DipHunter-GTP-v...` → `BitcoinKimi-v...`.
3. Upload artifact name: `DipHunter-GTP-v...` → `BitcoinKimi-v...`.
4. Publish step: `tag="gtp-v..."` → `tag="kimi-v..."`, and release
   title/notes "DipHunter GTP" → "Bitcoin Kimi".

After that, every push builds a signed APK and publishes the rolling
`kimi-v1.0-KIMI-Bitcoin` prerelease that the in-app updater reads.

### 2. `.github/workflows/train-edge-model.yml` — train + publish the model

1. The three `edge-model-chat-GTP` strings → `edge-model-KIMI-Bitcoin`
   (the `gh release delete`, `gh release create` lines).
2. Optional: update the comment mentioning chat-GTP.

Then: **Actions → Train edge model → Run workflow → KIMI-Bitcoin**.
The trainer pulls settled KXBTC15M markets + Coinbase spot, validates
walk-forward, and publishes the model + manifest to the release the app
fetches. If the holdout doesn't beat the market, the manifest says so and
the app won't activate it.

### Also recommended: full unit-test workflow

```yaml
name: Unit tests

on:
  push:
    branches: [main, KIMI-Bitcoin]
  pull_request:
  workflow_dispatch:

concurrency:
  group: unit-tests-${{ github.ref }}
  cancel-in-progress: true

jobs:
  test:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - name: All unit tests
        run: ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
```

## Findings (review of main @ d34495b)

### Profitability reality check (from docs/backtest-2026-09-25.md)

- App picks as shipped: 1,451 OOS bets, **-$226.50** (-3.3% ROI), CI
  crosses zero.
- Naive always-favorite: -$1,119. Always-cheap: -$1,727. Random side:
  -$439. Every naive strategy loses.
- Market mid (Brier 0.1592) is **more accurate than the blended model**
  (0.1610). The market is near-efficient; prediction-only edges are very
  hard.

### Where a real edge could live (ranked)

1. **Settlement-mechanics edge** (docs/profit-research-chat-GTP-2026-09-28.md):
   KXBTC15M settles against the final-minute average of the CF Benchmarks
   BRTI. The `cfbenchmarks_value` WebSocket streams the running average +
   count; at observation k of 60, `S_k = k × running_avg`, so the
   remaining average only needs `(60K − S_k) / (60 − k)`. Late in the
   final minute the outcome is near-deterministic arithmetic while the
   book may lag. Measure the lag with a timestamped recorder + replay
   before betting — never assume it.
2. **Late-window harvesting**: OOS 2–1m left won 94.9% at 92.9¢ avg ask
   (+$7.86 / 39 bets). Thin margin; one loss wipes ~14 wins.
3. **32–50¢ slice**: 60.9% on 92 OOS bets, +$89.46, CI excluded zero —
   the only slice that did. Hypothesis, not result; paper-test first.
4. **Execution hygiene**: prefer maker orders over taker (7% fee), never
   cross wide spreads, skip markets within 5bp of strike (worst bucket:
   -$224.65).
