package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.insights.ScalpFreeze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.21: the 1.0.10 removals are visible again and frozen. History still resolves to their cards. */
class ScalpRemovedStrategiesTest {
    private fun old(id: String, vid: String) = ScalpTrade(id = id, ticker = "KXBTC15M-26OCT181200-00", side = "YES", state = ScalpState.CLOSED,
        signalAtMs = 0L, signalAsk = 0.40, fairAtSignal = 0.50, ruleVersion = "old|$vid|P")
    private val restored = listOf("sf30", "sf32", "mkdip", "diph60", "dipnotp", "r-kelly")

    @Test fun restoredIdsStayOnTheBoardAndAreFrozen() {
        assertTrue(ScalpModels.REMOVED_CODES.isEmpty())
        val codes = ScalpModels.ALL.map { it.code }.toSet()
        restored.forEach {
            assertTrue(it, it in codes)
            assertNotNull(it, ScalpModels.modelByCode(it))
            assertTrue(it, it in ScalpFreeze.DEFAULT_FROZEN)
            assertTrue(it, ScalpFreeze.blocksRestored(it, null))
            assertFalse(it, ScalpFreeze.blocksRestored(it, "ACTIVE"))
        }
        assertTrue(Research.KELLY in Research.IDS)
        assertEquals(restored.toSet(), ScalpFreeze.RESTORED)
    }

    @Test fun keptStrategiesStay() {
        val codes = ScalpModels.ALL.map { it.code }.toSet()
        listOf("fair", "fairh60", "dip", "momo", "momoh60", "momonotp", "cfr", "fcride", "r-late", "r-hold", "r-maker",
            "sf31", "sf51", "v2-sf30", "v2-sf32", "v2-mkdip", "v2-diph60", "v2-dipnotp", "v2-llm").forEach { assertTrue(it, it in codes) }
        assertTrue(Research.LATE in Research.IDS && Research.MAKER in Research.IDS)
    }

    @Test fun oldRecordsResolveOntoTheirCards() {
        val dipSeed = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        assertEquals("sf30", ScalpModels.modelOf(old("a", "sf30"))?.code)
        assertEquals("r-kelly", ScalpModels.modelOf(old("b", "r-kelly"))?.code)
        assertEquals("mkdip", ScalpModels.modelOf(old("c", ScalpModels.MAKER_DIP_PARAMS.id))?.code)
        assertEquals("diph60", ScalpModels.modelOf(old("d", dipSeed.copy(hold60 = true).id))?.code)
        assertEquals("dipnotp", ScalpModels.modelOf(old("e", dipSeed.copy(target = ScalpParams.NO_TP).id))?.code)
        val board = ScalpModels.leaderboardFromStats(restored.map { ScalpStat(it, n = 2, netUsd = -1.0) })
        restored.forEach { code -> assertEquals(2, board.first { it.model.code == code }.roundTrips) }
        assertEquals(ScalpModels.ALL.size, board.size)
    }

    @Test fun keptIdsStillResolve() {
        val dipSeed = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        assertEquals(ScalpModels.Model.DIP_HUNTER, ScalpModels.modelOf(old("a", dipSeed.id)))
        assertEquals(ScalpModels.Model.R_LATE, ScalpModels.modelOf(old("b", "r-late")))
        assertNotNull(ScalpModels.modelOf(old("c", "v2-sf30")))
    }
}
