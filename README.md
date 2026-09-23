# Kalshi Odds (Android MVP)

Android app for **Dirk Diggler** that shows live Kalshi prediction-market odds for **Bitcoin** (`KXBTC15M`) and **WTI crude** (`KXWTI15M`) 15-minute contracts.

- **UI:** Jetpack Compose + Material 3 (dark-friendly, large YES %)
- **Network:** Retrofit + OkHttp + kotlinx.serialization
- **Refresh:** pull-to-refresh / refresh button + WorkManager every **15 minutes**
- **Offline:** last successful response cached in DataStore Preferences

Package: `com.dirk.kalshiodds`

## What the app does

1. Fetches open markets for both series tickers from the public Kalshi Trade API.
2. Displays each market as a card with:
   - Large **YES implied probability** (%)
   - Bid / ask / last (cents)
   - Volume, 24h volume, local close time, status
   - Subtitle and floor strike when present
3. Probability rule:
   - If both `yes_bid_dollars` and `yes_ask_dollars` are present → mid = `(bid + ask) / 2`
   - Else → `last_price_dollars`
   - Shown as percent (`× 100`)
4. Caches the last successful JSON payload so the UI still works offline.

This MVP is **read-only**. It does **not** place trades and does **not** call authenticated endpoints.


## Dip Hunter AI (TFLite)

On-device proprietary YES/NO prediction via a small **TensorFlow Lite** MLP:

- Architecture: `Input(8) → Dense(32, ReLU) → Dense(16, ReLU) → Dense(2, softmax)` with output order **[P(NO), P(YES)]**
- Features (see `ml/FEATURES.md`): mid, volume_norm, time-to-expiry fraction, volatility, momentum, mean reversion, series id, open-interest norm — standardized with `feature_scaler.json`
- Trained offline on Kalshi **settled** KXBTC15M / KXWTI15M markets + 1-minute candlesticks (`ml/train_diphunter.py`)
- Bundled assets: `diphunter.tflite`, `feature_scaler.json`; embedded Kotlin fallback weights if TFLite fails to load
- **Feedback loop:** logs predictions (DataStore), matches settled `result`, scores accuracy + Brier; UI shows `Model score: X/Y correct` when samples exist

**Edge hunting (v0.1.7+):** Dip Hunter edge (AI−market pp), ranked opportunities by |edge|, spread/OI/liquidity on cards, ≥5pp edge alerts + stance text (Lean YES/NO — no orders), model score HUD with Brier after settlements.

Retrain: `/workspace/tflite-train/bin/python ml/train_diphunter.py && /workspace/tflite-train/bin/python ml/export_fallback_kt.py`

## Open in Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog / Koala / later) with Android SDK **35** (or 34+) and a JDK 17+.
2. **File → Open** and select this folder: `kalshi-odds-app`
3. Let Gradle sync (uses the included Gradle Wrapper, distribution **8.9**).
4. Connect a device/emulator (API 26+).
5. Click **Run** ▶ on the `app` configuration.

### Command line (on a machine with Android SDK)

```bash
# optional: export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/DipHunter-debug.apk
```

### Note about this Linux box

On the creation machine, OpenJDK 21 + Android SDK Platform 35 were installed under `/workspace/android-sdk`, and **`./gradlew :app:assembleDebug` succeeded**. Debug APK: `app/build/outputs/apk/debug/DipHunter-debug.apk`.

On your own machine, open the project in Android Studio (or set `sdk.dir` in `local.properties`) and sync/run. Do not commit `local.properties`.

## API notes (verified)

- Base URL: `https://api.elections.kalshi.com/trade-api/v2`
- Endpoints used (public, no auth):
  - `GET /markets?series_ticker=KXBTC15M&status=open`
  - `GET /markets?series_ticker=KXWTI15M&status=open`
- Useful market fields: `ticker`, `title`, `yes_sub_title`, `yes_bid_dollars`, `yes_ask_dollars`, `last_price_dollars`, `volume_fp`, `volume_24h_fp`, `close_time`, `status`, `floor_strike`
- Example tickers: `KXBTC15M-26SEP231600-00`, `KXWTI15M-26SEP231600-00`
- Response `status` is often `"active"` even when the query uses `status=open`.

Unknown JSON fields are ignored by the serializer (`ignoreUnknownKeys = true`).

## Project layout

```
app/src/main/java/com/dirk/kalshiodds/
  data/api/       Retrofit KalshiApi + NetworkModule
  data/dto/       MarketDto / MarketsResponse
  data/local/     DataStore MarketCache
  data/repo/      MarketRepository
  domain/         MarketUiModel + probability mapping
  ui/             OddsScreen, OddsViewModel, theme, MarketCard
  worker/         MarketRefreshWorker (15 min PeriodicWorkRequest)
  MainActivity.kt
  KalshiOddsApp.kt
```

## Future trading keys — important warning

If you later add trading:

- **Never** embed Kalshi private API keys, RSA private keys, or passwords in the APK, source, or BuildConfig that ships to users.
- Prefer a backend that holds credentials, or Android Keystore + user-supplied secrets that never leave the device.
- This MVP intentionally uses **only** the public market endpoints.

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
