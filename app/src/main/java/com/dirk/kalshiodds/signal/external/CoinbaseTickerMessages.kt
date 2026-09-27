package com.dirk.kalshiodds.signal.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Coinbase Exchange public WebSocket feed (`wss://ws-feed.exchange.coinbase.com`)
 * messages for the `ticker` and `heartbeat` channels. Market data only, no keys.
 *
 * Ticker (one per trade match):
 * `{"type":"ticker","product_id":"BTC-USD","price":"65000.01","time":"2026-09-27T12:00:00.123456Z",…}`
 *
 * Heartbeat (once a second per product):
 * `{"type":"heartbeat","product_id":"BTC-USD","last_trade_id":…,"time":"…"}`
 */
object CoinbaseTickerMessages {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    sealed class Parsed {
        data class Tick(
            val productId: String,
            val asset: String,
            val price: Double,
            /** Exchange ISO-8601 time, unparsed (see [parseTimeMs]). */
            val time: String?
        ) : Parsed()

        data class Heartbeat(val productId: String, val asset: String) : Parsed()
        data class Subscribed(val channels: List<String>) : Parsed()
        data class Error(val message: String) : Parsed()
        data class Other(val type: String?) : Parsed()
    }

    /** `BTC-USD` → `BTC`; only the three coins the app scores. */
    fun assetOf(productId: String?): String? {
        val base = productId?.trim()?.uppercase()?.substringBefore('-') ?: return null
        return base.takeIf { it in ASSETS }
    }

    /**
     * Coinbase products for the Kalshi series the app scores (`KXBTC15M` →
     * `BTC-USD`). Streaming a coin nothing scores is wasted parsing on a phone.
     */
    fun productsFor(series: Iterable<String>): List<String> =
        series.mapNotNull { ExternalSnapshot.assetOf(it) }
            .distinct()
            .map { "$it-USD" }
            .ifEmpty { PRODUCTS }

    fun subscribe(productIds: List<String>, channels: List<String> = CHANNELS): String =
        buildJsonObject {
            put("type", "subscribe")
            putJsonArray("product_ids") { productIds.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("channels") { channels.forEach { add(JsonPrimitive(it)) } }
        }.toString()

    /**
     * Null for text that is not a JSON object. Never throws. A ticker with an
     * unknown product or an unusable price comes back as [Parsed.Other] so the
     * caller simply ignores it.
     */
    fun parse(text: String): Parsed? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val type = obj.str("type")
        return when (type) {
            "ticker" -> {
                val product = obj.str("product_id")
                val asset = assetOf(product) ?: return Parsed.Other(type)
                val price = obj.str("price")?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: return Parsed.Other(type)
                Parsed.Tick(productId = product!!, asset = asset, price = price, time = obj.str("time"))
            }
            "heartbeat" -> {
                val product = obj.str("product_id")
                val asset = assetOf(product) ?: return Parsed.Other(type)
                Parsed.Heartbeat(productId = product!!, asset = asset)
            }
            "subscriptions" -> {
                val names = (obj["channels"] as? JsonArray).orEmpty().mapNotNull { ch ->
                    when (ch) {
                        is JsonPrimitive -> ch.contentOrNull
                        is JsonObject -> ch.str("name")
                        else -> null
                    }
                }
                Parsed.Subscribed(names)
            }
            "error" -> Parsed.Error(
                listOfNotNull(obj.str("message"), obj.str("reason")).joinToString(": ").ifBlank { "error" }
            )
            else -> Parsed.Other(type)
        }
    }

    /** ISO-8601 exchange time → epoch ms, or null. */
    fun parseTimeMs(time: String?): Long? {
        if (time.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(time).toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    val ASSETS = setOf("BTC", "ETH", "SOL")
    val PRODUCTS = listOf("BTC-USD", "ETH-USD", "SOL-USD")
    val CHANNELS = listOf("ticker", "heartbeat")
}
