package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.24.1: a sync stores each row once and restores settings only from the current snapshot. */
class SupabaseSyncApplyTest {

    private val sync = SupabaseSync()

    private fun snap(side: String, at: Long) = ScoredSnapshotRow(
        ticker = "T", series = "KXBTC15M", side = side, edgePp = 4.0, fairPp = 60.0, marketPp = 56.0,
        regime = null, uncertainty = null, createdAtMs = at
    )

    private fun store() = InMemoryResultsStore().apply {
        insertSnapshots(listOf(snap("YES", 1_000L)))
        insertAlert(AlertRow(alertId = "a1", ticker = "T", series = "KXBTC15M", side = "YES", edgePp = 4.0, fairPp = 60.0, marketPp = 56.0, reason = "r", regime = null, createdAtMs = 1_000L))
        insertTicket(TicketAttemptRow(ticker = "T", side = "YES", stakeUsd = 5.0, approved = true, result = "paper", createdAtMs = 1_000L))
    }

    private fun records(): List<SyncMerge.Record> {
        val local = SupabaseSync.LocalBundle(
            snapshots = listOf(snap("YES", 1_000L),
                snap("NO", 2_000L)),
            alerts = listOf(
                AlertRow(alertId = "a1", ticker = "T", series = "KXBTC15M", side = "YES", edgePp = 4.0, fairPp = 60.0, marketPp = 56.0, reason = "r", regime = null, createdAtMs = 1_000L),
                AlertRow(alertId = "a2", ticker = "T", series = "KXBTC15M", side = "NO", edgePp = 3.0, fairPp = 40.0, marketPp = 43.0, reason = "r", regime = null, createdAtMs = 2_000L)
            ),
            tickets = listOf(
                TicketAttemptRow(ticker = "T", side = "YES", stakeUsd = 5.0, approved = true, result = "paper", createdAtMs = 1_000L),
                TicketAttemptRow(ticker = "T", side = "NO", stakeUsd = 5.0, approved = true, result = "paper", createdAtMs = 2_000L)
            )
        )
        return sync.pack(local)
    }

    @Test
    fun rowsAlreadyStoredAreNotStoredAgain() {
        val s = store()
        repeat(3) { sync.apply(records(), s, onSettings = {}, onPaper = {}, existing = s.syncKeys()) }
        assertEquals(2, s.recentSnapshots(50).size)
        assertEquals(2, s.recentAlerts(50).size)
        assertEquals(2, s.recentTickets(50).size)
    }

    @Test
    fun onlyTheCurrentSettingsSnapshotIsRestored() {
        val restored = mutableListOf<String>()
        val change = SyncMerge.Record("settings:edge:5", "settings", 5L, """{"key":"edge","oldValue":"4","newValue":"5","snapshotJson":"{\"old\":true}"}""")
        val current = SyncMerge.Record(SupabaseSync.SETTINGS_CURRENT, "settings_snapshot", 9L, """{"edgeThresholdPp":6.0}""")
        sync.apply(listOf(change, current), InMemoryResultsStore(), onSettings = { restored += it }, onPaper = {})
        assertEquals(listOf("""{"edgeThresholdPp":6.0}"""), restored)
    }
}
