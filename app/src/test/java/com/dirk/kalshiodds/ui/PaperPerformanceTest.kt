package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.scalp.*
import org.junit.Assert.*
import org.junit.Test

class PaperPerformanceTest {
    private fun a() = PaperScalpAccount("adaptive-35", cash = 19880.0, policyVersion = 3,
        policyStartedMs = 2000, policyStartEquity = 19900.0,
        curve = listOf(PaperCurvePoint(1000, 19900.0, -100.0, 1.0, policyVersion = 2),
            PaperCurvePoint(2000, 19900.0, -100.0, 1.0, policyVersion = 3),
            PaperCurvePoint(17000, 19880.0, -120.0, 2.0, policyVersion = 3)))
    @Test fun recordedChartKeepsOldLossAndNewRuleChartUsesActualBalance() {
        val state = PaperScalpState(accounts = listOf(a()), lastQuoteMs = 17000)
        val all = PaperPerformance.series(state, 35, false, null, false).first().points
        val newer = PaperPerformance.series(state, 35, true, null, false).first().points
        assertEquals(-100.0, all.first().value, 0.0); assertEquals(0.0, newer.first().value, 0.0)
        assertEquals(-20.0, newer.last().value, 0.0)
    }
    @Test fun noFakeHistoryOrFutureValueAtCursor() {
        val series = PaperPerformance.Series("a", "A", listOf(PaperPerformance.Point(1000, 10.0), PaperPerformance.Point(2000, -5.0)))
        assertNull(PaperPerformance.valueAt(series, 999))
        assertEquals(10.0, PaperPerformance.valueAt(series, 1999)!!.value, 0.0)
        assertEquals(2, PaperPerformance.observations(a().copy(curve = a().curve.take(1)), 2000).size)
    }
    @Test fun missingTimeSplitsCurveInsteadOfDrawingOvernightProfitLine() {
        val points = listOf(PaperPerformance.Point(1000, 0.0), PaperPerformance.Point(16000, 4.0), PaperPerformance.Point(900000, -3.0))
        assertEquals(listOf(2, 1), PaperPerformance.segments(points).map { it.size })
    }
    @Test fun drawdownUsesPeakInSelectedObservedRange() {
        val state = PaperScalpState(accounts = listOf(a()), lastQuoteMs = 17000)
        val values = PaperPerformance.series(state, 35, true, null, true).first().points.map { it.value }
        assertEquals(listOf(0.0, -20.0), values)
    }
    @Test fun axisContainsZeroAndBothGainAndLossWithReadableSteps() {
        val line = PaperPerformance.Series("a", "A", listOf(PaperPerformance.Point(1, -113.0), PaperPerformance.Point(2, 98.0)))
        val axis = PaperPerformance.axis(listOf(line))
        assertTrue(axis.low <= -113); assertTrue(axis.high >= 98); assertTrue(axis.step > 0)
        val empty = PaperPerformance.axis(emptyList()); assertTrue(empty.high > empty.low)
    }
    @Test fun timeRangeExcludesEarlierPointsAndMissingBooksStayEmpty() {
        val state = PaperScalpState(accounts = listOf(a()), lastQuoteMs = 8_000_000)
        val series = PaperPerformance.series(state, 35, false, 1, false)
        assertEquals(1, series.first().points.size)
        assertTrue(series.drop(1).all { it.points.isEmpty() })
    }
}
