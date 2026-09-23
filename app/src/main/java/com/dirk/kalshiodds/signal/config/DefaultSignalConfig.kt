package com.dirk.kalshiodds.signal.config

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DefaultSignalConfig(
    val watchBtc: Boolean = true,
    val watchEth: Boolean = true,
    val watchSol: Boolean = true,
    val extraTickers: List<String> = emptyList(),
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
    val pathSimEnabled: Boolean = SignalConstants.DEFAULT_PATH_SIM
) {
    companion object {
        const val ASSET_NAME = "default_signal_config.json"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun load(context: Context?): DefaultSignalConfig {
            if (context == null) return DefaultSignalConfig()
            return runCatching {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                parse(text)
            }.getOrElse { DefaultSignalConfig() }
        }

        fun parse(raw: String): DefaultSignalConfig {
            val decoded = json.decodeFromString<DefaultSignalConfig>(raw)
            return decoded.copy(extraTickers = CryptoMarkets.filterCrypto(decoded.extraTickers))
        }
    }
}
