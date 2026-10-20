package com.dirk.kalshiodds.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class EtTimeTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun summerUsesEdt() {
        val t = ms("2026-10-20T17:52:07Z")
        assertEquals("1:52 PM ET", EtTime.clock(t))
        assertEquals("1:52:07 PM ET", EtTime.clockSec(t))
        assertEquals("Oct 20, 1:52 PM ET", EtTime.dateClock(t))
        assertEquals("Oct 20, 1:52:07 PM ET", EtTime.dateClockSec(t))
        assertEquals("Oct 20, 2026, 1:52 PM ET", EtTime.fullDateClock(t))
        assertEquals("Oct 20, 2026, 1:52:07 PM ET", EtTime.fullDateClockSec(t))
        assertEquals("1:52:07.000 PM ET", EtTime.clockMillis(t))
        assertEquals("1:52 PM", EtTime.clockBare(t))
        assertEquals("10/20 1:52 PM", EtTime.shortDateClockBare(t))
    }

    @Test fun winterUsesEst() {
        assertEquals("1:52 PM ET", EtTime.clock(ms("2026-01-15T18:52:00Z")))
        assertEquals("Jan 15, 1:52 PM ET", EtTime.dateClock(ms("2026-01-15T18:52:00Z")))
    }

    @Test fun springForwardSkipsTwoAm() {
        assertEquals("1:59 AM ET", EtTime.clock(ms("2026-03-08T06:59:00Z")))
        assertEquals("3:00 AM ET", EtTime.clock(ms("2026-03-08T07:00:00Z")))
    }

    @Test fun fallBackRepeatsOneAm() {
        assertEquals("1:30 AM ET", EtTime.clock(ms("2026-11-01T05:30:00Z")))
        assertEquals("1:30 AM ET", EtTime.clock(ms("2026-11-01T06:30:00Z")))
        assertEquals("Nov 1, 2:00 AM ET", EtTime.dateClock(ms("2026-11-01T07:00:00Z")))
    }

    @Test fun ignoresTheDeviceZone() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            assertEquals("1:52 PM ET", EtTime.clock(ms("2026-10-20T17:52:00Z")))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("12:00 AM ET", EtTime.clock(ms("2026-10-20T04:00:00Z")))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun midnightAndNoonAreTwelve() {
        assertEquals("12:00 AM ET", EtTime.clock(ms("2026-10-20T04:00:00Z")))
        assertEquals("12:00 PM ET", EtTime.clock(ms("2026-10-20T16:00:00Z")))
    }

    @Test fun hoursAndRanges() {
        assertEquals("12 AM", EtTime.hour(0))
        assertEquals("1 PM", EtTime.hour(13))
        assertEquals("11 PM", EtTime.hour(23))
        assertEquals("12 PM–4 PM ET", EtTime.hourRange(12, 16))
        assertEquals("8 PM–12 AM ET", EtTime.hourRange(20, 24))
        assertEquals("12a", EtTime.hourTick(0))
        assertEquals("3p", EtTime.hourTick(15))
    }

    @Test fun wallTimeInEt() {
        val z = ZonedDateTime.of(2026, 10, 20, 13, 45, 0, 0, ZoneId.of("America/New_York"))
        assertEquals("1:45 PM ET", EtTime.clock(z))
        assertEquals("America/New_York", EtTime.ZONE.id)
    }
}
