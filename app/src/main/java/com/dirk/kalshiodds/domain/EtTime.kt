package com.dirk.kalshiodds.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one display formatter for user-visible times: America/New_York, 12-hour with AM/PM, labeled "ET".
 * java.time handles daylight saving, so EDT and EST both read "ET".
 * Display only. Stored epoch values, ticker keys, and file names are never formatted through here.
 */
object EtTime {
    val ZONE: ZoneId = ZoneId.of("America/New_York")
    const val LABEL = "ET"

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZONE)
    private val CLOCK_SEC = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZONE)
    private val CLOCK_MS = DateTimeFormatter.ofPattern("h:mm:ss.SSS a", Locale.US).withZone(ZONE)
    private val DAY_CLOCK = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZONE)
    private val DAY_CLOCK_SEC = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a", Locale.US).withZone(ZONE)
    private val YEAR_CLOCK = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US).withZone(ZONE)
    private val YEAR_CLOCK_SEC = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.US).withZone(ZONE)
    private val SHORT_DAY_CLOCK = DateTimeFormatter.ofPattern("M/d h:mm a", Locale.US).withZone(ZONE)
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZONE)

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    /** "1:52 PM ET" */
    fun clock(ms: Long): String = "${clockBare(ms)} $LABEL"
    /** "1:52:07 PM ET" */
    fun clockSec(ms: Long): String = "${clockSecBare(ms)} $LABEL"
    /** "1:52:07.123 PM ET", for debug logs where milliseconds matter. */
    fun clockMillis(ms: Long): String = "${CLOCK_MS.format(at(ms))} $LABEL"
    /** "Oct 20, 1:52 PM ET" */
    fun dateClock(ms: Long): String = "${DAY_CLOCK.format(at(ms))} $LABEL"
    /** "Oct 20, 1:52:07 PM ET" */
    fun dateClockSec(ms: Long): String = "${DAY_CLOCK_SEC.format(at(ms))} $LABEL"
    /** "Oct 20, 2026, 1:52 PM ET" */
    fun fullDateClock(ms: Long): String = "${YEAR_CLOCK.format(at(ms))} $LABEL"

    /** "Oct 20, 2026, 1:52:07 PM ET" */
    fun fullDateClockSec(ms: Long): String = "${YEAR_CLOCK_SEC.format(at(ms))} $LABEL"

    /** "2026-10-20", the Eastern calendar date. No clock, so callers add the ET label. */
    fun day(ms: Long): String = DAY.format(at(ms))

    /** "1:52 PM" without the label, for chart ticks where one "ET" sits on the axis. */
    fun clockBare(ms: Long): String = CLOCK.format(at(ms))
    fun clockSecBare(ms: Long): String = CLOCK_SEC.format(at(ms))
    /** "10/20 1:52 PM" without the label, for chart ticks. */
    fun shortDateClockBare(ms: Long): String = SHORT_DAY_CLOCK.format(at(ms))
    fun dateClockBare(ms: Long): String = DAY_CLOCK.format(at(ms))

    /** A wall time already in ET, e.g. from a ticker. "1:45 PM ET" */
    fun clock(zdt: ZonedDateTime): String = "${CLOCK.format(zdt)} $LABEL"
    fun clockBare(zdt: ZonedDateTime): String = CLOCK.format(zdt)

    /** Hour of day 0–23 → "12 AM", "1 PM". */
    fun hour(hour: Int): String {
        val h = hour.coerceIn(0, 23)
        val hr = if (h % 12 == 0) 12 else h % 12
        return "$hr ${if (h < 12) "AM" else "PM"}"
    }

    /** Compact hour tick for dense chart axes and heatmap columns: 0 → "12a", 15 → "3p". */
    fun hourTick(hour: Int): String {
        val h = hour.coerceIn(0, 23)
        val hr = if (h % 12 == 0) 12 else h % 12
        return "$hr${if (h < 12) "a" else "p"}"
    }

    /** Hour range in ET, e.g. 12..16 → "12 PM–4 PM ET". 24 reads as midnight. */
    fun hourRange(startHour: Int, endHour: Int): String =
        "${hour(startHour % 24)}–${hour(endHour % 24)} $LABEL"
}
