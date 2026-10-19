package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.MemoryScalpControls
import com.dirk.kalshiodds.decision.ScalpBook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.11: Force trade / Force bench / Auto change who is allowed to take paper trades. */
class BenchOverrideTest {
    @After fun resetRegistry() { BenchRegistry.publish(emptyMap()) }

    @Test fun forceTradeOverridesALosingBenchAndAutoReturns() {
        val controls = MemoryScalpControls()
        val book = ScalpBook()
        val insights = ScalpInsights(book, controls, null) {}
        insights.start()
        series("fair", List(30) { -1.0 }).forEach { insights.onSettled("fair", it) }
        assertTrue(book.benched.contains("fair"))
        assertEquals(BenchStatus.BENCHED, BenchRegistry.decisions.value.getValue("fair").status)
        assertFalse(BenchRegistry.decisions.value.getValue("fair").trading)

        controls.save(controls.state.value.copy(benchOverrides = mapOf("fair" to "ACTIVE")))
        insights.recompute()
        assertFalse("force trade keeps a losing scalper in the live book", book.benched.contains("fair"))
        assertEquals(BenchStatus.FORCED_ACTIVE, BenchRegistry.decisions.value.getValue("fair").status)
        assertTrue(BenchRegistry.decisions.value.getValue("fair").trading)

        controls.save(controls.state.value.copy(benchOverrides = mapOf("fair" to "BENCHED")))
        insights.recompute()
        assertTrue("force bench is shadow-only", book.benched.contains("fair"))
        assertEquals(BenchStatus.FORCED_BENCHED, BenchRegistry.decisions.value.getValue("fair").status)
        assertFalse(BenchRegistry.decisions.value.getValue("fair").trading)

        controls.save(controls.state.value.copy(benchOverrides = emptyMap()))
        insights.recompute()
        assertEquals(BenchStatus.BENCHED, BenchRegistry.decisions.value.getValue("fair").status)
        assertFalse(BenchRegistry.decisions.value.getValue("fair").trading)
        assertTrue(book.benched.contains("fair"))
        assertTrue(controls.state.value.benchOverrides.isEmpty())
    }
}
