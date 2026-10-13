package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0363Test {
    @Test
    fun fourSimpleDipScalpersArePriceOnlyWith15CentStops() {
        val simple = listOf("sf41", "sf42", "sf43", "sf44").map { ShockFav.v(it) }
        simple.forEach { assertEquals(ShockFav.Kind.SIMPLE_DIP, it.kind); assertEquals("Simple", it.group); assertEquals(15, ShockFav.stopC(it)) }
        assertEquals(listOf(0.05, 0.05, 0.10, 0.10), simple.map { it.dip })
        assertEquals(listOf(0.08, 0.15, 0.08, 0.15), simple.map { it.tp })
        val p = simple[0]
        assertEquals("YES", ShockFav.simpleDipSide(p, 0.40, 0.46, 0.62, 0.55))
        assertEquals("NO", ShockFav.simpleDipSide(p, 0.50, 0.50, 0.44, 0.50))
        assertNull(ShockFav.simpleDipSide(p, 0.43, 0.46, 0.55, 0.55))
        assertNull(ShockFav.simpleDipSide(p, 0.02, 0.20, null, 0.5))
        assertTrue(ScalpModels.tag(ScalpModels.Model.SF41)!!.contains("Simple"))
    }

    @Test
    fun queuedCopiesAreNewModelsAndOriginalsStay() {
        val c = ScalpParams.QUEUED_COPIES
        assertEquals(ScalpModels.Model.FAIR_HOLD, ScalpModels.modelOf(c[0]))
        assertEquals(ScalpModels.Model.DIP_NOTP, ScalpModels.modelOf(c[1]))
        assertEquals(ScalpModels.Model.MOMO_NOTP, ScalpModels.modelOf(c[2]))
        c.forEach { assertTrue(ScalpParams.byId(it.id) != null) }
        assertEquals(c.size, c.map { it.id }.toSet().size)
        assertTrue(ShockFav.v("sf32").tpNet)
        assertTrue(!ShockFav.v("sf30").tpNet)
        assertEquals(58, ScalpModels.ALL.size)
    }
}
