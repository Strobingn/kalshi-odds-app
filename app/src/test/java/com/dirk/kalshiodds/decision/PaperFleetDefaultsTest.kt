package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every paper scalper's factory slider is 15–55¢ with a 25¢ take-profit,
 * except a floor that already sits above 55¢. That row heals into its own book.
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
        val (lo, hi) = PaperFleetDefaults.band(id)
        assertEquals("$id buy min", lo, s.minEntryCents)
        assertEquals("$id buy max", hi, s.maxEntryCents)
        val entry = ((lo + hi) / 2).coerceIn(lo.coerceAtLeast(1), 98)
        val profit = if (entry + PaperFleetDefaults.PROFIT_CENTS > 99) (99 - entry).coerceAtLeast(1) else PaperFleetDefaults.PROFIT_CENTS
        assertEquals("$id profit", profit, s.takeProfitCents)
        assertTrue("$id ${s.minEntryCents}–${s.maxEntryCents} floor ${ScalpFloors.cents(id)}", s.maxEntryCents!! > ScalpFloors.cents(id))
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

    @Test fun factoryBandStaysUnderAHigherStrategyFloor() {
        assertFleet("fcride", ScalpParamSanity.settings("fcride", PaperFleetDefaults.factory()))
        assertFleet("sf17f", ScalpControls().settings("sf17f"))
        val floor = ScalpFloors.cents("sf17f")
        assertTrue(floor > PaperFleetDefaults.BUY_MAX)
        // 20–40¢ and a stored 15–55¢ sit under the favourite floor. Entry would
        // accept nothing, so the row is put back in that book.
        listOf(
            ScalpStrategySettings(minEntryCents = 20, maxEntryCents = 40, takeProfitCents = 15),
            PaperFleetDefaults.factory(),
            ScalpStrategySettings(minEntryCents = 70, maxEntryCents = 67, takeProfitCents = 25)
        ).forEach { raw ->
            val healed = ScalpParamSanity.settings("sf17f", raw)
            assertTrue("${healed.minEntryCents}–${healed.maxEntryCents}", healed.maxEntryCents!! > floor)
            assertFalse(FairGapTune.priceBlocked("sf17f", (floor + 1) / 100.0, healed))
            assertTrue(FairGapTune.priceBlocked("sf17f", PaperFleetDefaults.BUY_MAX / 100.0, healed))
        }
        val hand = ScalpParamSanity.settings("sf17f", ScalpStrategySettings(minEntryCents = 76, maxEntryCents = 90, takeProfitCents = 15))
        assertEquals(76, hand.minEntryCents)
        assertEquals(90, hand.maxEntryCents)
        assertEquals(15, hand.takeProfitCents)
        assertTrue(ScalpFloors.cents("fcride") > PaperFleetDefaults.BUY_MIN)
        assertTrue(ScalpFloors.cents("fcride") < PaperFleetDefaults.BUY_MAX)
        var learned = ScalpControls().settings("sf17f")
        repeat(12) { learned = Research.learn("sf17f", learned, 0.50, 55) }
        assertTrue("${learned.minEntryCents}–${learned.maxEntryCents}", learned.maxEntryCents!! > learned.minEntryCents)
        assertTrue(learned.maxEntryCents!! > floor)
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
