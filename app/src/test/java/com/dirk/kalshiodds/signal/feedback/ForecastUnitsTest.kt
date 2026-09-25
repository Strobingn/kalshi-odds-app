package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Scorecard display: hit = picked side vs settlement; side-Brier is not
 * the P(YES) Brier. The 0.3.11 mix scored P(YES)>0.5 as a hit and printed
 * (0.945−1)² ≈ 0.003 next to a fade that actually went 0/5.
 */
class ForecastUnitsTest {

    @Test
    fun percentAndUnitProbabilitiesNormalize() {
        assertEquals(0.44, ForecastUnits.probability01(44.0), 1e-9)
        assertEquals(0.44, ForecastUnits.probability01(0.44), 1e-9)
        assertEquals(0.945, ForecastUnits.probability01(0.945), 1e-9)
    }

    @Test
    fun fadeOfHighYesThatLosesIsZeroHitsAndSideBrierAboutPointEightNineThree() {
        val rows = (0 until 5).map { i ->
            row(
                ticker = "KXETH15M-$i",
                predictedYes = 0.945,
                outcome = "yes",
                predictedSide = "NO",
                score = 1,
                brier = 0.003
            )
        }
        val hits = rows.count { ForecastUnits.hit(it) }
        val sideBrier = rows.map { ForecastUnits.sideBrier(it) }.average()
        val yesBrier = rows.map { ForecastUnits.brier(it) }.average()
        assertEquals(0, hits)
        val pSide = 1.0 - 0.945
        val expectedSide = (pSide - 1.0) * (pSide - 1.0)
        assertEquals(expectedSide, sideBrier, 1e-9)
        assertTrue("side-Brier must be ~0.893, not the P(YES) 0.003", abs(sideBrier - 0.893) < 0.002)
        assertFalse(abs(sideBrier - 0.003) < 0.001)
        assertEquals((0.945 - 1.0) * (0.945 - 1.0), yesBrier, 1e-9)

        val card = ScorecardMetrics.window(rows)
        assertEquals(0, card.hits)
        assertEquals(5, card.total)
        assertEquals(sideBrier, card.brier!!, 1e-9)
        assertEquals(yesBrier, card.pUpBrier!!, 1e-9)
        assertFalse(card.showBrier)
    }

    @Test
    fun agreeingPickHitsAndSideBrierMatchesPickedProbability() {
        val rows = (0 until 5).map { i ->
            row(
                ticker = "KXBTC15M-$i",
                predictedYes = 0.945,
                outcome = "yes",
                predictedSide = "YES"
            )
        }
        assertEquals(5, rows.count { ForecastUnits.hit(it) })
        val side = rows.map { ForecastUnits.sideBrier(it) }.average()
        val yes = rows.map { ForecastUnits.brier(it) }.average()
        assertEquals((0.945 - 1.0) * (0.945 - 1.0), side, 1e-9)
        assertEquals(yes, side, 1e-9)
        val card = ScorecardMetrics.window(rows)
        assertEquals(5, card.hits)
        assertEquals(5, card.total)
        assertEquals(side, card.brier!!, 1e-9)
    }

    @Test
    fun nullPredictedSideFallsBackToProbabilityLean() {
        val yesLean = row(
            ticker = "KXSOL15M-1",
            predictedYes = 0.945,
            outcome = "yes",
            predictedSide = null
        )
        assertTrue(ForecastUnits.pickedSideIsYes(yesLean))
        assertTrue(ForecastUnits.hit(yesLean))

        val noLeanMiss = row(
            ticker = "KXSOL15M-2",
            predictedYes = 0.20,
            outcome = "yes",
            predictedSide = null
        )
        assertFalse(ForecastUnits.pickedSideIsYes(noLeanMiss))
        assertFalse(ForecastUnits.hit(noLeanMiss))

        val blankSide = row(
            ticker = "KXSOL15M-3",
            predictedYes = 0.80,
            outcome = "yes",
            predictedSide = "  "
        )
        assertTrue(ForecastUnits.hit(blankSide))
    }

    @Test
    fun storedScoreAndBrierColumnsAreIgnoredOnRead() {
        val rows = (0 until 5).map { i ->
            row(
                ticker = "KXETH15M-$i",
                predictedYes = 0.945,
                outcome = "yes",
                predictedSide = "NO",
                score = 1,
                brier = 0.003
            )
        }
        val card = ScorecardMetrics.window(rows)
        assertEquals(0, card.hits)
        assertEquals((1.0 - 0.945 - 1.0) * (1.0 - 0.945 - 1.0), card.brier!!, 1e-9)
        assertTrue(rows.all { it.score == 1 && it.brier == 0.003 })
    }

    @Test
    fun sideFromProbabilityIgnoresEdgeSign() {
        assertEquals("YES", ForecastUnits.sideFromProbability(0.945))
        assertEquals("NO", ForecastUnits.sideFromProbability(0.20))
        assertEquals("YES", ForecastUnits.sideFromProbability(94.5))
    }

    private fun row(
        ticker: String,
        predictedYes: Double,
        outcome: String,
        predictedSide: String?,
        score: Int? = null,
        brier: Double? = null
    ) = PredictionLogEntry(
        ticker = ticker,
        series = ticker.substringBefore("-"),
        predictedYes = predictedYes,
        predictedNo = 1.0 - predictedYes,
        marketMid = 0.63,
        timestampMs = 1L,
        closeTimeMs = 1L,
        outcome = outcome,
        score = score,
        brier = brier,
        predictedSide = predictedSide,
        edgePp = -8.0
    )
}
