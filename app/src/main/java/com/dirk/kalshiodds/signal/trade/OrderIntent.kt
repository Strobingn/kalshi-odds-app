package com.dirk.kalshiodds.signal.trade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A live order that may already be at Kalshi. The client_order_id stays
 * stable across dismiss, a stake edit that changes the ticket key, and
 * process death until Kalshi confirms the order or rejects it for good.
 */
data class PendingOrderIntent(
    val clientOrderId: String,
    val ticker: String,
    val side: String,
    val action: String,
    val limitPrice: Double,
    val yesLimitPrice: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val bookSide: String,
    val kind: String,
    val state: String,
    val updatedAtMs: Long
) {
    val isOpen: Boolean get() = state == STATE_INFLIGHT || state == STATE_UNCERTAIN

    fun matchesSlot(ticket: TradeTicket): Boolean =
        ticker.equals(ticket.ticker, ignoreCase = true) &&
            side.equals(ticket.side, ignoreCase = true) &&
            action == actionOf(ticket)

    fun sameTerms(ticket: TradeTicket): Boolean =
        matchesSlot(ticket) &&
            contracts == ticket.contracts &&
            kotlin.math.abs(limitPrice - ticket.limitPrice) < PRICE_EPSILON

    fun toJson(): JSONObject = JSONObject()
        .put("clientOrderId", clientOrderId)
        .put("ticker", ticker)
        .put("side", side)
        .put("action", action)
        .put("limitPrice", limitPrice)
        .put("yesLimitPrice", yesLimitPrice)
        .put("contracts", contracts)
        .put("stakeUsd", stakeUsd)
        .put("bookSide", bookSide)
        .put("kind", kind)
        .put("state", state)
        .put("updatedAtMs", updatedAtMs)

    companion object {
        const val STATE_INFLIGHT = "inflight"
        const val STATE_UNCERTAIN = "uncertain"
        const val PRICE_EPSILON = 0.0005

        fun actionOf(ticket: TradeTicket): String = if (ticket.isSell) "SELL" else "BUY"

        fun from(ticket: TradeTicket, clientOrderId: String, state: String, nowMs: Long) = PendingOrderIntent(
            clientOrderId = clientOrderId,
            ticker = ticket.ticker,
            side = ticket.side.uppercase(),
            action = actionOf(ticket),
            limitPrice = ticket.limitPrice,
            yesLimitPrice = ticket.yesLimitPrice,
            contracts = ticket.contracts,
            stakeUsd = ticket.stakeUsd,
            bookSide = ticket.bookSide,
            kind = ticket.kind.name,
            state = state,
            updatedAtMs = nowMs
        )

        fun encode(intents: List<PendingOrderIntent>): String {
            val array = JSONArray()
            intents.filter { it.isOpen && it.clientOrderId.isNotBlank() }.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decode(raw: String?): List<PendingOrderIntent> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val out = ArrayList<PendingOrderIntent>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("clientOrderId")
                val ticker = o.optString("ticker")
                if (id.isBlank() || ticker.isBlank()) continue
                out += PendingOrderIntent(
                    clientOrderId = id,
                    ticker = ticker,
                    side = o.optString("side", "YES"),
                    action = o.optString("action", "BUY"),
                    limitPrice = o.optDouble("limitPrice"),
                    yesLimitPrice = o.optDouble("yesLimitPrice", o.optDouble("limitPrice")),
                    contracts = o.optInt("contracts"),
                    stakeUsd = o.optDouble("stakeUsd"),
                    bookSide = o.optString("bookSide", "bid"),
                    kind = o.optString("kind", "MANUAL"),
                    state = o.optString("state", STATE_UNCERTAIN),
                    updatedAtMs = o.optLong("updatedAtMs")
                )
            }
            return out.filter { it.isOpen }
        }
    }
}

interface OrderIntentStore {
    fun load(): List<PendingOrderIntent>
    fun save(intents: List<PendingOrderIntent>)
}

class MemoryOrderIntentStore : OrderIntentStore {
    private var items: List<PendingOrderIntent> = emptyList()
    override fun load(): List<PendingOrderIntent> = items
    override fun save(intents: List<PendingOrderIntent>) {
        items = intents.filter { it.isOpen }
    }
}

class SharedPrefsOrderIntentStore(context: Context) : OrderIntentStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<PendingOrderIntent> = PendingOrderIntent.decode(prefs.getString(KEY, null))

    override fun save(intents: List<PendingOrderIntent>) {
        prefs.edit().putString(KEY, PendingOrderIntent.encode(intents)).commit()
    }

    companion object {
        private const val PREFS = "diphunter_order_intents"
        private const val KEY = "pending_json"
    }
}
