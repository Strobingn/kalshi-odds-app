package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.ScalpForecastRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnrHotfixStoreTest {
    @Test fun walIsEnabledAndChartTicksAreIndexedNewestFirst() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val db = store.writableForTest()
        val journal = db.rawQuery("PRAGMA journal_mode", null).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }
        assertTrue(db.isWriteAheadLoggingEnabled || journal.equals("wal", true))
        val ticker = "KXBTC15M-26OCT181530-30"
        store.insertChartTicks((1..5).map { ChartTickRow(ticker = ticker, tMs = it.toLong(), yesBid = 0.4) })
        val ticks = store.chartTicks(ticker, 0L, 10L, 2)
        assertEquals(listOf(4L, 5L), ticks.map { it.tMs })
        val plan = db.rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT ticker, t_ms FROM (
              SELECT ticker, t_ms FROM chart_ticks
              WHERE ticker = ? AND t_ms >= ? AND t_ms <= ?
              ORDER BY t_ms DESC LIMIT 2
            ) ORDER BY t_ms ASC
            """.trimIndent(),
            arrayOf(ticker, "0", "10")
        ).use { c ->
            buildString {
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) append(c.getString(i)).append(' ')
                    append('\n')
                }
            }
        }
        assertTrue(plan, plan.contains("idx_chart_ticks_ticker"))
    }

    @Test fun singleInsertDoesNotPruneInlineAndBatchIsOneTransaction() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT181530-30"
        val oldAt = 1_000L
        store.insertLedger(row(ticker, oldAt))
        assertEquals(1, store.recentLedger(5).size)
        val n = store.insertLedgerBatch(
            listOf(row(ticker, oldAt + 10), row("KXETH15M-26OCT181530-30", oldAt + 11))
        )
        assertEquals(2, n)
        assertEquals(3, store.recentLedger(10).size)
    }

    private fun row(ticker: String, at: Long) = LedgerRow(
        timestampMs = at,
        modelVersion = ScalpForecastRecorder.MODEL,
        ticker = ticker,
        series = ticker.substringBefore('-'),
        settlementRule = null,
        settlementSource = null,
        secondsRemaining = null,
        spot = null,
        targetStrike = null,
        zDistance = null,
        rawModelProb = 0.4,
        calibratedProb = null,
        marketMid = 0.5,
        bid = null,
        ask = null,
        spread = null,
        depthAtBest = null,
        imbalance = null,
        bookAgeMs = null,
        decision = "FORECAST",
        sizeContracts = null,
        reasonCodes = "test",
        finalProb = 0.4,
        closeTimeMs = at + 60_000L
    )
}
