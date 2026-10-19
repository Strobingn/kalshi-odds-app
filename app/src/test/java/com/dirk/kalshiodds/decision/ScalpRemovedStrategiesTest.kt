package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.10: loser strategies leave the registry; saved history with their ids must still load without crashing. */
class ScalpRemovedStrategiesTest {
    private fun old(id: String, vid: String) = ScalpTrade(id = id, ticker = "KXBTC15M-26OCT181200-00", side = "YES", state = ScalpState.CLOSED,
        signalAtMs = 0L, signalAsk = 0.40, fairAtSignal = 0.50, ruleVersion = "old|$vid|P")
    private val removed = listOf("sf30", "sf32", "mkdip", "diph60", "dipnotp", "r-kelly")

    @Test fun removedIdsAreNotInRegistry() {
        assertEquals(removed.toSet(), ScalpModels.REMOVED_CODES)
        val codes = ScalpModels.ALL.map { it.code }.toSet()
        removed.forEach { assertFalse(it, it in codes); assertNull(it, ScalpModels.modelByCode(it)) }
        assertFalse(Research.KELLY in Research.IDS)
    }

    @Test fun keptStrategiesStay() {
        val codes = ScalpModels.ALL.map { it.code }.toSet()
        listOf("fair", "fairh60", "dip", "momo", "momoh60", "momonotp", "cfr", "fcride", "r-late", "r-hold", "r-maker",
            "sf31", "sf51", "v2-sf30", "v2-sf32", "v2-mkdip", "v2-diph60", "v2-dipnotp", "v2-llm").forEach { assertTrue(it, it in codes) }
        assertTrue(Research.LATE in Research.IDS && Research.MAKER in Research.IDS)
    }

    @Test fun oldRecordsWithRemovedIdsDoNotCrashAndAreUnattributed() {
        val dipSeed = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        val oldIds = listOf("sf30", "sf32-x", "r-kelly", ScalpModels.MAKER_DIP_PARAMS.id, dipSeed.copy(hold60 = true).id, dipSeed.copy(target = ScalpParams.NO_TP).id)
        for (vid in oldIds) {
            val t = old("old-$vid", vid)
            assertNull(vid, ScalpModels.modelOf(t))
            assertNull(vid, ScalpModels.scalperIdOf(t)?.let { ScalpModels.modelByCode(it) })
        }
        val board = ScalpModels.leaderboardFromStats(removed.map { ScalpStat(it) })
        assertTrue(board.none { it.model.code in removed })
        assertEquals(ScalpModels.ALL.size, board.size)
    }

    @Test fun keptIdsStillResolve() {
        val dipSeed = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        assertEquals(ScalpModels.Model.DIP_HUNTER, ScalpModels.modelOf(old("a", dipSeed.id)))
        assertEquals(ScalpModels.Model.R_LATE, ScalpModels.modelOf(old("b", "r-late")))
        assertNotNull(ScalpModels.modelOf(old("c", "v2-sf30")))
    }
}
