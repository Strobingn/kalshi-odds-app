package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.RegimeTag
import com.dirk.kalshiodds.signal.engine.TteRegime

/**
 * Auto-mute / downrank series and regimes whose **rolling** hit rate
 * is below a configurable floor.
 *
 * Buckets:
 *  - series ticker (`KXBTC15M`, …)
 *  - mid-path regime (`VOL_SPIKE` / `QUIET` / `TREND` / `CHOP`)
 *  - TTE window (`EARLY` / `LATE`)
 *
 * A bucket needs [minSamples] settled outcomes in the rolling window
 * before it can mute (cold-start safe). Combined keys
 * (`series|regime`, `series|tte`) mute only when both sides are weak.
 */
object Allowlist {

    data class Bucket(
        val key: String,
        val label: String,
        val hits: Int,
        val total: Int,
        val hitRate: Double,
        val muted: Boolean
    )

    data class State(
        val mutedSeries: Set<String> = emptySet(),
        val mutedRegimes: Set<String> = emptySet(),
        val mutedTte: Set<String> = emptySet(),
        val buckets: List<Bucket> = emptyList(),
        val floor: Double = SignalConstants.DEFAULT_MUTE_HIT_RATE_FLOOR,
        val ready: Boolean = false
    ) {
        fun isSeriesMuted(series: String): Boolean =
            mutedSeries.any { series.equals(it, ignoreCase = true) || series.contains(it, ignoreCase = true) }

        fun isRegimeMuted(regime: String?): Boolean {
            if (regime.isNullOrBlank()) return false
            val n = normalizeRegime(regime) ?: return false
            return n in mutedRegimes
        }

        fun isTteMuted(tte: String?): Boolean {
            if (tte.isNullOrBlank()) return false
            val n = normalizeTte(tte) ?: return false
            return n in mutedTte
        }

        fun muteReason(series: String, regime: String?, tte: String?): String? {
            val parts = mutableListOf<String>()
            if (isSeriesMuted(series)) parts += "series ${shortSeries(series)}"
            if (isRegimeMuted(regime)) parts += "regime ${normalizeRegime(regime)}"
            if (isTteMuted(tte)) parts += "TTE ${normalizeTte(tte)}"
            if (parts.isEmpty()) return null
            return "muted — ${parts.joinToString(" · ")} hit rate below floor"
        }

        fun isMuted(series: String, regime: String?, tte: String?): Boolean =
            muteReason(series, regime, tte) != null
    }

    fun evaluate(
        entries: List<PredictionLogEntry>,
        nowMs: Long = System.currentTimeMillis(),
        floor: Double = SignalConstants.DEFAULT_MUTE_HIT_RATE_FLOOR,
        minSamples: Int = SignalConstants.MIN_MUTE_SAMPLES,
        rollingDays: Int = SignalConstants.SCORECARD_ROLLING_DAYS
    ): State {
        val start = nowMs - rollingDays * 86_400_000L
        val settled = entries.filter { e ->
            val ok = e.outcome.equals("yes", true) || e.outcome.equals("no", true)
            ok && (e.settledAtMs ?: e.timestampMs) >= start
        }
        if (settled.isEmpty()) {
            return State(floor = floor, ready = false)
        }

        val seriesBuckets = buckets(
            settled.groupBy { it.series.ifBlank { "unknown" } },
            floor,
            minSamples
        ) { key -> "Series ${shortSeries(key)}" }
        val regimeBuckets = buckets(
            settled.mapNotNull { e ->
                val r = normalizeRegime(e.regime) ?: return@mapNotNull null
                r to e
            }.groupBy({ it.first }, { it.second }),
            floor,
            minSamples
        ) { key -> "Regime $key" }
        val tteBuckets = buckets(
            settled.mapNotNull { e ->
                val t = normalizeTte(e.tteBucket) ?: return@mapNotNull null
                t to e
            }.groupBy({ it.first }, { it.second }),
            floor,
            minSamples
        ) { key -> "TTE $key" }

        return State(
            mutedSeries = seriesBuckets.filter { it.muted }.map { it.key }.toSet(),
            mutedRegimes = regimeBuckets.filter { it.muted }.map { it.key }.toSet(),
            mutedTte = tteBuckets.filter { it.muted }.map { it.key }.toSet(),
            buckets = seriesBuckets + regimeBuckets + tteBuckets,
            floor = floor,
            ready = settled.size >= minSamples
        )
    }

    private fun buckets(
        grouped: Map<String, List<PredictionLogEntry>>,
        floor: Double,
        minSamples: Int,
        label: (String) -> String
    ): List<Bucket> = grouped.map { (key, rows) ->
        val hits = rows.count { it.score == 1 || (it.score == null && sideHit(it)) }
        val rate = if (rows.isEmpty()) 0.0 else hits.toDouble() / rows.size
        Bucket(
            key = key,
            label = label(key),
            hits = hits,
            total = rows.size,
            hitRate = rate,
            muted = rows.size >= minSamples && rate < floor
        )
    }.sortedBy { it.key }

    private fun sideHit(e: PredictionLogEntry): Boolean {
        val predYes = when (e.predictedSide?.uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> e.predictedYes > 0.5
        }
        return predYes == e.outcome.equals("yes", true)
    }

    fun normalizeRegime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val u = raw.uppercase().replace(' ', '_')
        return when {
            u.contains("VOL") -> RegimeTag.VOL_SPIKE.name
            u.contains("QUIET") -> RegimeTag.QUIET.name
            u.contains("TREND") -> RegimeTag.TREND.name
            u.contains("CHOP") -> RegimeTag.CHOP.name
            else -> RegimeTag.entries.firstOrNull { it.name == u }?.name
        }
    }

    fun normalizeTte(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val u = raw.uppercase().replace(' ', '_')
        return when {
            u.contains("LATE") || u.contains("LAST") -> TteRegime.LATE.name
            u.contains("EARLY") -> TteRegime.EARLY.name
            else -> TteRegime.entries.firstOrNull { it.name == u }?.name
        }
    }

    fun shortSeries(series: String): String = when {
        series.contains("BTC", true) -> "BTC"
        series.contains("ETH", true) -> "ETH"
        series.contains("SOL", true) -> "SOL"
        else -> series
    }
}
