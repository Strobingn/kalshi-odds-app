package com.dirk.kalshiodds.signal.trend

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's late comeback: the underdog at 20–40¢ up 4¢ in 30 s, minutes 10–13, Kalshi prices only. */
class LateComebackTest {

    @Test
    fun theUnderdogThatIsClimbingLateIsTheCall() {
        // YES is the underdog: its mid went 26¢ → 31¢ in 30 s, ask 32¢, 4:00 left.
        val up = LateComebackRule.signal(240L, yesAsk = 0.32, noAsk = 0.70, yesMidNow = 0.31, yesMidThen = 0.26)!!
        assertEquals("YES", up.side)
        assertEquals(0.32, up.ask, 0.0)
        // NO is the underdog when YES falls: YES mid 74¢ → 69¢, NO ask 32¢.
        assertEquals("NO", LateComebackRule.signal(200L, 0.70, 0.32, 0.69, 0.74)!!.side)
        assertNull("only 3¢", LateComebackRule.signal(240L, 0.32, 0.70, 0.29, 0.26))
        assertNull("41¢ is no underdog", LateComebackRule.signal(240L, 0.41, 0.61, 0.40, 0.35))
        assertNull("19¢ is too far behind", LateComebackRule.signal(240L, 0.19, 0.83, 0.18, 0.13))
        assertNull("minute 9 (5:01 left)", LateComebackRule.signal(301L, 0.32, 0.70, 0.31, 0.26))
        assertNotNull("minute 10 (5:00 left)", LateComebackRule.signal(300L, 0.32, 0.70, 0.31, 0.26))
        assertNotNull("2:00 left", LateComebackRule.signal(120L, 0.32, 0.70, 0.31, 0.26))
        assertNull("1:59 left", LateComebackRule.signal(119L, 0.32, 0.70, 0.31, 0.26))
        assertNull("no mid 30 s ago", LateComebackRule.signal(240L, 0.32, 0.70, 0.31, null))
        val d = LateComebackRule.decide("KXBTC15M-26OCT201215-15", 0L, up, 0.32, 0.70)!!
        assertTrue("$5 all-in", d.sized.costUsd <= 5.0 && d.sized.contracts >= 14)
    }

    @Test
    fun theTapeReadsTheMidFromThirtySecondsAgo() {
        val tape = MidTape()
        for (s in 0..60) tape.note("T", s * 1000L + 300L, 0.20 + s * 0.001)
        assertEquals(0.20 + 30 * 0.001, tape.midAgo("T", 60_300L, 30)!!, 1e-12)
        assertNull("nothing that old", tape.midAgo("T", 60_300L, 90))
        assertNull(tape.midAgo("other", 60_300L, 30))
        tape.note("T", 61_000L, Double.NaN)
        assertEquals("a bad mid is skipped", 0.26, tape.midAgo("T", 61_000L, 0)!!, 1e-12)
    }

    @Test
    fun theComebackGoesBeforeClearLeadOnTheCard() {
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val m = market(yesAsk = 0.32, noAsk = 0.70).copy(comebackSide = "YES", comebackAsk = 0.32)
        val call = BetCall.decide(m, ctx)
        assertEquals(BetCall.Headline.BET_UP, call.headline)
        assertTrue(call.ticket!!.comeback && call.ticket!!.clearLead)
        assertTrue(call.ticket!!.gateNote!!.startsWith("Late comeback"))
        val off = TicketBuilder.Context(settings = SignalSettings(lateComebackEnabled = false), alertsPaused = false)
        assertEquals("switched off: no call", BetCall.Headline.NO_BET, BetCall.decide(m, off).headline)
    }

    private fun market(yesAsk: Double, noAsk: Double) = MarketUiModel(
        ticker = "KXBTC15M-26OCT201215-15",
        title = "BTC",
        subtitle = null,
        floorStrike = 100_000.0,
        yesBid = yesAsk - 0.01,
        yesAsk = yesAsk,
        noBid = noAsk - 0.01,
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        aiYesPercent = 50.0,
        aiNoPercent = 50.0,
        volume = 2000.0,
        volume24h = 2000.0,
        openInterest = 200.0,
        liquidityDollars = 8000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 240_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = "YES",
        primaryHeroSide = "YES"
    )
}
