package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.ui.KashiArenaData
import com.dirk.kalshiodds.ui.ScalpTabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.14: tweaked paper copies of the money makers (r-late, Fair-gap) have their own ids, rules and cards. */
class ScalpTweakVariantsTest {
    private fun trade(vid: String) = ScalpTrade("t-$vid", "KXBTC15M-26OCT181200-00", "YES", ScalpState.CLOSED, 0L, 0.40, 0.55, ruleVersion = "v|$vid|P")

    @Test fun newIdsAreUniqueAndResolve() {
        val codes = ScalpModels.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        listOf("fair-t1", "fair-t2", "r-late-t1", "r-late-t2").forEach { assertTrue(it, it in codes) }
        val (t1, t2) = ScalpParams.FAIR_TWEAKS
        assertEquals("g12-t10-s08-d15-w300", t1.id); assertEquals("g16-t14-s08-d15-w300", t2.id)
        assertNotEquals(ScalpParams.seedFor("BTC").id, t1.id)
        assertEquals(ScalpModels.Model.FAIR_T1, ScalpModels.modelOf(t1)); assertEquals(ScalpModels.Model.FAIR_T2, ScalpModels.modelOf(t2))
        assertEquals(ScalpModels.Model.FAIR_T1, ScalpModels.modelOf(trade(t1.id)))
        assertEquals(ScalpModels.Model.FAIR_T2, ScalpModels.modelOf(trade(t2.id)))
        assertEquals(t1, ScalpParams.byId(t1.id))
        assertEquals(ScalpModels.Model.R_LATE_T1, ScalpModels.modelOf(trade("r-late-t1")))
        assertEquals(ScalpModels.Model.R_LATE_T2, ScalpModels.modelOf(trade("r-late-t2")))
        // Originals unchanged.
        assertEquals(ScalpModels.Model.FAIR_GAP, ScalpModels.modelOf(trade(ScalpParams.seedFor("BTC").id)))
        assertEquals(ScalpModels.Model.FAIR_GAP, ScalpModels.modelOf(trade("g10-t12-s08-d15-w300-mk")))
        assertEquals(ScalpModels.Model.R_LATE, ScalpModels.modelOf(trade("r-late")))
        assertEquals("stop 8¢", ScalpModels.stopLabel(ScalpModels.Model.FAIR_T1))
    }

    @Test fun researchCopiesRunWithTheirOwnRules() {
        assertEquals(listOf(Research.LATE, Research.HOLD, Research.MAKER, Research.LATE_T1, Research.LATE_T2), Research.IDS)
        assertEquals(10, Research.defaultEdge(Research.LATE))
        assertEquals(12, Research.defaultEdge(Research.LATE_T1)); assertEquals(8, Research.defaultEdge(Research.LATE_T2))
        assertEquals(FairGapTune.Band(0.24, 0.42), Research.lateBand(Research.LATE, null))
        assertEquals(FairGapTune.Band(0.26, 0.42), Research.lateBand(Research.LATE_T1, null))
        assertEquals(FairGapTune.Band(0.22, 0.44), Research.lateBand(Research.LATE_T2, null))
        // Dirk's sliders still win.
        assertEquals(FairGapTune.Band(0.30, 0.40), Research.lateBand(Research.LATE_T2, ScalpStrategySettings(minEntryCents = 30, maxEntryCents = 40)))
        assertTrue(Research.isLate(Research.LATE_T1) && !Research.isLate(Research.MAKER))
        listOf("fair-t1", "fair-t2", Research.LATE_T1, Research.LATE_T2).forEach { assertTrue(it, !FairGapTune.isTweaked(it)) }
    }

    @Test fun newVariantsGetTheirOwnCardsOnTheScalpTab() {
        val originals = KashiArenaData.rows(ScalpTabState(), v2 = false).map { it.model }
        val research = KashiArenaData.rows(ScalpTabState(), v2 = true).map { it.model }
        assertTrue(ScalpModels.Model.FAIR_T1 in originals && ScalpModels.Model.FAIR_T2 in originals)
        assertTrue(ScalpModels.Model.R_LATE_T1 in research && ScalpModels.Model.R_LATE_T2 in research)
        assertTrue(ScalpModels.Model.FAIR_GAP in originals && ScalpModels.Model.R_LATE in research)
    }
}
