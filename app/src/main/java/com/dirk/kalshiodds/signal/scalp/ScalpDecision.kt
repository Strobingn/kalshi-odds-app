package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import kotlin.math.abs

enum class ExitReason { TARGET, STOP, TIMEOUT, MOMENTUM_FADE, WINDOW_CLOSE }

/**
 * Spot impulse for SPOT_LEAD / OPEN_DRIVE / SPOT_DIVERGENCE / SPOT_REBOUND.
 * Fractions, not percent: 0.0006 = a 6bp move. Nulls mean the feature is
 * unavailable (stream disconnected / series unsupported) — strategies must
 * fail soft.
 */
data class ScalpSpot(
    /** Spot return over the last ~15s. */
    val return15s: Double? = null,
    /** Spot return over the last 1m. */
    val return1m: Double? = null,
    /** Age of the session low over the provider's horizon (ms). */
    val sessionLowAgeMs: Long? = null,
    /** Fraction the last spot print sits above that session low (2bp = 0.0002). */
    val sessionLowRebound: Double? = null
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
 * - **DOUBLE_DIP** — two lows in the window, second ≥1¢ higher, mid ≤45¢,
 *   book no longer ask-heavy (accumulation). Skipped while the tape falls.
 * - **CAPITULATION_FADE** — ≥3¢ off the window high with the low ≤30s old,
 *   on the FIRST uptick with a stabilizing tape (seller exhaustion).
 * - **WICK_BUY** — mid ran ≥4¢ below the fast EMA inside the window (stop
 *   sweep) and reclaims the EMA this tick. Shortest hold in the roster.
 * - **SPOT_DIVERGENCE** — Kalshi ≥2¢ off its window high while spot's 1m
 *   return is flat-to-up (≥ −5bp): venue-local panic, not information. Exit
 *   on spot-impulse decay (MOMENTUM_FADE).
 * - **SPOT_REBOUND** — spot ≥2bp off a session low ≤60s old and heating up
 *   (15s impulse above the 1m pace) while Kalshi lags (≥2¢ off its high,
 *   velocity ≤0.2¢/s). Exit on spot-impulse decay (MOMENTUM_FADE).
 * - **IMBALANCE_FLIP** — min imbalance ≤ −0.4 over the last 30s, now ≥ +0.2,
 *   |velocity| ≤1.0 (depth rotated, tape not runaway). Exit below +0.05
 *   (MOMENTUM_FADE).
 * - **SLOW_BLEED_FADE** — velocity sustained in −0.05..−0.2¢/s for ≥90s,
 *   bleed not accelerating, spot flat (|1m| ≤5bp): the grind is exhausted.
 * - **VOLUME_DRYUP** — spread ≥3¢ (quotes pulled), depth not ask-heavy, mid
 *   ≤1.5¢ above the window low, first uptick: thin books turn fast.
 * - **RANGE_ANCHOR** — window min ≤45¢, whole range ≤6¢, ≥2min of samples,
 *   mid just ticked up off the floor (the level is defended).
 *
 * The 50¢ max-entry gate is NOT here — it lives in the engine, one shared
 * check before any decider runs. All of the above respect it by design.
 *
 * ## Exits (priority order), all filled at the bid
 * 1. TARGET — bid ≥ entry + TP (also: VWAP touch for VWAP_REVERT, range-mid
 *    touch for RANGE_FADE, mid back at 50¢ ± band for EXTREME_REVERSAL).
 * 2. MOMENTUM_FADE — the entry signal decayed (momentum reversed, imbalance
 *    flipped/decayed, pulse recovered, spot impulse decayed).
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
    // ---- 20-strategy roster (bitcoin-swarm experiment) ----------------------
    /** DOUBLE_DIP: minimum samples before two lows are meaningful. */
    private val doubleDipMinSamples: Int = 30,
    /** DOUBLE_DIP: the whole roster's 50c gate aside, this buyer only works
     *  deep in the YES-cheap zone. */
    private val doubleDipMaxMidPp: Double = 45.0,
    /** DOUBLE_DIP: second low must be at least this far above the first. */
    private val doubleDipHigherLowMinPp: Double = 1.0,
    /** DOUBLE_DIP: book must be at least this bid-side (0.0 = not ask-heavy). */
    private val doubleDipImbalanceMin: Double = 0.0,
    /** CAPITULATION_FADE: minimum drop from the window high. */
    private val capitulationDropPp: Double = 3.0,
    /** CAPITULATION_FADE: the window low must be this fresh (ms). */
    private val capitulationLowMaxAgeMs: Long = 30_000L,
    /** WICK_BUY: minimum sweep below the fast EMA within the window. */
    private val wickDipMinPp: Double = 4.0,
    /** SPOT_DIVERGENCE: minimum Kalshi drop from the window high. */
    private val spotDivergenceDropPp: Double = 2.0,
    /** SPOT_DIVERGENCE: spot 1m must be at least this (≥ −5bp = flat-to-up). */
    private val spotDivergenceFlatMin: Double = -0.0005,
    /** SPOT_DIVERGENCE: minimum samples before the window means anything. */
    private val spotDivergenceMinSamples: Int = 20,
    /** SPOT_REBOUND: the session low must be this fresh (ms). */
    private val spotReboundMaxLowAgeMs: Long = 60_000L,
    /** SPOT_REBOUND: minimum bounce off the session low (fraction, 2bp). */
    private val spotReboundMin: Double = 0.0002,
    /** SPOT_REBOUND: Kalshi must still be this far off its window high. */
    private val spotReboundKalshiLagPp: Double = 2.0,
    /** SPOT_REBOUND: refuse when Kalshi already ran (¢/s). */
    private val spotReboundKalshiMaxVelocity: Double = 0.2,
    /** IMBALANCE_FLIP: the recent (30s) low imbalance must be at/under this. */
    private val flipLowImbalance: Double = -0.4,
    /** IMBALANCE_FLIP: current imbalance must reach at least this. */
    private val flipEnterImbalance: Double = 0.2,
    /** IMBALANCE_FLIP: exit (fade) when imbalance decays below this. */
    private val flipExitImbalance: Double = 0.05,
    /** SLOW_BLEED_FADE: minimum samples (≥90s of history at 1/s). */
    private val slowBleedMinSamples: Int = 90,
    /** SLOW_BLEED_FADE: |velocity| band edges (¢/s) — the bleed must be slow. */
    private val slowBleedMinAbsVelocity: Double = 0.05,
    private val slowBleedMaxAbsVelocity: Double = 0.2,
    /** SLOW_BLEED_FADE: spot is "flat" when |1m return| stays under this. */
    private val slowBleedSpotFlatMax: Double = 0.0005,
    /** SLOW_BLEED_FADE: the bleed may not be accelerating (¢/s² floor). */
    private val slowBleedMaxAccel: Double = 0.02,
    /** VOLUME_DRYUP: spread this wide = quotes pulled (activity collapsed). */
    private val dryupSpreadCents: Int = 3,
    /** VOLUME_DRYUP: depth may not be ask-heavy while we buy the low. */
    private val dryupImbalanceMin: Double = 0.0,
    /** VOLUME_DRYUP: mid must sit within this of the window low. */
    private val dryupNearLowPp: Double = 1.5,
    /** RANGE_ANCHOR: minimum samples (≥2min at 1/s). */
    private val rangeAnchorMinSamples: Int = 120,
    /** RANGE_ANCHOR: the defended floor must be at/under this. */
    private val rangeAnchorMaxFloorPp: Double = 45.0,
    /** RANGE_ANCHOR: the whole-window range must stay at/under this. */
    private val rangeAnchorMaxRangePp: Double = 6.0,
    /** RANGE_ANCHOR: mid may be at most this far above the floor. */
    private val rangeAnchorTickUpPp: Double = 1.0,
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
        spot: ScalpSpot? = null,
        /** Best-guess top-of-book spread in cents (VOLUME_DRYUP). Null = soft-off. */
        spreadCents: Int? = null,
        /** Min near-mid imbalance over the last ~30s (IMBALANCE_FLIP). Null = soft-off. */
        imbalanceLow: Double? = null
    ): ScalpDecision.ScalpDecision {
        if (position != null) return exitDecision(features, position, bestBidCents, imbalance, pulse, spot, nowMs)
        return entryDecision(features, bestBidCents, bestAskCents, imbalance, nowMs, closeTimeEpochMs, pulse, spot, spreadCents, imbalanceLow)
    }

    private fun entryDecision(
        features: ScalpMath.ScalpFeatures,
        bestBidCents: Int?,
        bestAskCents: Int?,
        imbalance: Double?,
        nowMs: Long,
        closeTimeEpochMs: Long?,
        pulse: LocalOrderBook.Pulse?,
        spot: ScalpSpot?,
        spreadCents: Int?,
        imbalanceLow: Double?
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
            ScalpStrategy.DOUBLE_DIP -> doubleDipEntry(features, ask, imbalance)
            ScalpStrategy.CAPITULATION_FADE -> capitulationEntry(features, ask)
            ScalpStrategy.WICK_BUY -> wickBuyEntry(features, ask)
            ScalpStrategy.SPOT_DIVERGENCE -> spotDivergenceEntry(features, ask, spot)
            ScalpStrategy.SPOT_REBOUND -> spotReboundEntry(features, ask, spot)
            ScalpStrategy.IMBALANCE_FLIP -> imbalanceFlipEntry(features, ask, imbalance, imbalanceLow)
            ScalpStrategy.SLOW_BLEED_FADE -> slowBleedEntry(features, ask, spot)
            ScalpStrategy.VOLUME_DRYUP -> volumeDryupEntry(features, ask, imbalance, spreadCents)
            ScalpStrategy.RANGE_ANCHOR -> rangeAnchorEntry(features, ask)
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

    // ---- 20-strategy roster (bitcoin-swarm experiment) -----------------------

    private fun doubleDipEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        imbalance: Double?
    ): ScalpDecision.ScalpDecision {
        if (features.sampleCount < doubleDipMinSamples) return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (last > doubleDipMaxMidPp) return ScalpDecision.Hold
        val firstLow = features.windowMinPp ?: return ScalpDecision.Hold
        val secondLow = features.secondLowPp ?: return ScalpDecision.Hold
        // A genuinely HIGHER second low — an exact retest means the level is
        // untested support, not accumulation.
        if (secondLow + 1e-9 < firstLow + doubleDipHigherLowMinPp) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        // The book must no longer be ask-heavy — sellers done, buyers absorbing.
        if ((imbalance ?: return ScalpDecision.Hold) < doubleDipImbalanceMin) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun capitulationEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        val drop = features.dropFromWindowMaxPp ?: return ScalpDecision.Hold
        if (drop + 1e-9 < capitulationDropPp) return ScalpDecision.Hold
        // The low that confirms the capitulation must be recent — a 3¢ drop
        // off a high from ten minutes ago is a trend, not a flush.
        val lowAge = features.windowMinAgeMs ?: return ScalpDecision.Hold
        if (lowAge > capitulationLowMaxAgeMs) return ScalpDecision.Hold
        // The FIRST uptick after the low: prev must still be at/below the
        // current print.
        val prev = features.prevMidPp ?: return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (prev + 1e-9 >= last) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun wickBuyEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        // The sweep already happened (maxDropFromShortEma within the window)…
        val swept = features.maxDropFromShortEmaPp ?: return ScalpDecision.Hold
        if (swept + 1e-9 < wickDipMinPp) return ScalpDecision.Hold
        // …and this tick reclaimed the fast EMA (mid back at/above it).
        val ema = features.shortEmaPp ?: return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (last + 1e-9 < ema) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun spotDivergenceEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        // Spot is the information leg: flat-to-up over 1m means the Kalshi dip
        // is venue-local panic, not news.
        val flat = spot?.return1m ?: return ScalpDecision.Hold
        if (flat < spotDivergenceFlatMin) return ScalpDecision.Hold
        val drop = features.dropFromWindowMaxPp ?: return ScalpDecision.Hold
        if (drop + 1e-9 < spotDivergenceDropPp) return ScalpDecision.Hold
        if (features.sampleCount < spotDivergenceMinSamples) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun spotReboundEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        val s = spot ?: return ScalpDecision.Hold
        val age = s.sessionLowAgeMs ?: return ScalpDecision.Hold
        if (age > spotReboundMaxLowAgeMs) return ScalpDecision.Hold
        val rebound = s.sessionLowRebound ?: return ScalpDecision.Hold
        if (rebound + 1e-9 < spotReboundMin) return ScalpDecision.Hold
        // Heating up: the short impulse must exceed the 1m average pace.
        val impulse = s.impulse ?: return ScalpDecision.Hold
        val heat = s.return1m?.let { impulse > it / 4.0 } ?: true
        if (!heat) return ScalpDecision.Hold
        // Kalshi hasn't followed yet: still off its high and not already run.
        val drop = features.dropFromWindowMaxPp ?: return ScalpDecision.Hold
        if (drop + 1e-9 < spotReboundKalshiLagPp) return ScalpDecision.Hold
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        if (velocity > spotReboundKalshiMaxVelocity) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun imbalanceFlipEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        imbalance: Double?,
        imbalanceLow: Double?
    ): ScalpDecision.ScalpDecision {
        // The rotation must have actually happened: ask-heavy depth within the
        // last 30s, bid-heavy now.
        val low = imbalanceLow ?: return ScalpDecision.Hold
        if (low > flipLowImbalance) return ScalpDecision.Hold
        if ((imbalance ?: return ScalpDecision.Hold) < flipEnterImbalance) return ScalpDecision.Hold
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        if (abs(velocity) > imbalanceMaxAbsVelocity) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun slowBleedEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        spot: ScalpSpot?
    ): ScalpDecision.ScalpDecision {
        if (features.sampleCount < slowBleedMinSamples) return ScalpDecision.Hold
        val velocity = features.velocityPpPerSec ?: return ScalpDecision.Hold
        // Sustained SMALL negative drift — inside the band only. A fast fall is
        // DIP_HUNT's falling knife, not an exhaustion bleed.
        if (velocity > -slowBleedMinAbsVelocity + 1e-9) return ScalpDecision.Hold
        if (velocity < -slowBleedMaxAbsVelocity - 1e-9) return ScalpDecision.Hold
        // The bleed may not be accelerating into a collapse.
        if ((features.accelerationPpPerSec2 ?: 0.0) < -slowBleedMaxAccel - 1e-9) {
            return ScalpDecision.Hold
        }
        // Spot flat: a real spot move means the bleed is information, not noise.
        val s1m = spot?.return1m
        if (s1m != null && abs(s1m) > slowBleedSpotFlatMax) return ScalpDecision.Hold
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun volumeDryupEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int,
        imbalance: Double?,
        spreadCents: Int?
    ): ScalpDecision.ScalpDecision {
        // Quotes pulled = activity collapsed. The common spread gate already
        // caps at maxSpreadCents, so a 3¢ print here means "as wide as allowed".
        val spread = spreadCents ?: return ScalpDecision.Hold
        if (spread < dryupSpreadCents) return ScalpDecision.Hold
        // …but the remaining depth may not be ask-heavy.
        if ((imbalance ?: return ScalpDecision.Hold) < dryupImbalanceMin) return ScalpDecision.Hold
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        val min = features.windowMinPp ?: return ScalpDecision.Hold
        if (last > min + dryupNearLowPp) return ScalpDecision.Hold
        // First bid lift: an uptick off the floor on thinning quotes.
        val prev = features.prevMidPp ?: return ScalpDecision.Hold
        if (prev + 1e-9 >= last) return ScalpDecision.Hold
        if (!stabilizing(features)) return ScalpDecision.Hold
        if ((features.velocityPpPerSec ?: return ScalpDecision.Hold) < -maxFallPpPerSec - 1e-9) {
            return ScalpDecision.Hold
        }
        return ScalpDecision.Enter(entryPriceCents = ask)
    }

    private fun rangeAnchorEntry(
        features: ScalpMath.ScalpFeatures,
        ask: Int
    ): ScalpDecision.ScalpDecision {
        if (features.sampleCount < rangeAnchorMinSamples) return ScalpDecision.Hold
        val min = features.windowMinPp ?: return ScalpDecision.Hold
        if (min > rangeAnchorMaxFloorPp) return ScalpDecision.Hold
        val max = features.windowMaxPp ?: return ScalpDecision.Hold
        if (max - min > rangeAnchorMaxRangePp) return ScalpDecision.Hold
        // Just ticked up off the defended floor.
        val last = features.lastMidPp ?: return ScalpDecision.Hold
        if (last > min + rangeAnchorTickUpPp) return ScalpDecision.Hold
        val prev = features.prevMidPp ?: return ScalpDecision.Hold
        if (prev + 1e-9 >= last) return ScalpDecision.Hold
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
            ScalpStrategy.SPOT_DIVERGENCE, ScalpStrategy.SPOT_REBOUND -> {
                // The divergence/rebound thesis was the spot impulse — when it
                // decays below half the SPOT_LEAD threshold there is no lead
                // left to converge. Null spot mid-position = treat as decayed.
                if ((spot?.impulse ?: 0.0) < spotEnter / 2.0) {
                    return ScalpDecision.Exit(ExitReason.MOMENTUM_FADE, bid)
                }
            }
            ScalpStrategy.IMBALANCE_FLIP -> {
                // The rotation unwound — depth no longer bid-heavy.
                if ((imbalance ?: 1.0) < flipExitImbalance) {
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
