package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.*
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0367Test {
    @Test fun openOnlyBookAndStatsLeaderboard() {
        val store = InMemoryScalpPersistence()
        val book = ScalpBook(store = store, liveExtras = true, bankrollUsd = { Double.POSITIVE_INFINITY })
        val close = 1_791_850_500_000L
        val tk = "KXBTC15M-26OCT122015-15"
        val t0 = close - 600_000L
        for (k in 100 downTo 1) book.tickSpot("BTC", 100_000.0, t0 - k * 1000L)
        val q = ScalpRule.Quote(tk, t0, close, t0, 0.49, 50.0, 0.51, 50.0, 100_000.0, 100_000.0, 3e-5)
        book.onQuote(q, true); book.onShockFavQuote(q, true)
        book.flushWrites()
        assertTrue(book.snapshot().all { it.state != ScalpState.CLOSED })
        // force a closed row through the store+stats path
        val closed = ScalpTrade(id = "c1", ticker = tk, side = "YES", state = ScalpState.CLOSED,
            signalAtMs = t0, signalAsk = 0.5, fairAtSignal = 0.5, contracts = 10, entryPrice = 0.5,
            soldContracts = 10, proceedsUsd = 5.5, closedAtMs = t0 + 10_000, netUsd = 0.4,
            ruleVersion = "${ScalpRule.VERSION}|sf30|P")
        store.upsertScalpAndStats(closed, null)
        book.reloadStats()
        val lb = ScalpModels.leaderboardFromStats(book.stats.value)
        assertTrue(lb.any { it.model == ScalpModels.Model.SF30 && it.roundTrips == 1 })
    }

    /** JVM benchmark: 100k closes, 115 scalpers — leaderboard < 50 ms, close+stats < 5 ms. */
    @Test fun bench100k() {
        val store = InMemoryScalpPersistence()
        val codes = ScalpModels.ALL.map { it.code } // all 115 scalpers
        val base = 1_700_000_000_000L
        val rows = ArrayList<ScalpTrade>(100_000)
        for (i in 0 until 100_000) {
            val code = codes[i % codes.size]
            val net = (i % 11 - 5) * 0.1
            rows += ScalpTrade(id = "b$i", ticker = "KXBTC15M-26OCT${1000 + i / 200}-15", side = "YES",
                state = ScalpState.CLOSED, signalAtMs = base + i * 1000L, signalAsk = 0.5, fairAtSignal = 0.5,
                contracts = 10, entryPrice = 0.5, soldContracts = 10, proceedsUsd = 5.0 + net,
                entryFeeUsd = 0.07, exitFeeUsd = 0.07, closedAtMs = base + i * 1000L + 5000, netUsd = net,
                ruleVersion = "${ScalpRule.VERSION}|${if (code.startsWith("v2-") || code.startsWith("sf") || code.startsWith("fc")) code else ScalpParams.STRATEGY_SEEDS.values.first().id}|P")
        }
        // warm
        rows.take(1000).forEach { store.upsertScalpAndStats(it, null) }
        val tClose0 = System.nanoTime()
        for (i in 1000 until 1100) store.upsertScalpAndStats(rows[i], null)
        val closeMs = (System.nanoTime() - tClose0) / 1e6 / 100.0
        rows.drop(1100).forEach { store.upsertScalpAndStats(it, null) }
        val stats = store.loadStats()
        val tLb0 = System.nanoTime()
        repeat(20) { ScalpModels.leaderboardFromStats(stats) }
        val lbMs = (System.nanoTime() - tLb0) / 1e6 / 20.0
        println("BENCH closeMs=$closeMs lbMs=$lbMs scalpers=${stats.size} n=${stats.sumOf { it.n }}")
        assertTrue("close+stats $closeMs ms", closeMs < 5.0)
        assertTrue("leaderboard $lbMs ms", lbMs < 50.0)
    }
}
