package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpV2
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0368Test {
    private val bases = listOf("sf41", "sf42", "sf43", "sf44")

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by new hard rules; covered by ScalperRulesTest")
    @Test fun originalsAndPlainV2Unchanged() {
        assertEquals(0.05, ShockFav.v("sf41").dip, 1e-12); assertEquals(0.15, ShockFav.v("sf42").tp, 1e-12)
        bases.forEach {
            val s = ScalpV2.spec("v2-$it")
            assertEquals(ScalpV2.MAX_ASK, s.maxAsk, 1e-12); assertEquals(ScalpV2.TRAIL, s.trail, 1e-12)
            assertEquals(null, s.stop); assertEquals(ScalpV2.CLIP, s.clip); assertTrue(!s.walk)
        }
    }

    @Test fun tweakAndSizeCopiesOfV2Sf41to44AreOwnScalpers() {
        assertEquals(16, ScalpV2.EXTRA_SPECS.size)
        val tweaks = ScalpV2.EXTRA_SPECS.filter { !it.walk }
        assertEquals(tweaks.size, tweaks.map { Triple(it.maxAsk, it.trail, it.stop) }.toSet().size) // every tweak differs
        ScalpV2.EXTRA_SPECS.forEach { sp ->
            assertTrue(sp.id, ScalpModels.modelByCode(sp.id) == null)
            assertTrue(ScalpModels.leaderboardFromStats(emptyList()).none { it.model.code == sp.id })
        }
        assertNull(ScalpModels.modelByCode("v2-sf41"))
        assertNull(ScalpModels.modelByCode("v2-sf42"))
        assertNull(ScalpModels.v2Of(ScalpModels.Model.SF41))
        assertNull(ScalpModels.v2Of(ScalpModels.Model.SF42))
        assertEquals(listOf(5, 10), ScalpV2.EXTRA_SPECS.filter { it.walk && it.base == "sf41" }.map { it.clip })
    }

    @org.junit.Ignore("Kalshi Scalper 1.0.0: pins Kashi rules/identity superseded by new hard rules; covered by ScalperRulesTest")
    @Test fun shockV2CopiesDiverge() {
        val p = (1..20).map { ScalpV2.spec(String.format("v2-sf%02d", it)).let { s -> s.maxAsk to s.trail } }
        assertEquals(20, p.toSet().size)
    }

    @Test fun depthWalkPartialFillAndWorseAvg() {
        val asks = listOf(0.40 to 3.0, 0.42 to 4.0, 0.47 to 50.0, 0.55 to 100.0)
        val (n5, usd5) = ScalpV2.walk(asks, 5) { it <= 0.50 }
        assertEquals(5, n5); assertEquals((3 * 0.40 + 2 * 0.42) / 5, usd5 / n5, 1e-12)
        val (n10, usd10) = ScalpV2.walk(asks.take(2) + (0.55 to 100.0), 10) { it <= 0.50 }
        assertEquals(7, n10) // partial: only 7 at or under the 50¢ cap
        assertTrue(usd10 / n10 > 0.40)
        // quote ladders: YES asks from NO bids, NO asks from YES bids
        val q = ScalpRule.quoteFromBook("KXBTC15M-X", 1_000L, 900_000L, 0L, listOf(0.55 to 2.0, 0.54 to 9.0), listOf(0.60 to 3.0, 0.58 to 7.0), null, null, null)!!
        assertEquals(listOf(0.40 to 3.0, 0.42 to 7.0), q.askLadder("YES").map { Math.round(it.first * 100) / 100.0 to it.second })
        assertEquals(listOf(0.55 to 2.0, 0.54 to 9.0), q.bidLadder("YES"))
    }
}
