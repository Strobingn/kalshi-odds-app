package com.dirk.kalshiodds.signal.updown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpDownBettorTest {

    private fun reading(sec: Int, up: Double, yesAsk: Double?, noAsk: Double?, ticker: String = "KXBTC15M-26NOV050015-15") =
        UpDownLive.Reading(ticker, sec, 1_000L * sec, up, 0.5, 0.5, yesAsk, noAsk)

    @Test
    fun buysOnlyWhenTheChanceBeatsAskFeeAndCut() {
        // 60% against a 55c ask: fee 1.73c, cut 1c -> 3.3c of edge: buy YES.
        assertEquals("YES", UpDownBettor.choose(0.60, 0.55, 0.46)?.first)
        // 56% against 55c: under the fee.
        assertNull(UpDownBettor.choose(0.56, 0.55, 0.46))
        // 30% UP is 70% DOWN against a 60c NO ask: buy NO.
        assertEquals("NO", UpDownBettor.choose(0.30, 0.71, 0.60)?.first)
        // Never above 85c, never under 10c.
        assertNull(UpDownBettor.choose(0.99, 0.86, 0.15))
        assertNull(UpDownBettor.choose(0.20, 0.09, 0.92))
    }

    @Test
    fun theHighestPriceStillLeavesTheCut() {
        val p = UpDownBettor.maxPayable(0.60)
        assertEquals(UpDownBettor.CUT, 0.60 - p - UpDownBettor.fee(p), 1e-6)
    }

    @Test
    fun oneLookAMinuteAndSettlement() {
        var saved = ""
        val b = UpDownBettor(persist = { saved = it }, depth = { _, _ -> listOf(0.55 to 40.0, 0.56 to 500.0) })
        assertNull(b.onReading(reading(10, 0.70, 0.55, 0.46), 0)) // before second 20
        val bet = b.onReading(reading(25, 0.70, 0.55, 0.46), 1)
        assertNotNull(bet)
        assertEquals(UpDownBettor.CONTRACTS, bet!!.contracts)
        assertTrue(bet.price in 0.55..0.56)
        assertNull(b.onReading(reading(70, 0.70, 0.55, 0.46), 2)) // same minute
        assertNotNull(b.onReading(reading(81, 0.70, 0.55, 0.46), 3)) // next minute
        assertEquals(setOf("KXBTC15M-26NOV050015-15"), b.openTickers())
        b.onSettled("KXBTC15M-26NOV050015-15", "yes")
        val done = b.bets()
        assertTrue(done.all { it.result == "YES" && (it.pnlUsd ?: 0.0) > 0 })
        assertTrue(b.openTickers().isEmpty())
        assertEquals(2, UpDownBettor.parse(saved).size)
        b.enabled = false
        assertNull(b.onReading(reading(145, 0.70, 0.55, 0.46), 4))
    }

    @Test
    fun noFillWhereTheEdgeIsGone() {
        // The book offers only at 62c and up: the model's 60% cannot pay that.
        val b = UpDownBettor(depth = { _, _ -> listOf(0.62 to 1000.0) })
        assertNull(b.onReading(reading(25, 0.60, 0.55, 0.46), 1))
    }
}
