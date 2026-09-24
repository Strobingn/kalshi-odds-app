package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.engine.OverlayThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayThrottleTest {

    @Test
    fun firstEventAppliesImmediately() {
        var now = 1_000L
        val throttle = OverlayThrottle<Int>(intervalMs = 250L, nowMs = { now })
        assertEquals(OverlayThrottle.Decision.APPLY_NOW, throttle.onEvent(1))
        assertNull(throttle.pending)
    }

    @Test
    fun floodDoesNotResetTrailingTimer() {
        var now = 1_000L
        val throttle = OverlayThrottle<Int>(intervalMs = 250L, nowMs = { now })
        assertEquals(OverlayThrottle.Decision.APPLY_NOW, throttle.onEvent(1))

        now = 1_010L
        assertEquals(OverlayThrottle.Decision.SCHEDULE_TRAILING, throttle.onEvent(2))
        now = 1_020L
        assertEquals(OverlayThrottle.Decision.HOLD, throttle.onEvent(3))
        now = 1_030L
        assertEquals(OverlayThrottle.Decision.HOLD, throttle.onEvent(4))
        assertEquals(4, throttle.pending)
        assertEquals(220L, throttle.remainingMs())

        now = 1_250L
        assertEquals(4, throttle.takeTrailing())
        assertNull(throttle.pending)
    }

    @Test
    fun intervalElapsedAppliesImmediatelyAgain() {
        var now = 5_000L
        val throttle = OverlayThrottle<String>(intervalMs = 250L, nowMs = { now })
        assertEquals(OverlayThrottle.Decision.APPLY_NOW, throttle.onEvent("a"))
        now = 5_300L
        assertEquals(OverlayThrottle.Decision.APPLY_NOW, throttle.onEvent("b"))
        assertNull(throttle.pending)
    }
}
