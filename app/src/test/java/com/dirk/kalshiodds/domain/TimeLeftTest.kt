package com.dirk.kalshiodds.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLeftTest {

    @Test
    fun minutesAndSecondsForShortWindow() {
        val now = 1_000_000L
        val close = now + 4 * 60_000L + 32_000L
        assertEquals("4:32 left", TimeLeft.format(close, now))
        assertEquals(false, TimeLeft.isExpired(close, now))
    }

    @Test
    fun hoursFormat() {
        val now = 1_000_000L
        assertEquals("2h 05m left", TimeLeft.format(now + 2 * 3_600_000L + 5 * 60_000L, now))
    }

    @Test
    fun expiredWhenPastClose() {
        val now = 5_000L
        assertEquals("Expired", TimeLeft.format(1_000L, now))
        assertTrue(TimeLeft.isExpired(1_000L, now))
        assertEquals("—", TimeLeft.format(null, now))
    }
}
