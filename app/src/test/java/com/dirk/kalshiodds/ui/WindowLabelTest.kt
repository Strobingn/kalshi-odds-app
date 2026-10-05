package com.dirk.kalshiodds.ui

import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowLabelTest {
    @Test
    fun parsesFifteenMinuteTickerInEasternTime() {
        assertEquals("BTC · 1:45 PM window", WindowLabel.of("KXBTC15M-26SEP251345-45"))
        assertEquals("ETH · 11:45 PM window", WindowLabel.of("KXETH15M-26SEP252345-40"))
        assertEquals("SOL · 12:00 AM window", WindowLabel.of("KXSOL15M-27SEP250000-20"))
    }

    @Test
    fun midnightCrossingKeepsTheTickerDate() {
        assertEquals("BTC · 11:45 PM window", WindowLabel.of("KXBTC15M-26SEP252345-50"))
        assertEquals("BTC · 12:00 AM window", WindowLabel.of("KXBTC15M-27SEP250000-50"))
        assertEquals("1:45 PM", WindowLabel.parseTickerClock("KXBTC15M-26SEP251345-45"))
    }

    @Test
    fun kalshiClockIsYearMonthDayThenHourMinute() {
        val expected = ZonedDateTime.of(
            LocalDateTime.of(2026, 10, 4, 19, 15),
            WindowLabel.ET
        ).toInstant().toEpochMilli()
        assertEquals(expected, WindowLabel.closeEpochMs("KXBTC15M-26OCT041915-15"))
        assertEquals("7:15 PM", WindowLabel.parseTickerClock("KXBTC15M-26OCT041915-15"))
        assertNull(WindowLabel.closeEpochMs("KXBTC15M-T"))
    }

    @Test
    fun closeEpochOverridesTickerClock() {
        val close = ZonedDateTime.of(
            LocalDateTime.of(2025, 9, 26, 14, 0),
            WindowLabel.ET
        ).toInstant().toEpochMilli()
        assertEquals("BTC · 2:00 PM window", WindowLabel.of("KXBTC15M-26SEP251345-45", close))
    }
}
