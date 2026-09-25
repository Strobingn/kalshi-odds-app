package com.dirk.kalshiodds.backtest

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reproduces the in-app "0/5 · Brier 0.003" without touching production.
 *
 * Hit rate uses predictedSide; Brier uses (predictedYes − 1_{YES})².
 * Fading a 94.5¢ YES that settles YES yields 0/5 and Brier ≈ 0.003.
 *
 * Fix (describe only): PredictionLogStore.kt:184-193 and
 * ScorecardMetrics.kt:257-269 should either score the side-probability
 * or label P(YES) Brier separately from side hit rate.
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
        // (0.945 - 1)^2 = 0.003025
        assertTrue("Brier should print as 0.003 with %.3f", abs(w.brier!! - 0.003025) < 1e-9)
        assertEquals("0.003", String.format(java.util.Locale.US, "%.3f", w.brier))

        val sideBriers = rows.map { e ->
            val pSide = 1.0 - e.predictedYes
            val ySide = 0.0 // faded YES, lost
            (pSide - ySide) * (pSide - ySide)
        }
        assertTrue("side Brier is terrible (~0.89), not 0.003", sideBriers.average() > 0.8)
    }
}
