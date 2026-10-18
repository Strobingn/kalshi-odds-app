package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import org.junit.Assert.*
import org.junit.Test

class ScalpBenchTest {
    @Test fun warmsUpBelowWindow() {
        val d = ScalpBench.decide("a", false, series("r", List(29) { -1.0 }), emptyList(), 30)
        assertEquals(BenchStatus.ACTIVE, d.status); assertTrue(d.reason.contains("29/30"))
    }
    @Test fun benchesAtWindowWhenNetNegative() {
        val d = ScalpBench.decide("a", false, series("r", List(30) { if (it % 2 == 0) 1.0 else -1.1 }), emptyList(), 30)
        assertEquals(BenchStatus.BENCHED, d.status); assertFalse(d.trading); assertTrue(d.netUsd < 0)
    }
    @Test fun staysActiveWhenNetZeroOrPositive() {
        assertEquals(BenchStatus.ACTIVE, ScalpBench.decide("a", false, series("r", List(30) { if (it % 2 == 0) 1.0 else -1.0 }), emptyList(), 30).status)
        assertEquals(BenchStatus.ACTIVE, ScalpBench.decide("a", false, series("r", List(30) { 0.5 }), emptyList(), 30).status)
    }
    @Test fun onlyLastWindowCounts() {
        val old = series("o", List(30) { -5.0 }, start = 0)
        val recent = series("n", List(30) { 0.1 }, start = 10_000_000)
        assertEquals(BenchStatus.ACTIVE, ScalpBench.decide("a", false, old + recent, emptyList(), 30).status)
    }
    @Test fun unbenchesWhenShadowPositive() {
        val real = series("r", List(30) { -1.0 }, start = 0)
        val shadow = series("s", List(30) { 0.2 }, start = 10_000_000)
        val d = ScalpBench.decide("a", true, real, shadow, 30)
        assertEquals(BenchStatus.ACTIVE, d.status); assertTrue(d.shadowNetUsd > 0)
    }
    @Test fun staysBenchedWhenShadowNotPositiveOrTooFew() {
        val real = series("r", List(30) { -1.0 }, start = 0)
        assertEquals(BenchStatus.BENCHED, ScalpBench.decide("a", true, real, series("s", List(30) { 0.0 }, start = 10_000_000), 30).status)
        // 10 positive shadows still leave 20 losing real trades in the last 30 → net negative.
        assertEquals(BenchStatus.BENCHED, ScalpBench.decide("a", true, real, series("s", List(10) { 0.5 }, start = 10_000_000), 30).status)
        assertEquals(BenchStatus.BENCHED, ScalpBench.decide("a", true, emptyList(), series("s", List(5) { 3.0 }), 30).status)
    }
    @Test fun overridesWin() {
        val losing = series("r", List(30) { -1.0 })
        assertEquals(BenchStatus.FORCED_ACTIVE, ScalpBench.decide("a", true, losing, emptyList(), 30, BenchOverride.FORCE_ACTIVE).status)
        assertTrue(ScalpBench.decide("a", false, losing, emptyList(), 30, BenchOverride.FORCE_ACTIVE).trading)
        assertFalse(ScalpBench.decide("a", false, series("r", List(30) { 1.0 }), emptyList(), 30, BenchOverride.FORCE_BENCHED).trading)
    }
    @Test fun ignoresOpenAndNaN() {
        val trades = series("r", List(30) { -1.0 }).mapIndexed { i, t -> if (i < 5) t.copy(state = ScalpState.OPEN) else if (i < 7) t.copy(netUsd = Double.NaN) else t }
        assertEquals(BenchStatus.ACTIVE, ScalpBench.decide("a", false, trades, emptyList(), 30).status)
    }
    @Test fun windowCoercedToOne() {
        assertEquals(BenchStatus.BENCHED, ScalpBench.decide("a", false, series("r", listOf(-0.01)), emptyList(), 0).status)
    }
}
