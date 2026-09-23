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
    val resumeOnNewSession: Boolean = SignalConstants.DEFAULT_RESUME_ON_NEW_SESSION
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
