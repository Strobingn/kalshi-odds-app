package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAutoTunerTest {

    @Test
    fun notEnoughSamplesDoesNotSitOut() {
        val samples = (0 until 10).map { i ->
            EdgeAutoTuner.Sample(0.70, 0.50, outcomeYes = true, edgeAfterFeesPp = 8.0)
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 30)
        assertFalse(r.enoughSamples)
        assertFalse(r.sitOut)
        assertTrue(r.reason.contains("Not enough"))
    }

    @Test
    fun sitsOutWhenModelLosesToMarket() {
        val samples = (0 until 40).map { i ->
            val yes = i % 2 == 0
            EdgeAutoTuner.Sample(
                modelYes = if (yes) 0.20 else 0.80,
                marketMid = if (yes) 0.80 else 0.20,
                outcomeYes = yes,
                edgeAfterFeesPp = 12.0
            )
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 30)
        assertTrue(r.enoughSamples)
        assertTrue(r.sitOut)
        assertTrue(r.modelBrier!! > r.marketBrier!!)
        assertTrue(r.reason.contains("hasn't beaten Kalshi's prices"))
        assertFalse(r.reason.contains("Brier"))
        assertFalse(r.reason.contains("log-loss"))
        assertFalse(r.reason.contains("Sit out"))
    }

    @Test
    fun picksThresholdThatMaximizesEvAndSurvivesDayResampling() {
        // 8 UTC days of genuinely winning bets (model right, price cheap):
        // the day-block bootstrap keeps a positive 10th-percentile EV.
        val samples = buildList {
            var day = 0L
            repeat(64) { i ->
                if (i % 8 == 0) day++
                val strong = i % 4 != 0
                if (strong) {
                    add(EdgeAutoTuner.Sample(0.80, 0.40, true, edgeAfterFeesPp = 12.0, closeDay = day))
                } else {
                    add(EdgeAutoTuner.Sample(0.55, 0.52, false, edgeAfterFeesPp = 1.0, closeDay = day))
                }
            }
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 20)
        assertTrue(r.enoughSamples)
        assertFalse(r.sitOut)
        assertTrue(r.thresholdPp >= 1.5)
        assertTrue((r.evAtThreshold ?: 0.0) > 5.0)
        assertTrue("10th-percentile EV must survive day resampling", (r.evP10 ?: 0.0) > 0.0)
    }

    @Test
    fun sitsOutWhenEvDoesNotSurviveDayResampling() {
        // One lucky day carries the whole EV: most days lose. The full-sample
        // EV is positive but the day-block bootstrap 10th percentile is not.
        val samples = buildList {
            repeat(10) {
                add(EdgeAutoTuner.Sample(0.90, 0.20, true, edgeAfterFeesPp = 40.0, closeDay = 7))
            }
            repeat(60) { i ->
                val day = 1L + (i % 6)
                // Slight favorite priced about right: small negative EV.
                add(EdgeAutoTuner.Sample(0.60, 0.58, false, edgeAfterFeesPp = 40.0, closeDay = day))
            }
        }
        val r = EdgeAutoTuner.tune(samples, minSamples = 20)
        assertTrue(r.sitOut)
        assertTrue(r.reason.contains("resampling"))
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
        val r = EdgeAutoTuner.fromEntries(rows, minSamples = 20)
        assertEquals(25, r.n)
        assertTrue(r.enoughSamples)
    }
}
