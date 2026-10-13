package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.Open50Model
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0364Test {
    private val codes = (51..61).map { "sf$it" }

    @Test
    fun open50CardsArePaperBtcGroupWith10CentStopAndNoEdgeLabel() {
        codes.forEach { c ->
            val p = ShockFav.v(c)
            assertEquals(ShockFav.Kind.OPEN50, p.kind); assertEquals("Open 50", p.group)
            assertEquals(10, ShockFav.stopC(p)); assertTrue(p.noEdge2s); assertTrue(ShockFav.isO50(c))
            assertTrue(ScalpModels.ALL.any { it.code == c })
        }
        assertTrue(ScalpModels.tag(ScalpModels.Model.SF51)!!.contains("Open 50"))
        assertEquals("BTC", ShockFav.TRADE_COIN)
    }

    @Test
    fun entryWindowAndUptick() {
        assertTrue(ShockFav.o50InEntryWindow(890.0)); assertTrue(ShockFav.o50InEntryWindow(870.0))
        assertTrue(!ShockFav.o50InEntryWindow(897.0)); assertTrue(!ShockFav.o50InEntryWindow(860.0))
        assertEquals("YES", ShockFav.o50UptickSide(0.50, 0.51)); assertEquals("NO", ShockFav.o50UptickSide(0.50, 0.49)); assertNull(ShockFav.o50UptickSide(0.50, 0.505))
    }

    @Test
    fun exitsTrailTakeProfitMaxHoldAndModelDrop() {
        val trail2 = ShockFav.v("sf51"); val trail3 = ShockFav.v("sf52"); val tp5 = ShockFav.v("sf54")
        assertEquals("trail 2¢ below peak", ShockFav.o50Exit(trail2, 0.50, 0.56, 0.54, 10.0, 800.0))
        assertNull(ShockFav.o50Exit(trail3, 0.50, 0.56, 0.54, 10.0, 800.0))
        assertNull(ShockFav.o50Exit(tp5, 0.50, 0.55, 0.55, 10.0, 800.0)) // +5 gross, < +5 net of fees
        assertTrue(ShockFav.o50Exit(tp5, 0.50, 0.59, 0.59, 10.0, 800.0)!!.startsWith("net take profit"))
        assertEquals("max hold 3 min", ShockFav.o50Exit(tp5, 0.50, 0.5, 0.5, 180.0, 800.0))
        assertEquals("model predicts drop", ShockFav.o50Exit(ShockFav.v("sf61"), 0.50, 0.50, 0.50, 5.0, 800.0, aiPred = -0.02))
    }

    @Test
    fun modelIsDeterministicAndVersioned() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 1.0, 5.0, 1.0, 1.0, 0.2, 0.5, 0.01, 0.01, 15.0)
        assertEquals(Open50Model.predict(x), Open50Model.predict(x.copyOf()), 0.0)
        assertTrue(Open50Model.VERSION.startsWith("o50ai-v"))
        assertTrue(Open50Model.predict(x).isFinite())
    }
}
