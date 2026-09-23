# Kalshi Odds / Dip Hunter (Android)

Android app for **Dirk Diggler** that shows live Kalshi **crypto** prediction-market odds and fires **analysis-only** signal alerts.

**Default watchlist (crypto only):**

| Series | Asset | Role |
|--------|--------|------|
| `KXBTC15M` | Bitcoin 15-minute | Default on |
| `KXETH15M` | Ethereum 15-minute | Default on |
| `KXSOL15M` | Solana 15-minute | Default on |
| Extra tickers | Other crypto-denominated Kalshi contracts (XRP, DOGE, …) | Optional, Settings |

**WTI crude (`KXWTI15M`) and all non-crypto markets are excluded** from defaults, UI toggles, REST watchlists, WebSocket subscriptions, scoring, and alerts.

- **UI:** Jetpack Compose + Material 3
- **Network:** Retrofit REST poll + optional authenticated Kalshi WebSocket
- **Alerts:** local `NotificationCompat` HIGH channel via a foreground WS service
- **Offline:** last successful crypto snapshot cached in DataStore

Package: `com.dirk.kalshiodds` · version **0.2.4**

Analysis UI is unchanged. **0.2.4 stops mid-session crashes** from the 0.2.3 keep-alive path (shared TFLite, live order-book races, specialUse FGS). **0.2.3 keeps live odds alive in the background** via a sticky foreground service (no change to trading rules). **0.2.2 added approve-gated limit tickets.** There is no background auto-fire, no set-and-forget trading, and no order on app start. **Not financial advice. High variance — you can lose the full stake.**

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
5. **Auth.** Uses the Key ID + PEM already in Settings (`EncryptedSharedPreferences`). PEM is never logged. Soft-fail with a clear error. Create: `POST /trade-api/v2/portfolio/events/orders`. Cancel: `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`.

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

1. Fetches open **crypto** markets (`KXBTC15M` / `KXETH15M` / `KXSOL15M` + extra crypto tickers).
2. Displays each market as a card (AI YES/NO, edge, bid/ask, volume, OI).
3. Probability rule: mid = `(yes_bid + yes_ask) / 2` else `last_price`.
4. Scoring engine (dedicated tick dispatcher):

   `fairValue = blend(TFLite, volume-flow+aggressor, related mid, velocity, imbalance, lead–lag, depth/decay, cancel/pull)`  
   then **calibrate** when enough settlements exist.  
   `delta = calibratedFair − marketMid` (percentage points)

   Alerts fire only when `|delta|` ≥ threshold **and** the skip filter passes (10s debounce per ticker).

## Dip Hunter AI (TFLite)

On-device YES/NO prediction via a small **TensorFlow Lite** MLP (`Input(8) → Dense(32) → Dense(16) → Dense(2, softmax)` = `[P(NO), P(YES)]`). Features: see `ml/FEATURES.md`. The historical trainer still includes settled WTI rows; **runtime inference and the live watchlist are crypto-only** (series_id 0).

**Edge hunting:** Dip Hunter edge (AI−market pp), ranked opportunities, stance text (Lean YES/NO — no orders).

## Open in Android Studio

1. Install Android Studio with SDK **35** and JDK 17+.
2. **File → Open** this folder.
3. Run the `app` configuration on API 26+.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/DipHunter-debug.apk
```

## API notes

- REST base: `https://api.elections.kalshi.com/trade-api/v2`
- WS: `wss://external-api-ws.kalshi.com/trade-api/ws/v2` (elections host as alternate)
- Public REST (no auth): `GET /markets?series_ticker=KXBTC15M|KXETH15M|KXSOL15M&status=open`
- Authenticated create (after Approve): `POST /portfolio/events/orders` (V2 bid/ask, GTC limit). Cancel: `DELETE /portfolio/events/orders/{order_id}`.
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
    feedback/        Calibrator, ScorecardMetrics, OnlineAdapter, Allowlist, Guardrails
    sizing/          PositionSizer, NetExpectedValue (advisory)
    trade/           PayoutGate, TicketBuilder, TicketSession (approve-gated)
    external/        Public Binance/Coinbase spot · vol · funding
    checklist/       Pre-trade checklist + copy text
    notify/          SignalNotifier (HIGH alerts + ongoing FGS)
    service/         LiveSignalsService + keep-alive / boot / battery prompt
    model/           MarketTick, SignalAlert, WsConnectionState
    SignalHub.kt     tick dispatcher → UI + notifications
  ui/                OddsScreen, SettingsScreen, ScorecardScreen, ViewModels, MarketCard
  worker/            MarketRefreshWorker (15 min)
  MainActivity.kt
  KalshiOddsApp.kt
  AppContainer.kt
app/src/main/assets/default_signal_config.json
```

## Configuration (Settings + DataStore)

- Watch toggles: BTC 15m / ETH 15m / SOL 15m
- Extra **crypto** ticker list (non-crypto values are dropped)
- Edge threshold (pp), notifications on/off, Live signals on/off
- Skip filter: min confidence, min liquidity, max spread, hide weak opportunities
- Bankroll, Kelly / fixed-fraction, fee rate, net-EV ranking
- Auto-mute floor, streak / drawdown guard, resume
- Ticket stake ($5 default, $25 hard cap), quality gates for tickets
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
