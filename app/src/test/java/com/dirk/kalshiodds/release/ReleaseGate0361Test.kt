package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ForecastScalp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0361Test {
    @Test
    fun everySfVariantHasAStopFromBt61OrTheWidestDefault() {
        val by = ShockFav.VARIANTS.associateBy { it.code }
        assertEquals(10, ShockFav.stopC(by.getValue("sf16")))
        assertEquals(10, ShockFav.stopC(by.getValue("sf21")))
        assertEquals(10, ShockFav.stopC(by.getValue("sf23")))
        assertEquals(15, ShockFav.stopC(by.getValue("sf19")))
        assertEquals(15, ShockFav.stopC(by.getValue("sf17")))
        assertEquals(25, ShockFav.stopC(by.getValue("sf03"))) // train chose none → widest
        assertEquals(25, ShockFav.stopC(by.getValue("sf31"))) // not studied → widest
        ShockFav.VARIANTS.forEach { assertTrue(ShockFav.stopC(it) in listOf(10, 15, 20, 25)) }
        assertEquals(25, ForecastScalp.STOP_C)
        ScalpModels.ALL.forEach { assertTrue(ScalpModels.stopLabel(it).startsWith("stop ")) }
    }

    @Test
    fun labelsDuplicatesAndZeroTradeVariantsWithoutRemovingThem() {
        val codes = ScalpModels.ALL.map { it.code }
        listOf("sf01", "sf09", "sf10", "sf11").forEach { assertFalse(it, it in codes) }
        assertTrue(ShockFav.TAGS.getValue("sf01").startsWith("duplicate"))
        assertTrue(ShockFav.TAGS.getValue("sf09").startsWith("duplicate"))
        assertTrue(ShockFav.TAGS.getValue("sf10").startsWith("zero trades"))
        assertTrue(ShockFav.TAGS.getValue("sf11").startsWith("zero trades"))
    }

    @Test
    fun holdSiblingsAreSeparateModelsNextToUnchangedOriginals() {
        val sibs = ScalpParams.HOLD60_SIBLINGS
        assertEquals(2, sibs.size)
        sibs.forEach { assertTrue(it.id.endsWith("-h60")); assertNotNull(ScalpParams.byId(it.id)) }
        assertEquals(ScalpModels.Model.DIP_HOLD, ScalpModels.modelOf(sibs[0]))
        assertEquals(ScalpModels.Model.MOMO_HOLD, ScalpModels.modelOf(sibs[1]))
        val dip = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER)
        assertFalse(dip.hold60)
        assertFalse(dip.id.endsWith("-h60"))
        assertEquals(ScalpModels.Model.DIP_HUNTER, ScalpModels.modelOf(dip))
    }

    @Test
    fun stopSavedReadsTheNoStopShadowFromTheNote() {
        val t = ScalpTrade(id = "a", ticker = "KXBTC15M-X", side = "YES", state = ScalpState.CLOSED, signalAtMs = 1, signalAsk = 0.5, fairAtSignal = 0.5,
            netUsd = -2.0, note = "x · nostop=-5.00")
        val (saved, n) = ScalpModels.stopSaved(listOf(t))
        assertEquals(1, n)
        assertEquals(3.0, saved, 1e-9)
    }
}
