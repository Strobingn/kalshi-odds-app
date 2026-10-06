package com.dirk.kalshiodds.signal.external

/**
 * Exact settlement-source data forwarded by Kalshi from CF Benchmarks.
 *
 * Crypto contracts settle from the CF real-time index and the final-minute
 * average, not a single Coinbase or Binance print. This small value object is
 * deliberately separate from public-exchange context features so callers
 * cannot mistake a proxy quote for the settlement basis.
 */
data class CfBenchmarksValue(
    val indexId: String,
    val valueUsd: Double,
    val sourceTsMs: Long,
    val receivedAtMs: Long,
    val finalMinuteAverageUsd: Double? = null,
    val finalMinuteSamples: Int = 0
) {
    fun isFresh(nowMs: Long, maxAgeMs: Long = MAX_FRESH_AGE_MS): Boolean =
        sourceTsMs > 0L && nowMs >= sourceTsMs && nowMs - sourceTsMs <= maxAgeMs

    /** During the settlement minute this is the best available partial settlement reference. */
    val settlementReferenceUsd: Double get() = finalMinuteAverageUsd ?: valueUsd

    companion object {
        const val MAX_FRESH_AGE_MS = 3_000L

        /** CF payloads use epoch milliseconds; accept a whole-second epoch defensively. */
        fun epochMillis(value: Long): Long = if (value in 1L..99_999_999_999L) value * 1_000L else value

        fun forSeries(series: String): String? = when {
            series.uppercase().contains("BTC") -> "BRTI"
            series.uppercase().contains("ETH") -> "ETHUSD_RTI"
            series.uppercase().contains("SOL") -> "SOLUSD_RTI"
            else -> null
        }

        val DEFAULT_INDEX_IDS = listOf("BRTI", "ETHUSD_RTI", "SOLUSD_RTI")
    }
}
