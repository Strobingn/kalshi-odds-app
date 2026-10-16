package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ScalpWindowPersistenceTest {
    @Test fun indexedWindowReadsAndFullWindowSecondsSurviveStoreRecreation() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT122015-15"
        val start = ScalpTicker.closeMs(ticker)!! - 900_000L
        // More than the old 2000-row chart query cap: retain the entire 15-minute span, not just its beginning.
        val ticks = (0 until 3600).map { ChartTickRow(ticker, start + it * 250, yesBid = (it % 99) / 100.0, spotUsd = 100_000.0 + it) }
        store.insertChartTicks(ticks)
        val pending = ScalpTrade("t", ticker, "YES", ScalpState.PENDING_ENTRY, start + 1000, .4, .5,
            ruleVersion = "${ScalpRule.VERSION}|sf41|P")
        val buy = ScalpFillLog.capture(pending.copy(state = ScalpState.OPEN, contracts = 10,
            entryPrice = .4, entryAtMs = start + 3000, entryFeeUsd = .17), pending, start + 3000)
        val sold = ScalpFillLog.capture(buy.copy(state = ScalpState.CLOSED, soldContracts = 10,
            proceedsUsd = 4.5, exitFeeUsd = .18, closedAtMs = start + 5000, netUsd = .15), buy, start + 5000)
        store.upsertScalpAndStats(sold, null)
        store.upsertScalpAndStats(sold.copy(id = "other", ticker = "KXBTC15M-OTHER"), null)
        val restored = SqliteResultsStore(ctx)
        val window = restored.windowTrades(ticker, 2000)
        assertEquals(listOf("t"), window.map { it.id })
        assertEquals(2, ScalpFillLog.recorded(window.single()).size)
        val series = restored.chartSeconds(ticker, start, start + 900_000)
        assertEquals(900, series.size)
        assertEquals(start + 750, series.first().tMs)
        assertEquals(start + 899_750, series.last().tMs)
        assertEquals(ticks.last().yesBid!! * 100, series.last().upBidCents!!.toDouble(), 1e-4)
        assertEquals(ticks.last().spotUsd, series.last().spotUsd)
    }
}
