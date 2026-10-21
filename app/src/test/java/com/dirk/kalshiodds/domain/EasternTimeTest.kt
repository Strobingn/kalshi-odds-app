package com.dirk.kalshiodds.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EasternTimeTest {
    @Test
    fun handlesEasternStandardAndDaylightTime() {
        val winter = Instant.parse("2026-01-15T17:00:00Z").toEpochMilli()
        val summer = Instant.parse("2026-07-15T16:00:00Z").toEpochMilli()
        assertEquals("12:00 PM EST", EasternTime.short(winter))
        assertEquals("12:00 PM EDT", EasternTime.short(summer))
        assertEquals("Jan 15, 12:00 PM EST", EasternTime.date(winter))
        assertEquals("12:00:00 PM EDT", EasternTime.seconds(summer))
    }
}
