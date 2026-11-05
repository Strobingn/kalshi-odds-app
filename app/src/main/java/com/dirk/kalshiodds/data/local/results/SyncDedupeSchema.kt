package com.dirk.kalshiodds.data.local.results

/**
 * DB v6 (1.24.1): removes the copies cloud sync stored. Before 1.24.1 every sync wrote each of this device's recent
 * snapshots, alerts and tickets into the database again, so the same row sat there many times and the oldest real
 * history was pruned to make room.
 *
 * Only exact copies go: rows with the same identity (snapshot: ticker, time, side; alert: alert id, ticker, time,
 * side; ticket: ticker, time, side). The first one stored (lowest id) is kept, which is the original rather than a
 * synced copy. Nothing else is touched. History pruned out earlier comes back from the cloud on the next sync, since
 * the cloud rows it no longer has are new to it.
 */
object SyncDedupeSchema {
    const val VERSION = 6

    fun upgradeSql(): List<String> = listOf(
        """
        DELETE FROM ${SqliteResultsStore.TABLE_SNAP} WHERE id NOT IN (
          SELECT MIN(id) FROM ${SqliteResultsStore.TABLE_SNAP} GROUP BY ticker, created_at_ms, side
        )
        """.trimIndent(),
        """
        DELETE FROM ${SqliteResultsStore.TABLE_ALERT} WHERE id NOT IN (
          SELECT MIN(id) FROM ${SqliteResultsStore.TABLE_ALERT} GROUP BY alert_id, ticker, created_at_ms, side
        )
        """.trimIndent(),
        """
        DELETE FROM ${SqliteResultsStore.TABLE_TICKET} WHERE id NOT IN (
          SELECT MIN(id) FROM ${SqliteResultsStore.TABLE_TICKET} GROUP BY ticker, created_at_ms, side
        )
        """.trimIndent()
    )
}
