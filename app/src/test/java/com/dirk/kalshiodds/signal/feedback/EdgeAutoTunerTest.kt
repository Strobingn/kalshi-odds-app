package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAutoTunerTest {

    @Test
    fun notEnoughSamplesKeepsAutoTuningSittingOut() {
        val samples = (0 until 10).map { i ->
            EdgeAutoTuner.Sample(0.70, 0.50, outcomeYes = true, edgeAfterFeesPp = 8.0)
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 30)
        assertFalse(r.enoughSamples)
        assertTrue(r.sitOut)
        assertTrue(r.reason.contains("need"))
    }

    @Test
    fun sitsOutWhenModelLosesToMarket() {
        val samples = (0 until 40).map { i ->
            val yes = i % 2 == 0
            EdgeAutoTuner.Sample(
                modelYes = if (yes) 0.20 else 0.80,
                marketMid = if (yes) 0.80 else 0.20,
                outcomeYes = yes,
                edgeAfterFeesPp = 12.0,
                timestampMs = i * 86_400_000L
            )
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 30, minDays = 14, bootstrapReps = 100)
        assertTrue(r.enoughSamples)
        assertTrue(r.sitOut)
        assertTrue(r.modelBrier!! > r.marketBrier!!)
        assertTrue(r.reason.contains("hasn't beaten Kalshi's prices"))
        assertFalse(r.reason.contains("Brier"))
        assertFalse(r.reason.contains("log-loss"))
        assertFalse(r.reason.contains("Sit out"))
    }

    @Test
    fun picksThresholdThatMaximizesEv() {
        val samples = buildList {
            repeat(20) {
                add(EdgeAutoTuner.Sample(0.80, 0.40, true, edgeAfterFeesPp = 12.0, timestampMs = it * 86_400_000L))
            }
            repeat(20) {
                add(EdgeAutoTuner.Sample(0.55, 0.52, false, edgeAfterFeesPp = 1.0, timestampMs = (it + 20) * 86_400_000L))
            }
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 20, minDays = 14, bootstrapReps = 100)
        assertTrue(r.enoughSamples)
        assertFalse(r.sitOut)
        assertTrue(r.thresholdPp >= 1.5)
        assertTrue((r.evAtThreshold ?: 0.0) > 5.0)
    }

    @Test
    fun fromEntriesMapsSettledOnly() {
        val rows = (0 until 30).map { i ->
            PredictionLogEntry(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                predictedYes = 0.70,
                predictedNo = 0.30,
                marketMid = 0.40,
                timestampMs = i.toLong(),
                closeTimeMs = i.toLong(),
                outcome = if (i < 25) "yes" else "void",
                predictedSide = "YES",
                edgePp = 10.0
            )
        }
        val r = EdgeAutoTuner.fromEntries(rows, minSamples = 20, minDays = 1, bootstrapReps = 100)
        assertEquals(25, r.n)
        assertTrue(r.enoughSamples)
    }
}
