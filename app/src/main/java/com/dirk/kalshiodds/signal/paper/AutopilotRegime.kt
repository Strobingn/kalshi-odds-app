package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Regime tags stamped on an autopilot fill from features already on the
 * market card. Missing inputs become "unknown" — never a guessed bucket.
 */
object AutopilotRegime {
    const val MIN_BUCKET = 8
    val ET: ZoneId = ZoneId.of("America/New_York")

    data class Tags(
        val vol: String,
        val role: String,
        val session: String,
        val path: String,
        val strike: String
    ) {
        val key: String
            get() = "path=$path|role=$role|vol=$vol|session=$session|strike=$strike"

        fun short(): String = "$path · $role · vol $vol · $session · strike $strike"
    }

    fun tags(market: MarketUiModel, side: String, ask: Double, nowMs: Long): Tags = Tags(
        vol = volBucket(market),
        role = if (ask >= 0.50) "favorite" else "underdog",
        session = sessionBucket(market, nowMs),
        path = pathBucket(market.regimeTag),
        strike = strikeBucket(market)
    )

    fun volBucket(market: MarketUiModel): String {
        val vol = market.midVolPp?.takeIf { it.isFinite() && it >= 0.0 }
        if (vol != null) {
            return when {
                vol < 0.40 -> "low"
                vol < 1.20 -> "mid"
                else -> "high"
            }
        }
        return when (pathBucket(market.regimeTag)) {
            "vol" -> "high"
            "quiet" -> "low"
            "trend", "chop" -> "mid"
            else -> "unknown"
        }
    }

    fun pathBucket(regimeTag: String?): String {
        val u = regimeTag?.trim()?.uppercase() ?: return "unknown"
        if (u.isEmpty()) return "unknown"
        return when {
            u.contains("VOL") -> "vol"
            u.contains("TREND") -> "trend"
            u.contains("CHOP") -> "chop"
            u.contains("QUIET") -> "quiet"
            else -> "unknown"
        }
    }

    fun sessionBucket(market: MarketUiModel, nowMs: Long): String {
        val tagged = market.sessionTag?.trim()?.takeIf { it.isNotEmpty() }
        if (tagged != null) return tagged.lowercase()
        val at = market.closeTimeEpochMs ?: nowMs
        val hour = Instant.ofEpochMilli(at).atZone(ET).hour
        return when (hour) {
            in 0..6 -> "overnight"
            in 7..11 -> "morning"
            in 12..16 -> "afternoon"
            else -> "evening"
        }
    }

    fun strikeBucket(market: MarketUiModel): String {
        val strike = market.floorStrike?.takeIf { it.isFinite() && abs(it) > 1e-9 } ?: return "unknown"
        val distance = market.spotVsTargetUsd?.takeIf { it.isFinite() }
            ?: market.spotUsd?.takeIf { it.isFinite() }?.let { it - strike }
            ?: return "unknown"
        val rel = abs(distance) / abs(strike)
        return when {
            rel < 0.0005 -> "near"
            rel < 0.002 -> "mid"
            else -> "far"
        }
    }
}
