package com.dirk.kalshiodds.domain

/**
 * Human countdown until a Kalshi market close / expiration.
 * Pure formatting — Compose ticks this with a 1s clock.
 */
object TimeLeft {

    fun remainingMs(closeEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Long? {
        val close = closeEpochMs?.takeIf { it > 0L } ?: return null
        return close - nowMs
    }

    fun isExpired(closeEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val left = remainingMs(closeEpochMs, nowMs) ?: return false
        return left <= 0L
    }

    /**
     * Short windows (15m ladders): `4:32 left`.
     * Hours: `2h 05m left`. Days: `1d 4h left`. Past close: `Expired`.
     */
    fun format(closeEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        val left = remainingMs(closeEpochMs, nowMs) ?: return "—"
        if (left <= 0L) return "Expired"
        val totalSec = (left + 999L) / 1000L
        val days = totalSec / 86_400L
        val hours = (totalSec % 86_400L) / 3_600L
        val minutes = (totalSec % 3_600L) / 60L
        val seconds = totalSec % 60L
        return when {
            days >= 1L -> "${days}d ${hours}h left"
            hours >= 1L -> String.format(java.util.Locale.US, "%dh %02dm left", hours, minutes)
            else -> String.format(java.util.Locale.US, "%d:%02d left", minutes, seconds)
        }
    }
}
