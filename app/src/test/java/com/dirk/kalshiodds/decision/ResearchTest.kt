package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class ResearchTest {
    private fun q(spot: Double, strike: Double, tauS: Double, cf: Double? = null, yb: Double = 0.48, ya: Double = 0.50) = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT161500-00", nowMs = 1_000_000L, closeMs = 1_000_000L + (tauS * 1000).toLong(), bookAtMs = 1_000_000L,
        yesBid = yb, yesBidSize = 100.0, yesAsk = ya, yesAskSize = 100.0, spot = spot, strike = strike, sigmaPerSec = 0.00003,
        cfSpot = cf, cfAgeMs = cf?.let { 0L })

    @Test fun feeIsCeilOf7PercentCPP() {
        assertEquals(18, Research.feeCents(10, 0.5))   // 17.5 → 18
        assertEquals(0.18, Research.fee(10, 0.5), 1e-9)
        assertEquals(7, Research.feeCents(10, 0.9))    // 6.3 → 7
    }

    @Test fun fairUsesBrtiWhenFreshElseFlagsFallback() {
        val live = Research.fair(q(100_100.0, 100_000.0, 600.0, cf = 100_050.0))!!
        assertTrue(live.brti); assertEquals(100_050.0, live.underlying, 1e-9); assertEquals(50.0, live.gapUsd!!, 1e-9)
        val fb = Research.fair(q(100_100.0, 100_000.0, 600.0))!!
        assertFalse(fb.brti); assertEquals(100_100.0, fb.underlying, 1e-9)
    }

    @Test fun finalMinuteVarianceShrinksTowardCertainty() {
        val early = Research.fair(q(100_020.0, 100_000.0, 600.0))!!.pYes
        val late = Research.fair(q(100_020.0, 100_000.0, 10.0))!!.pYes
        assertTrue(late > early); assertTrue(late > 0.95)
    }

    @Test fun edgeGateCountsEntryAndExitFees() {
        // p 0.555, ask 0.50, 10 ct: 5.5¢ − 1.8¢ entry − 1.8¢ exit = 1.9¢ → fails a 2¢ gate; holding to settlement (no exit fee) passes.
        assertFalse(Research.gateOk(0.555, 0.50, 10, false, 2.0))
        assertTrue(Research.gateOk(0.555, 0.50, 10, true, 2.0))
    }

    @Test fun kellyIsFractionalAndCapped() {
        val n = Research.kellyContracts(0.70, 0.50, 0.25, 1000.0, 10_000.0)
        val c = 0.50 + Research.fee(1, 0.50)
        assertEquals(kotlin.math.floor(0.25 * (0.70 - c) / (1 - c) * 1000 / c).toInt(), n)
        assertTrue(Research.kellyContracts(0.70, 0.50, 0.25, 1000.0, 5.0) <= 10)
        assertEquals(0, Research.kellyContracts(0.40, 0.50, 0.25, 1000.0, 100.0))
    }

    @Test fun fairExitRules() {
        assertNotNull(Research.fairExit(0.60, 0.65, 0.55, 10))
        assertNotNull(Research.fairExit(0.50, 0.50, 0.60, 10))
        assertNull(Research.fairExit(0.62, 0.60, 0.55, 10))
    }

    @Test fun aiLearnsIsGentleBoundedAndManualWins() {
        var s = ScalpStrategySettings(edgeCents = 2)
        repeat(30) { s = Research.learn("r-hold", s, -1.0, 99) }
        assertEquals(6, s.edgeCents)                       // at most +4¢ over your setting
        assertTrue(s.aiNote.isNotBlank())
        repeat(60) { s = Research.learn("r-hold", s, 1.0, 99) }
        assertEquals(2, s.edgeCents)                       // never below your setting
        var o = ScalpStrategySettings(maxEntryCents = 50)
        repeat(60) { o = Research.learn("sf41", o, -1.0, 55) }
        assertEquals(40, o.maxEntryCents)                  // at most −10¢
        o = o.copy(maxEntryCents = 45).manual()
        assertNull(o.aiBaseCents); assertEquals(0, o.aiN)
        val off = ScalpStrategySettings(aiLearns = false, edgeCents = 3)
        assertEquals(off, Research.learn("r-late", off, -5.0, 99))
    }

    @Test fun researchModelsAreLabelledAndMapped() {
        Research.IDS.forEach { id ->
            val m = ScalpModels.modelByCode(id)!!
            assertTrue(m.label.startsWith("Research"))
            assertTrue(ScalpModels.isResearch(m)); assertFalse(ScalpModels.isV2(m))
        }
    }
}
