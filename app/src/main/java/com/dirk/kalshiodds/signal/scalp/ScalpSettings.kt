package com.dirk.kalshiodds.signal.scalp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scalpStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_scalp",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * DipHunter scalper settings. Off by default, paper by default — the scalper
 * is the ONE non-approve-gated path in the app and every safety lives here
 * or in [ScalpGuardrails]. Prices on Kalshi are cents (1..99); `pp` fields
 * are percentage points of probability (1 cent = 1 pp).
 *
 * Defaults are the least-bad combination from the 2,100-combo grid backtest in
 * `docs/scalping-params.md`: EVERY parameter combination lost money after
 * Kalshi taker fees (best ≈ −4.6¢/contract/trade at realistic spreads; even
 * an idealized zero-fee mid-fill was ≈ +0.37¢, i.e. noise). These defaults
 * exist so that an accidental enable bleeds as slowly as possible — they are
 * not an endorsement. Do not enable live mode; see the bar in
 * `docs/scalping-params.md` ("Do NOT enable live until…").
 */
data class ScalpSettings(
    /** Master switch. False = engine never trades, only accumulates features. */
    val enabled: Boolean = false,
    /** False = paper fills (default). True = real Kalshi orders via [com.dirk.kalshiodds.data.api.KalshiTradeClient]. */
    val liveMode: Boolean = false,
    /** Max dollars spent per entry (position cost + entry fee). */
    val maxStakeUsd: Double = 5.0,
    /** Sell at the bid once it is this many cents above entry. Backtest: 6¢ is the only TP with any hope of clearing ~5–6¢ round-trip friction. */
    val takeProfitPp: Double = 6.0,
    /** Cut at the bid once it is this many cents below entry. Grid-optimal per the backtest; wider stops did not help. */
    val stopLossPp: Double = 5.0,
    /** Max time an open position is held before a timeout exit. Backtest: 8 min — beyond ~9 min the time stop almost never fires (median bounce lead 2 min). */
    val maxHoldMs: Long = 8 * 60 * 1000L,
    /** Rolling one-hour cap on completed scalp entries. Backtest: 2 — caps the bleed rate; one scalp per market. */
    val maxTradesPerHour: Int = 2,
    /** Stop opening new positions once today's realized loss exceeds this. */
    val maxDailyLossUsd: Double = 10.0,
    /** Rolling feature window in seconds ([ScalpMath]). */
    val windowSeconds: Int = 60,
    /** Min drop below the short EMA (and below the window max) to qualify as a dip. Backtest: 5¢ dominates the grid (cheapest entry, not a stronger signal). */
    val dipMinDropPp: Double = 5.0,
    /**
     * Persisted circuit breaker. When true the engine refuses to trade until
     * the user manually clears it — survives reboots because it lives here.
     */
    val killSwitch: Boolean = false
) {
    val paper: Boolean get() = !liveMode
}

/**
 * DataStore-backed store, same pattern as
 * [com.dirk.kalshiodds.data.prefs.DataPrefs]. All mutators are explicit
 * one-field writes so the wiring agent can bind settings UI directly.
 */
class ScalpSettingsStore(context: Context) {
    private val app = context.applicationContext

    val settings: Flow<ScalpSettings> = app.scalpStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toSettings() }

    suspend fun hydrate(): ScalpSettings =
        runCatching { settings.first() }.getOrElse { ScalpSettings() }

    suspend fun updateEnabled(enabled: Boolean) {
        app.scalpStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun updateLiveMode(liveMode: Boolean) {
        app.scalpStore.edit { it[KEY_LIVE] = liveMode }
    }

    suspend fun updateMaxStakeUsd(usd: Double) {
        app.scalpStore.edit { it[KEY_STAKE] = usd.coerceIn(1.0, 10.0) }
    }

    suspend fun updateTakeProfitPp(pp: Double) {
        app.scalpStore.edit { it[KEY_TP] = pp.coerceIn(1.0, 20.0) }
    }

    suspend fun updateStopLossPp(pp: Double) {
        app.scalpStore.edit { it[KEY_SL] = pp.coerceIn(1.0, 30.0) }
    }

    suspend fun updateMaxHoldMs(ms: Long) {
        app.scalpStore.edit { it[KEY_HOLD] = ms.coerceIn(30_000L, 60 * 60 * 1000L) }
    }

    suspend fun updateMaxTradesPerHour(n: Int) {
        app.scalpStore.edit { it[KEY_TPH] = n.coerceIn(1, 60) }
    }

    suspend fun updateMaxDailyLossUsd(usd: Double) {
        app.scalpStore.edit { it[KEY_DAILY_LOSS] = usd.coerceIn(1.0, 100.0) }
    }

    suspend fun updateWindowSeconds(sec: Int) {
        app.scalpStore.edit { it[KEY_WINDOW] = sec.coerceIn(10, 600) }
    }

    suspend fun updateDipMinDropPp(pp: Double) {
        app.scalpStore.edit { it[KEY_DIP] = pp.coerceIn(0.5, 20.0) }
    }

    suspend fun updateKillSwitch(tripped: Boolean) {
        app.scalpStore.edit { it[KEY_KILL] = tripped }
    }

    private fun Preferences.toSettings() = ScalpSettings(
        enabled = this[KEY_ENABLED] ?: false,
        liveMode = this[KEY_LIVE] ?: false,
        maxStakeUsd = this[KEY_STAKE] ?: 5.0,
        takeProfitPp = this[KEY_TP] ?: 6.0,
        stopLossPp = this[KEY_SL] ?: 5.0,
        maxHoldMs = this[KEY_HOLD] ?: 8 * 60 * 1000L,
        maxTradesPerHour = this[KEY_TPH] ?: 2,
        maxDailyLossUsd = this[KEY_DAILY_LOSS] ?: 10.0,
        windowSeconds = this[KEY_WINDOW] ?: 60,
        dipMinDropPp = this[KEY_DIP] ?: 5.0,
        killSwitch = this[KEY_KILL] ?: false
    )

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("scalp_enabled")
        private val KEY_LIVE = booleanPreferencesKey("scalp_live_mode")
        private val KEY_STAKE = doublePreferencesKey("scalp_max_stake_usd")
        private val KEY_TP = doublePreferencesKey("scalp_take_profit_pp")
        private val KEY_SL = doublePreferencesKey("scalp_stop_loss_pp")
        private val KEY_HOLD = longPreferencesKey("scalp_max_hold_ms")
        private val KEY_TPH = intPreferencesKey("scalp_max_trades_per_hour")
        private val KEY_DAILY_LOSS = doublePreferencesKey("scalp_max_daily_loss_usd")
        private val KEY_WINDOW = intPreferencesKey("scalp_window_seconds")
        private val KEY_DIP = doublePreferencesKey("scalp_dip_min_drop_pp")
        private val KEY_KILL = booleanPreferencesKey("scalp_kill_switch")
    }
}
