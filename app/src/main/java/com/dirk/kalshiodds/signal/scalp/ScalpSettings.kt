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
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    val maxStakeUsd: Double = DEFAULT_MAX_STAKE_USD,
    /** Sell at the bid once it is this many cents above entry. Backtest: 6¢ is the only TP with any hope of clearing ~5–6¢ round-trip friction. */
    val takeProfitPp: Double = DEFAULT_TAKE_PROFIT_PP,
    /** Cut at the bid once it is this many cents below entry. Grid-optimal per the backtest; wider stops did not help. */
    val stopLossPp: Double = DEFAULT_STOP_LOSS_PP,
    /** Max time an open position is held before a timeout exit. Backtest: 8 min — beyond ~9 min the time stop almost never fires (median bounce lead 2 min). */
    val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    /** Rolling one-hour cap on completed scalp entries. Backtest: 2 — caps the bleed rate; one scalp per market. */
    val maxTradesPerHour: Int = DEFAULT_MAX_TRADES_PER_HOUR,
    /** Stop opening new positions once today's realized loss exceeds this. */
    val maxDailyLossUsd: Double = 10.0,
    /** Rolling feature window in seconds ([ScalpMath]). */
    val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    /** Min drop below the short EMA (and below the window max) to qualify as a dip. Backtest: 5¢ dominates the grid (cheapest entry, not a stronger signal). */
    val dipMinDropPp: Double = DEFAULT_DIP_MIN_DROP_PP,
    /**
     * Persisted circuit breaker. When true the engine refuses to trade until
     * the user manually clears it — survives reboots because it lives here.
     */
    val killSwitch: Boolean = false,
    /**
     * Master aggression switch for this experiment branch. True = run the
     * full [ScalpStrategy] registry concurrently (each strategy one position,
     * per-strategy TP/SL/hold/window defaults), with the aggressive profile
     * from [effective]. False = single DIP_HUNT with the conservative
     * defaults above. The daily-loss breaker and kill switch are NOT relaxed
     * either way.
     */
    val aggressive: Boolean = true,
    /** Max concurrent open scalp positions across all strategies. */
    val maxOpenPositions: Int = 12,
    /**
     * Per-strategy enable set for aggressive mode — strategy NAMES
     * ([ScalpStrategy.name]) that may trade. EMPTY = all strategies on (the
     * default). Strategies absent here are computed but never entered.
     */
    val enabledStrategies: Set<String> = emptySet()
) {
    val paper: Boolean get() = !liveMode

    /** True when [strategy] may open positions under these settings. */
    fun strategyEnabled(strategy: ScalpStrategy): Boolean =
        enabledStrategies.isEmpty() || strategy.name in enabledStrategies

    /**
     * Resolve the settings the engine actually runs with. When [aggressive]
     * is on, any field still at its conservative default is replaced by the
     * aggressive-profile value; a stored value the user changed (it differs
     * from the declared default) always wins. `maxStakeUsd`, `maxDailyLossUsd`
     * and `maxOpenPositions` are identical in both profiles — the daily-loss
     * circuit breaker is never raised or removed.
     */
    fun effective(): ScalpSettings {
        if (!aggressive) return this
        return copy(
            maxStakeUsd = if (maxStakeUsd == DEFAULT_MAX_STAKE_USD) {
                AGGRESSIVE_MAX_STAKE_USD
            } else {
                maxStakeUsd
            },
            maxTradesPerHour = if (maxTradesPerHour == DEFAULT_MAX_TRADES_PER_HOUR) {
                AGGRESSIVE_MAX_TRADES_PER_HOUR
            } else {
                maxTradesPerHour
            },
            windowSeconds = if (windowSeconds == DEFAULT_WINDOW_SECONDS) {
                AGGRESSIVE_WINDOW_SECONDS
            } else {
                windowSeconds
            },
            takeProfitPp = if (takeProfitPp == DEFAULT_TAKE_PROFIT_PP) {
                AGGRESSIVE_TAKE_PROFIT_PP
            } else {
                takeProfitPp
            },
            stopLossPp = if (stopLossPp == DEFAULT_STOP_LOSS_PP) {
                AGGRESSIVE_STOP_LOSS_PP
            } else {
                stopLossPp
            },
            maxHoldMs = if (maxHoldMs == DEFAULT_MAX_HOLD_MS) {
                AGGRESSIVE_MAX_HOLD_MS
            } else {
                maxHoldMs
            },
            dipMinDropPp = if (dipMinDropPp == DEFAULT_DIP_MIN_DROP_PP) {
                AGGRESSIVE_DIP_MIN_DROP_PP
            } else {
                dipMinDropPp
            }
        )
    }

    companion object {
        // Conservative declared defaults — the baseline effective() compares
        // against to detect "user never changed this".
        const val DEFAULT_MAX_STAKE_USD = 5.0
        const val DEFAULT_MAX_TRADES_PER_HOUR = 2
        const val DEFAULT_WINDOW_SECONDS = 60
        const val DEFAULT_TAKE_PROFIT_PP = 6.0
        const val DEFAULT_STOP_LOSS_PP = 5.0
        const val DEFAULT_MAX_HOLD_MS = 480_000L
        const val DEFAULT_DIP_MIN_DROP_PP = 5.0

        // Aggressive profile (bitcoin-swarm experiment branch): 11 concurrent
        // strategies trading the full window. One entry per strategy per
        // window is the design intent behind the 60/h cap (4 windows/h × ~15)
        // and the 12-open cap (one per strategy). The daily-loss breaker and
        // kill switch are identical in both profiles — never relaxed.
        const val AGGRESSIVE_MAX_STAKE_USD = 10.0
        const val AGGRESSIVE_MAX_TRADES_PER_HOUR = 60
        const val AGGRESSIVE_WINDOW_SECONDS = 30
        const val AGGRESSIVE_TAKE_PROFIT_PP = 4.0
        const val AGGRESSIVE_STOP_LOSS_PP = 6.0
        const val AGGRESSIVE_MAX_HOLD_MS = 300_000L
        const val AGGRESSIVE_DIP_MIN_DROP_PP = 2.0

        /** Fraction of the paper bankroll each entry may risk (aggressive). */
        const val BANKROLL_FRACTION_PER_TRADE = 0.25

        /** Paper bankroll seed (cents) for the stats display — aggressive. */
        const val AGGRESSIVE_BANKROLL_SEED_CENTS = 100_000L
    }
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

    suspend fun updateAggressive(aggressive: Boolean) {
        app.scalpStore.edit { it[KEY_AGGRESSIVE] = aggressive }
    }

    suspend fun updateMaxOpenPositions(n: Int) {
        app.scalpStore.edit { it[KEY_MAX_OPEN] = n.coerceIn(1, 12) }
    }

    /** Replace the per-strategy enable set. Empty = all strategies on. */
    suspend fun updateEnabledStrategies(strategies: Set<String>) {
        app.scalpStore.edit { it[KEY_STRATEGIES] = strategies }
    }

    private fun Preferences.toSettings() = ScalpSettings(
        enabled = this[KEY_ENABLED] ?: false,
        liveMode = this[KEY_LIVE] ?: false,
        maxStakeUsd = this[KEY_STAKE] ?: ScalpSettings.DEFAULT_MAX_STAKE_USD,
        takeProfitPp = this[KEY_TP] ?: ScalpSettings.DEFAULT_TAKE_PROFIT_PP,
        stopLossPp = this[KEY_SL] ?: ScalpSettings.DEFAULT_STOP_LOSS_PP,
        maxHoldMs = this[KEY_HOLD] ?: ScalpSettings.DEFAULT_MAX_HOLD_MS,
        maxTradesPerHour = this[KEY_TPH] ?: ScalpSettings.DEFAULT_MAX_TRADES_PER_HOUR,
        maxDailyLossUsd = this[KEY_DAILY_LOSS] ?: 10.0,
        windowSeconds = this[KEY_WINDOW] ?: ScalpSettings.DEFAULT_WINDOW_SECONDS,
        dipMinDropPp = this[KEY_DIP] ?: ScalpSettings.DEFAULT_DIP_MIN_DROP_PP,
        killSwitch = this[KEY_KILL] ?: false,
        aggressive = this[KEY_AGGRESSIVE] ?: true,
        maxOpenPositions = this[KEY_MAX_OPEN] ?: 12,
        enabledStrategies = this[KEY_STRATEGIES] ?: emptySet()
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
        private val KEY_AGGRESSIVE = booleanPreferencesKey("scalp_aggressive")
        private val KEY_MAX_OPEN = intPreferencesKey("scalp_max_open_positions")
        private val KEY_STRATEGIES = stringSetPreferencesKey("scalp_enabled_strategies")
    }
}
