package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
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
    fun noEntryRestsOneCentAboveTheNoBid() {
        // NO quote mirrors the YES quote: NO bid = 1 − yes ask = 0.42,
        // NO ask = 1 − yes bid = 0.45. The resting NO bid is 0.42 + 1¢.
        val c = MakerEdge.compare(0.40, "NO", yesBid = 0.55, yesAsk = 0.58)!!
        assertEquals(0.45, c.takePrice, 1e-12) // 1 − yes bid
        assertEquals(0.43, c.restPrice!!, 1e-12) // (1 − yes ask) + 1¢
        // NO win chance is 1 − p = 0.60.
        assertEquals(0.60 - 0.43, c.evRest!!, 1e-12)
        // Resting saves 2¢ of spread plus the taker fee.
        val fee = KalshiFee.perContract(0.45, 0.07)
        assertEquals(0.45 + fee - 0.43, c.savePerContract!!, 1e-9)
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
        // Out of band there is no rest price; the take side is still compared.
        assertEquals(0.05, MakerEdge.compare(0.9, "YES", yesBid = 0.04, yesAsk = 0.10)!!.restPrice!!, 1e-12)
        assertNull(MakerEdge.compare(0.9, "YES", yesBid = 0.03, yesAsk = 0.10)!!.restPrice)
        assertNull(MakerEdge.compare(0.9, "YES", yesBid = 0.95, yesAsk = 0.97)!!.restPrice)
    }
}
