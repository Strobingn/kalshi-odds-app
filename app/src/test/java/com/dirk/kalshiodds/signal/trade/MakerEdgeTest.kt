package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MakerEdgeTest {

    @Test
    fun restIsAFeeFreeCentBetterEntry() {
        // P(YES) 60%, YES bid 55 / ask 58.
        val c = MakerEdge.compare(0.60, "YES", yesBid = 0.55, yesAsk = 0.58)!!
        assertEquals(0.58, c.takePrice, 1e-12)
        assertEquals(0.56, c.restPrice!!, 1e-12) // bid + 1¢, under the ask
        // Saving = (ask + taker fee) − rest price.
        val fee = KalshiFee.perContract(0.58, 0.07)
        assertEquals(0.58 + fee - 0.56, c.savePerContract!!, 1e-9)
        // EV(rest) > EV(take) by exactly the saving.
        assertEquals(c.evRest!! - c.evTake, c.savePerContract!!, 1e-9)
        assertEquals(0.60 - 0.56, c.evRest, 1e-12)
    }

    @Test
    fun noRoomToRestWhenSpreadIsOneCent() {
        val c = MakerEdge.compare(0.60, "YES", yesBid = 0.57, yesAsk = 0.58)!!
        assertNull(c.restPrice)
        assertNull(c.evRest)
        assertTrue(c.summary().contains("no room"))
    }

    @Test
    fun noEntryRestsAtOneMinusTheYesRestMirror() {
        // NO take = 1 − yes bid; NO rest mirrors the YES rest price.
        val c = MakerEdge.compare(0.40, "NO", yesBid = 0.55, yesAsk = 0.58)!!
        assertEquals(0.45, c.takePrice, 1e-12) // 1 − yes bid
        assertEquals(0.44, c.restPrice!!, 1e-12) // 1 − (bid + 1¢)
        // NO win chance is 1 − p = 0.60.
        assertEquals(0.60 - 0.44, c.evRest!!, 1e-12)
    }

    @Test
    fun unusableQuotesReturnNull() {
        assertNull(MakerEdge.compare(null, "YES", 0.55, 0.58))
        assertNull(MakerEdge.compare(0.6, "YES", null, null))
        assertNull(MakerEdge.compare(0.6, "YES", 0.55, null))
        assertNull(MakerEdge.compare(Double.NaN, "YES", 0.55, 0.58))
    }

    @Test
    fun restPriceStaysInsideTheFiveToNinetyFiveBand() {
        // bid 4¢ → bid+1 = 5¢ is allowed; bid 3¢ → 4¢ is not.
        assertNotNull(MakerEdge.compare(0.9, "YES", yesBid = 0.04, yesAsk = 0.10))
        assertNull(MakerEdge.compare(0.9, "YES", yesBid = 0.03, yesAsk = 0.10))
        assertNull(MakerEdge.compare(0.9, "YES", yesBid = 0.95, yesAsk = 0.96))
    }
}
