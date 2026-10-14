# Kalshi Trader (Android)

The shared guide for every branch of `Strobingn/kalshi-odds-app`: `kashi`, the Claude / ChatGPT / Cursor / Grok / Codex branches, and anything new. Read it before you start a branch, and follow the [rules every branch follows](#rules-every-branch-follows).

> The reference build is the **`kashi`** branch (Kalshi Trader **0.3.65**, package `com.dirk.kalshiodds.kashi`). `main` holds only this guide and some older code. Don't build from `main`.

---

## What the app is

**Kalshi Trader** (formerly DipHunter / Kalshi Odds) is a sideloaded **Android** app written in **Kotlin** with Jetpack Compose and Material 3. It watches Kalshi's **Bitcoin 15-minute** up/down markets (`KXBTC15M`), scores each window, runs **paper** strategies, and keeps a full record of results. Real orders are possible, but only one at a time, through a manual approval (see [Safety rules](#safety-rules)).

### Tabs

The FieldOps bottom bar, left to right:

| Tab | What it does |
|---|---|
| **Home** | Live BTC 15m card (UP/YES vs DOWN/NO bid/ask in cents, chart, AI %, $10 profit-if-win per side), *This window* decision, paper book, positions, D3 daily-favourite card |
| **Scalp** | The paper scalpers: leaderboard, per-window winner, paper price ladder, one page per algorithm, trade detail |
| **Scorecard** | Settled calls, W-L, win rate, paper P&L, 4-hour ET buckets. Every count comes from one source |
| **Live** | Approve-gated tickets. They wait for **Approve** and the **REAL MONEY** confirm |
| **Real Money** | Kalshi cash, Approve, and real orders. Never automatic |
| **Data** | CSV/JSON import, Kalshi and Coinbase backfill, offline model, Supabase sync status (*Last synced*, HTTP errors), DB size |
| **More** | Settings, History, Signal history, Calibration report, Strategy ladder, Scalp Data, Scalp (paper) rules, Crash log, Check for updates |

### Features

- REST polling plus an optional authenticated Kalshi WebSocket (`ticker`, `orderbook_delta`, `trade`), run by a foreground service with a WorkManager watchdog so odds stay live in the background.
- Fair value and direction: spot vs strike, microstructure, a lightweight ML blend, and calibration from settled windows. Heavy ML is optional and off by default.
- Always-on **Autopilot** (paper only), the paper book, and paper limit orders.
- The scalp lab: dozens of paper scalpers, each with its own ledger.
- SQLite persistence (results, chart ticks, tickets, paper and scalp ledgers), `results.log`, CSV export.
- A crash log with view, share, and export, uploaded through sync.
- A signature-checked in-app update checker.

---

## Architecture

```
app/src/main/java/com/dirk/kalshiodds/
  data/api, dto        Kalshi REST (public reads, signed V2 order create/cancel)
  data/backfill        Kalshi settled windows + candlesticks, Coinbase 1m spot
  data/local/...       SQLite stores: results, chart, paper, ledger, history, archive
  data/supabase        SupabaseSync / SyncMerge / SupabaseMirror (cloud sync)
  decision/            Scalp, ScalpModels, ScalpV2, ShockFav, Open50Model, Ladder, forecasters
  signal/              WS client, engine, fair value, paper, trade (tickets), d3, feedback, service
  crash/               CrashLog, SafeScopes, HeapInfo
  update/              KashiReleasePolicy, KashiUpdateClient, ApkArchiveInspector, installer
  ui/                  Compose screens, FieldOps chrome (ui/components/FieldChrome.kt), theme
  worker/              refresh, backfill, sync workers
```

### Data sources

- **Kalshi Trade API v2**: public market data (`/markets`, candlesticks, settlements), the WebSocket feed, and the user's signed portfolio and orders. Orders go only to V2 `POST /portfolio/events/orders`, never the retired v1 `/portfolio/orders`.
- **Coinbase / Binance public spot**: BTC price, 1-minute candles, and funding. Market data only, no exchange orders.
- **On-device models**: compact JSON/TFLite weights in `app/src/main/assets`, retrained by `ml/` scripts and the `train-edge-model` workflow.

### Sync (Supabase `diphunter_sync`)

The sync is optional and uses one table, whose schema is in `supabase/diphunter_sync.sql` on `kashi`:

| column | type | notes |
|---|---|---|
| `key` | text, primary key | namespaced per branch (kashi uses the prefix `kashi:`) |
| `kind` | text | `paper`, `bet`, `signal`, `settings`, `scalp`, `snapshot`, `shadow`, `crash`, ... |
| `updated_at` | bigint (ms) | last-writer-wins merge |
| `payload` | jsonb | the record |

- **Push** upserts in batches per kind, so one failing kind doesn't block the others.
- **Pull** runs one query per kind (`kind=eq.<kind>`, newest first) on a `(kind, updated_at, key)` index, plus the `kind` and `updated_at desc` indexes. A pull never uses `kind=not.in(...)`. `snapshot`, `scalp`, and `shadow` are push-only. `healthcheck` rows and rows from other namespaces are ignored.
- **Crash rows**: each crash file from `CrashLog` is uploaded once as a row with `kind = "crash"`, `key = "crash:<ms>"`, and the stack trace cut to 8 KB.
- The app uses only the **publishable/anon** key, which the user enters on the device. **The Kalshi key is never uploaded.** RLS stays enabled.

### Update checker

`update/KashiReleasePolicy.kt` reads `GET /repos/Strobingn/kalshi-odds-app/releases` (paged, 100 per page). It never uses `/releases/latest`, because that pointer can belong to another branch. A release qualifies only if all of these hold:

1. The tag matches `vX.Y.Z-debug`.
2. The release notes contain the exact line `app-id: <package id>`.
3. The asset is the expected file (`DipHunter-debug.apk` on kashi).
4. Before install, `ApkArchiveInspector` checks the APK's **package id** and **signing-cert SHA-256** against the expected values.

If any check fails, the release is skipped. So another branch's higher version number can never replace your build.

---

## The Scalp tab

The scalpers are **paper strategies only**. They have no order path, and **no LLM sits anywhere in the trade path**.

- **BTC only.** They trade `KXBTC15M` windows. Older ETH/SOL rows are filtered out, not traded.
- **Paper, unlimited bankroll.** Every scalper draws from one shared free paper bankroll with no per-model slice, so a strategy's results aren't limited by sizing.
- **10-contract clips.** Each entry is up to 10 contracts, capped by the size showing at the ask.
- **A stop on every bet.** Every model has a stop, and its card and leaderboard row show it (e.g. `stop 6¢`, `stop … after 60s`, or `(default)` when it wasn't trained).
- **Trailing exits.** Exits use net take-profit targets (after both fees), trailing off the peak bid, fair-value early exits, or holding to settlement, depending on the model.
- **Model families.** Fair-gap, Dip-hunter, Momentum-sniper, Extreme-reversion, CF-reprice, Maker dip-rebound (maker-first, $0 maker fee, queue-modelled fills), Forecast ride and its mirror Forecast fade, the 60-second hold and no-TP siblings, and the `sfNN` shock-lag / late-favourite variants.
- **v2 rules** (0.3.65). Each original model has a `v2-` copy. The copy takes the same entries but **only when the ask is ≤ 50¢**, keeps the original's stop and has no time limit. It sells only once the **gain after fees reaches the stop size**, then exits **2¢ off the peak bid**. The v2 copies are compared side by side with their originals.
- **Open 50.** The `Open 50 · sfNN` variants act at the window open near 50¢, every window. They can rest maker bids at 49¢ that fill when the ask trades through.
- **Deterministic direction model.** `Open50Model` (`o50ai-v1`) is a generated, versioned gradient-boosted model (60 depth-3 trees) with 13 numeric inputs: BTC returns, volatility, distance to strike, ETH/SOL moves, book imbalance, ask, spread, and elapsed time. It predicts a side's bid change over the next 60 seconds. A trade needs the prediction to beat spread plus both fees. The forecast scalpers likewise use a deterministic short-horizon forecaster. Same inputs, same output, no LLM calls.

### Fees

Kalshi's taker fee per order, in cents, rounded up:

```
fee = ceil( 7 · C · P · (1 − P) )      C = contracts, P = price in dollars
```

For example, 10 contracts at 50¢ gives ceil(7 · 10 · 0.25) = ceil(17.5) = 18¢. Every scalp P&L, take-profit check, and leaderboard number is net of the entry fee **and** the exit fee. Maker fills use the series maker rate, which is 0 by default.

### Leaderboard

There is one combined leaderboard: survivors first, then sorted by **net P&L after fees**. Each row shows wins and losses, the net result, and the stop. The tab also shows the per-window winner and a paper price ladder. Tap a model to open its own page with every round trip, the trade detail, and its equity curve. **More → Scalp Data** shows scalping results only. Scalp rows sync as `kind = scalp` (push-only).

---

## Safety rules

1. **Autopilot and all scalpers are paper only.** They have no code path to the Kalshi order API, and paper never blocks or changes a live order.
2. **Real money moves only through a manual Approve on one ticket, followed by typing `REAL MONEY`** on the confirm sheet. That sheet shows the contracts, the limit, the fee, the total cost, and the profit if the bet wins. Only limit orders are sent, never market orders, and nothing is automatic or happens in the background.
3. The live size has a hard cap at the final order-build step, and each order has one `client_order_id` that is reused on retries so nothing gets sent twice.
4. The Kalshi API key (Key ID + PEM) is stored only in Android Keystore-backed `EncryptedSharedPreferences`. It is never logged, never synced, and never written in plaintext.

---

## Build and release

### GitHub Actions

| Workflow (on `kashi`) | Trigger | Output |
|---|---|---|
| `kashi-build-apk.yml` | push to `kashi-*` branches, PRs into `kashi`, manual | unit tests and ML trainer tests, then `assembleDebug` + `verifyDebugCert`; uploads `KalshiTrader-kashi-v<ver>-<sha>.apk` as an **artifact** (never a release) |
| `kashi-unit-tests.yml` | kashi branches | `./gradlew testDebugUnitTest` |
| `kashi-release-guard.yml` | release published/edited | requires the `app-id:` line in `v*-debug` release notes. If the line is missing, it adds it and fails the job |
| `train-edge-model.yml` | schedule / manual | retrains the edge model |

**The branch name decides which workflows run.** Name work branches `<yourbranch>-<version>` (e.g. `kashi-0.3.66`) so the APK build triggers on them, and give each branch family its **own** prefix so pushes don't start another branch's builds. Workflows on `main` fire on pushes to `main`, so README-only changes to `main` use `[skip ci]`.

### Signing

Debug APKs are signed with a **permanent, committed debug keystore** so that sideloaded updates install over the old version without wiping data. `verifyDebugCert` fails the build if the cert's SHA-256 changes. The keystore is debug-only and for private sideloading. **Never** commit a release/upload key, keystore passwords, or Play credentials.

### Releases

- Use the tag `vX.Y.Z-debug` with the APK attached as the expected asset.
- **The first line of the release notes must be `app-id: <your package id>`**, for example `app-id: com.dirk.kalshiodds.kashi` for kashi. Without it the in-app updater ignores the release.
- Bump `versionCode` and `versionName` every release.

### Per-branch identity

**Every branch uses its own `applicationId` and its own signing key.** Pick something like `com.dirk.kalshiodds.<branch>`, then set your own `KashiReleasePolicy`-style constants (package id, cert SHA-256, app-id marker, asset name) and release notes to match.

> ⚠️ **Never reuse `com.dirk.kalshiodds.kashi`** (or the kashi signing key) on another branch. Android would treat your APK as an update to Kalshi Trader, and kashi's updater would accept or reject it in confusing ways.

---

## Data backup

The app's scalp, paper, and market history is also archived to a **separate private repository**. It's private on purpose. That archive is not linked here and must never be linked from public branches, and no credentials for it (or for anything else) belong in this repo. Back up the on-device keys from **Data → Back up credentials** (passphrase + system file picker) before uninstalling. Encrypted prefs are excluded from Android Auto Backup.

---

## UI conventions (FieldOps)

- **FieldOps chrome**: a gray accent on light and dark surfaces, with consistent type, spacing, and corner radii (`ui/components/FieldChrome.kt`, `ui/theme`).
- **Green means UP / YES and red means DOWN / NO, and nothing else.** P&L and win rate use neutral `+/−` text, not color.
- Every screen is a bottom tab or at most two taps from one (`DipNav.MAX_TAPS_FROM_TAB`). No screen gets dropped, and system Back pops the in-app stack.
- Show prices in cents. A missing value is `—`, never `0%` or `0¢`. Missing asks disable the button with a reason (e.g. *No sellers on YES right now*).
- Every count on a screen comes from one source, so the summary, the list, and the empty state can't disagree.
- The version (`v<versionName> (<versionCode>)`) appears in Settings and at the bottom of Home.
- Layouts must work at 360dp width and at 1.3× font scale.

---

## Rules every branch follows

1. **No secrets in git, ever.** No Kalshi Key IDs or PEMs, no Supabase URLs with keys, no service-role or anon keys, no GitHub tokens, no passwords, no private data. Grep your diff before every push, e.g. `git diff | grep -iE 'key|token|secret|BEGIN .*PRIVATE|eyJ|sb_'`.
2. **Paper by default.** Autopilot, scalpers, and every model stay paper. Real money needs a manual **Approve** and a typed **`REAL MONEY`** every time.
3. **No LLM in the trade path.** Entry and exit decisions come from deterministic, versioned code and models.
4. **Fees are always counted:** `ceil(7·C·P·(1−P))` cents per taker order, on both the entry and the exit.
5. **Your own package id and signing key.** Never `com.dirk.kalshiodds.kashi`.
6. **Releases are `vX.Y.Z-debug`, and the first line of the notes is `app-id: <package id>`.** Bump versions every time.
7. **Branch names match your workflow triggers.** Don't add workflows that build on every branch (`"**"`).
8. **Sync rows are namespaced** with your own key prefix in `diphunter_sync`. Never overwrite or delete another branch's rows, and never upload the Kalshi key.
9. **BTC 15m (`KXBTC15M`) only**, unless the branch is explicitly about something else. Never WTI or non-crypto.
10. **Don't touch other branches.** Open PRs into your own branch. `kashi` changes only through kashi PRs, and `main` holds docs only.
11. **Tests ship with fixes.** Run `./gradlew testDebugUnitTest` before every release.
12. **FieldOps UI conventions apply.**

---

*Unofficial client. Not financial advice. Prediction-market data © Kalshi. Built for Dirk Diggler.*
