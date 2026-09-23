package com.dirk.kalshiodds.signal.ws

import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object KalshiWsMessages {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Serializable
    data class Command(
        val id: Int,
        val cmd: String,
        val params: Map<String, JsonElement>? = null
    )

    @Serializable
    data class Envelope(
        val type: String? = null,
        val id: Int? = null,
        val sid: Int? = null,
        val seq: Int? = null,
        val cmd: String? = null,
        val msg: JsonObject? = null
    )

    sealed class Parsed {
        data class Ticker(val tick: MarketTick) : Parsed()
        data class Trade(val tick: MarketTick) : Parsed()
        data class OrderbookSnapshot(
            val ticker: String,
            val yesLevels: List<Pair<Double, Double>>,
            val noLevels: List<Pair<Double, Double>>,
            val seq: Int?,
            val receiveElapsedNanos: Long
        ) : Parsed()
        data class OrderbookDelta(
            val ticker: String,
            val price: Double,
            val delta: Double,
            val side: String,
            val seq: Int?,
            val receiveElapsedNanos: Long
        ) : Parsed()
        data class Subscribed(val sid: Int?, val raw: String) : Parsed()
        data class Error(val code: Int?, val message: String) : Parsed()
        data class Other(val type: String?, val raw: String) : Parsed()
    }

    fun subscribe(id: Int, channels: List<String>, marketTickers: List<String>? = null): String {
        val params = mutableMapOf<String, JsonElement>(
            "channels" to json.parseToJsonElement(json.encodeToString(channels))
        )
        if (!marketTickers.isNullOrEmpty()) {
            params["market_tickers"] = json.parseToJsonElement(json.encodeToString(marketTickers))
        }
        return json.encodeToString(Command(id = id, cmd = "subscribe", params = params))
    }

    fun unsubscribe(id: Int, sids: List<Int>): String {
        val params = mapOf(
            "sids" to json.parseToJsonElement(json.encodeToString(sids))
        )
        return json.encodeToString(Command(id = id, cmd = "unsubscribe", params = params))
    }

    fun parse(raw: String, receiveElapsedNanos: Long): Parsed {
        val env = runCatching { json.decodeFromString<Envelope>(raw) }.getOrNull()
            ?: return Parsed.Other(null, raw)
        return when (env.type) {
            "ticker" -> {
                val tick = parseTicker(env.msg, receiveElapsedNanos) ?: return Parsed.Other(env.type, raw)
                Parsed.Ticker(tick)
            }
            "trade" -> {
                val tick = parseTrade(env.msg, receiveElapsedNanos) ?: return Parsed.Other(env.type, raw)
                Parsed.Trade(tick)
            }
            "orderbook_snapshot" -> {
                parseSnapshot(env.msg, env.seq, receiveElapsedNanos) ?: return Parsed.Other(env.type, raw)
            }
            "orderbook_delta" -> {
                parseDelta(env.msg, env.seq, receiveElapsedNanos) ?: return Parsed.Other(env.type, raw)
            }
            "subscribed" -> Parsed.Subscribed(env.sid ?: env.msg?.intField("sid"), raw)
            "error" -> Parsed.Error(
                code = env.msg?.intField("code"),
                message = env.msg?.stringField("msg") ?: env.msg?.stringField("message") ?: "WS error"
            )
            else -> Parsed.Other(env.type, raw)
        }
    }

    fun parseTicker(msg: JsonObject?, receiveElapsedNanos: Long): MarketTick? {
        if (msg == null) return null
        val ticker = msg.stringField("market_ticker") ?: return null
        val bid = msg.dollarField("yes_bid_dollars", "yes_bid")
        val ask = msg.dollarField("yes_ask_dollars", "yes_ask")
        val last = msg.dollarField("price_dollars", "last_price_dollars", "price")
        return MarketTick(
            ticker = ticker,
            series = MarketTick.inferSeries(ticker),
            yesBid = bid,
            yesAsk = ask,
            lastPrice = last,
            volume = msg.dollarField("volume_fp", "volume"),
            openInterest = msg.dollarField("open_interest_fp", "open_interest"),
            closeTimeEpochMs = null,
            source = TickSource.WS_TICKER,
            receiveElapsedNanos = receiveElapsedNanos,
            exchangeTsMs = msg.longField("ts_ms") ?: msg.longField("ts")?.times(1000),
            tradeSize = msg.dollarField("last_trade_size_fp")
        )
    }

    fun parseTrade(msg: JsonObject?, receiveElapsedNanos: Long): MarketTick? {
        if (msg == null) return null
        val ticker = msg.stringField("market_ticker") ?: return null
        val yes = msg.dollarField("yes_price_dollars", "yes_price")
        return MarketTick(
            ticker = ticker,
            series = MarketTick.inferSeries(ticker),
            yesBid = yes,
            yesAsk = yes,
            lastPrice = yes,
            volume = null,
            openInterest = null,
            closeTimeEpochMs = null,
            source = TickSource.WS_TRADE,
            receiveElapsedNanos = receiveElapsedNanos,
            exchangeTsMs = msg.longField("ts_ms") ?: msg.longField("ts")?.times(1000),
            tradeSize = msg.dollarField("count_fp", "count"),
            takerSide = msg.stringField("taker_side") ?: msg.stringField("taker_outcome_side")
        )
    }

    fun parseSnapshot(msg: JsonObject?, seq: Int?, receiveElapsedNanos: Long): Parsed.OrderbookSnapshot? {
        if (msg == null) return null
        val ticker = msg.stringField("market_ticker") ?: return null
        val yes = msg.levelArray("yes_dollars_fp", "yes_dollars", "yes")
        val no = msg.levelArray("no_dollars_fp", "no_dollars", "no")
        return Parsed.OrderbookSnapshot(
            ticker = ticker,
            yesLevels = yes,
            noLevels = no,
            seq = seq,
            receiveElapsedNanos = receiveElapsedNanos
        )
    }

    fun parseDelta(msg: JsonObject?, seq: Int?, receiveElapsedNanos: Long): Parsed.OrderbookDelta? {
        if (msg == null) return null
        val ticker = msg.stringField("market_ticker") ?: return null
        val side = msg.stringField("side") ?: return null
        val price = msg.dollarField("price_dollars", "price") ?: return null
        val delta = msg.rawDouble("delta_fp", "delta") ?: return null
        return Parsed.OrderbookDelta(
            ticker = ticker,
            price = price,
            delta = delta,
            side = side,
            seq = seq,
            receiveElapsedNanos = receiveElapsedNanos
        )
    }

    private fun JsonObject.levelArray(vararg names: String): List<Pair<Double, Double>> {
        for (n in names) {
            val arr = this[n] as? JsonArray ?: continue
            val out = ArrayList<Pair<Double, Double>>(arr.size)
            for (el in arr) {
                val pair = el as? JsonArray ?: continue
                if (pair.size < 2) continue
                val pricePrim = pair[0] as? JsonPrimitive ?: continue
                val sizePrim = pair[1] as? JsonPrimitive ?: continue
                val priceRaw = pricePrim.doubleOrNull ?: pricePrim.contentOrNull?.toDoubleOrNull() ?: continue
                val size = sizePrim.doubleOrNull ?: sizePrim.contentOrNull?.toDoubleOrNull() ?: continue
                val priceText = pricePrim.contentOrNull.orEmpty()
                val price = if (!priceText.contains('.') && priceRaw > 1.0 && priceRaw <= 100.0) {
                    priceRaw / 100.0
                } else {
                    priceRaw
                }
                if (size > 0.0) out += price to size
            }
            if (out.isNotEmpty() || arr.isEmpty()) return out
        }
        return emptyList()
    }

    private fun JsonObject.stringField(vararg names: String): String? {
        for (n in names) {
            val v = this[n] ?: continue
            val s = when (v) {
                is JsonPrimitive -> v.contentOrNull
                else -> v.toString().trim('"')
            }
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    private fun JsonObject.longField(vararg names: String): Long? {
        for (n in names) {
            val v = this[n] as? JsonPrimitive ?: continue
            v.longOrNull?.let { return it }
            v.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.intField(name: String): Int? {
        val v = this[name] as? JsonPrimitive ?: return null
        return v.longOrNull?.toInt() ?: v.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.rawDouble(vararg names: String): Double? {
        for (n in names) {
            val v = this[n] as? JsonPrimitive ?: continue
            v.doubleOrNull?.let { return it }
            v.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Accepts dollar strings ("0.4500"), integer cents (45), or 0–1 floats.
     */
    private fun JsonObject.dollarField(vararg names: String): Double? {
        for (n in names) {
            val v = this[n] ?: continue
            val prim = (v as? JsonPrimitive) ?: continue
            val asDouble = prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull() ?: continue
            val raw = prim.contentOrNull.orEmpty()
            return when {
                raw.contains('.') -> asDouble
                asDouble > 1.0 && asDouble <= 100.0 && !n.contains("volume") && !n.contains("interest") && !n.contains("count") && !n.contains("size") ->
                    asDouble / 100.0
                else -> asDouble
            }
        }
        return null
    }
}
