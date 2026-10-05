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

### CI (IMPORTANT — not yet committed)

The GitHub token available to the assistant lacks the `workflow` scope, so
changes under `.github/workflows/` were rejected with 403. To make CI
fire and produce the APK + release that the in-app updater reads, apply
this one-file change manually (web editor works):

In `.github/workflows/build-apk.yml` **on the KIMI-Bitcoin branch**:

1. Trigger list: `branches: [Claude, main, chat-GTP]` →
   `branches: [KIMI-Bitcoin]` (or just add `KIMI-Bitcoin` to the list).
2. Rename APK step: `DipHunter-GTP-v...` → `BitcoinKimi-v...`.
3. Upload artifact name: `DipHunter-GTP-v...` → `BitcoinKimi-v...`.
4. Publish step: `tag="gtp-v..."` → `tag="kimi-v..."`, and the release
   title/notes "DipHunter GTP" → "Bitcoin Kimi".

After that, every push to `KIMI-Bitcoin` builds a signed APK, publishes
the rolling `kimi-v1.0-KIMI-Bitcoin` prerelease, and installed apps pick
it up via the in-app "Check for app update" flow.

Also recommended: add a `unit-tests.yml` running the **full**
`:app:testDebugUnitTest` suite (the APK workflow only runs ~13
cherry-picked test classes; most of the suite, including
`KnownIssuesRegressionTest`, never runs in CI):

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
