package com.dirk.kalshiodds.signal.trade

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakerCostTest {

    private val et = ZoneId.of("America/New_York")

    @Test
    fun priceBandsCoverZeroToOneHundredWithoutGaps() {
        var edge = 0
        for (b in TakerCost.PRICE_BANDS) {
            assertEquals(edge, b.loCents)
            assertTrue(b.hiCents > b.loCents)
            edge = b.hiCents
        }
        assertEquals(100, edge)
    }

    @Test
    fun timeBandsCoverTheWholeWindow() {
        var edge = 0L
        for (b in TakerCost.TIME_BANDS) {
            assertEquals(edge, b.loSec)
            edge = b.hiSec
        }
        assertTrue(edge > TakerCost.WINDOW_SECONDS)
        assertEquals("10–15 min", TakerCost.timeBand(900L)?.label)
        assertEquals("under 1 min", TakerCost.timeBand(0L)?.label)
        assertEquals("1–3 min", TakerCost.timeBand(60L)?.label)
        assertNull(TakerCost.timeBand(901L))
        assertNull(TakerCost.timeBand(-1L))
        assertNull(TakerCost.timeBand(null))
    }

    @Test
    fun priceBandEdgesBelongToTheUpperBand() {
        assertEquals("20–30¢", TakerCost.priceBand(0.25)?.label)
        assertEquals("30–40¢", TakerCost.priceBand(0.30)?.label)
        assertEquals("0–5¢", TakerCost.priceBand(0.001)?.label)
        assertEquals("95–100¢", TakerCost.priceBand(0.999)?.label)
        assertNull(TakerCost.priceBand(0.0))
        assertNull(TakerCost.priceBand(1.0))
        assertNull(TakerCost.priceBand(Double.NaN))
        assertNull(TakerCost.priceBand(null))
    }

    @Test
    fun studyNumbersMatchTheTapeReport() {
        // docs/tape-study-2026-10-04.md, taker ROI by price paid and by time left.
        assertEquals(-9.97, TakerCost.priceBand(0.25)!!.roiPct, 1e-9)
        assertEquals(-7.91, TakerCost.priceBand(0.45)!!.roiPct, 1e-9)
        assertEquals(-0.73, TakerCost.priceBand(0.85)!!.roiPct, 1e-9)
        assertEquals(0.04, TakerCost.priceBand(0.97)!!.roiPct, 1e-9)
        assertEquals(-5.22, TakerCost.timeBand(700L)!!.roiPct, 1e-9)
        assertEquals(-0.22, TakerCost.timeBand(120L)!!.roiPct, 1e-9)
        // No band in the study made money beyond noise.
        assertTrue(TakerCost.PRICE_BANDS.all { it.roiPct < TakerCost.FLAT_ROI_PCT })
        assertTrue(TakerCost.TIME_BANDS.all { it.roiPct < 0.0 })
    }

    @Test
    fun breakEvenIsAllInCostPerContract() {
        // 19 contracts at 25¢: position $4.75, fee ceil_cent(0.07·19·0.25·0.75) = $0.25, all-in $5.00.
        val fee = KalshiFee.total(19, 0.25)
        val s = TakerCost.of(price = 0.25, contracts = 19)!!
        assertEquals(0.25, fee, 1e-9)
        assertEquals(fee, s.feeUsd, 1e-9)
        assertEquals(25.0, s.impliedPct, 1e-9)
        assertEquals(5.00 / 19 * 100.0, s.breakEvenPct, 1e-6)
        assertEquals(5.0, s.feePctOfCost, 1e-6)
        assertTrue(s.breakEvenPct > s.impliedPct)
    }

    @Test
    fun ticketFiguresWinOverRecomputedOnes() {
        val s = TakerCost.of(price = 0.50, contracts = 9, allInUsd = 4.66, feeUsd = 0.16)!!
        assertEquals(0.16, s.feeUsd, 1e-9)
        assertEquals(4.66 / 9 * 100.0, s.breakEvenPct, 1e-6)
    }

    @Test
    fun nothingToPriceReturnsNull() {
        assertNull(TakerCost.of(price = null, contracts = 5))
        assertNull(TakerCost.of(price = 0.25, contracts = 0))
        assertNull(TakerCost.of(price = 1.0, contracts = 5))
        assertNull(TakerCost.of(price = 0.0, contracts = 5))
    }

    @Test
    fun linesSayWhatHappenedInPlainWords() {
        val dog = TakerCost.of(price = 0.25, contracts = 19, tteSeconds = 700L)!!
        assertEquals("Break-even: must win 26.3% of the time. The price says 25.0%.", dog.breakEvenLine)
        assertEquals("Fee $0.25 is 5.0% of this bet.", dog.feeLine)
        assertEquals("Buyers at 20–30¢ lost 10.0% of their stake on average.", dog.priceLine)
        assertEquals("With 10–15 min left, buyers lost 5.2% of their stake on average.", dog.timeLine)
        assertTrue(dog.warn)
        assertEquals(5, dog.lines.size)
        assertEquals(TakerCost.FOOTNOTE, dog.lines.last())

        val fav = TakerCost.of(price = 0.97, contracts = 5, tteSeconds = 120L)!!
        assertEquals("Buyers at 95–100¢ about broke even.", fav.priceLine)
        assertEquals("With 1–3 min left, buyers about broke even.", fav.timeLine)
        assertFalse(fav.warn)

        val noClock = TakerCost.of(price = 0.85, contracts = 5)!!
        assertNull(noClock.timeLine)
        assertFalse(noClock.warn)
        assertEquals(4, noClock.lines.size)
    }

    @Test
    fun outcomeWording() {
        assertEquals("lost 7.8% of their stake on average", TakerCost.outcome(-7.80))
        assertEquals("about broke even", TakerCost.outcome(0.04))
        assertEquals("about broke even", TakerCost.outcome(-0.22))
        assertEquals("made 1.5% on their stake on average", TakerCost.outcome(1.5))
    }

    @Test
    fun closeTimeComesFromTheTicker() {
        // KXBTC15M-26OCT041430-30 closed at 14:30 ET on 4 Oct 2026 (18:30 UTC).
        val expected = ZonedDateTime.of(2026, 10, 4, 14, 30, 0, 0, et).toInstant().toEpochMilli()
        assertEquals(expected, TakerCost.closeEpochMs("KXBTC15M-26OCT041430-30"))
        assertEquals(1791138600000L, expected)
        assertNull(TakerCost.closeEpochMs("KXBTC15M"))
        assertNull(TakerCost.closeEpochMs("KXBTC15M-26XXX041430-30"))
        assertNull(TakerCost.closeEpochMs("KXBTC15M-26OCT042530-30"))
        assertNull(TakerCost.closeEpochMs("KXBTCD-26OCT0414-T85000"))
    }

    @Test
    fun timeLeftOnlyInsideTheWindow() {
        val close = TakerCost.closeEpochMs("KXBTC15M-26OCT041430-30")!!
        assertEquals(700L, TakerCost.tteSeconds("KXBTC15M-26OCT041430-30", close - 700_000L))
        assertEquals(0L, TakerCost.tteSeconds("KXBTC15M-26OCT041430-30", close))
        assertNull(TakerCost.tteSeconds("KXBTC15M-26OCT041430-30", close + 1_000L))
        assertNull(TakerCost.tteSeconds("KXBTC15M-26OCT041430-30", close - 901_000L))
        assertNull(TakerCost.tteSeconds("nope", close))
    }

    @Test
    fun ticketSummarySkipsSellsAndBlockedTickets() {
        val close = TakerCost.closeEpochMs("KXBTC15M-26OCT041430-30")!!
        val buy = ticket()
        val s = TakerCost.of(buy, nowMs = close - 400_000L)
        assertNotNull(s)
        assertEquals("6–10 min", s!!.timeBand?.label)
        assertEquals("20–30¢", s.priceBand?.label)
        assertEquals(5.00 / 19 * 100.0, s.breakEvenPct, 1e-6)
        assertNull(TakerCost.of(buy.copy(kind = TicketKind.SELL), nowMs = close - 400_000L))
        assertNull(TakerCost.of(buy.copy(blockedReason = "Market closed"), nowMs = close - 400_000L))
    }

    private fun ticket() = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-26OCT041430-30",
        side = "YES",
        bookSide = "bid",
        stakeUsd = 5.0,
        limitPrice = 0.25,
        yesLimitPrice = 0.25,
        contracts = 19,
        estimatedFillUsd = 5.0,
        maxPayoutUsd = 19.0,
        estimatedAvgFill = 0.25,
        sizingNote = "test",
        kind = TicketKind.MANUAL,
        feeUsd = 0.25,
        allInUsd = 5.0
    )
}
