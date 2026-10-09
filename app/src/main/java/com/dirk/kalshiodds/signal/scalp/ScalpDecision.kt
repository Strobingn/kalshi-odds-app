package com.dirk.kalshiodds.signal.scalp

import kotlin.math.abs

enum class ExitReason { TARGET, STOP, TIMEOUT, MOMENTUM_FADE }

/**
 * Pure scalping state machine, strategy-aware. FLAT → (setup detected) →
 * ENTER → IN_POSITION → EXIT → FLAT. No I/O, no Android — the engine feeds
 * [ScalpMath.ScalpFeatures] plus current best bid/ask and the open position;
 * tests drive it with synthetic price series.
 *
 * ## DIP_HUNT entry (FLAT → ENTER)
 * All must hold:
 * 1. Dip within the window: `maxDropFromShortEmaPp ≥ dipMinDropPp` (price ran
 *    at least that far below the fast trend at some point) AND
 *    `dropFromWindowMaxPp ≥ dipMinDropPp` (price is still that far off the
 *    window high — a full recovery does not re-trigger).
 * 2. Stabilizing: `acceleration ≥ 0` (fall decelerating / bounce starting)
 *    OR `|velocity| ≤ stableVelocityPpPerSec` (flat at the bottom).
 * 3. Not a falling knife: `velocity ≥ −maxFallPpPerSec`.
 * 4. Book quality: `ask − bid ≤ maxSpreadCents` and imbalance is not
 *    strongly adverse (`imbalance ≥ −maxAdverseImbalance`, null = ok).
 *
 * ## MOMENTUM_SNIPER entry
 * `velocity > momentumThresholdPpPerSec` AND `acceleration > 0` (a live,
 *   accelerating up-move), same spread / imbalance filters, AND at least
 *   [noEntryWithinMs] of trading room before the window closes
 *   ([closeTimeEpochMs] − now > [noEntryWithinMs]; a null close time is
 *   allowed — REST-only sessions may not have one).
 *
 * ## EXTREME_REVERSAL entry
 * `lastMidPp ≤ 8¢ or ≥ 92¢` AND the velocity has flipped favorable for a
 * YES long (`velocity > 0`, i.e. snapping back up off the floor at ≤8¢ or
 * resuming up after a pullback at ≥92¢) with non-negative acceleration.
 *
 * ## Exits (priority order), all filled at the bid
 * - DIP_HUNT:   TARGET (bid ≥ entry + TP) → STOP (bid ≤ entry − SL) → TIMEOUT
 * - MOMENTUM:   TARGET (bid ≥ entry + TP) → MOMENTUM_FADE (velocity < 0)
 *               → STOP (bid ≤ entry − SL) → TIMEOUT
 * - REVERSAL:   TARGET (bid ≥ entry + TP, or mid back at 50¢ ± backAt50BandPp)
 *               → STOP (bid ≤ entry − SL) → TIMEOUT
 *
 * Everything is in cents / cents-per-second. Entry fills at the ask, exit
 * fills at the bid — thresholds use the bid so a TARGET means a real
 * sellable bounce.
 */
class ScalpDecision(
    private val strategy: ScalpStrategy = ScalpStrategy.DIP_HUNT,
    private val dipMinDropPp: Double = 2.0,
    private val takeProfitPp: Double = 3.0,
    private val stopLossPp: Double = 4.0,
    private val maxHoldMs: Long = 10 * 60 * 1000L,
    /** Max bid/ask spread in cents for an entry. */
    private val maxSpreadCents: Int = 3,
    /** Imbalance floor in [-1, 1]; below this the book is too ask-heavy. */
    private val maxAdverseImbalance: Double = 0.6,
    /** Velocity floor (cents/second); more negative than this = falling knife. */
    private val maxFallPpPerSec: Double = 0.5,
    /** |velocity| at-or-below this counts as "flat at the bottom". */
    private val stableVelocityPpPerSec: Double = 0.05,
    /** MOMENTUM_SNIPER: minimum velocity (cents/second) to enter. */
    private val momentumThresholdPpPerSec: Double = 0.3,
    /** MOMENTUM_SNIPER: no entries within this much of the window close. */
    private val noEntryWithinMs: Long = 60_000L,
    /** EXTREME_REVERSAL: snap-back zone edges (cents). */
    private val extremeLowPp: Double = 8.0,
    private val extremeHighPp: Double = 92.0,
    /** EXTREME_REVERSAL: exit TARGET once mid is back within this of 50¢. */
    private val backAt50BandPp: Double = 3.0
) {

    sealed class ScalpDecision {
        data object Hold : ScalpDecision()

        /** Fill at [entryPriceCents] (the current ask). */
        data class Enter(val entryPriceCents: Int) : ScalpDecision()

        /** Fill at [exitPriceCents] (the current bid) for [reason]. */
        data class Exit(val reason: ExitReason, val exitPriceCents: Int) : ScalpDecision()
    }

    /** Open position view the decision needs. */
    data class OpenPosition(
        val entryPriceCents: Int,
        val entryTimeMs: Long
    )

    fun decide(
        features: ScalpMath.ScalpFeatures,
        bestBidCents: Int?,
        bestAskCents: Int?,
        imbalance: Double? = null,
        position: OpenPosition? = null,
        nowMs: Long,
        closeTimeEpochMs: Long? = null
    ): ScalpDecision.ScalpDecision {
        if (position != null) return exitDecision(features, position, bestBidCents, nowMs)
        return entryDecision(features, bestBidCents, bestAskCents, imbalance, nowMs, closeTimeEpochMs)
    }

    private fun entryDecision(
        features: ScalpMath.ScalpFeatures,
        bestBidCents: Int?,
        bestAskCents: Int?,
        imbalance: Double?,
        nowMs: Long,
        closeTimeEpochMs: Long?
    ): ScalpDecision.ScalpDecision {
        val ask = bestAskCents ?: return ScalpDecision.Hold
        val bid = bestBidCents ?: return ScalpDecision.Hold
        if (ask - bid > maxSpreadCents) return ScalpDecision.Hold
        if (imbalance != null && imbalance < -maxAdverseImbalance) return ScalpDecision.Hold

        return when (strategy) {
            ScalpStrategy.DIP_HUNT -> dipHuntEntry(features, ask)
            ScalpStrategy.MOMENTUM_SNIPER -> momentumEntry(features, ask, nowMs, closeTimeEpochMs)
            ScalpStrategy.EXTREME_REVERSAL -> reversalEntry(features, ask)
        }
    }

    private fun dipHuntEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        val dipFromEmaMax = features.maxDropFromShortEmaPp ?: return ScalpDecision.Hold
        val dipFromMax = features.dropFromWindowMaxPp ?: return ScalpDecision.Hold
        if (dipFromEmaMax + 1e-9 < dipMinDropPp) return ScalpDecision.Hold
        if (dipFromMax + 1e-9 < dipMinDropPp) return ScalpDecision.Hold

        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        val acceleration = features.accelerationPpPerSec2 ?: return ScalpDecision.Hold
        val stabilizing = acceleration >= -1e-9 || abs(velocity) <= stableVelocityPpPerSec + 1e-9
        if (!stabilizing) return ScalpDecision.Hold
        if (velocity < -maxFallPpPerSec - 1e-9) return ScalpDecision.Hold

        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun momentumEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        nowMs: Long,
        closeTimeEpochMs: Long?
    ): ScalpDecision.ScalpDecision {
        // Momentum entries need room — refuse inside the last stretch of a window.
        if (closeTimeEpochMs != null && closeTimeEpochMs - nowMs <= noEntryWithinMs) {
            return ScalpDecision.Hold
        }
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        val acceleration = features.accelerationPpPerSec2 ?: return ScalpDecision.Hold
        if (velocity <= momentumThresholdPpPerSec + 1e-9) return ScalpDecision.Hold
        if (acceleration <= 1e-9) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun reversalEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        val atExtreme = last <= extremeLowPp || last >= extremeHighPp
        if (!atExtreme) return ScalpDecision.Hold
        // The favorable flip for a YES long is always upward: snapping back
        // toward 50¢ off the floor (≤8¢), or resuming up after a pullback at
        // the top extreme (≥92¢ → convergence to $1). A downward snap from
        // ≥92¢ is adverse and never entered.
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        val acceleration = features.accelerationPpPerSec2 ?: return ScalpDecision.Hold
        if (velocity <= 1e-9) return ScalpDecision.Hold
        if (acceleration < -1e-9) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun exitDecision(
        features: ScalpMath.ScalpFeatures,
        position: OpenPosition,
        bestBidCents: Int?,
        nowMs: Long
    ): ScalpDecision.ScalpDecision {
        val bid = bestBidCents ?: return ScalpDecision.Hold
        if (bid >= position.entryPriceCents + takeProfitPp) {
            return ScalpDecision.Exit(ExitReason.TARGET, bid)
        }
        when (strategy) {
            ScalpStrategy.MOMENTUM_SNIPER -> {
                val velocity = features.velocityPpPerSec
                if (velocity != null && velocity < 0.0) {
                    return ScalpDecision.Exit(ExitReason.MOMENTUM_FADE, bid)
                }
            }
            ScalpStrategy.EXTREME_REVERSAL -> {
                val last = features.lastMidPp
                if (last != null && abs(last - 50.0) <= backAt50BandPp + 1e-9) {
                    return ScalpDecision.Exit(ExitReason.TARGET, bid)
                }
            }
            ScalpStrategy.DIP_HUNT -> Unit
        }
        if (bid <= position.entryPriceCents - stopLossPp) {
            return ScalpDecision.Exit(ExitReason.STOP, bid)
        }
        if (nowMs - position.entryTimeMs >= maxHoldMs) {
            return ScalpDecision.Exit(ExitReason.TIMEOUT, bid)
        }
        return ScalpDecision.Hold
    }
}
