package com.dirk.kalshiodds.signal.scalp

import kotlin.math.abs

enum class ExitReason { TARGET, STOP, TIMEOUT }

/**
 * Pure scalping state machine. FLAT → (dip + stabilization) → ENTER →
 * IN_POSITION → EXIT (target / stop / timeout) → FLAT. No I/O, no Android —
 * the engine feeds [ScalpMath.ScalpFeatures] plus current best bid/ask and
 * the open position; tests drive it with synthetic price series.
 *
 * ## Entry rule (FLAT → ENTER)
 * All must hold:
 * 1. Dip within the window: `maxDropFromShortEmaPp ≥ dipMinDropPp` (price ran
 *    at least that far below the fast trend at some point) AND
 *    `dropFromWindowMaxPp ≥ dipMinDropPp` (price is still that far off the
 *    window high — a full recovery does not re-trigger).
 * 2. Stabilizing: `acceleration ≥ 0` (fall decelerating / bounce starting)
 *    OR `|velocity| ≤ stableVelocityPpPerSec` (flat at the bottom).
 * 3. Not a falling knife: `velocity ≥ −maxFallPpPerSec` — the print is still
 *    accelerating down too fast to catch.
 * 4. Book quality: `ask − bid ≤ maxSpreadCents` and imbalance is not
 *    strongly adverse (`imbalance ≥ −maxAdverseImbalance`, null = ok).
 *
 * ## Exit rule (IN_POSITION → EXIT), checked in priority order
 * - TARGET: best bid ≥ entry + takeProfitPp
 * - STOP:   best bid ≤ entry − stopLossPp
 * - TIMEOUT: now − entryTimeMs ≥ maxHoldMs
 *
 * Everything is in cents / cents-per-second. Entry fills at the ask, exit
 * fills at the bid — thresholds use the bid so a TARGET means a real
 * sellable bounce.
 */
class ScalpDecision(
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
    private val stableVelocityPpPerSec: Double = 0.05
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
        nowMs: Long
    ): ScalpDecision.ScalpDecision {
        if (position != null) return exitDecision(position, bestBidCents, nowMs)
        return entryDecision(features, bestBidCents, bestAskCents, imbalance)
    }

    private fun entryDecision(
        features: ScalpMath.ScalpFeatures,
        bestBidCents: Int?,
        bestAskCents: Int?,
        imbalance: Double?
    ): ScalpDecision.ScalpDecision {
        val ask = bestAskCents ?: return ScalpDecision.Hold
        val bid = bestBidCents ?: return ScalpDecision.Hold
        if (ask - bid > maxSpreadCents) return ScalpDecision.Hold
        if (imbalance != null && imbalance < -maxAdverseImbalance) return ScalpDecision.Hold

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

    private fun exitDecision(
        position: OpenPosition,
        bestBidCents: Int?,
        nowMs: Long
    ): ScalpDecision.ScalpDecision {
        val bid = bestBidCents ?: return ScalpDecision.Hold
        if (bid >= position.entryPriceCents + takeProfitPp) {
            return ScalpDecision.Exit(ExitReason.TARGET, bid)
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
