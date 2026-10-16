package com.dirk.kalshiodds.data.local.results

import com.dirk.kalshiodds.data.local.archive.DataArchive

/**
 * Pending Kalshi `client_order_id` for one ticker/side/kind.
 * Survives a process kill between a timed-out Approve and the retry.
 */
data class PendingClientOrder(
    val key: String,
    val clientOrderId: String,
    val attempted: Boolean = false,
    val ticker: String = "",
    val side: String = "",
    val kind: String = "",
    val updatedAtMs: Long = 0L
)

interface PendingOrderIdStore {
    fun save(order: PendingClientOrder)
    fun find(key: String): PendingClientOrder?
    fun clear(key: String)

    companion object {
        val None: PendingOrderIdStore = object : PendingOrderIdStore {
            override fun save(order: PendingClientOrder) = Unit
            override fun find(key: String): PendingClientOrder? = null
            override fun clear(key: String) = Unit
        }
    }
}

/** JVM stand-in that survives a new [com.dirk.kalshiodds.signal.trade.TicketSession]. */
class MemoryPendingOrderIdStore : PendingOrderIdStore {
    private val rows = LinkedHashMap<String, PendingClientOrder>()

    @Synchronized
    override fun save(order: PendingClientOrder) {
        rows[order.key] = order
    }

    @Synchronized
    override fun find(key: String): PendingClientOrder? = rows[key]

    @Synchronized
    override fun clear(key: String) {
        rows.remove(key)
    }
}

/** Shared by the SQLite results file and the in-memory fallback. */
interface ResultsDatabase : ResultsStore, DataArchive, PendingOrderIdStore
