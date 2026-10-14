package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScalpPersistenceTest {
    @Test
    fun scalpRowsSurviveReopenAndUpsertReplaces() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val t = ScalpTrade(
            id = "a1", ticker = "KXBTC15M-26OCT091015-15", side = "YES", state = ScalpState.OPEN,
            signalAtMs = 1L, signalAsk = 0.35, fairAtSignal = 0.5, contracts = 10, entryPrice = 0.36,
            entryFeeUsd = 0.17, entryAtMs = 4L, ruleVersion = "1|fair|P"
        )
        store.upsertScalpAndStats(t, null)
        store.upsertScalpAndStats(t.copy(state = ScalpState.CLOSED, soldContracts = 10, proceedsUsd = 5.1, exitFeeUsd = 0.18, closedAtMs = 9L, netUsd = 1.15), t)
        // 0.3.67: closed rows leave the in-memory book; history is SQLite + stats.
        val reopened = ScalpBook(SqliteResultsStore(ctx))
        assertTrue(reopened.snapshot().isEmpty())
        val row = store.loadScalps().single()
        assertEquals(ScalpState.CLOSED, row.state)
        assertEquals(1.15, row.netUsd!!, 1e-9)
        assertEquals(0.36, row.entryPrice!!, 1e-9)
        assertEquals(9L, row.closedAtMs)
        assertTrue(reopened.stats.value.sumOf { it.n } >= 1 || store.loadStats().sumOf { it.n } >= 1)
    }
}
