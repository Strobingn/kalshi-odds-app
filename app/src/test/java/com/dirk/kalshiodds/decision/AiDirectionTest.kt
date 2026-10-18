package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

class AiDirectionTest {
    private fun q(t: String, close: Long, now: Long = 0) = ScalpRule.Quote(t, now, close, now, .5, 10.0, .52, 10.0, 100_000.0, 100_000.0, 1e-4)

    @Test fun picksCurrentBtcWindowAndRollsOver() {
        val m = mapOf("a" to q("KXBTC15M-A", 900_000), "b" to q("KXBTC15M-B", 1_800_000), "e" to q("KXETH15M-A", 300_000))
        assertEquals("KXBTC15M-A", AiDirection.current(m, 100_000)?.ticker)
        assertEquals("KXBTC15M-B", AiDirection.current(m, 900_001)?.ticker) // first window closed → next one
        assertNull(AiDirection.current(m, 2_000_000))
    }

    @Test fun upDownAndNeutral() {
        val w = q("KXBTC15M-A", 600_000)
        assertEquals(AiDirection.Call.UP, AiDirection.forecast(w, 0) { .7 }.call)
        val d = AiDirection.forecast(w, 0) { .2 }
        assertEquals(AiDirection.Call.DOWN, d.call); assertEquals(.8, d.confidence!!, 1e-9); assertEquals(600L, d.secondsLeft)
        assertEquals(AiDirection.Call.NONE, AiDirection.forecast(w, 0) { .53 }.call)
        assertEquals(AiDirection.Call.NONE, AiDirection.forecast(w, 0) { null }.call)
        assertEquals(AiDirection.Call.NONE, AiDirection.forecast(null, 0) { .9 }.call)
        assertEquals("AI says DOWN", AiDirection.label(d)); assertEquals("10:00 left", AiDirection.timeLeft(600))
    }
}
