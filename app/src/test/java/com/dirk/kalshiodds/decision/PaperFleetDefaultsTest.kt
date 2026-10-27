package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every paper scalper's factory slider is 15–55¢ with a 25¢ take-profit.
 * A stored 31–32¢ (or 34–35¢) band, and a take-profit above 30¢, heal on load.
 */
class PaperFleetDefaultsTest {
    private fun ids(): List<String> {
        val all = LinkedHashSet<String>()
        ScalpModels.Model.values().forEach { all += it.code }
        all += TwentyScalperFleet.IDS
        all += ScalpVariants.IDS
        all += KeptScalpers.IDS
        all += KeptScalpers.HFT_IDS
        all += Research.IDS
        return all.toList()
    }

    private fun assertFleet(id: String, s: ScalpStrategySettings) {
        assertEquals("$id buy min", PaperFleetDefaults.BUY_MIN, s.minEntryCents)
        assertEquals("$id buy max", PaperFleetDefaults.BUY_MAX, s.maxEntryCents)
        assertEquals("$id profit", PaperFleetDefaults.PROFIT_CENTS, s.takeProfitCents)
    }

    @Test fun everyScalperDefaultsToFifteenFiftyFiveAndTwentyFive() {
        val codes = ids()
        assertTrue("scalper id list", codes.size >= 20)
        codes.forEach { id ->
            assertFleet(id, ScalpControls().settings(id))
            assertFleet(id, ScalpParamSanity.settings(id, ScalpStrategySettings()))
            assertFleet(id, ScalpParamSanity.settings(id, PaperFleetDefaults.factory()))
        }
    }

    @Test fun resetToFactoryIsFifteenFiftyFiveAndTwentyFive() {
        val reset = PaperFleetDefaults.factory()
        assertEquals(15, reset.minEntryCents)
        assertEquals(55, reset.maxEntryCents)
        assertEquals(25, reset.takeProfitCents)
        TwentyScalperFleet.IDS.forEach { id ->
            assertFleet(id, ScalpParamSanity.settings(id, reset))
        }
    }

    @Test fun collapsedThirtyOneToThirtyTwoAndHotProfitHeal() {
        listOf("s05-student-tail", "sf42", "sf17f", "v2-sf41", "fair").forEach { id ->
            val collapsed = ScalpStrategySettings(minEntryCents = 31, maxEntryCents = 32, takeProfitCents = 36)
            assertFleet(id, ScalpParamSanity.settings(id, collapsed))
            val tighter = ScalpStrategySettings(minEntryCents = 34, maxEntryCents = 35, takeProfitCents = 40)
            assertFleet(id, ScalpParamSanity.settings(id, tighter))
        }
    }

    @Test fun saveHealsACollapsedRowWithoutWipingAHandSetBand() {
        val store = MemoryScalpControls()
        store.save(store.state.value.copy(strategySettings = mapOf(
            "s05-student-tail" to ScalpStrategySettings(minEntryCents = 31, maxEntryCents = 32, takeProfitCents = 32),
            "sf41" to ScalpStrategySettings(minEntryCents = 20, maxEntryCents = 40, takeProfitCents = 15)
        )))
        assertFleet("s05-student-tail", store.state.value.settings("s05-student-tail"))
        val hand = store.state.value.settings("sf41")
        assertEquals(20, hand.minEntryCents)
        assertEquals(40, hand.maxEntryCents)
        assertEquals(15, hand.takeProfitCents)
    }

    @Test fun explicitOffProfitOnAWideBandStaysOff() {
        val off = ScalpParamSanity.settings("sf41", ScalpStrategySettings(minEntryCents = 20, maxEntryCents = 40, takeProfitCents = 0))
        assertEquals(20, off.minEntryCents)
        assertEquals(40, off.maxEntryCents)
        assertEquals(0, off.takeProfitCents)
    }

    @Test fun paperClipFloorAndModelFilterGatesStay() {
        assertEquals(50, PaperClip.MIN)
        assertEquals(0.55, ModelFilter.DEFAULT_THRESHOLD, 0.0)
        assertEquals(0.65, ModelFilter.HIGH_SCORE, 0.0)
    }
}
