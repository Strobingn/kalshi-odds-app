# Kimmy branch

Working branch for review findings and improvements. All work stays on this branch.

## Changes vs main

- `worker/MarketRefreshWorker.kt`, `worker/SyncWorker.kt`: cap WorkManager
  retries at 3 attempts. Both workers previously returned `Result.retry()`
  on any exception, so a permanent failure (bad credentials, revoked
  Supabase project, API schema change) retried with backoff forever and
  looked "fine" while silently doing nothing.

## Recommended, not yet applied (GitHub token lacks `workflow` scope)

- `.github/workflows/build-apk.yml`: add `Kimmy` to the push-trigger branch
  list so this branch builds/tests/publishes APKs like `main`.
- Add `.github/workflows/unit-tests.yml` running the **full**
  `:app:testDebugUnitTest` suite on pushes to `main` / `Kimmy` and on PRs.
  The APK workflow only runs a cherry-picked subset of trading-critical
  test classes; most of the suite (including `KnownIssuesRegressionTest`)
  never runs in CI. Proposed file:

```yaml
name: Unit tests

on:
  push:
    branches: [main, Kimmy]
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
