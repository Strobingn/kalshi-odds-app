# Kalshi Trader (Android)

The shared guide for every branch of `Strobingn/kalshi-odds-app`. Read it before you start a branch, and follow the [rules every branch follows](#rules-every-branch-follows).

Checked against the remote on **17 Oct 2026**. The phone build to install is **`kashi`**: Kalshi Trader **0.3.89** (versionCode 104, package `com.dirk.kalshiodds.kashi`). `main` is this guide plus older app code. Do not install `main` over Kalshi Trader.

---

## Current branches

Each line below is its own install. Package id and signing key stay on that line. A higher version number on another branch is not an update.

| Branch | App | Package | Version | What that tip is |
|---|---|---|---|---|
| `kashi` | Kalshi Trader | `com.dirk.kalshiodds.kashi` | **0.3.89** (104) | Reference build. Tip is the merge of PR #126 (`kashi-0389-variant-params`). Release tag `v0.3.89-debug`. |
| `kashi-next-dev` | Kalshi Scalper | `com.dirk.kalshiodds.scalp` | **1.0.3** (4) | Separate scalper. 1.0.3 retunes losing paper strategies toward the fair-gap rule: 24–42¢, BRTI gap 10–21¢ after the taker fee, no 3¢ trail, about 4.5 minutes or settlement, one side per window. Includes `scalp-1.0.3-fairgap`. |
| `Claude` | Claude Bitcoin | `com.dirk.kalshiodds.claude` | **1.12.0** (65) | Strategy Arena. 1.12 gives every strategy the full settings set (window minutes, side filter, spread, order rate, resting-bid wait, trail, daily loss pause). 1.11 reads account fills, orders, and positions on the WebSocket and adds the 5 Hz CF index. Nothing in that subscribe places an order. |
| `grok-bitcoin` | grok-bitcoin | `com.dirk.kalshiodds.grokbitcoin` | **1.59.0** (95) | Dense scalp tab, live Kalshi price chart, late-favourite and cheap-side paper rules, Claude-style sliders. Default price band 15–85¢. |
| `chat-GTP` | Chat Bitcoin | `com.dirk.chatbitcoin` | **1.0** | Separate install. V3 is a Bitcoin-only shadow lab: a one-contract shadow after an executable ask clears spread, fee, and 3¢. No order path and no buy controls. |
| `grokbot` | grokbot | `com.dirk.kalshiodds.chatgtp` | **1.3.4** | Sync failures show the Supabase error. Rows use a `grokbot:` prefix. Scorecard dollars are settled paper fills since the last bankroll reset. |
| `bitcoin-swarm` | Bitcoin Swarm | `com.strobingn.bitcoinswarm` | **0.3.18** (33) | Twenty paper scalp strategies and a 50¢ entry gate. |
| `KIMI-Bitcoin` | KIMI | `com.dirk.scalphunter.kimi` | **1.0** | Own package. Last commit 15 Oct 2026. |
| `main` | this guide | `com.dirk.kalshiodds.chatgtp` | **1.0** | Docs and older DipHunter code. Same package id as `grokbot`. |

`kashi-0384-jobstart-anr` through `kashi-0389-variant-params` are already in `kashi` (PRs #121–#126). Older `cursor/*`, `vibe/*`, and experiment branches are still on the remote; they are not the installs in the table.

`main` and `grokbot` share `com.dirk.kalshiodds.chatgtp`. Do not sideload one over the other.

---

## What the app is

**Kalshi Trader** (formerly DipHunter / Kalshi Odds) is a sideloaded **Android** app written in **Kotlin** with Jetpack Compose and Material 3. It watches Kalshi's **Bitcoin 15-minute** up/down markets (`KXBTC15M`), scores each window, runs **paper** strategies, and keeps a full record of results. Real orders are possible, but only one at a time, through a manual approval (see [Safety rules](#safety-rules)).

### Tabs

The FieldOps bottom bar on `kashi`, left to right (`AppRoutes.TABS`):

| Tab | What it does |
|---|---|
| **Home** | Live BTC 15m card (UP/YES vs DOWN/NO bid/ask in cents, chart, AI %, $10 profit-if-win per side), *This window* decision, paper book, positions, D3 daily-favourite card |
| **Scalp** | Paper scalpers: live BTC 15-minute price chart, leaderboard, per-window winner, one page per algorithm, trade detail, **Reset scalp paper money** |
| **Scorecard** | **Paper fills** and **Forecasts** kept apart. Forecast rows with no fill are hit rate only and never enter paper W-L or P&L |
| **Live** | Approve-gated tickets. They wait for **Approve** and the **REAL MONEY** confirm |
| **Real Money** | Kalshi cash, Approve, and real orders. Never automatic |
| **Data** | CSV/JSON import, Kalshi and Coinbase backfill, offline model, Supabase sync status (*Last synced*, HTTP errors), DB size |
| **More** | Settings, History, Signal history, Calibration report, Strategy ladder, Scalp Data, Scalp (paper) rules, Crash log, Check for updates |

### Features

- REST polling plus an optional authenticated Kalshi WebSocket (`ticker`, `orderbook_delta`, `trade`), run by a foreground service with a WorkManager watchdog so odds stay live in the background.
- Fair value and direction: spot vs strike, microstructure, a lightweight ML blend, and calibration from settled windows. Heavy ML is optional and off by default.
- Always-on **Autopilot** (paper only), the paper book, and paper limit orders.
- The scalp lab: paper scalpers, each with its own ledger. Proven losers (at least 30 closed trades and a confidence interval below zero) are retired off the cards; a retired original can still feed its kept v2 copy as a hidden row.
- SQLite persistence (results, chart ticks, tickets, paper and scalp ledgers), `results.log`, CSV export. The scalp book keeps open trades in memory and pages closed trades.
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
- **Coinbase / Binance public spot**: BTC price, 1-minute candles, and funding. Market data only, no exchange orders. On `kashi` the Coinbase spot line is orange.
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

`update/KashiReleasePolicy.kt` reads `GET /repos/Strobingn/kalshi-odds-app/releases` (paged, 100 per page, up to 3 pages). It never uses `/releases/latest`, because that pointer can belong to another branch. A release qualifies only if all of these hold:

1. The tag matches `vX.Y.Z-debug`.
2. The release notes contain the exact line `app-id: <package id>`.
3. The asset is the expected file (`DipHunter-debug.apk` on kashi).
4. Before install, `ApkArchiveInspector` checks the APK's **package id** and **signing-cert SHA-256** against the expected values.

If any check fails, the release is skipped. So another branch's higher version number can never replace your build.

Kashi's expected cert SHA-256 is `64e2a43a6897c4556a36b82ea31dc89550c65e56b053658e1438bdf3c4cc4608` (`CN=DipHunter Debug, OU=Sideload, O=Dirk`).

---

## The Scalp tab

The scalpers are **paper strategies only**. They have no order path, and **no LLM sits anywhere in the trade path**.

- **BTC only.** They trade `KXBTC15M` windows. Older ETH/SOL rows are filtered out, not traded. A tie does not default to UP. The short-horizon forecast is antisymmetrised so the two sides are mirrors.
- **Chart.** The Scalp tab opens with the live BTC 15-minute price chart. Each strategy also has its own window chart and execution markers. Spot on the chart is orange.
- **Shared paper bankroll of $20,000** (`SignalConstants.PAPER_START_USD` on `kashi`). Open scalp cost, entries plus fees, draws from that one book. There is no per-model slice. **Open 50** is the exception: it is not capped by that bankroll. **Reset scalp paper money** asks for confirmation, archives the previous run (it does not delete it), and starts stats over. Open positions keep running. It never touches Kalshi.
- **10-contract clips** by default (`ShockFav.CLIP`, `ScalpV2.CLIP`), capped by the size showing at the ask. Extra v2 copies include 5- and 10-contract book-walk fills.
- **Buy ceiling 55¢** (`ScalpRule.ENTRY_HI`). Maker Open 50 bids stay at 49¢.
- **A stop on every new trade.** The default is **4% of the entry bid**, and never tighter than **3¢** or **spread + both taker fees + 1¢**. The trigger is the bid you could have sold at when the trade opened, not the ask you paid. The 1% stop from 0.3.83 fired on one-tick noise; 0.3.85 raised the default to 4% and left a deliberate non-1% user cap alone.
- **Trail.** New v2 trades arm an immediate **3¢** trail under the peak bid (`ScalpControls.peakTrailCents`). A variant that carries its own trail keeps that trail unless Settings is in manual mode or that strategy's knob is set.
- **Churn guards.** After an exit the same strategy waits 15 seconds before re-entering that market, or 60 seconds after a stop. A runaway guard pauses new entries after **20 round trips per strategy per market in 15 minutes** and logs it.
- **v2 copies.** Each original has a `v2-` copy. Shock v2 `sf01`–`sf20` each have their own max ask and trail so they do not trade as one. **0.3.89:** `v2-sf51`–`v2-sf60` keep that original's trail or net take-profit, and the maker copies keep the 49¢ ceiling. The global 3¢ peak trail does not replace an own-trail variant.
- **Retired lanes.** Models with at least 30 closed trades and a confidence interval below zero leave the leaderboard (`ScalpModels.RETIRED_CODES`). Their history stays. A retired original can still seed its kept v2 copy as a hidden row.
- **Model families still in the book.** Fair-gap, dip, momentum, extreme-reversion, CF-reprice, maker dip-rebound, forecast ride and forecast fade, 60-second hold and no-TP siblings, shock-lag and late-favourite `sfNN` variants, Open 50, and g14-style rebuilt copies. g14 itself is not a proven edge.
- **Deterministic direction model.** `Open50Model` (`o50ai-v1`) is a generated, versioned gradient-boosted model (60 depth-3 trees) with 13 numeric inputs: BTC returns, volatility, distance to strike, ETH/SOL moves, book imbalance, ask, spread, and elapsed time. It predicts a side's bid change over the next 60 seconds. A trade needs the prediction to beat spread plus both fees. Same inputs, same output, no LLM calls.
- **Arena and sliders.** The Scalp tab uses the Arena layout. Per-strategy sliders set that model's price range. The resolved range is shown in settings.

### Fees

Kalshi's taker fee per order, in cents, rounded up:

```
fee = ceil( 7 · C · P · (1 − P) )      C = contracts, P = price in dollars
```

For example, 10 contracts at 50¢ gives ceil(7 · 10 · 0.25) = ceil(17.5) = 18¢. Every scalp P&L, take-profit check, and leaderboard number is net of the entry fee **and** the exit fee. Maker fills use the series maker rate, which is 0 by default.

### Leaderboard

There is one combined leaderboard: survivors first, then sorted by **net P&L after fees**. Retired models are absent. Each row shows wins and losses, the net result, and the stop. Tap a model for its round trips, trade detail, and equity curve. **More → Scalp Data** shows scalping results only. Scalp rows sync as `kind = scalp` (push-only).

### Kashi since the 0.3.65 guide

The previous copy of this file stopped at 0.3.65. These are the later `kashi` merges, grouped:

| Versions | What landed |
|---|---|
| 0.3.66–0.3.68 | Scalp sync drains its backlog. The tab redraws only when the board changes. Closed trades live in SQLite; RAM holds open trades; the trade list is paged. Window reads walk the `close_ms` index. |
| 0.3.69–0.3.78 | 0.3.69 tried 25-contract clips on net-positive scalpers. The current default clip is 10. Then V2 paper controls, a 55¢ ceiling (50¢ in 0.3.73), live opening direction, per-strategy window charts, orange spot, the Arena layout, and per-strategy sliders. |
| 0.3.79–0.3.80 | Losing scalpers retire. Scorecard splits **Paper fills** from **Forecasts**. |
| 0.3.81–0.3.84, 0.3.88 | ANR fixes: `AppContainer` is built off the main thread, teardown does not `runBlocking`, WorkManager `onStartJob` stays off the main thread, card chart and SQLite reads stay off the main thread, and the results database uses WAL. |
| 0.3.85–0.3.86 | 4% floored stop, re-entry cooldowns, the 20-trip runaway guard, and **Reset scalp paper money**. |
| 0.3.87 | Live BTC 15-minute chart on the Scalp tab. Side-neutral scalpers. |
| 0.3.89 | `v2-sf51`–`v2-sf60` use their own trail, take-profit, and maker ceiling. |

---

## Safety rules

1. **Autopilot and all scalpers are paper only.** They have no code path to the Kalshi order API, and paper never blocks or changes a live order.
2. **Real money moves only through a manual Approve on one ticket, followed by typing `REAL MONEY`** on the confirm sheet. That sheet shows the contracts, the limit, the fee, the total cost, and the profit if the bet wins. Only limit orders are sent, never market orders, and nothing is automatic or happens in the background.
3. The live size has a hard cap at the final order-build step, and each order has one `client_order_id` that is reused on retries so nothing gets sent twice.
4. The Kalshi API key (Key ID + PEM) is stored only in Android Keystore-backed `EncryptedSharedPreferences`. It is never logged, never synced, and never written in plaintext.

---

## Build and release

### GitHub Actions on `kashi`

| Workflow | Trigger | Output |
|---|---|---|
| `kashi-build-apk.yml` | push to `kashi` and `kashi-*`, PRs into `kashi`, manual | unit tests and trainer tests, then `assembleDebug` + `verifyDebugCert`. Uploads `KalshiTrader-kashi-v<ver>-<sha>.apk`. A push to **`kashi` itself** also publishes `v<ver>-debug` for the in-app updater. `kashi-*` pushes and PRs do not publish. |
| `kashi-unit-tests.yml` | push and PRs to `kashi` | `./gradlew testDebugUnitTest` |
| `kashi-release-guard.yml` | release published, edited, or created | `v*-debug` notes must start with `app-id: com.dirk.kalshiodds.kashi`. If the line is missing, the job prepends it and fails. |
| `train-edge-model.yml` | schedule / manual | Retrains the edge model. The schedule runs only from the default branch. While `main` is the default, run it with `workflow_dispatch` on `kashi`. |

**The branch name decides which workflows run.** Name Kashi work branches `kashi-<version>` (for example `kashi-0389-variant-params`) so `kashi-build-apk.yml` runs. Give every other family its own prefix so a push does not start Kashi's build.

`main` still has `build-apk.yml`, which triggers on every branch (`"**"`). A README-only commit on `main` should say `[skip ci]`.

### Signing

Debug APKs are signed with a **permanent, committed debug keystore** so that sideloaded updates install over the old version without wiping data. `verifyDebugCert` fails the build if the cert's SHA-256 changes. The keystore is debug-only and for private sideloading. **Never** commit a release/upload key, keystore passwords, or Play credentials.

### Releases

- Use the tag `vX.Y.Z-debug` with the APK attached as the expected asset.
- **The first line of the release notes must be `app-id: <your package id>`**, for example `app-id: com.dirk.kalshiodds.kashi` for kashi. Without it the in-app updater ignores the release.
- Bump `versionCode` and `versionName` every release.

### Per-branch identity

**Every branch uses its own `applicationId` and its own signing key.** Pick something like `com.dirk.kalshiodds.<branch>`, then set your own release-policy constants (package id, cert SHA-256, app-id marker, asset name) and release notes to match.

> Never reuse `com.dirk.kalshiodds.kashi` (or the kashi signing key) on another branch. Android would treat your APK as an update to Kalshi Trader, and kashi's updater would accept or reject it against that package and cert.

---

## Data backup

The app's scalp, paper, and market history is also archived to a **separate private repository**. It's private on purpose. That archive is not linked here and must never be linked from public branches, and no credentials for it (or for anything else) belong in this repo. Back up the on-device keys from **Data → Back up credentials** (passphrase + system file picker) before uninstalling. Encrypted prefs are excluded from Android Auto Backup.

---

## UI conventions (FieldOps)

- **FieldOps chrome**: a gray accent on light and dark surfaces, with consistent type, spacing, and corner radii (`ui/components/FieldChrome.kt`, `ui/theme`).
- **Green means UP / YES and red means DOWN / NO, and nothing else.** P&L and win rate use neutral `+/−` text, not color.
- Every screen is a bottom tab or at most two taps from one (`DipNav.MAX_TAPS_FROM_TAB`). No screen gets dropped, and system Back pops the in-app stack.
- Show prices in cents. A missing value is `—`, never `0%` or `0¢`. Missing asks disable the button with a reason (for example *No sellers on YES right now*).
- Every count on a screen comes from one source, so the summary, the list, and the empty state can't disagree.
- The version (`v<versionName> (<versionCode>)`) appears in Settings and at the bottom of Home.
- Layouts must work at 360dp width and at 1.3× font scale.

---

## Rules every branch follows

1. **No secrets in git, ever.** No Kalshi Key IDs or PEMs, no Supabase URLs with keys, no service-role or anon keys, no GitHub tokens, no passwords, no private data. Grep your diff before every push, for example `git diff | grep -iE 'key|token|secret|BEGIN .*PRIVATE|eyJ|sb_'`.
2. **Paper by default.** Autopilot, scalpers, and every model stay paper. Real money needs a manual **Approve** and a typed **`REAL MONEY`** every time.
3. **No LLM in the trade path.** Entry and exit decisions come from deterministic, versioned code and models.
4. **Fees are always counted:** `ceil(7·C·P·(1−P))` cents per taker order, on both the entry and the exit.
5. **Your own package id and signing key.** Never `com.dirk.kalshiodds.kashi`.
6. **Releases are `vX.Y.Z-debug`, and the first line of the notes is `app-id: <package id>`.** Bump versions every time.
7. **Branch names match your workflow triggers.** Don't add workflows that build on every branch (`"**"`).
8. **Sync rows are namespaced** with your own key prefix in `diphunter_sync`. Never overwrite or delete another branch's rows, and never upload the Kalshi key.
9. **BTC 15m (`KXBTC15M`) only**, unless the branch is explicitly about something else. Never WTI or non-crypto.
10. **Don't touch other branches.** Open PRs into your own branch. `kashi` changes only through kashi PRs. `main` holds this guide.
11. **Tests ship with fixes.** Run `./gradlew testDebugUnitTest` before every release.
12. **FieldOps UI conventions apply.**

---

## This checkout (`main`)

`main` is not Kalshi Trader 0.3.89. Its Gradle id is `com.dirk.kalshiodds.chatgtp` and `versionName` is `1.0`, the same package as `grokbot`. Build the phone app from the branch in the table above.

Paper rules that landed on `main` in PR #108 are a different book from `kashi`. They are written up in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md): a $1,000 paper start, negative balances allowed, a 3% stop, and a 3¢ absolute trail. On `kashi` the paper book starts at $20,000 and the scalp stop is the 4% floor described above. Do not copy one into the other.

The long 0.3.15 DipHunter changelog that used to open this file is in git history.

---

*Unofficial client. Not financial advice. Prediction-market data © Kalshi. Built for Dirk Diggler.*
