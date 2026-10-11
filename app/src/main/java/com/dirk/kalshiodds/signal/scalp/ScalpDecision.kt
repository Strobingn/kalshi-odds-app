package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import kotlin.math.abs

enum class ExitReason { TARGET, STOP, TIMEOUT, MOMENTUM_FADE, WINDOW_CLOSE }

/**
 * Spot impulse for SPOT_LEAD / OPEN_DRIVE. Fractions, not percent:
 * 0.0006 = a 6bp move. Nulls mean the feature is unavailable (stream
 * disconnected / series unsupported) — strategies must fail soft.
 */
data class ScalpSpot(
    /** Spot return over the last ~15s. */
    val return15s: Double? = null,
    /** Spot return over the last 1m. */
    val return1m: Double? = null
) {
    /** Short-horizon impulse: prefer the 15s reading; fall back to 1m ÷ 4. */
    val impulse: Double? get() = return15s ?: return1m?.let { it / 4.0 }
}

/**
 * Pure scalping state machine, strategy-aware. FLAT → (setup detected) →
 * ENTER → IN_POSITION → EXIT → FLAT. No I/O, no Android — the engine feeds
 * [ScalpMath.ScalpFeatures] plus book/pulse/spot context and the open
 * position; tests drive it with synthetic price series.
 *
 * ## Entries (FLAT → ENTER), all YES-long at the ask
 * - **DIP_HUNT** — dip ≥ dipMinDropPp below the fast EMA AND off the window
 *   high, stabilizing (accel ≥ 0 or |velocity| ≤ stable), not a falling knife.
 * - **MOMENTUM_SNIPER** — velocity > threshold AND accelerating. Trades the
 *   ENTIRE window: late-window momentum is where convergence moves get
 *   violent, so there is deliberately no close-time gate.
 * - **EXTREME_REVERSAL** — mid ≤ 8¢ or ≥ 92¢ with the velocity flipped
 *   favorable-up and non-negative accel.
 * - **VWAP_REVERT** — mid ≥ vwapK below the rolling VWAP proxy and
 *   stabilizing. Exit when the bid touches the VWAP (mean reversion done).
 * - **FIFTY_FLIP** — mid crosses UP through 50¢ from within ±band with
 *   velocity ≥ threshold and bid-side imbalance support. (YES-long cannot
 *   take the downward cross.)
 * - **BOOK_IMBALANCE** — near-mid imbalance ≥ +0.5 with a non-crashing tape.
 *   Exit when the imbalance decays below +0.05 (MOMENTUM_FADE).
 * - **PULSE_SNIPE** — extreme YES-side cancel burst (cancelSpike ≤ −0.6)
 *   with quotes re-supporting (quotePull ≥ +0.2) and no free-fall. Exit when
 *   the cancel EMA recovers above −0.2 (MOMENTUM_FADE).
 * - **SPOT_LEAD** — 15s spot impulse ≥ +5bp with Kalshi not already dumping.
 *   Exit when the impulse decays below half the entry threshold
 *   (MOMENTUM_FADE).
 * - **RANGE_FADE** — quiet regime (window mid std ≤ 1.5¢) only: fade a
 *   ≥ 3¢ extension below the rolling range midpoint, stabilized. Exit at the
 *   range midpoint (TARGET). No entries at all in volatile regimes.
 * - **LATE_DRIFT** — final 90s only: mid ≥ 58¢ AND whole-window velocity
 *   positive (YES winning and still drifting — convergence pressure, with
 *   the trend, never against). Never enters inside the window-close buffer.
 * - **OPEN_DRIVE** — first 60s only: opening velocity one-directional for
 *   ≥ 10s, non-negative accel, spot agreement when available.
 *
 * ## Exits (priority order), all filled at the bid
 * 1. TARGET — bid ≥ entry + TP (also: VWAP touch for VWAP_REVERT, range-mid
 *    touch for RANGE_FADE, mid back at 50¢ ± band for EXTREME_REVERSAL).
 * 2. MOMENTUM_FADE — the entry signal decayed (momentum reversed, imbalance
 *    flipped, pulse recovered, spot impulse decayed).
 * 3. STOP — bid ≤ entry − SL.
 * 4. TIMEOUT — held ≥ maxHoldMs.
 * WINDOW_CLOSE is never emitted here — it is the engine's force-exit a few
 * seconds before the window settles (paper positions can't carry over).
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
    /** EXTREME_REVERSAL: snap-back zone edges (cents). */
    private val extremeLowPp: Double = 8.0,
    private val extremeHighPp: Double = 92.0,
    /** EXTREME_REVERSAL: exit TARGET once mid is back within this of 50¢. */
    private val backAt50BandPp: Double = 3.0,
    // ---- swarm strategies (bitcoin-swarm experiment) -----------------------
    /** VWAP_REVERT: enter when mid is at least this far below the VWAP proxy. */
    private val vwapK: Double = 3.0,
    /** FIFTY_FLIP: the ±band around 50¢ a cross must originate from. */
    private val fiftyBandPp: Double = 4.0,
    /** FIFTY_FLIP: minimum velocity through the cross. */
    private val fiftyVelocityPpPerSec: Double = 0.3,
    /** FIFTY_FLIP: minimum bid-side imbalance supporting the cross. */
    private val fiftyImbalanceMin: Double = 0.1,
    /** BOOK_IMBALANCE: enter at/above this near-mid imbalance. */
    private val imbalanceEnter: Double = 0.5,
    /** BOOK_IMBALANCE: exit (fade) when imbalance decays below this. */
    private val imbalanceExit: Double = 0.05,
    /** BOOK_IMBALANCE: refuse to enter when |velocity| exceeds this. */
    private val imbalanceMaxAbsVelocity: Double = 1.0,
    /** PULSE_SNIPE: |cancelSpike| beyond this counts as an extreme burst. */
    private val pulseEnter: Double = 0.6,
    /** PULSE_SNIPE: require at least this quotePull (re-support) to fade. */
    private val pulseQuotePullMin: Double = 0.2,
    /** PULSE_SNIPE: exit once cancelSpike recovers above this. */
    private val pulseRecover: Double = -0.2,
    /** SPOT_LEAD: minimum 15s spot impulse (fraction) to enter. */
    private val spotEnter: Double = 0.0005,
    /** RANGE_FADE: quiet-regime ceiling on window mid std. */
    private val rangeQuietStdPp: Double = 1.5,
    /** RANGE_FADE: minimum extension from range midpoint to fade. */
    private val rangeExtensionPp: Double = 3.0,
    /** RANGE_FADE: minimum samples before the range is meaningful. */
    private val rangeMinSamples: Int = 10,
    /** LATE_DRIFT: how much window remains when this strategy arms. */
    private val lateDriftLeadMs: Long = 90_000L,
    /** LATE_DRIFT: minimum mid (YES side only — never against convergence). */
    private val lateDriftMinMidPp: Double = 58.0,
    /** LATE_DRIFT: minimum whole-window velocity. */
    private val lateDriftMinVelocity: Double = 0.05,
    /** OPEN_DRIVE: only the first this-many ms of a window. */
    private val openDriveMaxAgeMs: Long = 60_000L,
    /** OPEN_DRIVE: the drive must have run at least this long. */
    private val openDriveMinSpanMs: Long = 10_000L,
    /** OPEN_DRIVE: minimum whole-window velocity. */
    private val openDriveMinVelocity: Double = 0.2,
    /** No entries inside this buffer before close — the engine force-exits. */
    private val windowCloseBufferMs: Long = 5_000L
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
        closeTimeEpochMs: Long? = null,
        pulse: LocalOrderBook.Pulse? = null,
        spot: ScalpSpot? = null
    ): ScalpDecision.ScalpDecision {
        if (position != null) return exitDecision(features, position, bestBidCents, imbalance, pulse, spot, nowMs)
        return entryDecision(features, bestBidCents, bestAskCents, imbalance, nowMs, closeTimeEpochMs, pulse, spot)
    }

    private fun entryDecision(
        features: ScalpMath.ScalpFeatures,
        bestBidCents: Int?,
        bestAskCents: Int?,
        imbalance: Double?,
        nowMs: Long,
        closeTimeEpochMs: Long?,
        pulse: LocalOrderBook.Pulse?,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        val ask = bestAskCents ?: return ScalpDecision.Hold
        val bid = bestBidCents ?: return ScalpDecision.Hold
        if (ask - bid > maxSpreadCents) return ScalpDecision.Hold
        if (imbalance != null && imbalance < -maxAdverseImbalance) return ScalpDecision.Hold

        return when (strategy) {
            ScalpStrategy.DIP_HUNT -> dipHuntEntry(features, ask)
            ScalpStrategy.MOMENTUM_SNIPER -> momentumEntry(features, ask)
            ScalpStrategy.EXTREME_REVERSAL -> reversalEntry(features, ask)
            ScalpStrategy.VWAP_REVERT -> vwapEntry(features, ask)
            ScalpStrategy.FIFTY_FLIP -> fiftyFlipEntry(features, ask, imbalance)
            ScalpStrategy.BOOK_IMBALANCE -> bookImbalanceEntry(features, ask, imbalance)
            ScalpStrategy.PULSE_SNIPE -> pulseSnipeEntry(features, ask, pulse)
            ScalpStrategy.SPOT_LEAD -> spotLeadEntry(features, ask, spot)
            ScalpStrategy.RANGE_FADE -> rangeFadeEntry(features, ask)
            ScalpStrategy.LATE_DRIFT -> lateDriftEntry(features, ask, nowMs, closeTimeEpochMs)
            ScalpStrategy.OPEN_DRIVE -> openDriveEntry(features, ask, spot)
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
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun momentumEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        // Full-window strategy: late-window momentum is where convergence
        // moves get violent — deliberately NO close-time gate here.
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

    private fun vwapEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        val vwap = features.vwapPp ?: return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (vwap - last + 1e-9 < vwapK) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun fiftyFlipEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        imbalance: Double?
    ): ScalpDecision.ScalpDecision {
        val prev = features.prevMidPp ?: return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        // YES-long: only the upward cross is actionable.
        if (prev >= 50.0 || last < 50.0) return ScalpDecision.Hold
        if (prev < 50.0 - fiftyBandPp) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < fiftyVelocityPpPerSec) {
            return ScalpDecision.Hold
        }
        if ((imbalance ?: return ScalpDecision.Hold) < fiftyImbalanceMin) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun bookImbalanceEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        imbalance: Double?
    ): ScalpDecision.ScalpDecision {
        if ((imbalance ?: return ScalpDecision.Hold) < imbalanceEnter) return ScalpDecision.Hold
        // Don't step in front of a freight train: a runaway tape overrides depth.
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        if (abs(velocity) > imbalanceMaxAbsVelocity) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun pulseSnipeEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        pulse: LocalOrderBook.Pulse?
    ): ScalpDecision.ScalpDecision {
        val p = pulse ?: return ScalpDecision.Hold
        // Negative cancelSpike = YES-side cancel burst (bid support pulled).
        if (p.cancelSpike > -pulseEnter) return ScalpDecision.Hold
        // Fade only when quotes re-support: asks pulled / bids rebuilt.
        if (p.quotePull < pulseQuotePullMin) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun spotLeadEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        val impulse = spot?.impulse ?: return ScalpDecision.Hold
        if (impulse < spotEnter) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -0.2) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun rangeFadeEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        val std = features.midStdPp ?: return ScalpDecision.Hold
        if (std > rangeQuietStdPp) return ScalpDecision.Hold
        if (features.sampleCount < rangeMinSamples) return ScalpDecision.Hold
        val rangeMid = rangeMid(features) ?: return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (rangeMid - last + 1e-9 < rangeExtensionPp) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun lateDriftEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        nowMs: Long,
        closeTimeEpochMs: Long?
    ): ScalpDecision.ScalpDecision {
        val closeAt = closeTimeEpochMs ?: return ScalpDecision.Hold
        val room = closeAt - nowMs
        if (room > lateDriftLeadMs || room < windowCloseBufferMs) return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        // YES side only, WITH the trend: mid ≥ 58 and the whole-window move up.
        if (last < lateDriftMinMidPp) return ScalpDecision.Hold
        if ((features.windowVelocityPpPerSec ?: return ScalpDecision.Hold) < lateDriftMinVelocity) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun openDriveEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        if (features.windowSpanMs < openDriveMinSpanMs) return ScalpDecision.Hold
        if (features.windowSpanMs > openDriveMaxAgeMs) return ScalpDecision.Hold
        if ((features.windowVelocityPpPerSec ?: return ScalpDecision.Hold) < openDriveMinVelocity) {
            return ScalpDecision.Hold
        }
        if ((features.accelerationPpPerSec2 ?: return ScalpDecision.Hold) < -1e-9) return ScalpDecision.Hold
        // Spot agreement when the feature is available; absent = ok.
        val impulse = spot?.impulse
        if (impulse != null && impulse < 0.0) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun stabilizing(features: ScalpMath.ScalpFeatures): Boolean {
        val velocity = features.velocityPpPerSec ?: return false
        val acceleration = features.accelerationPpPerSec2 ?: return false
        return acceleration >= -1e-9 || abs(velocity) <= stableVelocityPpPerSec + 1e-9
    }

    private fun rangeMid(features: ScalpMath.ScalpFeatures): Double? {
        val lo = features.windowMinPp ?: return null
        val hi = features.windowMaxPp ?: return null
        return lo + (hi - lo) / 2.0
    }

    private fun exitDecision(
        features: ScalpMath.ScalpFeatures,
        position: OpenPosition,
        bestBidCents: Int?,
        imbalance: Double?,
        pulse: LocalOrderBook.Pulse?,
        spot: ScalpSpot?,
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
            ScalpStrategy.BOOK_IMBALANCE -> {
                // Signal decayed: depth no longer supports the drift.
                if ((imbalance ?: 1.0) < imbalanceExit) {
                    return ScalpDecision.Exit(ExitReason.MOMENTUM_FADE, bid)
                }
            }
            ScalpStrategy.PULSE_SNIPE -> {
                // The cancel burst unwound — no longer a liquidity dip.
                if ((pulse?.cancelSpike ?: -1.0) > pulseRecover) {
                    return ScalpDecision.Exit(ExitReason.MOMENTUM_FADE, bid)
                }
            }
            ScalpStrategy.SPOT_LEAD -> {
                // The spot impulse decayed — the lead is gone.
                if ((spot?.impulse ?: 1.0) < spotEnter / 2.0) {
                    return ScalpDecision.Exit(ExitReason.MOMENTUM_FADE, bid)
                }
            }
            ScalpStrategy.VWAP_REVERT -> {
                val vwap = features.vwapPp
                if (vwap != null && bid >= vwap - 0.5) {
                    return ScalpDecision.Exit(ExitReason.TARGET, bid)
                }
            }
            ScalpStrategy.RANGE_FADE -> {
                val mid = rangeMid(features)
                if (mid != null && bid >= mid) {
                    return ScalpDecision.Exit(ExitReason.TARGET, bid)
                }
            }
            ScalpStrategy.EXTREME_REVERSAL -> {
                val last = features.lastMidPp
                if (last != null && abs(last - 50.0) <= backAt50BandPp + 1e-9) {
                    return ScalpDecision.Exit(ExitReason.TARGET, bid)
                }
            }
            else -> Unit
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
