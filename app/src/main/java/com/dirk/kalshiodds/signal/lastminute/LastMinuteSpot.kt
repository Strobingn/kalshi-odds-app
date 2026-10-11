package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.ln
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live BRTI stand-in: median of Coinbase / Kraken / Bitstamp / Gemini
 * public BTC-USD tickers. Falls back to the existing Binance/Coinbase
 * feed the caller supplies. 1-minute Coinbase candles feed [sig_s].
 *
 * BRTI itself is a licensed CF Benchmarks product; this is an approximation
 * from the same constituent-exchange family (see research/last_minute/README.md).
 */
data class BrtiQuote(
    val price: Double,
    val source: String,
    val constituents: Map<String, Double> = emptyMap(),
    val fallback: Boolean = false,
    val fetchedAtMs: Long = 0L
)

data class MinuteClose(
    val epochSec: Long,
    val close: Double
)

class BrtiCompositeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SignalConstants.EXTERNAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SignalConstants.EXTERNAL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(
            SignalConstants.EXTERNAL_CONNECT_TIMEOUT_MS + SignalConstants.EXTERNAL_READ_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        .followRedirects(true)
        .build()
) {
    fun fetchSpot(fallbackPrice: Double? = null, fallbackSource: String? = null): BrtiQuote? {
        val now = System.currentTimeMillis()
        val parts = linkedMapOf<String, Double>()
        fetchCoinbaseSpot()?.let { parts["Coinbase"] = it }
        fetchKrakenSpot()?.let { parts["Kraken"] = it }
        fetchBitstampSpot()?.let { parts["Bitstamp"] = it }
        fetchGeminiSpot()?.let { parts["Gemini"] = it }
        if (parts.isNotEmpty()) {
            val median = median(parts.values.toList()) ?: return fallback(fallbackPrice, fallbackSource, now)
            return BrtiQuote(
                price = median,
                source = "BRTI composite (${parts.keys.joinToString(", ")})",
                constituents = parts,
                fallback = false,
                fetchedAtMs = now
            )
        }
        return fallback(fallbackPrice, fallbackSource, now)
    }

    fun fetchMinuteCloses(limit: Int = LastMinuteConstants.VOL_MINUTES + 2): List<MinuteClose> {
        val n = limit.coerceIn(30, 300)
        val arr = getJsonArray(
            "https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=60&limit=$n"
        ) ?: return emptyList()
        val rows = mutableListOf<MinuteClose>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val t = row.optLong(0)
            val close = row.optDouble(4)
            if (t > 0L && close.isFinite() && close > 0.0) {
                rows.add(MinuteClose(epochSec = t, close = close))
            }
        }
        return rows.sortedBy { it.epochSec }
    }

    fun logCloses(candles: List<MinuteClose>): List<Double> =
        candles.mapNotNull { if (it.close > 0.0) ln(it.close) else null }

    private fun fallback(price: Double?, source: String?, now: Long): BrtiQuote? {
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val src = source?.takeIf { it.isNotBlank() } ?: "fallback spot"
        return BrtiQuote(
            price = p,
            source = "fallback: $src",
            fallback = true,
            fetchedAtMs = now
        )
    }

    private fun fetchCoinbaseSpot(): Double? =
        getJsonObject("https://api.exchange.coinbase.com/products/BTC-USD/ticker")
            ?.optString("price")?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun fetchKrakenSpot(): Double? {
        val obj = getJsonObject("https://api.kraken.com/0/public/Ticker?pair=XBTUSD") ?: return null
        val result = obj.optJSONObject("result") ?: return null
        val keys = result.keys()
        while (keys.hasNext()) {
            val row = result.optJSONObject(keys.next() as String) ?: continue
            val c = row.optJSONArray("c") ?: continue
            c.optString(0).toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
        }
        return null
    }

    private fun fetchBitstampSpot(): Double? =
        getJsonObject("https://www.bitstamp.net/api/v2/ticker/btcusd/")
            ?.optString("last")?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun fetchGeminiSpot(): Double? =
        getJsonObject("https://api.gemini.com/v1/pubticker/btcusd")
            ?.optString("last")?.toDoubleOrNull()?.takeIf { it > 0.0 }

    fun median(values: List<Double>): Double? {
        val v = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (v.isEmpty()) return null
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }

    private fun getJsonObject(url: String): JSONObject? {
        val body = get(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getJsonArray(url: String): JSONArray? {
        val body = get(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "DipHunter/0.3.17").build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
