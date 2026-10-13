package com.dirk.kalshiodds.signal.paper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ScalpReviewTest {
    @Test fun keepsOnlyAnAfterFeePositiveTrackThatSurvivesDayResampling() {
        val assessment = ScalpReview.assess(fills(days = 3, eachDay = 10, pnlUsd = 0.25))

        assertEquals(ScalpReview.Decision.KEEP, assessment.decision)
        assertEquals(30, assessment.settledFills)
        assertEquals(3, assessment.distinctDays)
        org.junit.Assert.assertTrue((assessment.lower95PerContractUsd ?: 0.0) > 0.0)
    }

    @Test fun requestsATweakForAConsistentlyNegativeTrack() {
        val assessment = ScalpReview.assess(fills(days = 3, eachDay = 10, pnlUsd = -0.10))

        assertEquals(ScalpReview.Decision.TWEAK, assessment.decision)
        org.junit.Assert.assertTrue((assessment.lower95PerContractUsd ?: 0.0) < 0.0)
    }

    @Test fun refusesToRankAHandfulOfLuckyFills() {
        val assessment = ScalpReview.assess(fills(days = 1, eachDay = 8, pnlUsd = 10.0))

        assertEquals(ScalpReview.Decision.COLLECT, assessment.decision)
        assertNull(assessment.lower95PerContractUsd)
    }

    private fun fills(days: Int, eachDay: Int, pnlUsd: Double): List<PaperFill> = buildList {
        repeat(days) { day ->
            repeat(eachDay) { index ->
                add(
                    PaperFill(
                        id = "$day-$index",
                        ticker = "KXBTC15M-$day-$index",
                        side = "YES",
                        stakeUsd = 0.5,
                        contracts = 1,
                        limitPrice = 0.5,
                        source = "test",
                        createdAtMs = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli() + day * 86_400_000L + index,
                        settled = true,
                        outcome = "yes",
                        won = pnlUsd >= 0.0,
                        pnlUsd = pnlUsd,
                        note = "test"
                    )
                )
            }
        }
    }
}
