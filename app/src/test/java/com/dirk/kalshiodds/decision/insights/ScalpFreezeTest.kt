package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpStat
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.decision.ScalpTrade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.14: losers are FROZEN (no new paper trades) but never removed — they still load, display and can be unfrozen. */
class ScalpFreezeTest {
    @After fun resetRegistry() { BenchRegistry.publish(emptyMap()) }

    private fun closed(code: String, i: Int, net: Double) = ScalpTrade("$code-$i", "KXBTC15M-26OCT181200-00", "YES", ScalpState.CLOSED,
        signalAtMs = i * 1000L, signalAsk = 0.40, fairAtSignal = 0.50, contracts = 1, entryPrice = 0.40, closedAtMs = i * 1000L + 500, netUsd = net,
        ruleVersion = "v|$code|P")

    @Test fun removedStrategiesAreOffTheBoardAndNotFrozen() {
        assertTrue(ScalpFreeze.DEFAULT_FROZEN.isEmpty())
        assertTrue(ScalpFreeze.RESTORED.isEmpty())
        listOf("dip", "sf44", "r-hold", "xrev", "sf51", "v2-llm").forEach { code ->
            assertNull(code, ScalpModels.modelByCode(code))
            assertNull(code, ScalpModels.modelOf(closed(code, 1, -1.0)))
            assertEquals(KeptScalpers.REMOVED_LABEL, closed(code, 1, -1.0).algoLabel)
            assertNull(code, ScalpFreeze.reason(code, emptyList()))
        }
        val board = ScalpModels.leaderboardFromStats(listOf(ScalpStat("dip", n = 5, wins = 1, netUsd = -3.0), ScalpStat("sf41", n = 2, netUsd = 1.0)))
        assertFalse(board.any { it.model.code == "dip" })
        assertEquals(2, board.first { it.model.code == "sf41" }.roundTrips)
        assertEquals(ScalpModels.ALL.size, board.size)
    }

    @Test fun defaultsDoNotFreezeTheKeptBoard() {
        val book = ScalpBook()
        ScalpInsights(book, MemoryScalpControls(), null) {}.start()
        ScalpModels.ALL.forEach { assertFalse(it.code, it.code in book.benched) }
        assertFalse(book.benched.contains("dip"))
    }

    @Test fun forceTradeOnARemovedCodeDoesNotCreateACard() {
        val controls = MemoryScalpControls()
        val book = ScalpBook()
        val insights = ScalpInsights(book, controls, null) {}
        insights.start()
        controls.save(controls.state.value.copy(benchOverrides = mapOf("dip" to "ACTIVE")))
        insights.recompute()
        assertNull(ScalpModels.modelByCode("dip"))
        assertFalse("dip" in book.benched)
    }

    @Test fun autoFreezeNeedsThirtyClearlyLosingTrades() {
        assertNull("29 trades is warm-up", ScalpFreeze.autoFreeze(List(29) { closed("sf41", it, -1.0) }))
        assertNull("net -9 is not below -10", ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.30) }))
        // Newest 30 at exactly -$0.25 each sum to -$7.50, which is not below -$10.
        assertNull("-$0.25/trade over 30 is only -$7.50", ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.25) }))
        val per = -10.0 / 30.0
        val first = List(29) { closed("sf41", it, per) }
        val exact = first + closed("sf41", 29, -10.0 - first.sumOf { it.netUsd!! })
        assertEquals(-10.0, exact.sumOf { it.netUsd!! }, 0.0)
        assertNull("exactly -$10 is not below -$10", ScalpFreeze.autoFreeze(exact))
        val hit = ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.50) })!!
        assertEquals(-15.0, hit.first, 1e-9); assertEquals(30, hit.second)
        // Only the newest 30 count: an old losing streak followed by 30 winners is not frozen.
        assertNull(ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -2.0) } + List(30) { closed("sf41", 100 + it, 0.5) }))
    }

    @Test fun frozenStrategyOpensNoNewPaperTrade() {
        val close = 1_792_342_800_000L
        val start = close - 900_000L
        val tk = "KXBTC15M-26OCT181300-00"
        val book = ScalpBook(liveExtras = false, bankrollUsd = { Double.POSITIVE_INFINITY })
        book.benched = setOf("sf41")
        val rnd = java.util.Random(7)
        var spot = 100_000.0
        for (k in 400 downTo 1) { spot += (rnd.nextDouble() - 0.5) * 20; book.tickSpot("BTC", spot, start - k * 1000L) }
        var t = start
        while (t < start + 400_000L) {
            spot += (rnd.nextDouble() - 0.5) * 20
            book.tickSpot("BTC", spot, t)
            val mid = if (t >= start + 300_000L) 0.42 else 0.50
            val q = ScalpRule.Quote(tk, t, close, t, mid - 0.01, 50.0, mid + 0.01, 50.0, spot, 100_000.0, 3e-5)
            book.onShockFavQuote(q, true)
            t += 1_000L
        }
        val rows = book.allTrades().filter { it.variantId == "sf41" }
        assertTrue("the dip still signals", rows.isNotEmpty())
        assertTrue(book.snapshot().none { it.variantId == "sf41" })
        assertTrue(rows.none { it.isPrimary })
        assertTrue(rows.all { it.ruleVersion.endsWith("|S") && "bench=shadow" in it.note })
    }

    @Test fun dollarLossAndDeepLossShareOneFrozenBadge() {
        val mild = List(30) { closed("sf41", it, -0.20) } // net -6: AI rule, not the -10 rule
        assertNull(ScalpFreeze.autoFreeze(mild))
        val why = ScalpFreeze.reason("sf41", mild)!!
        assertTrue(why.startsWith(ScalpFreeze.BADGE))
        assertNull(ScalpFreeze.reason("sf41", List(30) { closed("sf41", it, -0.02) }))
        val deep = List(30) { closed("sf41", it, -0.50) }
        assertTrue(ScalpFreeze.reason("sf41", deep)!!.contains("last 30 trades"))
        val shadow = List(30) { closed("sf41", 200 + it, 0.40) }
        assertNull("shadow improvement unfreezes a numeric freeze", ScalpFreeze.reason("sf41", deep, shadow, wasFrozen = true))
        assertNull("a removed code is not kept frozen by the old default list", ScalpFreeze.reason("dip", deep, shadow, wasFrozen = true))
        val forced = ScalpFreeze.apply(
            mapOf("sf41" to BenchDecision("sf41", BenchStatus.FORCED_ACTIVE, 0, 0.0, 0.0, "forced active")),
            mapOf("sf41" to mild),
            mapOf("sf41" to "ACTIVE")
        )
        assertEquals(BenchStatus.FORCED_ACTIVE, forced.getValue("sf41").status)
    }

    @Test fun autoFreezeAppliesThroughInsightsAndForceTradeWins() {
        val controls = MemoryScalpControls()
        val book = ScalpBook()
        val insights = ScalpInsights(book, controls, null) {}
        insights.start()
        List(30) { closed("sf41", it, -0.50) }.forEach { insights.onSettled("sf41", it) }
        assertTrue("sf41" in book.benched)
        assertTrue(BenchRegistry.decisions.value.getValue("sf41").reason.contains("last 30 trades"))
        controls.save(controls.state.value.copy(benchOverrides = mapOf("sf41" to "ACTIVE")))
        insights.recompute()
        assertFalse("sf41" in book.benched)
    }

    @Test fun forceBenchStaysForceBenched() {
        val d = ScalpFreeze.apply(mapOf("dip" to BenchDecision("dip", BenchStatus.FORCED_BENCHED, 0, 0.0, 0.0, "forced benched")), emptyMap(), mapOf("dip" to "BENCHED"))
        assertEquals("forced benched", d.getValue("dip").reason)
        assertFalse(ScalpFreeze.isFrozen(d["dip"]))
    }
}
