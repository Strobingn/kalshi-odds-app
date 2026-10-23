package com.dirk.kalshiodds.signal.trade

import java.util.UUID

/** Idempotent Kalshi client_order_id. UUID version 4, stable for retries of the same ticket. */
object ClientOrderIds {
    fun next(): String = UUID.randomUUID().toString()

    fun isUuid4(id: String): Boolean {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return false
        return uuid.version() == 4
    }
}
