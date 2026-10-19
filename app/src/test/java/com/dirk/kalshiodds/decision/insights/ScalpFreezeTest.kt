package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpStat
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

    private val kept = listOf("r-late", "fair", "r-maker", "fcfade", "fairh60", "sf51", "v2-sf51", "sf41", "v2-dip", "v2-fcride",
        "fair-t1", "fair-t2", "r-late-t1", "r-late-t2")

    @Test fun frozenStrategiesStayInTheRegistryAndOldRecordsStillLoad() {
        val codes = ScalpModels.ALL.map { it.code }.toSet()
        ScalpFreeze.DEFAULT_FROZEN.forEach { assertTrue("$it still listed", it in codes); assertNotNull(it, ScalpModels.modelByCode(it)) }
        assertEquals(setOf("sf30", "sf32", "mkdip", "diph60", "dipnotp", "r-kelly"), ScalpModels.REMOVED_CODES) // nothing new removed
        val dip = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        val old = mapOf(dip.id to "dip", "sf44" to "sf44", "r-hold" to "r-hold", "fcride-h600" to "fcride", "sf56n" to "sf56", "sf60" to "sf60",
            ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.MOMENTUM).id to "momo", ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.CF_REPRICE).id to "cfr")
        old.forEach { (vid, code) -> assertEquals(vid, code, ScalpModels.modelOf(closed(vid, 1, -1.0))?.code) }
        // History still shows on the board / scorecards.
        val board = ScalpModels.leaderboardFromStats(listOf(ScalpStat("dip", n = 5, wins = 1, netUsd = -3.0), ScalpStat("sf44", n = 2, netUsd = -1.0)))
        assertEquals(5, board.first { it.model.code == "dip" }.roundTrips)
        assertEquals(-1.0, board.first { it.model.code == "sf44" }.netUsd, 1e-9)
        assertEquals(ScalpModels.ALL.size, board.size)
        assertTrue("r-hold keeps running (frozen, not removed)", com.dirk.kalshiodds.decision.Research.HOLD in com.dirk.kalshiodds.decision.Research.IDS)
    }

    @Test fun defaultsAreFrozenOnStartAndWinnersAreNot() {
        val book = ScalpBook()
        ScalpInsights(book, MemoryScalpControls(), null) {}.start()
        ScalpFreeze.DEFAULT_FROZEN.forEach { code ->
            assertTrue("$code benched", code in book.benched)
            assertTrue(code, ScalpFreeze.isFrozen(BenchRegistry.decisions.value[code]))
        }
        kept.forEach { assertFalse("$it must keep trading", it in book.benched) }
        assertTrue(BenchRegistry.decisions.value.getValue("dip").reason.startsWith("FROZEN - losing"))
    }

    @Test fun forceTradeUnfreezesAndAutoRefreezes() {
        val controls = MemoryScalpControls()
        val book = ScalpBook()
        val insights = ScalpInsights(book, controls, null) {}
        insights.start()
        controls.save(controls.state.value.copy(benchOverrides = mapOf("dip" to "ACTIVE")))
        insights.recompute()
        assertFalse("manual Force trade wins", "dip" in book.benched)
        assertEquals(BenchStatus.FORCED_ACTIVE, BenchRegistry.decisions.value.getValue("dip").status)
        controls.save(controls.state.value.copy(benchOverrides = emptyMap()))
        insights.recompute()
        assertTrue("dip" in book.benched)
        assertTrue(ScalpFreeze.isFrozen(BenchRegistry.decisions.value["dip"]))
    }

    @Test fun autoFreezeNeedsThirtyClearlyLosingTrades() {
        assertNull("29 trades is warm-up", ScalpFreeze.autoFreeze(List(29) { closed("sf41", it, -1.0) }))
        assertNull("net -9 is not below -10", ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.30) }))
        assertNull("-0.25/trade is not clearly negative", ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.25) } + List(10) { closed("sf41", 100 + it, 0.0) }))
        val hit = ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -0.50) })!!
        assertEquals(-15.0, hit.first, 1e-9); assertEquals(30, hit.second)
        // Only the newest 30 count: an old losing streak followed by 30 winners is not frozen.
        assertNull(ScalpFreeze.autoFreeze(List(30) { closed("sf41", it, -2.0) } + List(30) { closed("sf41", 100 + it, 0.5) }))
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
