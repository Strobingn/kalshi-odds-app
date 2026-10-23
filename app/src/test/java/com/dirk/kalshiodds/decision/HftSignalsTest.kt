package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.api.KalshiTokenBucket
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import com.dirk.kalshiodds.signal.trade.ClientOrderIds
import com.dirk.kalshiodds.signal.ws.OrderbookSequencer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HftSignalsTest {
    @Before fun reset() { ModelFilter.resetForTest(); HftSignals.reset() }
    @After fun clear() { ModelFilter.resetForTest(); HftSignals.reset() }

    @Test fun studentTHasFatterTailsThanTheGaussian() {
        assertEquals(0.5, HftSignals.studentTCdf(0.0), 1e-9)
        assertEquals(HftSignals.studentTCdf(2.5), 1.0 - HftSignals.studentTCdf(-2.5), 1e-9)
        val t = HftSignals.studentTCdf(3.0)
        val g = DigitalOptionFairValue.normCdf(3.0)
        assertTrue("t=$t gaussian=$g", t < g)
        assertTrue(t > 0.90)
        val fair = HftSignals.studentTFair(100_000.0, 100_000.0, 3e-5, 600.0, "YES")!!
        assertEquals(0.5, fair, 0.02)
        assertEquals(1.0 - fair, HftSignals.studentTFair(100_000.0, 100_000.0, 3e-5, 600.0, "NO")!!, 1e-9)
    }

    @Test fun pinRiskBlocksTheTakerNearTheStrike() {
        assertTrue(HftSignals.pinRisk(100_000.0, 100_020.0, 40.0))
        assertFalse(HftSignals.pinRisk(100_000.0, 102_000.0, 40.0))
        assertFalse(HftSignals.pinRisk(100_000.0, 100_020.0, 400.0))
        ModelFilter.onClosed("sf41-logistic", 0.20, 600.0, 1L, DoubleArray(ModelFilter.FEATURES))
        ModelFilter.onClosed("sf41-logistic", -0.05, 600.0, 2L, DoubleArray(ModelFilter.FEATURES))
        val q = ScalpRule.Quote(
            "KXBTC15M-26OCT221200-00", 1_700_000_000_000L, 1_700_000_030_000L, 1_700_000_000_000L,
            0.48, 20.0, 0.52, 20.0, 100_000.0, 100_010.0, 3e-5
        )
        val d = ModelFilter.decide(ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }, q, "YES", 0.52, 0.55)
        assertFalse(d.enter)
        assertTrue(d.reason, d.reason.contains("pin risk"))
    }

    @Test fun ofiMatchesTheContKukanovStoikovIncrement() {
        val e = HftSignals.ofi(0.49, 10.0, 0.51, 8.0, 0.50, 12.0, 0.51, 8.0)
        // bid up: +12, bid not ≤ so previous bid size is not subtracted, ask unchanged: −8 + 8
        assertEquals(12.0, e, 1e-9)
        assertEquals(12.0 / 20.0, HftSignals.ofiOverDepth(e, 12.0, 8.0), 1e-9)
        val flat = HftSignals.ofi(0.50, 5.0, 0.52, 5.0, 0.50, 5.0, 0.52, 5.0)
        assertEquals(0.0, flat, 1e-9)
    }

    @Test fun vwapSlopeAndCrossCoinStress() {
        val px = listOf(0.40, 0.41, 0.42, 0.50, 0.55, 0.60)
        val sz = List(px.size) { 1.0 }
        assertTrue(HftSignals.momentum(HftSignals.vwapZ(px, sz)))
        HftSignals.noteSpot("BTC", 0L, 100.0)
        HftSignals.noteSpot("ETH", 0L, 100.0)
        HftSignals.noteSpot("SOL", 0L, 100.0)
        for (i in 1..8) {
            val up = 100.0 * (1.0 + i * 0.01)
            HftSignals.noteSpot("BTC", i * 60_000L, up)
            HftSignals.noteSpot("ETH", i * 60_000L, up * 1.001)
            HftSignals.noteSpot("SOL", i * 60_000L, up * 0.999)
        }
        assertTrue(HftSignals.crossCorr() > HftSignals.STRESS_CORR)
    }

    @Test fun reservationSpreadAndBatchedFee() {
        val r = HftSignals.reservation(0.50, inventory = 2.0, gamma = 40.0, sigma = 0.015, tauS = 600.0)
        assertTrue(r < 0.50)
        val spr = HftSignals.optimalSpread(40.0, 0.015, 600.0, 80.0, 0.50)
        val manual = 40.0 * HftSignals.dampedSigma(0.015, 0.50).let { it * it } * (600.0 / (365.25 * 24 * 3600)) +
            (2.0 / 40.0) * kotlin.math.ln(1.0 + 40.0 / 80.0)
        assertEquals(manual.coerceIn(0.01, 0.20), spr, 1e-9)
        assertTrue(HftSignals.dampedSigma(0.02, 0.01) < HftSignals.dampedSigma(0.02, 0.50))
        val one = HftSignals.orderFee(1, 0.50)
        val ten = HftSignals.orderFee(10, 0.50)
        assertEquals(0.02, one, 1e-9)
        assertEquals(0.18, ten, 1e-9)
        assertTrue(ten < one * 10)
        val half = HftSignals.kellyFraction(0.60, gain = 0.04, loss = 0.02, pin = false, tauS = 400.0)
        val quarter = HftSignals.kellyFraction(0.60, gain = 0.04, loss = 0.02, pin = true, tauS = 400.0)
        assertEquals(half / 2.0, quarter, 1e-9)
        assertTrue(half in 0.2..0.5)
    }

    @Test fun fixedSizeStaysDefaultAndKellyIsOptional() {
        assertEquals(ModelFilter.SizeMode.FIXED, ModelFilter.sizeMode())
        assertEquals(10, ModelFilter.sized(10, 0.25, 10))
        ModelFilter.setSizeMode(ModelFilter.SizeMode.KELLY)
        assertEquals(3, ModelFilter.sized(10, 0.30, 10))
    }

    @Test fun tokenBucket429StartsBetween50And100ms() {
        val b = KalshiTokenBucket(nowMs = { 0L }, randomUnit = { 0.0 })
        assertEquals(50L, b.backoffDelayMs(0, null, 0.0))
        assertEquals(100L, b.backoffDelayMs(0, null, 1.0))
        assertEquals(100L, b.backoffDelayMs(1, null, 0.0))
        assertTrue(b.backoffDelayMs(1, null, 1.0) in 100L..200L)
        assertEquals(50L, b.noteRead429(null))
    }

    @Test fun seqGapDropsTheBookUntilASnapshot() {
        val seq = OrderbookSequencer()
        assertEquals(OrderbookSequencer.Verdict.APPLY, seq.accept(1, 1))
        assertEquals(OrderbookSequencer.Verdict.GAP, seq.accept(1, 4))
        val book = TickBook()
        val ticker = "KXBTC15M-26OCT221200-00"
        book.applySnapshot(ticker, listOf(0.48 to 5.0), listOf(0.50 to 5.0), seq = 1)
        assertTrue(book.hasSnapshot(ticker))
        book.invalidateBooks(listOf(ticker))
        assertFalse(book.hasSnapshot(ticker))
    }

    @Test fun clientOrderIdIsUuid4() {
        val id = ClientOrderIds.next()
        assertTrue(ClientOrderIds.isUuid4(id))
        assertFalse(ClientOrderIds.isUuid4("not-a-uuid"))
    }
}
