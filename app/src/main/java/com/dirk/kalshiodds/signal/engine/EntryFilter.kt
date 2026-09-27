package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalSettings
import java.util.Locale
import kotlin.math.abs

/**
 * Entry-timing guard for Kalshi 15m crypto "price up?" windows
 * (docs/ml-review-2026-09-27.md #7). Pure / local.
 *
 * Out of sample (docs/backtest-2026-09-25.md) the shipped app pick lost
 * most when it entered in the first minutes of a window (14–12m left:
 * 995 of 1451 bets, −4.5% ROI) and when spot sat on the strike (<5bp:
 * −7.7% ROI, a coin flip bought at ~57¢). Neither CI excludes zero, so
 * this is a configurable guard, not a discovered edge.
 *
 * (a) **Too early** — block while fewer than
 *     [SignalSettings.entryMinElapsedMinutes] of the window have elapsed
 *     (default 3 → no entry before 12:00 left; exactly 12:00 passes).
 * (b) **Near strike** — block when |spot − strike| / strike is under
 *     [SignalSettings.entryMinStrikeDistanceBp] basis points (default 5),
 *     unless the chosen side's net edge is at least
 *     [SignalSettings.entryNearStrikeOverridePp] (default 10pp). The edge
 *     is signed: a large *negative* net edge never unlocks a coin flip.
 *
 * Missing data never blocks. No close time, or more time left than the
 * window (not a 15m contract / not open yet), skips (a). No spot or strike
 * skips (b). A skipped check is noted in [Result.reason].
 *
 * Mirrored in tools/backtest/pipeline.py `entry_filter` (same defaults and
 * reason strings) so the backtest can measure it.
 */
object EntryFilter {

    /** Kalshi crypto 15-minute window. */
    const val WINDOW_SECONDS = 900L

    data class Result(
        val passed: Boolean,
        /**
         * Blocked: why, e.g. `too early: 13:40 left (wait until 12:00)` or
         * `near strike: 2.1bp < 5bp`. Passed: a note about a check that was
         * skipped or overridden, else null.
         */
        val reason: String?,
        /** |spot − strike| / strike in basis points when both are known. */
        val distanceBp: Double? = null
    )

    fun evaluate(
        tteSeconds: Long?,
        spotUsd: Double?,
        strikeUsd: Double?,
        netEdgePp: Double?,
        settings: SignalSettings,
        windowSeconds: Long = WINDOW_SECONDS
    ): Result {
        if (!settings.entryFilterEnabled) return Result(passed = true, reason = null)
        val blocks = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val minElapsedSec = settings.entryMinElapsedMinutes.coerceAtLeast(0) * 60L
        val tte = tteSeconds?.takeIf { it >= 0L }
        when {
            minElapsedSec <= 0L -> Unit
            tte == null -> notes += "entry-time check skipped: no close time"
            tte > windowSeconds ->
                notes += "entry-time check skipped: ${clock(tte)} left is longer than the ${clock(windowSeconds)} window"
            windowSeconds - tte < minElapsedSec ->
                blocks += "too early: ${clock(tte)} left (wait until ${clock(windowSeconds - minElapsedSec)})"
        }

        val spot = spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        val strike = strikeUsd?.takeIf { it.isFinite() && it > 0.0 }
        val distanceBp = if (spot != null && strike != null) {
            abs(spot - strike) / strike * 10_000.0
        } else {
            null
        }
        val minBp = settings.entryMinStrikeDistanceBp
        if (minBp > 0.0) {
            when {
                distanceBp == null -> notes += "near-strike check skipped: " + when {
                    spot == null && strike == null -> "no spot or strike"
                    spot == null -> "no spot"
                    else -> "no strike"
                }
                distanceBp < minBp -> {
                    val overridePp = settings.entryNearStrikeOverridePp
                    val edge = netEdgePp?.takeIf { it.isFinite() }
                    if (overridePp > 0.0 && edge != null && edge >= overridePp) {
                        notes += "near strike ${bp(distanceBp)} < ${num(minBp)}bp allowed: " +
                            "net edge ${String.format(Locale.US, "%+.1f", edge)}pp ≥ ${num(overridePp)}pp"
                    } else {
                        blocks += "near strike: ${bp(distanceBp)} < ${num(minBp)}bp"
                    }
                }
            }
        }

        return if (blocks.isEmpty()) {
            Result(passed = true, reason = notes.joinToString(" · ").ifBlank { null }, distanceBp = distanceBp)
        } else {
            Result(passed = false, reason = blocks.joinToString(" · "), distanceBp = distanceBp)
        }
    }

    /** `m:ss` — 820 → `13:40`, 720 → `12:00`. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", s / 60L, s % 60L)
    }

    /** Time left at which [minElapsedMinutes] of a [windowSeconds] window have passed. */
    fun waitUntilLabel(minElapsedMinutes: Int, windowSeconds: Long = WINDOW_SECONDS): String =
        clock((windowSeconds - minElapsedMinutes.coerceAtLeast(0) * 60L).coerceAtLeast(0L))

    private fun bp(v: Double): String = String.format(Locale.US, "%.1fbp", v)

    private fun num(v: Double): String =
        if (v == Math.rint(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
}
