package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.SeriesKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human window title from a Kalshi 15m ticker.
 * The clock token is `yyMMMddHHmm` in America/New_York
 * (`KXBTC15M-26OCT041915-15` → 2026-Oct-04 19:15 ET).
 * A token that does not match returns null. Does not change trading logic.
 */
object WindowLabel {
    val ET: ZoneId = ZoneId.of("America/New_York")

    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4,
        "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8,
        "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val WINDOW = Regex(
        """^(\d{2})([A-Za-z]{3})(\d{2})(\d{4})$"""
    )

    fun coin(ticker: String): String = when (CryptoMarkets.kindFor(ticker)) {
        SeriesKind.BTC -> "BTC"
        SeriesKind.ETH -> "ETH"
        SeriesKind.SOL -> "SOL"
        SeriesKind.CRYPTO -> ticker.substringBefore("-").removePrefix("KX").take(3).uppercase(Locale.US)
    }

    fun of(ticker: String, closeEpochMs: Long? = null): String {
        val coin = coin(ticker)
        val clock = closeEpochMs?.let { formatEt(it) } ?: parseTickerClock(ticker) ?: "15m"
        return "$coin · $clock window"
    }

    fun formatEt(epochMs: Long): String =
        TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ET))

    fun parseTickerClock(ticker: String): String? {
        val zdt = closeZoned(ticker) ?: return null
        return TIME.format(zdt)
    }

    /**
     * Close instant encoded in a 15m ticker (`yy MMM dd HHmm`), or null when
     * the ticker has no clock. Callers must keep a pending order when this is null.
     */
    fun closeEpochMs(ticker: String): Long? = closeZoned(ticker)?.toInstant()?.toEpochMilli()

    private fun closeZoned(ticker: String): ZonedDateTime? {
        val key = ticker.split("-").getOrNull(1) ?: return null
        val m = WINDOW.matchEntire(key) ?: return null
        val year = 2000 + m.groupValues[1].toInt()
        val month = MONTHS[m.groupValues[2].uppercase(Locale.US)] ?: return null
        val day = m.groupValues[3].toInt()
        val hhmm = m.groupValues[4].toInt()
        val hour = hhmm / 100
        val minute = hhmm % 100
        if (hour !in 0..23 || minute !in 0..59 || day !in 1..31) return null
        return runCatching { ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ET) }.getOrNull()
    }
}
