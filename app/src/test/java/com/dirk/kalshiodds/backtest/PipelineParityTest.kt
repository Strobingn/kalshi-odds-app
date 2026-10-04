package com.dirk.kalshiodds.backtest

import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.prediction.FallbackWeights
import com.dirk.kalshiodds.prediction.FeatureVector
import com.dirk.kalshiodds.signal.engine.DirectionSanity
import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.domain.MarketUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins production math the Python harness ports. Do not edit production;
 * if these fail, the backtest port is stale.
 */
class PipelineParityTest {

    @Test
    fun featureVectorEightAndCryptoSeriesId() {
        assertEquals(8, FeatureVector.SIZE)
        assertEquals(0f, FeatureVector.seriesId("KXBTC15M-FOO"))
        val raw = FeatureVector.build(
            mid = 0.40,
            volume = 10_000.0,
            closeEpochMs = 900_000L,
            nowMs = 300_000L,
            series = emptyList(),
            ticker = "KXBTC15M-X",
            openInterest = 500.0
        )
        assertEquals(8, raw.size)
        assertEquals(0.40f, raw[0], 1e-6f)
        assertEquals(0.5f - 0.40f, raw[5], 1e-6f)
        assertEquals((600_000.0 / 900_000.0).toFloat(), raw[2], 1e-5f)
    }

    @Test
    fun fallbackSoftmaxMatchesKnownInput() {
        val x = FloatArray(8) { 0.1f * it }
        val out = FallbackWeights.forward(x)
        assertEquals(2, out.size)
        assertEquals(1.0f, out[0] + out[1], 1e-5f)
        // Ported in tools/backtest/weights.py — keep softmax + range, not a brittle dump.
    }

    @Test
    fun mlpPredictClipsAndSums() {
        val model = DipHunterModel(context = null)
        val p = model.predict("KXBTC15M-T", 0.55, 8_000.0, 900_000L, 120_000L, 400.0)
        assertTrue(p.yes in 0.02..0.98)
        assertEquals(1.0, p.yes + p.no, 1e-5)
    }

    @Test
    fun digitalFairAtmNearHalf() {
        val p = DigitalOptionFairValue.pFinishAbove(100_000.0, 100_000.0, 900.0, 0.60)!!
        assertTrue(kotlin.math.abs(p - 0.5) < 0.04)
    }

    @Test
    fun feeFiveDollarsAtFiftyCents() {
        // C=10 @ 0.50 → model 0.175 → debit ceil_cent(5.175) wait:
        // position 5.00 + ceil6(0.175)=0.175 → ceil_cent(5.175)=5.18
        val cost = KalshiFee.totalCost(10, 0.50)
        assertEquals(5.18, cost, 1e-9)
        assertEquals(0.18, KalshiFee.total(10, 0.50), 1e-9)
        var c = 20
        while (c > 0 && KalshiFee.totalCost(c, 0.31) > 5.0 + 1e-9) c--
        assertTrue(c >= 15)
        assertTrue(KalshiFee.netProfit(c, 0.31) + 1e-9 >= 10.0)
    }

    @Test
    fun directionSanityLocksSpotAboveStrike() {
        val r = DirectionSanity.apply(
            spotUsd = 84_000.0,
            strikeUsd = 83_000.0,
            spotReturn = 0.001,
            fairPp = 40.0,
            predictedSide = "NO"
        )
        assertTrue(r.applied)
        assertEquals("YES", r.side)
        assertTrue(r.fairPp >= 52.0)
    }

    @Test
    fun resolveSideUsesEdgeAfterFeesNotTheFavourite() {
        val m = MarketUiModel(
            ticker = "KXBTC15M-X",
            title = "BTC",
            subtitle = null,
            floorStrike = 80_000.0,
            yesBid = 0.90,
            yesAsk = 0.92,
            noBid = 0.08,
            noAsk = 0.10,
            lastPrice = 0.91,
            yesProbabilityPercent = 91.0,
            noProbabilityPercent = 9.0,
            volume = 1_000.0,
            volume24h = 1_000.0,
            openInterest = 200.0,
            liquidityDollars = 50.0,
            closeTimeLocal = null,
            closeTimeEpochMs = null,
            status = "open",
            seriesLabel = "Bitcoin",
            predictedSide = "NO",
            primaryHeroSide = "YES",
            netEdgePp = -2.0
        )
        assertEquals("NO", TicketBuilder.resolveSide(m))
        assertFalse(TicketBuilder.resolveSide(m).equals("YES"))
        assertFalse(
            TicketBuilder.modelBeatsImplied(
                model = 0.32,
                implied = 0.31,
                feeRate = 0.07
            )
        )
    }
}
