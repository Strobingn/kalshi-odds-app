package com.dirk.kalshiodds.signal.d3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class D3StrategyTest {

    private val et = ZoneId.of("America/New_York")
    private val close = ZonedDateTime.of(2026, 9, 30, 17, 0, 0, 0, et).toInstant().toEpochMilli()
    private val active = ZonedDateTime.of(2026, 9, 30, 15, 0, 0, 0, et).toInstant().toEpochMilli()
    private val waiting = ZonedDateTime.of(2026, 9, 30, 13, 0, 0, 0, et).toInstant().toEpochMilli()
    private val closed = ZonedDateTime.of(2026, 9, 30, 16, 0, 0, 0, et).toInstant().toEpochMilli()

    @Test
    fun windowIsFourteenToSixteenEt() {
        assertTrue(D3Window.closeIsFivePmEt(close))
        assertEquals(D3Phase.WAITING, D3Window.phase(waiting, close))
        assertEquals(D3Phase.ACTIVE, D3Window.phase(active, close))
        assertEquals(D3Phase.CLOSED, D3Window.phase(closed, close))
        assertEquals(D3Phase.CLOSED, D3Window.phase(close, close))
    }

    @Test
    fun favouriteAskInBandImprovesBidWhenSpreadWide() {
        val q = quote(yesBid = 0.88, yesAsk = 0.91, noBid = 0.09, noAsk = 0.12)
        val (side, ask) = D3Strategy.favourite(q)!!
        assertEquals("YES", side)
        assertEquals(0.91, ask, 1e-9)
        assertTrue(D3Strategy.inBand(ask))
        val bid = D3Strategy.bidPrice(0.88, 0.91)!!
        assertEquals(0.89, bid, 1e-9)
    }

    @Test
    fun tightSpreadJoinsBestBid() {
        val bid = D3Strategy.bidPrice(0.90, 0.91)!!
        assertEquals(0.90, bid, 1e-9)
    }

    @Test
    fun outsideBandDoesNotQualify() {
        val q = quote(yesBid = 0.97, yesAsk = 0.99, noBid = 0.01, noAsk = 0.03)
        assertNull(
            D3Strategy.evaluate(
                D3Strategy.Inputs(quote = q, nowMs = active, bankrollUsd = 1_000.0, liveCapUsd = 10.0)
            )
        )
    }

    @Test
    fun activeFavouriteFiresOncePerStrike() {
        val q = quote(yesBid = 0.88, yesAsk = 0.91, noBid = 0.09, noAsk = 0.12, yesAskSize = 40.0)
        val first = D3Strategy.evaluate(
            D3Strategy.Inputs(quote = q, nowMs = active, bankrollUsd = 1_000.0)
        )
        assertNotNull(first)
        assertEquals("YES", first!!.side)
        assertEquals(0.89, first.bidPrice, 1e-9)
        assertNull(
            D3Strategy.evaluate(
                D3Strategy.Inputs(quote = q, nowMs = active, alreadyTakenToday = true, bankrollUsd = 1_000.0)
            )
        )
        assertNull(
            D3Strategy.evaluate(
                D3Strategy.Inputs(quote = q, nowMs = waiting, bankrollUsd = 1_000.0)
            )
        )
    }

    @Test
    fun makerFeeComesFromSeriesScheduleNotHardcodedZero() {
        val lookedUp = D3Fees.fromSeries("quadratic", 1.0, makerMultiplier = null)
        assertEquals("quadratic", lookedUp.feeType)
        assertEquals(0.0, lookedUp.makerMultiplier, 1e-12)
        assertEquals(0.0, lookedUp.makerFeeRate, 1e-12)
        assertEquals(0.0, D3Fees.makerFeeUsd(10, 0.90, lookedUp), 1e-12)
        val withMaker = D3Fees.fromSeries("quadratic", 1.0, makerMultiplier = 1.0)
        assertTrue(D3Fees.makerFeeUsd(10, 0.90, withMaker) > 0.0)
    }

    @Test
    fun evidenceCopyIsStaticResearchText() {
        assertTrue(D3Copy.EVIDENCE.contains("297 fills"))
        assertTrue(D3Copy.EVIDENCE.contains("284-13"))
        assertTrue(D3Copy.EVIDENCE.contains("+$137.66"))
        assertTrue(D3Copy.EVIDENCE.contains("115-4"))
        assertTrue(D3Copy.EVIDENCE.contains("+$70.52"))
        assertTrue(D3Copy.EVIDENCE.contains("2026-09-30"))
        assertEquals("BTC daily 5 PM favourite (D3)", D3Copy.TITLE)
    }

    @Test
    fun honestQueueRequiresTradeThroughOrAheadConsumed() {
        val placed = active
        val bid = 0.90
        val ahead = 5.0
        assertFalse(
            D3FillMath.filled(
                "YES",
                bid,
                ahead,
                listOf(D3TradePrint("T", 0.90, 3.0, placed + 1)),
                placed
            )
        )
        assertTrue(
            D3FillMath.filled(
                "YES",
                bid,
                ahead,
                listOf(D3TradePrint("T", 0.90, 6.0, placed + 1)),
                placed
            )
        )
        assertTrue(
            D3FillMath.filled(
                "YES",
                bid,
                ahead,
                listOf(D3TradePrint("T", 0.89, 1.0, placed + 1)),
                placed
            )
        )
        assertFalse(
            D3FillMath.filled(
                "YES",
                bid,
                ahead,
                listOf(D3TradePrint("T", 0.89, 1.0, placed - 1)),
                placed
            )
        )
    }

    private fun quote(
        yesBid: Double,
        yesAsk: Double,
        noBid: Double,
        noAsk: Double,
        yesAskSize: Double? = 20.0
    ) = D3Quote(
        ticker = "KXBTCD-26SEP3017-T90000",
        eventTicker = "KXBTCD-26SEP3017",
        title = "Bitcoin price",
        subtitle = "$90,000 or above",
        strikeUsd = 90_000.0,
        yesBid = yesBid,
        yesAsk = yesAsk,
        noBid = noBid,
        noAsk = noAsk,
        yesAskSize = yesAskSize,
        yesBidSize = 12.0,
        closeTimeEpochMs = close,
        status = "active"
    )
}
