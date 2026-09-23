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
    val hideWeakOpportunities: Boolean = SignalConstants.DEFAULT_HIDE_WEAK
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
