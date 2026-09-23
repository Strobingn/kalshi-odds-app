package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs
import kotlin.math.sqrt

/** Early window vs last ~2–3 minutes before settlement. */
enum class TteRegime {
    EARLY,
    LATE;

    val label: String
        get() = when (this) {
            EARLY -> "Early window"
            LATE -> "Last 3 min"
        }

    val shortLabel: String
        get() = when (this) {
            EARLY -> "EARLY"
            LATE -> "LATE"
        }
}

/**
 * Mid-path regime from recent volatility and velocity.
 * Used on cards and to nudge blend weights / confidence.
 */
enum class RegimeTag {
    VOL_SPIKE,
    QUIET,
    TREND,
    CHOP;

    val label: String
        get() = when (this) {
            VOL_SPIKE -> "Vol spike"
            QUIET -> "Quiet"
            TREND -> "Trend"
            CHOP -> "Chop"
        }

    val shortLabel: String
        get() = when (this) {
            VOL_SPIKE -> "VOL"
            QUIET -> "QUIET"
            TREND -> "TREND"
            CHOP -> "CHOP"
        }
}

object MarketRegime {
    fun tteRegime(closeEpochMs: Long?, nowMs: Long): TteRegime {
        if (closeEpochMs == null) return TteRegime.EARLY
        return if (closeEpochMs - nowMs <= SignalConstants.LATE_TTE_MS) TteRegime.LATE else TteRegime.EARLY
    }

    fun tteSeconds(closeEpochMs: Long?, nowMs: Long): Long? {
        if (closeEpochMs == null) return null
        return ((closeEpochMs - nowMs) / 1000L).coerceAtLeast(0L)
    }

    fun tteBucket(closeEpochMs: Long?, nowMs: Long): String = tteRegime(closeEpochMs, nowMs).name

    /**
     * Classify from chronological mids (probability 0–1) and optional
     * velocity in probability units per second.
     */
    fun classify(
        mids: List<Double>,
        timesMs: List<Long> = emptyList(),
        velocityPerSec: Double? = null
    ): RegimeTag {
        if (mids.size < 3) return RegimeTag.QUIET
        val rets = mids.zipWithNext { a, b -> (b - a) * 100.0 }
        if (rets.isEmpty()) return RegimeTag.QUIET
        val mean = rets.average()
        val variance = rets.map { v ->
            val d = v - mean
            d * d
        }.average()
        val vol = sqrt(variance.coerceAtLeast(0.0))
        val net = (mids.last() - mids.first()) * 100.0
        val velPp = (velocityPerSec ?: if (timesMs.size == mids.size && timesMs.size >= 2) {
            val dt = (timesMs.last() - timesMs.first()) / 1000.0
            if (dt > 1e-6) (mids.last() - mids.first()) / dt else 0.0
        } else {
            0.0
        }) * 100.0
        val sameSign = net * velPp >= -1e-6

        return when {
            vol >= 2.2 || abs(velPp) >= 2.0 -> RegimeTag.VOL_SPIKE
            abs(net) >= 2.5 && abs(velPp) >= 0.25 && sameSign -> RegimeTag.TREND
            vol >= 0.9 && abs(net) < 2.0 -> RegimeTag.CHOP
            vol < 0.35 && abs(velPp) < 0.20 && abs(net) < 1.2 -> RegimeTag.QUIET
            abs(net) >= 2.0 && sameSign -> RegimeTag.TREND
            else -> RegimeTag.CHOP
        }
    }
}
