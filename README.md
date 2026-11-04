# Kalshi Odds / Dip Hunter (Android)

Android app for **Dirk Diggler** that shows live Kalshi **crypto** prediction-market odds and fires **analysis-only** signal alerts.

**Primary series:** `KXBTC15M` (Bitcoin 15-minute).

## Key Trading Rules (see [docs/trading-rules.md](docs/trading-rules.md) for full details)
- Buy the cheaper side under max ask when not still falling. No dip required.
- With more than range minutes left, also buy in the range (default 30–75¢).
- Sell at take profit or stop. **Never hold to settlement.** Last 60s is a cut.
- Size is a fraction of cash (default 25%). Bigger only when the range is clear.
- Settings live in the app and must be read from the store — do not hard-code.
- Do not chase spikes. Sell into them if already in, otherwise wait.
- Do not buy a falling price. Do not ignore time left. Settlement uses the BRTI average, not a raw Coinbase chart.

Agent map for working on this repo: [AGENTS.md](AGENTS.md).

**UI:** Jetpack Compose + Material 3  
**Network:** Retrofit REST poll + optional authenticated Kalshi WebSocket  
**Alerts:** local notifications via foreground service  
**Offline:** last successful snapshot cached in DataStore

Package: `com.dirk.kalshiodds`

## Build / Run
```bash
./gradlew assembleDebug
# or
./gradlew :app:testDebugUnitTest
```

Settings are the source of truth for take profit, stop, max ask, size fraction, range minutes, etc. Change them in the app; do not hard-code values in strategy code.

---

*(Full historical changelog continues below from prior versions.)*

