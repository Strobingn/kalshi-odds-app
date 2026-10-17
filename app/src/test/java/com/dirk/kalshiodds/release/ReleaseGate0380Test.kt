package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseGate0380Test {
    private fun trade(vid: String) = ScalpTrade(id = "t-$vid", ticker = "KXBTC15M-26OCT170900-00", side = "YES",
        state = ScalpState.PENDING_ENTRY, signalAtMs = 1L, signalAsk = .3, fairAtSignal = .5, ruleVersion = "${ScalpRule.VERSION}|$vid|P")

    @Test fun fifteenCopiesWithOwnCardsAndG14Params() {
        assertEquals(15, G14Copies.SOURCES.size)
        assertNotNull(G14Copies.params)
        assertTrue(G14Copies.params.hold60)
        for (src in G14Copies.SOURCES.keys) {
            val orig = ScalpModels.modelByCode(src); assertNotNull(src, orig)
            val copy = ScalpModels.modelByCode(G14Copies.copyCode(src))!!
            assertTrue(copy.label.contains("rebuilt like Fair-gap g14"))
            assertFalse(ScalpModels.isV2(copy))
            assertEquals(copy, ScalpModels.modelOf(trade(G14Copies.variantId(src))))
            assertEquals(src, G14Copies.sourceOf(copy.code))
        }
    }

    @Test fun copyVariantsDoNotCollideWithOtherLanes() {
        val vid = G14Copies.variantId("fcride")
        assertFalse(ForecastScalp.isForecast(trade(vid)))
        assertFalse(ShockFav.isShockFav(vid))
        assertFalse(ScalpV2.isV2(vid))
    }

    @Test fun v2SourcesListenToTheirBaseOriginal() {
        assertTrue("v2-fcfade" in G14Copies.copiesTriggeredBy("fcfade"))
        assertEquals(setOf("fcride", "v2-fcride"), G14Copies.copiesTriggeredBy("fcride").toSet())
        assertEquals(setOf("sf30", "v2-sf30"), G14Copies.copiesTriggeredBy("sf30").toSet())
    }

    @Test fun copiesStayActiveWhileRetiredOriginalsStayRetired() {
        ScalpModels.retirementForTest(true)
        try {
            assertTrue(ScalpModels.isRetired(ScalpModels.modelByCode("sf32")!!))
            for (src in G14Copies.SOURCES.keys) assertTrue(ScalpModels.modelByCode(G14Copies.copyCode(src))!! in ScalpModels.ACTIVE)
            // winners untouched and still active
            for (w in listOf("fairh60", "v2-sf08", "v2-sf09", "v2-sf01", "v2-sf06", "v2-sf07", "v2-sf24", "v2-sf17"))
                assertTrue(w, ScalpModels.modelByCode(w)!! in ScalpModels.ACTIVE)
        } finally { ScalpModels.retirementForTest(false) }
    }
}
