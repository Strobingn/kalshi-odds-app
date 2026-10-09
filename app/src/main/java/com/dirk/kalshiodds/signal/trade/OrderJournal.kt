package com.dirk.kalshiodds.signal.trade

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class OrderJournal(context: Context) {
    private val prefs = context.getSharedPreferences("edge_order_journal", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun load(account: String): List<PlacedOrder> =
        prefs.getString(account, null)?.let { json.decodeFromString<List<PlacedOrder>>(it) }.orEmpty()
    fun save(account: String, orders: List<PlacedOrder>) {
        val active = orders.filter { it.status in setOf("submitting", "unknown", "resting", "acknowledged") }
        val terminal = orders.filterNot { it in active }.takeLast(100)
        check(prefs.edit().putString(account, json.encodeToString(active + terminal)).commit()) {
            "Cannot save order journal; submission blocked"
        }
    }
}
