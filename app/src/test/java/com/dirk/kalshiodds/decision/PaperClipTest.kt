package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paper entries book at least [PaperClip.MIN]. A 1-lot touch and a saved size of 1 do not. */
class PaperClipTest {
    @Test fun sizedKeepsZeroAndLiftsAnythingPositive() {
        assertEquals(0, PaperClip.sized(0))
        assertEquals(0, PaperClip.sized(-3))
        assertEquals(PaperClip.MIN, PaperClip.sized(1))
        assertEquals(80, PaperClip.sized(80))
    }

    @Test fun takerDoesNotShrinkToAOneLotTouch() {
        assertEquals(PaperClip.MIN, PaperClip.taker(10, 1.0))
        assertEquals(80, PaperClip.taker(80, 1.0))
        assertEquals(0, PaperClip.taker(10, 0.0))
        assertEquals(0, PaperClip.taker(0, 40.0))
        assertEquals(0, PaperClip.taker(10, Double.NaN))
    }

    @Test fun ownClipAndAlgoCannotCollapseUnderTheFloor() {
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf07-v2", null))
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf42-v1", 1))
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf42-v1", 4))
        assertEquals(80, ScalpParamSanity.ownClip("sf42-v1", 80))
        val algoOne = ScalpControls(algo = mapOf("sf07-v2" to mapOf("contracts" to 1.0)))
        assertTrue(StrategyAlgo.clip(algoOne, "sf07-v2", 1) >= PaperClip.MIN)
        val explicit = ScalpControls(strategySettings = mapOf("sf42-v1" to ScalpStrategySettings(contracts = 80)))
        assertEquals(80, StrategyAlgo.clip(explicit, "sf42-v1", 5))
    }

    @Test fun learnerAndTunerStayAtTheFloor() {
        val code = KeptScalpers.V2_DIP_ID
        val lost = Research.learn(code, ScalpStrategySettings(contracts = 10), -2.0, 55)
        assertTrue((lost.contracts ?: 0) >= PaperClip.MIN)
        val shrunk = Research.learn(code, ScalpStrategySettings(contracts = 80), -2.0, 55)
        assertTrue(shrunk.contracts!! >= PaperClip.MIN)
        assertTrue(shrunk.contracts!! < 80)

        val loserNets = List(7) { 0.20 } + List(3) { -1.0 }
        val tape = PaperTape(code, 10, 7, loserNets.sum(), 0.7, loserNets, emptyList())
        val atFloor = PaperSettingTuner.propose(ScalpControls(), listOf(tape))
        assertTrue(atFloor.none { it.setting == "contracts" && it.newValue.toInt() < PaperClip.MIN })
        val high = ScalpControls(strategySettings = mapOf(code to ScalpStrategySettings(contracts = 80)))
        val down = PaperSettingTuner.propose(high, listOf(tape))
        val clip = down.first { it.setting == "contracts" }
        assertTrue(clip.newValue.toInt() >= PaperClip.MIN)
        assertTrue(clip.newValue.toInt() < clip.oldValue.toInt())
    }
}
