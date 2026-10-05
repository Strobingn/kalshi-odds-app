# KIMI-Bitcoin branch

Working branch created from `main` for review findings and improvements.
**All work stays on this branch — main and other branches are untouched.**

## Code changes vs main

- `worker/MarketRefreshWorker.kt`, `worker/SyncWorker.kt`,
  `worker/BackfillWorker.kt`: cap WorkManager retries at 3 attempts
  (`MAX_RUN_ATTEMPTS`). All three previously returned `Result.retry()` on
  any exception, so a permanent failure (bad credentials, revoked Supabase
  project, API schema change) retried with backoff forever and looked
  "fine" while silently doing nothing. `BackfillWorker` also now surfaces
  the failure message in its output data.

## Findings (review of main @ d34495b)

### Profitability reality check (from docs/backtest-2026-09-25.md)

The existing out-of-sample replay is the most honest evidence available:

- App picks as shipped: 1,451 OOS bets, **-$226.50** (-3.3% ROI), CI
  crosses zero.
- Naive always-favorite: -$1,119. Always-cheap: -$1,727. Random side:
  -$439. Every naive strategy loses.
- Market mid (Brier 0.1592) is **more accurate than the blended model**
  (0.1610). The market is near-efficient; prediction-only edges are very
  hard.
- No tuned rule produced a positive OOS edge whose 95% CI excludes zero.

### Where a real edge could live (ranked)

1. **Settlement-mechanics edge (most promising, already in
   docs/profit-research-chat-GTP-2026-09-28.md).** KXBTC15M settles against
   the final-minute average of the CF Benchmarks BRTI, not the last trade.
   Kalshi streams the running average + observation count on the
   `cfbenchmarks_value` WebSocket. At observation k of 60, with
   `S_k = k × running_avg`, the remaining average only needs to exceed
   `(60K − S_k) / (60 − k)` to settle above strike K. Late in the final
   minute the outcome becomes near-deterministic arithmetic while the book
   may lag. This is an arithmetic edge, not a prediction edge — the only
   kind that reliably exists. **Next engineering step (per the research
   doc): a timestamped benchmark/book recorder + chronological replay
   harness. The lag must be measured, never assumed.**
2. **Late-window harvesting (real but thin).** OOS: 2–1 minutes left won
   94.9% at 92.9¢ avg ask (+$7.86 over 39 bets). High win rate, tiny
   margin; one loss wipes ~14 wins; CI crosses zero. Capacity-limited.
3. **The 32–50¢ slice (hypothesis, not result).** App picks at 32–50¢ went
   60.9% on 92 OOS bets, +$89.46, CI excluded zero — the only slice that
   did. Exploratory and untuned; live-paper-test it before sizing.
4. **Execution hygiene (guaranteed small savings).** Prefer resting maker
   orders over taker (7% round-lot taker fee), never cross wide spreads,
   skip markets within 5bp of strike — that bucket lost -$224.65, the worst
   slice in the whole backtest. Avoiding bad bets beat every picking
   strategy in the data.

### CI / testing gaps (recommended; not pushed — token lacks `workflow` scope)

- `.github/workflows/build-apk.yml` triggers only on `Claude`, `main`,
  `chat-GTP`. Add `KIMI-Bitcoin` (and any other working branch) to the
  push-trigger list so branches get built/tested/released.
- The APK workflow runs only ~13 cherry-picked test classes. Most of the
  suite (including the 80KB `KnownIssuesRegressionTest`) never runs in CI.
  Proposed `.github/workflows/unit-tests.yml`:

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
