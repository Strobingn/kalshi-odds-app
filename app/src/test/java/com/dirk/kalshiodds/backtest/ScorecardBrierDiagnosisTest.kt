package com.dirk.kalshiodds.backtest

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ForecastUnits
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pre-0.3.12 mix printed 0/5 hits next to P(YES) Brier 0.003.
 * Production now scores hit + [ScorecardMetrics.WindowStats.brier] on the
 * picked side; P(YES) Brier is [ScorecardMetrics.WindowStats.pUpBrier].
 */
class ScorecardBrierDiagnosisTest {

    @Test
    fun fiveFadedFavoritesLookLikeMiracleBrier() {
        val rows = (0 until 5).map { i ->
            PredictionLogEntry(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                predictedYes = 0.945,
                predictedNo = 0.055,
                marketMid = 0.96,
                timestampMs = 1_000L * i,
                closeTimeMs = 2_000L * i,
                outcome = "yes",
                score = 0,
                brier = (0.945 - 1.0) * (0.945 - 1.0),
                predictedSide = "NO",
                edgePp = -1.5
            )
        }
        val w = ScorecardMetrics.window(rows)
        assertEquals("0/5 side hits", 0, w.hits)
        assertEquals(5, w.total)
        assertEquals(0, rows.count { ForecastUnits.hit(it) })
        val side = rows.map { ForecastUnits.sideBrier(it) }.average()
        val yes = rows.map { ForecastUnits.brier(it) }.average()
        assertTrue("picked-side Brier is ~0.893, not the P(YES) 0.003", abs(side - 0.893) < 0.002)
        assertEquals((0.945 - 1.0) * (0.945 - 1.0), yes, 1e-9)
        assertEquals(side, w.brier!!, 1e-9)
        assertEquals(yes, w.pUpBrier!!, 1e-9)
        assertEquals("0.893", String.format(java.util.Locale.US, "%.3f", w.brier))
        assertEquals("0.003", String.format(java.util.Locale.US, "%.3f", w.pUpBrier))
    }
}
