package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.3.67 SQLite (Robolectric) benchmark: 100k closed trades across every scalper id; stats read + close/update timing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScalpLedgerSqliteBenchTest {
    @Test fun sqlite100kStatsAndPaging() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val vids = ScalpModels.ALL.map { it.code }.filter { it.startsWith("sf") || it.startsWith("v2-sf") } + "fcride-h60"
        val base = 1_700_000_000_000L
        val n = 100_000
        fun trade(i: Int) = ScalpTrade(id = "s$i", ticker = "KXBTC15M-26OCT${1000 + i / 200}-15", side = "YES",
            state = ScalpState.CLOSED, signalAtMs = base + i * 1000L, signalAsk = 0.5, fairAtSignal = 0.5,
            contracts = 10, entryPrice = 0.5, soldContracts = 10, proceedsUsd = 5.0, entryFeeUsd = 0.07, exitFeeUsd = 0.07,
            closedAtMs = base + i * 1000L + 5000, netUsd = (i % 11 - 5) * 0.1, ruleVersion = "${ScalpRule.VERSION}|${vids[i % vids.size]}|P")
        val tBulk = System.nanoTime()
        for (i in 0 until n - 200) store.upsertScalpAndStats(trade(i), null)
        val bulkS = (System.nanoTime() - tBulk) / 1e9
        val t0 = System.nanoTime()
        for (i in n - 200 until n) store.upsertScalpAndStats(trade(i), null)
        val closeMs = (System.nanoTime() - t0) / 1e6 / 200
        val tl = System.nanoTime()
        var stats = store.loadStats()
        repeat(9) { stats = store.loadStats() }
        val lb = ScalpModels.leaderboardFromStats(stats)
        val readMs = (System.nanoTime() - tl) / 1e6 / 10
        val tp = System.nanoTime()
        val page = store.pageTrades(vids[0], null, null, 50)
        val page2 = store.pageTrades(vids[0], page.last().closedAtMs, page.last().id, 50)
        val pageMs = (System.nanoTime() - tp) / 1e6 / 2
        println("SQLITE_BENCH rows=$n bulkS=$bulkS closeMs=$closeMs statsReadMs=$readMs pageMs=$pageMs scalpers=${stats.size} board=${lb.size}")
        assertEquals(n, stats.sumOf { it.n })
        assertEquals(50, page2.size)
        assertTrue(page2.first().closedAtMs!! < page.last().closedAtMs!!)
        assertTrue("stats read $readMs", readMs < 50.0)
        assertTrue("close $closeMs", closeMs < 5.0)
    }
}
