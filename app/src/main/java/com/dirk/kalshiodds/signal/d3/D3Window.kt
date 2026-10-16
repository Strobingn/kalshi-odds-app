package com.dirk.kalshiodds.signal.d3

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 14:00–16:00 ET window on the KXBTCD event that closes at 17:00 ET.
 * Pure clock math — no I/O.
 */
object D3Window {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun et(nowMs: Long): ZonedDateTime =
        Instant.ofEpochMilli(nowMs).atZone(D3Constants.ET)

    fun closeIsFivePmEt(closeTimeEpochMs: Long?): Boolean {
        val close = closeTimeEpochMs ?: return false
        val z = et(close)
        return z.hour == D3Constants.CLOSE_HOUR_ET && z.minute == 0
    }

    fun windowBounds(closeTimeEpochMs: Long): Pair<Long, Long> {
        val close = Instant.ofEpochMilli(closeTimeEpochMs).atZone(D3Constants.ET)
        val start = close.minusHours(D3Constants.WINDOW_START_HOURS_BEFORE_CLOSE.toLong()).toInstant().toEpochMilli()
        val end = close.minusHours(D3Constants.WINDOW_END_HOURS_BEFORE_CLOSE.toLong()).toInstant().toEpochMilli()
        return start to end
    }

    fun phase(nowMs: Long, closeTimeEpochMs: Long?): D3Phase {
        if (closeTimeEpochMs == null) return D3Phase.WAITING
        val (start, end) = windowBounds(closeTimeEpochMs)
        return when {
            nowMs < start -> D3Phase.WAITING
            nowMs < end -> D3Phase.ACTIVE
            else -> D3Phase.CLOSED
        }
    }

    fun startsInMs(nowMs: Long, closeTimeEpochMs: Long?): Long? {
        if (closeTimeEpochMs == null) return null
        val (start, _) = windowBounds(closeTimeEpochMs)
        return (start - nowMs).coerceAtLeast(0L)
    }

    /** Today's 17:00 ET close, used when no live event has been fetched yet. */
    fun impliedCloseMs(nowMs: Long): Long {
        val z = et(nowMs)
        val close = z.with(LocalTime.of(D3Constants.CLOSE_HOUR_ET, 0)).withSecond(0).withNano(0)
        return if (z.isBefore(close) || z.isEqual(close)) {
            close.toInstant().toEpochMilli()
        } else {
            close.plusDays(1).toInstant().toEpochMilli()
        }
    }

    fun dayKey(nowMs: Long): String = et(nowMs).toLocalDate().format(DATE)

    fun dateLabel(nowMs: Long): String = et(nowMs).format(LABEL)

    fun sameEtDay(aMs: Long, bMs: Long): Boolean =
        et(aMs).toLocalDate() == et(bMs).toLocalDate()

    fun localDate(nowMs: Long): LocalDate = et(nowMs).toLocalDate()
}
