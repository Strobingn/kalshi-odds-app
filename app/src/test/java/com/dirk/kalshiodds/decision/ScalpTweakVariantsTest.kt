package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.ui.KashiArenaData
import com.dirk.kalshiodds.ui.ScalpTabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val tight = Research.LATE_SPECS.getValue(Research.LATE_T1)
        val loose = Research.LATE_SPECS.getValue(Research.LATE_T2)
        assertEquals(120, tight.minS); assertEquals(240, tight.maxS); assertEquals(12, tight.edgeCents)
        assertEquals(0.21, tight.maxGap, 0.0); assertEquals(26, tight.loCents); assertEquals(42, tight.hiCents)
        assertEquals(90, loose.minS); assertEquals(360, loose.maxS); assertEquals(8, loose.edgeCents)
        assertEquals(0.23, loose.maxGap, 0.0); assertEquals(22, loose.loCents); assertEquals(44, loose.hiCents)
        // Window bounds are seconds left, inclusive. 119s is outside the tight copy; 90s is inside the loose one.
        assertFalse(Research.lateWindowOk(119.0, Research.LATE_T1, 120, 300))
        assertTrue(Research.lateWindowOk(120.0, Research.LATE_T1, 120, 300))
        assertTrue(Research.lateWindowOk(240.0, Research.LATE_T1, 120, 300))
        assertFalse(Research.lateWindowOk(241.0, Research.LATE_T1, 120, 300))
        assertFalse(Research.lateWindowOk(89.0, Research.LATE_T2, 120, 300))
        assertTrue(Research.lateWindowOk(90.0, Research.LATE_T2, 120, 300))
        assertTrue(Research.lateWindowOk(360.0, Research.LATE_T2, 120, 300))
        assertFalse(Research.lateWindowOk(361.0, Research.LATE_T2, 120, 300))
        assertEquals(0.21, Research.lateMaxGapDollars(Research.LATE_T1), 0.0)
        assertEquals(0.23, Research.lateMaxGapDollars(Research.LATE_T2), 0.0)
        // 12¢ is 0.12 dollars after the fee, not 12 dollars.
        val wide = Research.edgeAfterCosts(0.60, 0.20, 1, true)
        assertTrue(wide > 0.12 && wide < 1.0)
        assertTrue(Research.gateOk(0.60, 0.20, 1, true, 12.0))
        assertFalse(Research.gateOk(0.60, 0.20, 1, true, wide * 100.0 + 1.0))
        assertTrue(Research.isLate(Research.LATE_T1) && !Research.isLate(Research.MAKER))
        listOf("fair-t1", "fair-t2", Research.LATE_T1, Research.LATE_T2).forEach { assertTrue(it, !FairGapTune.isTweaked(it)) }
    }

    @Test fun fairTweaksAreBtcOnlyAndNeverBecomeTheSeed() {
        assertEquals(2, ScalpParams.fairTweaksFor("BTC").size)
        assertTrue(ScalpParams.fairTweaksFor("ETH").isEmpty())
        assertTrue(ScalpParams.fairTweaksFor("SOL").isEmpty())
        assertEquals(0.12, ScalpParams.fairTweaksFor("btc")[0].minGap, 1e-9)
        assertEquals(0.10, ScalpParams.fairTweaksFor("BTC")[0].target, 1e-9)
        assertEquals(0.16, ScalpParams.fairTweaksFor("BTC")[1].minGap, 1e-9)
        assertEquals(0.14, ScalpParams.fairTweaksFor("BTC")[1].target, 1e-9)
        val seed = ScalpParams.seedFor("BTC")
        val tweak = ScalpParams.FAIR_TWEAKS[0]
        val trades = ArrayList<ScalpTrade>()
        for (w in 0 until 6) {
            repeat(10) { i ->
                trades += closed(tweak.id, w, i, 5.0)
                trades += closed(seed.id, w, i + 50, -1.0)
            }
        }
        val next = ScalpTuner.tune(trades, ScalpTuneState(paramsByCoin = mapOf("BTC" to seed.id)), 2_000_000_000_000L)
        assertEquals(seed.id, next.paramsFor("BTC").id)
        assertTrue(next.paramsByCoin.values.none { it in ScalpParams.FAIR_TWEAK_IDS })
    }

    private fun closed(vid: String, window: Int, i: Int, net: Double) = ScalpTrade(
        "$vid-$window-$i", "KXBTC15M-NOCLOSE-00", "YES", ScalpState.CLOSED,
        signalAtMs = window * 1_000_000L + i, signalAsk = 0.40, fairAtSignal = 0.55, contracts = 1, netUsd = net,
        ruleVersion = "v|$vid|P"
    )

    @Test fun newVariantsGetTheirOwnCardsOnTheScalpTab() {
        val originals = KashiArenaData.rows(ScalpTabState(), v2 = false).map { it.model }
        val research = KashiArenaData.rows(ScalpTabState(), v2 = true).map { it.model }
        assertTrue(ScalpModels.Model.FAIR_T1 in originals && ScalpModels.Model.FAIR_T2 in originals)
        assertTrue(ScalpModels.Model.R_LATE_T1 in research && ScalpModels.Model.R_LATE_T2 in research)
        assertTrue(ScalpModels.Model.FAIR_GAP in originals && ScalpModels.Model.R_LATE in research)
    }
}
