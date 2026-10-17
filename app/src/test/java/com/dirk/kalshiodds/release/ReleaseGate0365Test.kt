package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.G14Copies
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.data.supabase.SyncMerge
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpV2
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0365Test {
    @Test fun v2CopyForEveryOriginalExceptSf29() {
        val originals = ScalpModels.ALL.filter { !ScalpModels.isV2(it) && !G14Copies.isCopyCode(it.code) } // 0.3.80: g14 copies are not originals
        assertEquals(58, originals.size)
        assertEquals(57 + 16, ScalpModels.V2_MODELS.size) // 0.3.68: +16 tweak/size copies of v2 sf41–44
        assertNull(ScalpModels.v2Of(ScalpModels.Model.SF29))
        listOf(ScalpModels.Model.SF30, ScalpModels.Model.SF32, ScalpModels.Model.SF41, ScalpModels.Model.DIP_HUNTER, ScalpModels.Model.MOMENTUM,
            ScalpModels.Model.FAIR_GAP, ScalpModels.Model.CF_REPRICE, ScalpModels.Model.FORECAST_RIDE, ScalpModels.Model.MAKER_DIP).forEach {
            val v = ScalpModels.v2Of(it)!!
            assertEquals(it, ScalpModels.originalOf(v))
            assertEquals("group: v2", ScalpModels.tag(v))
        }
    }

    @Test fun v2EntryAndExitRule() {
        assertTrue(ScalpV2.entryOk(0.50)); assertFalse(ScalpV2.entryOk(0.56)); assertFalse(ScalpV2.entryOk(null))
        val stop = 0.10
        // below stop size net: hold even if it dips 2¢ off peak
        assertNull(ScalpV2.exit(0.40, stop, 0.48, 0.46, wasArmed = false))
        // stop keeps working
        assertTrue(ScalpV2.exit(0.40, stop, 0.40, 0.30, wasArmed = false)!!.startsWith("stop"))
        // armed (net ≥ 10¢) then 2¢ off the peak → sell
        assertTrue(ScalpV2.armed(0.40, 0.55, stop))
        assertTrue(ScalpV2.exit(0.40, stop, 0.57, 0.55, wasArmed = true)!!.contains("trail"))
        assertNull(ScalpV2.exit(0.40, stop, 0.57, 0.56, wasArmed = true))
        assertEquals(0.15, ScalpV2.stopFor("sf41"), 1e-9)
        assertEquals("v2-sf30", ScalpV2.v2Id("sf30"))
        assertEquals("not enough data", ScalpV2.status(29, 1.0, -1.0))
        assertEquals("beating original", ScalpV2.status(30, 0.1, -0.2))
        assertEquals("losing to original", ScalpV2.status(30, -0.3, -0.2))
    }

    @Test fun fairTakeProfitOnlyWhenNetPositive() {
        assertTrue(ScalpRule.netAfterFees(0.50, 0.51) < 0)
        assertTrue(ScalpRule.netAfterFees(0.50, 0.55) > 0)
        val p = ShockFav.v("sf01")
        assertNull(ShockFav.shockExit(p, 5.0, 600.0, 0.51, 0.51, entry = 0.50))
        assertEquals("bid caught fair", ShockFav.shockExit(p, 5.0, 600.0, 0.56, 0.56, entry = 0.50))
        // Simple dip has price-only exits
        listOf("sf41", "sf42", "sf43", "sf44").forEach { assertEquals(ShockFav.Kind.SIMPLE_DIP, ShockFav.v(it).kind) }
    }

    @Test fun open50EveryWindowAndScalpBacklogAlwaysPushed() {
        assertEquals("sf56n", ShockFav.o50VariantId(ShockFav.v("sf56"), "NO"))
        assertEquals(listOf("YES", "NO"), ShockFav.o50Sides(ShockFav.v("sf59"), 0.5, 0.5))
        assertTrue("scalp" in SyncMerge.ALWAYS_PUSH)
        val (line, warn) = SupabaseSync().storageLine(450L * 1_048_576, 3)
        assertTrue(warn); assertTrue(line.contains("90%")); assertTrue(line.contains("3"))
        assertFalse(SupabaseSync().storageLine(100L * 1_048_576, 0).second)
    }
}
