package com.dirk.kalshiodds.signal.config

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import org.json.JSONArray
import org.json.JSONObject

data class DefaultSignalConfig(
    val watchBtc: Boolean = true,
    val watchEth: Boolean = true,
    val watchSol: Boolean = true,
    val extraTickers: List<String> = emptyList(),
    val edgeThresholdPp: Double = 5.0,
    val notificationsEnabled: Boolean = true,
    val liveSignalsEnabled: Boolean = false,
    val subscribeTrades: Boolean = true,
    val debounceMs: Long = 10_000L
) {
    companion object {
        const val ASSET_NAME = "default_signal_config.json"

        fun load(context: Context?): DefaultSignalConfig {
            if (context == null) return DefaultSignalConfig()
            return runCatching {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                parse(text)
            }.getOrElse { DefaultSignalConfig() }
        }

        fun parse(json: String): DefaultSignalConfig {
            val obj = JSONObject(json)
            val extras = mutableListOf<String>()
            val arr: JSONArray? = obj.optJSONArray("extraTickers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    extras += arr.optString(i).trim()
                }
            }
            return DefaultSignalConfig(
                watchBtc = obj.optBoolean("watchBtc", true),
                watchEth = obj.optBoolean("watchEth", true),
                watchSol = obj.optBoolean("watchSol", true),
                extraTickers = CryptoMarkets.filterCrypto(extras),
                edgeThresholdPp = obj.optDouble("edgeThresholdPp", 5.0),
                notificationsEnabled = obj.optBoolean("notificationsEnabled", true),
                liveSignalsEnabled = obj.optBoolean("liveSignalsEnabled", false),
                subscribeTrades = obj.optBoolean("subscribeTrades", true),
                debounceMs = obj.optLong("debounceMs", 10_000L)
            )
        }
    }
}
