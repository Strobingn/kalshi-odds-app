package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.domain.withEdgeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DipHunterModelTest {
    @Test
    fun predictionInUnitIntervalAndSumsToOne() {
        val model = DipHunterModel(context = null)
        val now = System.currentTimeMillis()
        val close = now + 600_000
        for (i in 0 until 12) {
            val mid = 0.40 + i * 0.02
            model.predict("KXBTC15M-TEST", mid, volume = 100_000.0, closeEpochMs = close, nowMs = now + i * 800L)
        }
        val p = model.predict("KXBTC15M-TEST", 0.70, 80_000.0, close, now + 10_000)
        assertTrue(p.yes in 0.02..0.98)
        assertTrue(p.no in 0.02..0.98)
        assertTrue(kotlin.math.abs(p.yes + p.no - 1.0) < 1e-5)
        assertTrue(p.note.isNotEmpty())
    }

    @Test
    fun featureVectorHasEightFeaturesInDocumentedOrder() {
        assertEquals(8, FeatureVector.SIZE)
        assertEquals("mid_price", FeatureVector.NAMES[0])
        assertEquals("series_id", FeatureVector.NAMES[6])
        assertEquals(0, FeatureVector.IDX_NO)
        assertEquals(1, FeatureVector.IDX_YES)
        assertEquals(0f, FeatureVector.seriesId("KXBTC15M-FOO"))
        assertEquals(1f, FeatureVector.seriesId("KXWTI15M-FOO"))
    }

    @Test
    fun fallbackForwardProducesSoftmax() {
        val x = FloatArray(8) { 0.1f * it }
        val out = FallbackWeights.forward(x)
        assertEquals(2, out.size)
        val sum = out[0] + out[1]
        assertTrue(sum in 0.99f..1.01f)
        assertTrue(out[0] in 0f..1f)
        assertTrue(out[1] in 0f..1f)
    }

    @Test
    fun concurrentPredictDoesNotThrow() {
        val model = DipHunterModel(context = null)
        val now = System.currentTimeMillis()
        val close = now + 600_000
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (0 until 8).map { t ->
            Thread {
                try {
                    repeat(40) { i ->
                        val p = model.predict(
                            ticker = "KXBTC15M-T$t",
                            marketMid = 0.40 + (i % 10) * 0.02,
                            volume = 10_000.0,
                            closeEpochMs = close,
                            nowMs = now + i * 50L + t
                        )
                        check(p.yes in 0.02..0.98)
                        check(p.no in 0.02..0.98)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
    }

    @Test
    fun buildFeaturesMatchesTrainingLayout() {
        val model = DipHunterModel(context = null)
        val now = 1_000_000L
        val close = now + 450_000L
        val feats = model.buildFeaturesForTest("KXBTC15M-X", 0.55, 25_000.0, close, now)
        assertEquals(8, feats.size)
        assertTrue(feats[0] in 0.54f..0.56f)
        assertTrue(feats[2] in 0.4f..0.6f)
        assertEquals(0f, feats[6])
    }
}

class EdgeMetricsTest {
    @Test
    fun edgeStanceAndAlert() {
        val base = com.dirk.kalshiodds.domain.MarketUiModel(
            ticker = "T",
            title = "t",
            subtitle = null,
            floorStrike = null,
            yesBid = 0.40,
            yesAsk = 0.42,
            noBid = null,
            noAsk = null,
            lastPrice = null,
            yesProbabilityPercent = 41.0,
            noProbabilityPercent = 59.0,
            aiYesPercent = 55.0,
            aiNoPercent = 45.0,
            volume = 1.0,
            volume24h = null,
            closeTimeLocal = null,
            status = "active",
            seriesLabel = "Bitcoin",
            spreadDollars = 0.02
        )
        val edged = base.withEdgeMetrics()
        assertEquals(14.0, edged.edgePp!!, 1e-6)
        assertTrue(edged.edgeAlert)
        assertTrue(edged.stance!!.contains("YES"))
    }
}
