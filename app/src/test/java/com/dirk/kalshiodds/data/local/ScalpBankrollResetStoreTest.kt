package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.ScalpBankrollReset
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScalpBankrollResetStoreTest {
    @Test fun sqliteArchiveKeepsRowsClearsStatsAndUploadsOnlyTheFlattenedClose() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val closed = ScalpTrade(
            id = "old", ticker = "KXBTC15M-26OCT091015-15", side = "YES", state = ScalpState.CLOSED,
            signalAtMs = 1L, signalAsk = 0.20, fairAtSignal = 0.40, contracts = 10, entryPrice = 0.20,
            entryFeeUsd = 0.11, entryAtMs = 2L, soldContracts = 10, proceedsUsd = 2.0, exitFeeUsd = 0.0,
            closedAtMs = 9L, netUsd = -1.1, ruleVersion = "v|v2-dip|P"
        )
        store.upsertScalpAndStats(closed, null)
        store.markSynced(listOf("old"))
        val open = closed.copy(id = "open", state = ScalpState.OPEN, soldContracts = 0, proceedsUsd = 0.0, exitFeeUsd = 0.0, closedAtMs = null, netUsd = null)
        store.upsertScalp(open)
        val n = store.archiveDisplayedRun(listOf(ScalpBankrollReset.flatten(open, 50L)), 50L, "scalping bankroll reset", 20_000.0)
        assertEquals(2, n)
        assertEquals(2, store.tradeCount())
        assertTrue(store.loadScalps().isEmpty())
        assertTrue(store.loadStats().all { it.n == 0 })
        assertTrue(store.loadExits().isEmpty())
        assertTrue(store.loadWindows(48).isEmpty())
        assertTrue(store.pageTrades("v2-dip", null, null, 20).isEmpty())
        assertTrue(store.windowTrades("v2-dip", closed.ticker).isEmpty())
        assertTrue(store.loadActiveScalps().none { it.id == "open" })
        val unsynced = store.unsyncedClosed(10)
        assertTrue(unsynced.none { it.id == "old" })
        val uploaded = unsynced.single { it.id == "open" }
        assertEquals(ScalpState.CLOSED, uploaded.state)
        assertEquals("scalping bankroll reset", uploaded.exitReason)
        val marker = store.unsyncedScalpResets().single()
        assertEquals(50L, marker.atMs)
        assertEquals(2, marker.archivedN)
        assertEquals(20_000.0, marker.cashUsd, 0.0)
        store.markScalpResetsSynced(listOf(marker.atMs))
        assertTrue(store.unsyncedScalpResets().isEmpty())
        val fresh = closed.copy(id = "fresh", closedAtMs = 80L, netUsd = 1.25)
        store.upsertScalpAndStats(fresh, null)
        assertEquals(listOf("fresh"), store.pageTrades("v2-dip", null, null, 10).map { it.id })
        assertEquals(1, store.loadStats().sumOf { it.n })
        assertEquals(3, store.tradeCount())
        val reopened = ScalpBook(SqliteResultsStore(ctx))
        assertTrue(reopened.snapshot().none { it.id == "open" || it.id == "old" })
        assertEquals(1, reopened.stats.value.sumOf { it.n })
    }
}
