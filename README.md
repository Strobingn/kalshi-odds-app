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

Package: `com.dirk.kalshiodds` · version **0.1.8**

This release is **read-only**. It does **not** place trades, subscribe to fills/portfolio, or send orders.

## Live signals (v0.1.8)

Event-driven fair-value alerts on crypto ticks.

1. Create a Kalshi API key: [Account → API Keys](https://kalshi.com/account/profile) → Create Key. Save the **Key ID** and the **private key PEM** (RSA or Ed25519). The PEM cannot be downloaded again.
2. In the app: **Settings** → paste Key ID + PEM → **Save key**. The PEM is stored in `EncryptedSharedPreferences` and is **never logged**.
3. Enable **Notifications** (Android 13+ will prompt for `POST_NOTIFICATIONS`) and **Live signals (WS foreground)**.
4. The foreground service opens:

   `wss://external-api-ws.kalshi.com/trade-api/ws/v2`

   (fallback host: `wss://api.elections.kalshi.com/trade-api/ws/v2`)

   Handshake headers (required even for public `ticker`): `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP` (ms), `KALSHI-ACCESS-SIGNATURE` over `timestamp + "GET" + "/trade-api/ws/v2"` using RSA-PSS/SHA-256 or Ed25519.

5. The client subscribes to `ticker` (and optionally `trade`) **only for watched crypto markets**.
6. If keys are missing: fast REST poll continues and the status chip reads **“WS needs API key — using REST”**.

**Notifications:** channel `diphunter_signal_alerts` (HIGH). Foreground ongoing: `diphunter_live_signals` (LOW). Target path is tick-receive → notify in under 1s while the service is running. Killed-app remote push would need FCM later; this release is **local instant alerts** only.

## What the app does

1. Fetches open **crypto** markets (`KXBTC15M` / `KXETH15M` / `KXSOL15M` + extra crypto tickers).
2. Displays each market as a card (AI YES/NO, edge, bid/ask, volume, OI).
3. Probability rule: mid = `(yes_bid + yes_ask) / 2` else `last_price`.
4. Scoring engine (dedicated tick dispatcher):

   `fairValue = blend(DipHunter TFLite, volume-flow/momentum, related crypto mid)`  
   `delta = fairValue − marketMid` (percentage points)

   When `|delta|` crosses the configured threshold (default **5pp**), a `SignalAlert` is emitted (10s debounce per ticker) and a local notification is posted.

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
- Example tickers: `KXBTC15M-26SEP231600-00`, `KXETH15M-26SEP231645-45`, `KXSOL15M-26SEP231645-45`

## Project layout

```
app/src/main/java/com/dirk/kalshiodds/
  data/api/          Retrofit KalshiApi + NetworkModule
  data/dto/          MarketDto / MarketsResponse
  data/local/        DataStore MarketCache (btc/eth/sol/extra)
  data/repo/         MarketRepository (crypto series only)
  domain/            MarketUiModel, CryptoMarkets (allowlist / WTI reject)
  prediction/        DipHunterModel, FeatureVector, settlement scoring
  signal/
    config/          DataStore prefs + EncryptedSharedPreferences + default JSON
    ws/              KalshiWsAuth, KalshiWsClient, KalshiWsMessages
    engine/          ScoringEngine, TickBook
    notify/          SignalNotifier (HIGH + LOW channels)
    service/         LiveSignalsService (foreground WS)
    model/           MarketTick, SignalAlert, WsConnectionState
    SignalHub.kt     tick dispatcher → UI + notifications
  ui/                OddsScreen, SettingsScreen, ViewModels, MarketCard
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
- Kalshi API Key ID + private key PEM (secure storage)

Defaults live in `app/src/main/assets/default_signal_config.json` (`watchBtc/Eth/Sol: true`, threshold 5pp, live signals off).

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
