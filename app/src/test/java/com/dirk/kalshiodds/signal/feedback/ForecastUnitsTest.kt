package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.9 scorecard: 0/5 hits (edge-sign predictedSide) + Brier 0.003
 * (calibrated p≈0.945). Same five settlements must agree after the fix.
 */
class ForecastUnitsTest {

    @Test
    fun percentAndUnitProbabilitiesNormalize() {
        assertEquals(0.44, ForecastUnits.probability01(44.0), 1e-9)
        assertEquals(0.44, ForecastUnits.probability01(0.44), 1e-9)
        assertEquals(0.945, ForecastUnits.probability01(0.945), 1e-9)
    }

    @Test
    fun fiveSettlementsHitsAndBrierUseTheSameProbability() {
        val rows = (0 until 5).map { i ->
            PredictionLogEntry(
                ticker = "KXETH15M-$i",
                series = "KXETH15M",
                predictedYes = 0.945,
                predictedNo = 0.055,
                marketMid = 0.63,
                timestampMs = i.toLong(),
                closeTimeMs = i.toLong(),
                outcome = "no",
                score = 0,
                brier = (0.945 - 0.0) * (0.945 - 0.0),
                predictedSide = "NO",
                edgePp = -8.0
            )
        }
        val hits = rows.count { ForecastUnits.hit(it) }
        val brier = rows.map { ForecastUnits.brier(it) }.average()
        assertEquals(0, hits)
        assertEquals((0.945 - 0.0) * (0.945 - 0.0), brier, 1e-9)
        val card = ScorecardMetrics.window(rows)
        assertEquals(0, card.hits)
        assertEquals(5, card.total)
        assertEquals(brier, card.brier!!, 1e-9)
        assertFalse("old mix was 0/5 + Brier 0.003 from p vs y in percent", abs(card.brier!! - 0.003) < 0.001)
    }

    @Test
    fun sideFromProbabilityIgnoresEdgeSign() {
        assertEquals("YES", ForecastUnits.sideFromProbability(0.945))
        assertEquals("NO", ForecastUnits.sideFromProbability(0.20))
        assertEquals("YES", ForecastUnits.sideFromProbability(94.5))
    }

    private fun abs(v: Double) = kotlin.math.abs(v)
}
