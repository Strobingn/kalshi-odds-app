package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.external.CfIndexBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maker-fee, strike and settlement checks against Kalshi. */
class ContractChecksTest {

    private fun tick(v: Double) = KalshiWsMessages.Parsed.IndexValue("BRTI", v, null, null, null, null, null, null)

    @Test
    fun theFeeTypeDecidesWhatRestingFillsPay() {
        val free = FeeCheck("KXBTC15M", "quadratic", 1.0, 0L)
        assertEquals(false, free.makerFees)
        assertEquals(0.0, ContractCheckRunner.makerRate(free), 0.0)
        val paid = FeeCheck("KXBTC15M", "quadratic_with_maker_fees", 1.0, 0L)
        assertEquals(true, paid.makerFees)
        assertEquals(KalshiFee.MAKER_COEFFICIENT, ContractCheckRunner.makerRate(paid), 1e-12)
        assertTrue(ContractChecks.lines(ContractCheckState(fee = free))[0].contains("resting fills are free"))
        assertTrue(ContractChecks.lines(ContractCheckState(fee = paid))[0].contains("charges it on every resting fill"))
        assertTrue(ContractChecks.lines(ContractCheckState())[0].startsWith("Maker fee: not checked yet"))
        // A failed call keeps the last answer.
        val book = ContractCheckBook()
        book.setFee(free)
        book.setFee(FeeCheck("KXBTC15M", null, null, 5L, "timeout"))
        assertEquals("quadratic", book.snapshot().fee!!.feeType)
        assertEquals("timeout", book.snapshot().fee!!.error)
    }

    @Test
    fun aWindowIsReadAgainstTheIndexHistory() {
        val cf = CfIndexBook()
        val close = 2_000_000_000_000L
        val open = close - 900_000L
        // 100,000 the minute before the open, 100,100 from the open, 100,050 in the last minute.
        var at = open - 60_000L
        while (at < close) {
            val v = when {
                at < open -> 100_000.0
                at >= close - 60_000L -> 100_050.0
                else -> 100_100.0
            }
            cf.onUpdate(tick(v), at)
            at += 1_000L
        }
        val w = ContractCheckRunner.windowCheck(cf, "BTC", "KXBTC15M-X", close, 100_000.0, 100_050.0, close + 30_000L)
        assertNotNull(w)
        assertEquals(100_000.0, w!!.beforeOpen!!, 1e-9)
        assertEquals(100_100.0, w.afterOpen!!, 1e-9)
        assertEquals(100_100.0, w.atOpen!!, 1e-9)
        assertEquals(100_050.0, w.lastMinute!!, 1e-9)
        assertEquals(60, w.lastMinuteSeconds)
        assertNull("a window the phone never saw is not recorded", ContractCheckRunner.windowCheck(CfIndexBook(), "BTC", "Y", close, 1.0, 1.0, close + 1L))

        // Five windows like it: the strike is the minute before the open, and the settlement matches.
        val book = ContractCheckBook()
        for (i in 0 until 5) book.add(w.copy(ticker = "T$i", closeMs = close + i))
        book.add(w.copy(ticker = "T0"))
        assertEquals("no duplicates", 5, book.snapshot().windows.size)
        val fits = ContractChecks.strikeFits(book.snapshot().windows)
        assertEquals(ContractChecks.Reading.BEFORE_OPEN, fits.first().reading)
        assertEquals(0.0, fits.first().meanAbsBp, 1e-9)
        val lines = ContractChecks.lines(book.snapshot())
        assertTrue(lines[1], lines[1].startsWith("Strike: it is the average of the minute before the open"))
        assertTrue(lines.last(), lines.last().contains("5 of 5 windows"))
    }
}
