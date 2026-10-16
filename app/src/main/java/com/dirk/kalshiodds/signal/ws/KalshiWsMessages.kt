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
        data class Subscribed(val sid: Int?, val raw: String, val channel: String? = null) : Parsed()
        /**
         * `cfbenchmarks_value`: the CF Benchmarks index Kalshi settles the
         * crypto markets on. [finalMinuteAvg] is the running average of the
         * final minute before a quarter-hour close (only sent then).
         */
        data class IndexValue(
            val indexId: String,
            val value: Double,
            val sourceTsMs: Long?,
            val receivedAtMs: Long?,
            val avg60: Double?,
            val avg60Ticks: Int?,
            val finalMinuteAvg: Double?,
            val finalMinuteTicks: Int?
        ) : Parsed()
        data class Unsubscribed(val sids: List<Int>, val raw: String) : Parsed()
        data class Lifecycle(
            val ticker: String,
            val eventType: String,
            val result: String? = null
        ) : Parsed()
        /** [id] is the command id the error answers, when Kalshi sends it. */
        data class Error(val code: Int?, val message: String, val id: Int? = null) : Parsed()

        /**
         * Private `fill`: one of the account's orders traded. [outcomeSide] is
         * the side acquired ("yes"/"no"), [count] contracts at [yesPrice] (0–1).
         */
        data class Fill(
            val orderId: String?,
            val ticker: String,
            val outcomeSide: String?,
            val count: Double,
            val yesPrice: Double?,
            val isTaker: Boolean?,
            val clientOrderId: String?,
            val postPosition: Double?,
            val tsMs: Long?
        ) : Parsed() {
            /** Price paid per contract for the side acquired, 0–1. */
            val sidePrice: Double?
                get() = yesPrice?.let { if (outcomeSide.equals("no", ignoreCase = true)) 1.0 - it else it }
        }

        /** Private `user_orders`: an order's status and counts changed. */
        data class UserOrder(
            val orderId: String,
            val ticker: String?,
            val status: String?,
            val fillCount: Double?,
            val remainingCount: Double?,
            val clientOrderId: String?
        ) : Parsed()

        /** Private `market_positions`: the account's position in a market changed. */
        data class Position(
            val ticker: String,
            val position: Double?,
            val realizedPnlUsd: Double?
        ) : Parsed()
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

    /** Subscribe to the authenticated CF Benchmarks index channel. */
    fun subscribeIndices(id: Int, indexIds: List<String>, channel: String = INDEX_CHANNEL): String {
        val params = mapOf<String, JsonElement>(
            "channels" to json.parseToJsonElement(json.encodeToString(listOf(channel))),
            "index_ids" to json.parseToJsonElement(json.encodeToString(indexIds))
        )
        return json.encodeToString(Command(id = id, cmd = "subscribe", params = params))
    }

    const val INDEX_CHANNEL = "cfbenchmarks_value"

    /**
     * Up to 5 updates a second for BRTI / ETHUSD_RTI / SOLUSD_RTI (200 ms
     * granularity). Subscribed next to [INDEX_CHANNEL], which carries the
     * 60 s and final-minute averages.
     */
    const val INDEX_CHANNEL_5HZ = "cfbenchmarks_value_5hz"

    /**
     * Read-only account channels: the account's fills, order status changes,
     * and position changes. Subscribing never places, changes or cancels
     * anything. No market filter: they cover every market.
     */
    val PRIVATE_CHANNELS = listOf("fill", "user_orders", "market_positions")

    fun subscribePrivate(id: Int, channels: List<String> = PRIVATE_CHANNELS): String {
        val params = mapOf<String, JsonElement>(
            "channels" to json.parseToJsonElement(json.encodeToString(channels))
        )
        return json.encodeToString(Command(id = id, cmd = "subscribe", params = params))
    }

    fun isPrivateChannel(channel: String?): Boolean =
        channel != null && (channel in PRIVATE_CHANNELS || channel == "market_position" || channel == "user_order")

    fun parseFill(msg: JsonObject?): Parsed.Fill? {
        msg ?: return null
        val ticker = msg.stringField("market_ticker", "ticker") ?: return null
        val count = msg.rawDouble("count_fp", "count")?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val bookSide = msg.stringField("book_side")?.lowercase()
        val outcome = msg.stringField("outcome_side", "purchased_side", "side")?.lowercase()
            ?: when (bookSide) {
                "bid" -> "yes"
                "ask" -> "no"
                else -> null
            }
        val yes = msg.rawDouble("yes_price_dollars", "yes_price_fixed")
            ?: msg.rawDouble("yes_price")?.let { if (it > 1.0) it / 100.0 else it }
        return Parsed.Fill(
            orderId = msg.stringField("order_id"),
            ticker = ticker,
            outcomeSide = outcome,
            count = count,
            yesPrice = yes?.takeIf { it.isFinite() && it in 0.0..1.0 },
            isTaker = (msg["is_taker"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
            clientOrderId = msg.stringField("client_order_id"),
            postPosition = msg.rawDouble("post_position_fp", "post_position"),
            tsMs = msg.longField("ts_ms") ?: msg.longField("ts")?.let { if (it < 10_000_000_000L) it * 1000L else it }
        )
    }

    fun parseUserOrder(msg: JsonObject?): Parsed.UserOrder? {
        msg ?: return null
        val orderId = msg.stringField("order_id") ?: return null
        return Parsed.UserOrder(
            orderId = orderId,
            ticker = msg.stringField("ticker", "market_ticker"),
            status = msg.stringField("status")?.lowercase(),
            fillCount = msg.rawDouble("fill_count_fp", "fill_count"),
            remainingCount = msg.rawDouble("remaining_count_fp", "remaining_count"),
            clientOrderId = msg.stringField("client_order_id")
        )
    }

    fun parsePosition(msg: JsonObject?): Parsed.Position? {
        msg ?: return null
        val ticker = msg.stringField("market_ticker", "ticker") ?: return null
        return Parsed.Position(
            ticker = ticker,
            position = msg.rawDouble("position_fp", "position"),
            realizedPnlUsd = msg.rawDouble("realized_pnl_dollars")
                ?: msg.rawDouble("realized_pnl")?.let { it / 10_000.0 }
        )
    }

    fun parseIndex(msg: JsonObject?): Parsed.IndexValue? {
        msg ?: return null
        val indexId = msg.stringField("index_id", "id") ?: return null
        val data: JsonObject? = when (val d = msg["data"]) {
            is JsonObject -> d
            is JsonPrimitive -> d.contentOrNull?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            else -> null
        }
        val avg = msg["avg_60s_data"] as? JsonObject
        val fin = msg["last_60s_windowed_average_15min"] as? JsonObject
        val value = data?.rawDouble("value", "v", "price")
            ?: avg?.rawDouble("value")
            ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return Parsed.IndexValue(
            indexId = indexId,
            value = value,
            sourceTsMs = data?.longField("time", "ts", "timestamp", "source_ts_ms"),
            receivedAtMs = msg.longField("received_at"),
            avg60 = avg?.rawDouble("value")?.takeIf { it.isFinite() && it > 0.0 },
            avg60Ticks = avg?.intField("window_size"),
            finalMinuteAvg = fin?.rawDouble("value", "average", "avg")?.takeIf { it.isFinite() && it > 0.0 },
            finalMinuteTicks = fin?.intField("window_size") ?: fin?.intField("count")
        )
    }

    fun unsubscribe(id: Int, sids: List<Int>): String {
        val params = mapOf(
            "sids" to json.parseToJsonElement(json.encodeToString(sids))
        )
        return json.encodeToString(Command(id = id, cmd = "unsubscribe", params = params))
    }

    /**
     * Official way to change tickers on an existing sid without reconnecting.
     * https://docs.kalshi.com/websockets/websocket-connection
     * https://docs.kalshi.com/getting_started/quick_start_websockets
     * `action`: `add_markets` | `delete_markets` | `get_snapshot`.
     * `update_subscription` must target exactly one `sid`.
     */
    fun updateSubscription(
        id: Int,
        sid: Int,
        action: String,
        marketTickers: List<String>
    ): String {
        val params = mutableMapOf<String, JsonElement>(
            "sid" to JsonPrimitive(sid),
            "action" to JsonPrimitive(action)
        )
        if (marketTickers.isNotEmpty()) {
            params["market_tickers"] = json.parseToJsonElement(json.encodeToString(marketTickers))
        }
        return json.encodeToString(Command(id = id, cmd = "update_subscription", params = params))
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
            "subscribed" -> Parsed.Subscribed(
                env.sid ?: env.msg?.intField("sid"),
                raw,
                env.msg?.stringField("channel")
            )
            INDEX_CHANNEL, INDEX_CHANNEL_5HZ -> parseIndex(env.msg) ?: Parsed.Other(env.type, raw)
            "unsubscribed" -> Parsed.Unsubscribed(
                sids = env.msg?.intList("sids").orEmpty().ifEmpty {
                    listOfNotNull(env.sid)
                },
                raw = raw
            )
            "market_lifecycle_v2", "event_lifecycle" -> {
                val ticker = env.msg?.stringField("market_ticker") ?: return Parsed.Other(env.type, raw)
                val eventType = env.msg.stringField("event_type", "event", "status") ?: "updated"
                Parsed.Lifecycle(
                    ticker = ticker,
                    eventType = eventType,
                    result = env.msg.stringField("result")
                )
            }
            "error" -> Parsed.Error(
                code = env.msg?.intField("code"),
                message = env.msg?.stringField("msg") ?: env.msg?.stringField("message") ?: "WS error",
                id = env.id
            )
            "fill" -> parseFill(env.msg) ?: Parsed.Other(env.type, raw)
            "user_order", "user_orders" -> parseUserOrder(env.msg) ?: Parsed.Other(env.type, raw)
            "market_position", "market_positions" -> parsePosition(env.msg) ?: Parsed.Other(env.type, raw)
            else -> Parsed.Other(env.type, raw)
        }
    }

    fun parseTicker(msg: JsonObject?, receiveElapsedNanos: Long): MarketTick? {
        if (msg == null) return null
        val ticker = msg.stringField("market_ticker") ?: return null
        val bid = msg.dollarField("yes_bid_dollars", "yes_bid")
        val ask = msg.dollarField("yes_ask_dollars", "yes_ask")
        val noBid = msg.dollarField("no_bid_dollars", "no_bid")
        val noAsk = msg.dollarField("no_ask_dollars", "no_ask")
        val last = msg.dollarField("price_dollars", "last_price_dollars", "price")
        val snap = com.dirk.kalshiodds.domain.ConsistentQuote.fromSameUpdate(bid, ask, noBid, noAsk)
        return MarketTick(
            ticker = ticker,
            series = MarketTick.inferSeries(ticker),
            yesBid = snap?.yesBid ?: bid,
            yesAsk = snap?.yesAsk ?: ask,
            lastPrice = last,
            volume = msg.rawDouble("volume_fp", "volume"),
            openInterest = msg.rawDouble("open_interest_fp", "open_interest"),
            closeTimeEpochMs = null,
            source = TickSource.WS_TICKER,
            receiveElapsedNanos = receiveElapsedNanos,
            exchangeTsMs = msg.longField("ts_ms") ?: msg.longField("ts")?.times(1000),
            tradeSize = msg.rawDouble("last_trade_size_fp"),
            noBid = snap?.noBid ?: noBid,
            noAsk = snap?.noAsk ?: noAsk
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
            tradeSize = msg.rawDouble("count_fp", "count"),
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
                val size = sizePrim.doubleOrNull ?: sizePrim.contentOrNull?.toDoubleOrNull() ?: continue
                val priceText = pricePrim.contentOrNull ?: continue
                val price = com.dirk.kalshiodds.domain.KalshiPrice.parseDollars(priceText) ?: continue
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

    private fun JsonObject.intList(name: String): List<Int>? {
        val arr = this[name] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val p = el as? JsonPrimitive ?: return@mapNotNull null
            p.longOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull()
        }
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
     * Prefers FixedPointDollars (`yes_ask_dollars` = `"0.0150"`).
     * Current ticker / orderbook schemas require `*_dollars` /
     * `yes_dollars_fp` and do not list legacy integer fields
     * (https://docs.kalshi.com/websockets/market-ticker,
     * https://docs.kalshi.com/websockets/orderbook-updates).
     * Legacy integer-cent fields (`"15"`) are 15¢. `"1"` is 1¢, not $1.
     * Unusable placeholders (`"0.0000"`, `"1.0000"`) fall through to the
     * next name so a dollars miss can still use a legacy integer.
     */
    private fun JsonObject.dollarField(vararg names: String): Double? {
        for (n in names) {
            val v = this[n] ?: continue
            val prim = (v as? JsonPrimitive) ?: continue
            val raw = prim.contentOrNull ?: continue
            com.dirk.kalshiodds.domain.KalshiPrice.parseDollars(raw)?.let { return it }
        }
        return null
    }
}
