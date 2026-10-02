package com.dirk.kalshiodds.arb.data

import com.dirk.kalshiodds.arb.scan.EventInfo
import com.dirk.kalshiodds.arb.scan.Level
import com.dirk.kalshiodds.arb.scan.MarketBook
import com.dirk.kalshiodds.arb.scan.MarketInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses Kalshi public Trade API v2 JSON. Tolerant by design: current
 * FixedPointDollars strings (`"yes_ask_dollars": "0.4500"`, order book
 * `orderbook_fp.yes_dollars: [["0.4500","100.00"]]`) and the legacy integer
 * cent fields (`"yes_ask": 45`, `orderbook.yes: [[45, 100]]`).
 */
object KalshiParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class EventsPage(val events: List<EventInfo>, val cursor: String?)

    fun parseEventsPage(body: String): EventsPage {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return EventsPage(emptyList(), null)
        val events = (root["events"] as? JsonArray).orEmpty().mapNotNull { parseEvent(it as? JsonObject) }
        val cursor = root.str("cursor")?.takeIf { it.isNotBlank() }
        return EventsPage(events, cursor)
    }

    fun parseEvent(o: JsonObject?): EventInfo? {
        if (o == null) return null
        val ticker = o.str("event_ticker") ?: return null
        val markets = (o["markets"] as? JsonArray).orEmpty().mapNotNull { parseMarket(it as? JsonObject, ticker) }
        return EventInfo(
            eventTicker = ticker,
            seriesTicker = o.str("series_ticker") ?: "",
            title = o.str("title") ?: "",
            subTitle = o.str("sub_title") ?: "",
            mutuallyExclusive = (o["mutually_exclusive"] as? JsonPrimitive)?.booleanOrNull ?: false,
            category = o.str("category") ?: "",
            markets = markets
        )
    }

    fun parseMarket(o: JsonObject?, eventTicker: String? = null): MarketInfo? {
        if (o == null) return null
        val ticker = o.str("ticker") ?: return null
        return MarketInfo(
            ticker = ticker,
            eventTicker = o.str("event_ticker") ?: eventTicker ?: "",
            title = o.str("title") ?: "",
            subtitle = o.str("yes_sub_title") ?: o.str("subtitle") ?: "",
            status = o.str("status") ?: "",
            strikeType = o.str("strike_type"),
            floorStrike = o.num("floor_strike"),
            capStrike = o.num("cap_strike"),
            yesBidE4 = o.priceE4("yes_bid_dollars", "yes_bid"),
            yesAskE4 = o.priceE4("yes_ask_dollars", "yes_ask"),
            noBidE4 = o.priceE4("no_bid_dollars", "no_bid"),
            noAskE4 = o.priceE4("no_ask_dollars", "no_ask"),
            closeTime = o.str("close_time"),
            rulesPrimary = o.str("rules_primary")
        )
    }

    /** `GET /markets/{ticker}/orderbook` → buyable asks for both sides. */
    fun parseOrderbook(ticker: String, body: String): MarketBook {
        val root = json.parseToJsonElement(body) as? JsonObject
        val book = (root?.get("orderbook_fp") as? JsonObject)
            ?: (root?.get("orderbook") as? JsonObject)
            ?: return MarketBook(ticker, emptyList(), emptyList())
        val yesBids = book.levels("yes_dollars_fp", "yes_dollars", "yes")
        val noBids = book.levels("no_dollars_fp", "no_dollars", "no")
        return MarketBook.fromBids(ticker, yesBids, noBids)
    }

    /**
     * A price as E4 (1/10 000 dollar). Strings with a decimal point are
     * dollars (`"0.4500"`); bare integers are legacy cents (`45`).
     */
    internal fun toPriceE4(p: JsonPrimitive, dollarsField: Boolean): Int? {
        val raw = p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bd = raw.toBigDecimalOrNull() ?: return null
        val isDollars = raw.contains('.') || (dollarsField && p.isString && bd < BigDecimal.ONE)
        val e4 = if (isDollars) bd.movePointRight(4) else bd.movePointRight(2)
        return e4.setScale(0, RoundingMode.HALF_UP).toInt()
    }

    private fun JsonObject.priceE4(dollarsName: String, centsName: String): Int? {
        (this[dollarsName] as? JsonPrimitive)?.let { p -> toPriceE4(p, true)?.let { return it } }
        (this[centsName] as? JsonPrimitive)?.let { p -> toPriceE4(p, false)?.let { return it } }
        return null
    }

    private fun JsonObject.levels(vararg names: String): List<Level> {
        for (n in names) {
            val arr = this[n] as? JsonArray ?: continue
            val dollars = n.contains("dollars")
            val out = ArrayList<Level>(arr.size)
            for (el in arr) {
                val pair = el as? JsonArray ?: continue
                if (pair.size < 2) continue
                val price = (pair[0] as? JsonPrimitive)?.let { toPriceE4(it, dollars) } ?: continue
                val size = (pair[1] as? JsonPrimitive)?.let { sizeOf(it) } ?: continue
                if (size > 0 && price in 1..9_999) out += Level(price, size)
            }
            return out
        }
        return emptyList()
    }

    /** Whole contracts (fractional `_fp` sizes are floored). */
    private fun sizeOf(p: JsonPrimitive): Long? {
        val bd = p.contentOrNull?.trim()?.toBigDecimalOrNull() ?: return null
        return bd.setScale(0, RoundingMode.FLOOR).toLong()
    }

    private fun JsonObject.str(name: String): String? {
        val v: JsonElement = this[name] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.num(name: String): Double? {
        val p = this[name] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }
}
