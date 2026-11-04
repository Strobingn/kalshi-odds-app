# Kalshi Scalper

Android odds dashboard and paper scalper for Kalshi **Bitcoin and WTI 15-minute** markets. The app name is **Kalshi Scalper**. The application id is `com.dirk.kalshiodds.scalp` (Kotlin namespace `com.dirk.kalshiodds`). Strategy fills are paper trading only.

**UI:** Jetpack Compose + Material 3  
**Network:** Retrofit REST poll, plus an optional authenticated Kalshi WebSocket  
**Alerts:** local notifications from a foreground service  
**Offline:** the last successful snapshot is cached in DataStore

## Trading rules

Full rules: [docs/trading-rules.md](docs/trading-rules.md). Working on this repo: [AGENTS.md](AGENTS.md).

`KXBTC15M` opens every quarter hour (:00, :15, :30, :45). Settlement is the 60-second average of CF Benchmarks BRTI over the final minute versus the open. A last-second spike does not settle the window by itself. A tie goes to YES.

Time left is a different market:

| Time left | What to do |
|-----------|------------|
| 10+ min | Price stays about 30–75¢. Buy under 50¢, or in that range, when the price is not falling. |
| 5–10 min | Tightest spreads and the deepest book. Normal size. |
| Under 5 min | The book thins and the price moves fast. Only if already in profit or the direction is clear. |
| Last 60s | Settlement auction. Cut. Never hold to settlement. |

- Do not buy a falling price. Wait until it stops.
- Do not chase spikes. If already in, sell into the spike at the take profit. Otherwise wait for the next one.
- Take profit, stop, max ask, size, range minutes, and the other knobs are read from the in-app settings store. That store is the source of truth. Do not hard-code settings.

## Build and run

The repo uses the Gradle wrapper (`./gradlew`, Gradle 8.9 in `gradle/wrapper/gradle-wrapper.properties`). The only module is `:app` (`settings.gradle.kts`; the root project name is `DipHunter`). Compile target and CI both use **JDK 17** (Temurin in GitHub Actions). Android compile/target SDK is 35; min SDK is 26.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:verifyDebugCert
```

The debug APK is `app/build/outputs/apk/debug/DipHunter-debug.apk`. Open the repo in Android Studio (SDK 35, JDK 17) and run the `app` configuration on API 26+.

CI workflows in `.github/workflows`:

- `scalp-build-apk.yml` — pull requests and pushes to `kashi-next-dev`. Runs the ML trainer checks, `./gradlew testDebugUnitTest`, and `assembleDebug`. A push to `kashi-next-dev` publishes the in-app update.
- `kashi-unit-tests.yml` — `testDebugUnitTest` on branch `kashi`.
- `kashi-release-guard.yml` — checks `v*-debug` release notes on publish.
- `train-edge-model.yml` — scheduled edge-model training, also `workflow_dispatch`.

Scalper releases use tags `scalp-v*` (`scalp-v` plus `major.minor.patch`), published by `scripts/scalp-release.sh`. The asset name is `KalshiScalper.apk`. The notes must include the line `app-id: com.dirk.kalshiodds.scalp`.

## Keys and debug signing

A Kalshi API key is optional. REST odds still load without one. The paper scalper does not place exchange orders.

1. Create a key at [Kalshi → Account → API Keys](https://kalshi.com/account/profile). Save the **Key ID** and the **private key PEM** (RSA or Ed25519). The PEM cannot be downloaded again.
2. In the app: **Settings** → paste Key ID and PEM → save. The PEM goes into `EncryptedSharedPreferences` and is never logged. If the Android Keystore cannot open, the save is refused and the key is not written in plaintext.
3. **Settings → Test connection** signs `GET /trade-api/v2/portfolio/balance` and shows cash or the Kalshi error body.
4. With no key, the status chip reads that the WebSocket needs an API key and the app stays on REST.

Hosts still used by the app:

- Public REST: `https://api.elections.kalshi.com/trade-api/v2`
- Authenticated trade: `https://external-api.kalshi.com/trade-api/v2`
- WebSocket: `wss://external-api-ws.kalshi.com/trade-api/ws/v2` (elections host as fallback). The handshake signs `timestamp + GET + /trade-api/ws/v2` with `KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-TIMESTAMP`, and `KALSHI-ACCESS-SIGNATURE`.

Debug sideload builds use the committed keystore `app/signing/diphunter-debug.jks` (debug only, not a release certificate). `assembleDebug` and `verifyDebugCert` fail when the APK certificate does not match. Override with `DIPHUNTER_DEBUG_STORE_PASSWORD`, `DIPHUNTER_DEBUG_KEY_ALIAS`, and `DIPHUNTER_DEBUG_KEY_PASSWORD` in `gradle.properties` or as environment variables. Back up the key from **Data → Back up credentials** before uninstalling. Encrypted credential preferences are excluded from Android backup.

## Older notes

Version-specific feature notes, the 2026-09-25 backtest, and the 2026-09-27 ML review are in [docs/archive/](docs/archive/). They are historical. `ml/` and `research/` stay where they are.
