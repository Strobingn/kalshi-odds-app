package com.dirk.kalshiodds.signal.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.signalDataStore: DataStore<Preferences> by preferencesDataStore(name = "diphunter_signal_prefs")

data class SignalSettings(
    val watchBtc: Boolean = true,
    val watchEth: Boolean = true,
    val watchSol: Boolean = true,
    val extraTickers: List<String> = emptyList(),
    val extraTickersText: String = "",
    val edgeThresholdPp: Double = 5.0,
    val notificationsEnabled: Boolean = true,
    val liveSignalsEnabled: Boolean = false,
    val subscribeTrades: Boolean = true,
    val debounceMs: Long = 10_000L,
    val minConfidence: Double = SignalConstants.DEFAULT_MIN_CONFIDENCE,
    val minLiquidity: Double = SignalConstants.DEFAULT_MIN_LIQUIDITY,
    val maxSpreadCents: Double = SignalConstants.DEFAULT_MAX_SPREAD_CENTS,
    val hideWeakOpportunities: Boolean = SignalConstants.DEFAULT_HIDE_WEAK,
    val bankrollUsd: Double = SignalConstants.DEFAULT_BANKROLL_USD,
    val useKelly: Boolean = true,
    val kellyFraction: Double = SignalConstants.DEFAULT_KELLY_FRACTION,
    val fixedFraction: Double = SignalConstants.DEFAULT_FIXED_FRACTION,
    val maxBankrollFraction: Double = SignalConstants.DEFAULT_MAX_BANKROLL_FRACTION,
    val feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
    val rankByNetEv: Boolean = SignalConstants.DEFAULT_RANK_BY_NET_EV,
    val autoMute: Boolean = SignalConstants.DEFAULT_AUTO_MUTE,
    val muteHitRateFloor: Double = SignalConstants.DEFAULT_MUTE_HIT_RATE_FLOOR,
    val streakPauseN: Int = SignalConstants.DEFAULT_STREAK_PAUSE_N,
    val drawdownUsd: Double = SignalConstants.DEFAULT_DRAWDOWN_USD,
    val resumeOnNewSession: Boolean = SignalConstants.DEFAULT_RESUME_ON_NEW_SESSION,
    val ticketsEnabled: Boolean = true,
    val ticketStakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD,
    val ticketRespectGates: Boolean = SignalConstants.DEFAULT_TICKET_RESPECT_GATES,
    val heavyMlEnabled: Boolean = SignalConstants.DEFAULT_HEAVY_ML,
    val sequenceModelEnabled: Boolean = SignalConstants.DEFAULT_SEQUENCE_MODEL,
    val gbmEnabled: Boolean = SignalConstants.DEFAULT_GBM,
    val uncertaintyGateEnabled: Boolean = SignalConstants.DEFAULT_UNCERTAINTY_GATE,
    val maxUncertainty: Double = SignalConstants.DEFAULT_MAX_UNCERTAINTY,
    val continualFineTune: Boolean = SignalConstants.DEFAULT_CONTINUAL_FINETUNE,
    val policyEvalStakeUsd: Double = SignalConstants.DEFAULT_POLICY_EVAL_STAKE_USD,
    val extendedAiEnabled: Boolean = SignalConstants.DEFAULT_EXTENDED_AI,
    val regimeClassifierEnabled: Boolean = SignalConstants.DEFAULT_REGIME_CLASSIFIER,
    val anomalyGateEnabled: Boolean = SignalConstants.DEFAULT_ANOMALY_GATE,
    val survivalModelEnabled: Boolean = SignalConstants.DEFAULT_SURVIVAL_MODEL,
    val rlSizerEnabled: Boolean = SignalConstants.DEFAULT_RL_SIZER,
    val newsPulseEnabled: Boolean = SignalConstants.DEFAULT_NEWS_PULSE,
    val rivalFlowEnabled: Boolean = SignalConstants.DEFAULT_RIVAL_FLOW,
    val bayesianMmEnabled: Boolean = SignalConstants.DEFAULT_BAYESIAN_MM,
    val conformalEnabled: Boolean = SignalConstants.DEFAULT_CONFORMAL,
    val metaLabelEnabled: Boolean = SignalConstants.DEFAULT_META_LABEL,
    val pathSimEnabled: Boolean = SignalConstants.DEFAULT_PATH_SIM,
    val apiKeyId: String = "",
    val hasPrivateKey: Boolean = false
) {
    val credentialsConfigured: Boolean get() = apiKeyId.isNotBlank() && hasPrivateKey

    val watchedSeries: Set<String>
        get() = buildSet {
            if (watchBtc) add(KalshiApi.SERIES_BTC)
            if (watchEth) add(KalshiApi.SERIES_ETH)
            if (watchSol) add(KalshiApi.SERIES_SOL)
        }

    fun isWatchedTicker(ticker: String): Boolean {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return false
        val u = ticker.uppercase()
        if (extraTickers.any { it.equals(ticker, ignoreCase = true) }) return true
        if (watchBtc && (u.startsWith(KalshiApi.SERIES_BTC) || u.contains("BTC"))) return true
        if (watchEth && (u.startsWith(KalshiApi.SERIES_ETH) || (u.contains("ETH") && !u.contains("BTC")))) return true
        if (watchSol && (u.startsWith(KalshiApi.SERIES_SOL) || u.contains("SOL"))) return true
        return extraTickers.isNotEmpty() && extraTickers.any { u.startsWith(it.uppercase()) }
    }

    fun extraTickerList(): List<String> = CryptoMarkets.filterCrypto(extraTickers)
}

class SignalPreferences(
    private val context: Context,
    private val secrets: SecureCredentialStore = SecureCredentialStore(context),
    defaults: DefaultSignalConfig = DefaultSignalConfig.load(context)
) {
    private val app = context.applicationContext
    private val def = defaults

    /** Bumped when credentials change so [settings] re-emits. */
    private val secretRevision = MutableStateFlow(0)

    val settings: Flow<SignalSettings> = app.signalDataStore.data
        .map { it.toSettings() }
        .combine(secretRevision) { s, _ ->
            s.copy(
                apiKeyId = secrets.apiKeyId,
                hasPrivateKey = SecureCredentialStore.looksLikePem(secrets.privateKeyPem)
            )
        }

    suspend fun updateWatchBtc(value: Boolean) = edit { it[KEY_WATCH_BTC] = value }
    suspend fun updateWatchEth(value: Boolean) = edit { it[KEY_WATCH_ETH] = value }
    suspend fun updateWatchSol(value: Boolean) = edit { it[KEY_WATCH_SOL] = value }
    suspend fun updateExtraTickers(text: String) = edit { it[KEY_EXTRA] = text }
    suspend fun updateEdgeThresholdPp(value: Double) = edit {
        it[KEY_THRESHOLD] = value.coerceIn(0.5, 40.0)
    }
    suspend fun updateNotifications(value: Boolean) = edit { it[KEY_NOTIF] = value }
    suspend fun updateLiveSignals(value: Boolean) {
        LiveSignalsKeepAlive.setEnabled(app, value)
        edit { it[KEY_LIVE] = value }
    }
    suspend fun updateSubscribeTrades(value: Boolean) = edit { it[KEY_TRADES] = value }
    suspend fun updateMinConfidence(value: Double) = edit {
        it[KEY_MIN_CONF] = value.coerceIn(0.20, 0.85)
    }
    suspend fun updateMinLiquidity(value: Double) = edit {
        it[KEY_MIN_LIQ] = value.coerceIn(0.0, 50_000.0)
    }
    suspend fun updateMaxSpreadCents(value: Double) = edit {
        it[KEY_MAX_SPREAD] = value.coerceIn(1.0, 25.0)
    }
    suspend fun updateHideWeak(value: Boolean) = edit { it[KEY_HIDE_WEAK] = value }
    suspend fun updateBankrollUsd(value: Double) = edit {
        it[KEY_BANKROLL] = value.coerceIn(10.0, 1_000_000.0)
    }
    suspend fun updateUseKelly(value: Boolean) = edit { it[KEY_USE_KELLY] = value }
    suspend fun updateKellyFraction(value: Double) = edit {
        it[KEY_KELLY_FRAC] = value.coerceIn(0.05, 1.0)
    }
    suspend fun updateFixedFraction(value: Double) = edit {
        it[KEY_FIXED_FRAC] = value.coerceIn(0.002, 0.25)
    }
    suspend fun updateMaxBankrollFraction(value: Double) = edit {
        it[KEY_MAX_FRAC] = value.coerceIn(0.005, 0.25)
    }
    suspend fun updateFeeRate(value: Double) = edit {
        it[KEY_FEE_RATE] = value.coerceIn(0.0, 0.20)
    }
    suspend fun updateRankByNetEv(value: Boolean) = edit { it[KEY_RANK_NET] = value }
    suspend fun updateAutoMute(value: Boolean) = edit { it[KEY_AUTO_MUTE] = value }
    suspend fun updateMuteHitRateFloor(value: Double) = edit {
        it[KEY_MUTE_FLOOR] = value.coerceIn(0.15, 0.70)
    }
    suspend fun updateStreakPauseN(value: Int) = edit {
        it[KEY_STREAK_N] = value.coerceIn(2, 12)
    }
    suspend fun updateDrawdownUsd(value: Double) = edit {
        it[KEY_DRAWDOWN] = value.coerceIn(5.0, 5_000.0)
    }
    suspend fun updateResumeOnNewSession(value: Boolean) = edit { it[KEY_RESUME_SESSION] = value }
    suspend fun updateTicketsEnabled(value: Boolean) = edit { it[KEY_TICKETS] = value }
    suspend fun updateTicketStakeUsd(value: Double) = edit {
        it[KEY_TICKET_STAKE] = value.coerceIn(
            SignalConstants.TICKET_STAKE_MIN_USD,
            SignalConstants.TICKET_STAKE_HARD_CAP_USD
        )
    }
    suspend fun updateTicketRespectGates(value: Boolean) = edit { it[KEY_TICKET_GATES] = value }
    suspend fun updateHeavyMl(value: Boolean) = edit { it[KEY_HEAVY_ML] = value }
    suspend fun updateSequenceModel(value: Boolean) = edit { it[KEY_SEQ_MODEL] = value }
    suspend fun updateGbm(value: Boolean) = edit { it[KEY_GBM] = value }
    suspend fun updateUncertaintyGate(value: Boolean) = edit { it[KEY_UNC_GATE] = value }
    suspend fun updateMaxUncertainty(value: Double) = edit {
        it[KEY_MAX_UNC] = value.coerceIn(0.02, 0.40)
    }
    suspend fun updateContinualFineTune(value: Boolean) = edit { it[KEY_FINETUNE] = value }
    suspend fun updatePolicyEvalStakeUsd(value: Double) = edit {
        it[KEY_POLICY_STAKE] = value.coerceIn(
            SignalConstants.TICKET_STAKE_MIN_USD,
            SignalConstants.TICKET_STAKE_HARD_CAP_USD
        )
    }
    suspend fun updateExtendedAi(value: Boolean) = edit { it[KEY_EXT_AI] = value }
    suspend fun updateRegimeClassifier(value: Boolean) = edit { it[KEY_REGIME_CLF] = value }
    suspend fun updateAnomalyGate(value: Boolean) = edit { it[KEY_ANOMALY] = value }
    suspend fun updateSurvivalModel(value: Boolean) = edit { it[KEY_SURVIVAL] = value }
    suspend fun updateRlSizer(value: Boolean) = edit { it[KEY_RL_SIZER] = value }
    suspend fun updateNewsPulse(value: Boolean) = edit { it[KEY_NEWS] = value }
    suspend fun updateRivalFlow(value: Boolean) = edit { it[KEY_RIVAL] = value }
    suspend fun updateBayesianMm(value: Boolean) = edit { it[KEY_BAYES] = value }
    suspend fun updateConformal(value: Boolean) = edit { it[KEY_CONFORMAL] = value }
    suspend fun updateMetaLabel(value: Boolean) = edit { it[KEY_META] = value }
    suspend fun updatePathSim(value: Boolean) = edit { it[KEY_PATH_SIM] = value }

    fun saveCredentials(keyId: String, pem: String) {
        secrets.apiKeyId = keyId
        secrets.privateKeyPem = pem
        secretRevision.value += 1
    }

    fun clearCredentials() {
        secrets.clear()
        secretRevision.value += 1
    }

    fun credentialSnapshot(): Pair<String, String> = secrets.snapshot()

    /**
     * 0.3.1 one-time: force light mode (Heavy ML + Extended AI off) so
     * devices that persisted 0.3.0 defaults stop OOM-looping. Users can
     * re-enable in Settings.
     */
    suspend fun applySafeLightDefaultsIfNeeded() {
        app.signalDataStore.edit { prefs ->
            if (prefs[KEY_SAFE_V031] == true) return@edit
            prefs[KEY_HEAVY_ML] = false
            prefs[KEY_EXT_AI] = false
            prefs[KEY_SAFE_V031] = true
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        app.signalDataStore.edit(block)
    }

    private fun Preferences.toSettings(): SignalSettings {
        val extraText = this[KEY_EXTRA] ?: def.extraTickers.joinToString(", ")
        return SignalSettings(
            watchBtc = this[KEY_WATCH_BTC] ?: def.watchBtc,
            watchEth = this[KEY_WATCH_ETH] ?: def.watchEth,
            watchSol = this[KEY_WATCH_SOL] ?: def.watchSol,
            extraTickers = CryptoMarkets.filterCrypto(parseTickerList(extraText)),
            extraTickersText = extraText,
            edgeThresholdPp = this[KEY_THRESHOLD] ?: def.edgeThresholdPp,
            notificationsEnabled = this[KEY_NOTIF] ?: def.notificationsEnabled,
            liveSignalsEnabled = this[KEY_LIVE] ?: def.liveSignalsEnabled,
            subscribeTrades = this[KEY_TRADES] ?: def.subscribeTrades,
            debounceMs = this[KEY_DEBOUNCE] ?: def.debounceMs,
            minConfidence = this[KEY_MIN_CONF] ?: def.minConfidence,
            minLiquidity = this[KEY_MIN_LIQ] ?: def.minLiquidity,
            maxSpreadCents = this[KEY_MAX_SPREAD] ?: def.maxSpreadCents,
            hideWeakOpportunities = this[KEY_HIDE_WEAK] ?: def.hideWeakOpportunities,
            bankrollUsd = this[KEY_BANKROLL] ?: def.bankrollUsd,
            useKelly = this[KEY_USE_KELLY] ?: def.useKelly,
            kellyFraction = this[KEY_KELLY_FRAC] ?: def.kellyFraction,
            fixedFraction = this[KEY_FIXED_FRAC] ?: def.fixedFraction,
            maxBankrollFraction = this[KEY_MAX_FRAC] ?: def.maxBankrollFraction,
            feeRate = this[KEY_FEE_RATE] ?: def.feeRate,
            rankByNetEv = this[KEY_RANK_NET] ?: def.rankByNetEv,
            autoMute = this[KEY_AUTO_MUTE] ?: def.autoMute,
            muteHitRateFloor = this[KEY_MUTE_FLOOR] ?: def.muteHitRateFloor,
            streakPauseN = this[KEY_STREAK_N] ?: def.streakPauseN,
            drawdownUsd = this[KEY_DRAWDOWN] ?: def.drawdownUsd,
            resumeOnNewSession = this[KEY_RESUME_SESSION] ?: def.resumeOnNewSession,
            ticketsEnabled = this[KEY_TICKETS] ?: def.ticketsEnabled,
            ticketStakeUsd = this[KEY_TICKET_STAKE] ?: def.ticketStakeUsd,
            ticketRespectGates = this[KEY_TICKET_GATES] ?: def.ticketRespectGates,
            heavyMlEnabled = this[KEY_HEAVY_ML] ?: def.heavyMlEnabled,
            sequenceModelEnabled = this[KEY_SEQ_MODEL] ?: def.sequenceModelEnabled,
            gbmEnabled = this[KEY_GBM] ?: def.gbmEnabled,
            uncertaintyGateEnabled = this[KEY_UNC_GATE] ?: def.uncertaintyGateEnabled,
            maxUncertainty = this[KEY_MAX_UNC] ?: def.maxUncertainty,
            continualFineTune = this[KEY_FINETUNE] ?: def.continualFineTune,
            policyEvalStakeUsd = this[KEY_POLICY_STAKE] ?: def.policyEvalStakeUsd,
            extendedAiEnabled = this[KEY_EXT_AI] ?: def.extendedAiEnabled,
            regimeClassifierEnabled = this[KEY_REGIME_CLF] ?: def.regimeClassifierEnabled,
            anomalyGateEnabled = this[KEY_ANOMALY] ?: def.anomalyGateEnabled,
            survivalModelEnabled = this[KEY_SURVIVAL] ?: def.survivalModelEnabled,
            rlSizerEnabled = this[KEY_RL_SIZER] ?: def.rlSizerEnabled,
            newsPulseEnabled = this[KEY_NEWS] ?: def.newsPulseEnabled,
            rivalFlowEnabled = this[KEY_RIVAL] ?: def.rivalFlowEnabled,
            bayesianMmEnabled = this[KEY_BAYES] ?: def.bayesianMmEnabled,
            conformalEnabled = this[KEY_CONFORMAL] ?: def.conformalEnabled,
            metaLabelEnabled = this[KEY_META] ?: def.metaLabelEnabled,
            pathSimEnabled = this[KEY_PATH_SIM] ?: def.pathSimEnabled,
            apiKeyId = secrets.apiKeyId,
            hasPrivateKey = SecureCredentialStore.looksLikePem(secrets.privateKeyPem)
        )
    }

    companion object {
        private val KEY_WATCH_BTC = booleanPreferencesKey("watch_btc")
        private val KEY_WATCH_ETH = booleanPreferencesKey("watch_eth")
        private val KEY_WATCH_SOL = booleanPreferencesKey("watch_sol")
        private val KEY_EXTRA = stringPreferencesKey("extra_tickers")
        private val KEY_THRESHOLD = doublePreferencesKey("edge_threshold_pp")
        private val KEY_NOTIF = booleanPreferencesKey("notifications_enabled")
        private val KEY_LIVE = booleanPreferencesKey("live_signals_enabled")
        private val KEY_TRADES = booleanPreferencesKey("subscribe_trades")
        private val KEY_DEBOUNCE = longPreferencesKey("debounce_ms")
        private val KEY_MIN_CONF = doublePreferencesKey("min_confidence")
        private val KEY_MIN_LIQ = doublePreferencesKey("min_liquidity")
        private val KEY_MAX_SPREAD = doublePreferencesKey("max_spread_cents")
        private val KEY_HIDE_WEAK = booleanPreferencesKey("hide_weak_opportunities")
        private val KEY_BANKROLL = doublePreferencesKey("bankroll_usd")
        private val KEY_USE_KELLY = booleanPreferencesKey("use_kelly")
        private val KEY_KELLY_FRAC = doublePreferencesKey("kelly_fraction")
        private val KEY_FIXED_FRAC = doublePreferencesKey("fixed_fraction")
        private val KEY_MAX_FRAC = doublePreferencesKey("max_bankroll_fraction")
        private val KEY_FEE_RATE = doublePreferencesKey("fee_rate")
        private val KEY_RANK_NET = booleanPreferencesKey("rank_by_net_ev")
        private val KEY_AUTO_MUTE = booleanPreferencesKey("auto_mute")
        private val KEY_MUTE_FLOOR = doublePreferencesKey("mute_hit_rate_floor")
        private val KEY_STREAK_N = intPreferencesKey("streak_pause_n")
        private val KEY_DRAWDOWN = doublePreferencesKey("drawdown_usd")
        private val KEY_RESUME_SESSION = booleanPreferencesKey("resume_on_new_session")
        private val KEY_TICKETS = booleanPreferencesKey("tickets_enabled")
        private val KEY_TICKET_STAKE = doublePreferencesKey("ticket_stake_usd")
        private val KEY_TICKET_GATES = booleanPreferencesKey("ticket_respect_gates")
        private val KEY_HEAVY_ML = booleanPreferencesKey("heavy_ml_enabled")
        private val KEY_SEQ_MODEL = booleanPreferencesKey("sequence_model_enabled")
        private val KEY_GBM = booleanPreferencesKey("gbm_enabled")
        private val KEY_UNC_GATE = booleanPreferencesKey("uncertainty_gate_enabled")
        private val KEY_MAX_UNC = doublePreferencesKey("max_uncertainty")
        private val KEY_FINETUNE = booleanPreferencesKey("continual_finetune")
        private val KEY_POLICY_STAKE = doublePreferencesKey("policy_eval_stake_usd")
        private val KEY_EXT_AI = booleanPreferencesKey("extended_ai_enabled")
        private val KEY_REGIME_CLF = booleanPreferencesKey("regime_classifier_enabled")
        private val KEY_ANOMALY = booleanPreferencesKey("anomaly_gate_enabled")
        private val KEY_SURVIVAL = booleanPreferencesKey("survival_model_enabled")
        private val KEY_RL_SIZER = booleanPreferencesKey("rl_sizer_enabled")
        private val KEY_NEWS = booleanPreferencesKey("news_pulse_enabled")
        private val KEY_RIVAL = booleanPreferencesKey("rival_flow_enabled")
        private val KEY_BAYES = booleanPreferencesKey("bayesian_mm_enabled")
        private val KEY_CONFORMAL = booleanPreferencesKey("conformal_enabled")
        private val KEY_META = booleanPreferencesKey("meta_label_enabled")
        private val KEY_PATH_SIM = booleanPreferencesKey("path_sim_enabled")
        private val KEY_SAFE_V031 = booleanPreferencesKey("safe_light_defaults_v031")

        fun parseTickerList(text: String): List<String> =
            text.split(',', '\n', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
