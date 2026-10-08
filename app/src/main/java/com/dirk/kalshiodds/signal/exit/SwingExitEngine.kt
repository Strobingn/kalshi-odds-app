package com.dirk.kalshiodds.signal.exit

import com.dirk.kalshiodds.signal.trade.KalshiFee

/**
 * Swing-exit brain for an open Kalshi position.
 *
 * The old loop only knew how to get **in**: buy a cheap contract and ride
 * it into the settlement print. This engine handles the other half — get
 * **out** while the getting is good. Every tick it compares three numbers:
 *
 *     sellNet  = exit bid − taker fee on the sell      (certain, now)
 *     evHold   = P(side wins) × $1                     (risky, at expiry)
 *     peak     = best exit bid since entry             (what we had)
 *
 * and sells (proposes a reduce-only sell ticket — the app never
 * auto-places live orders) when one of these fires:
 *
 *  1. STOP_LOSS — bid fell ≥ stopLoss below entry. Cut it; don't pray.
 *  2. TIME_GUARD — inside the last minute with a profit on the table:
 *     bank it instead of sweating the 60-sample settlement average.
 *  3. TRAILING_STOP — the turn the user asked for: price ran up, then
 *     gave back ≥ trailingStop from the peak while still in profit.
 *  4. MOMENTUM_FLIP — the model's fair value is falling fast (EMA slope)
 *     and the bid has started slipping: the move is over, front-run the
 *     repricing.
 *  5. LOCK_OVERPRICED — the market bid pays more than the model says
 *     holding is worth (plus fees). Take the money.
 *
 * Two deliberate **hold** overrides:
 *  - Deep ITM (fair ≥ 0.97): settlement is nearly certain, so selling
 *    just donates the exit fee. Ride it.
 *  - Warm-up (first ~15 s): 15-minute binaries twitch constantly; the
 *    trailing stop stays off until the thesis has had time to breathe.
 *    (STOP_LOSS and TIME_GUARD stay armed.)
 *
 * Once SELL fires it stays fired ([State.fired]) until the caller drops
 * the state (position closed) — no flicker between hold/sell.
 *
 * Pure math, no Android, no network — analysis only.
 */
object SwingExitEngine {

    enum class Reason { STOP_LOSS, TIME_GUARD, TRAILING_STOP, MOMENTUM_FLIP, LOCK_OVERPRICED }
    enum class Action { HOLD, SELL }

    data class Config(
        /** Give-back from the peak bid that confirms the turn. */
        val trailingStopDollars: Double = 0.04,
        /** Sustained fair-value slope (prob/sec) that marks a reversal. */
        val momentumSlopePerSec: Double = -0.0020,
        /** Bid must also be off the peak by this much for MOMENTUM_FLIP. */
        val momentumMinDrawdownDollars: Double = 0.015,
        /** Hard loss cut below entry. */
        val stopLossDollars: Double = 0.08,
        /** Trailing/momentum/lock rules stay off for this long after entry. */
        val minHoldMs: Long = 15_000L,
        /** Bank profit this far before the settlement print. */
        val exitBeforeCloseMs: Long = 60_000L,
        /** At or above this model probability, ride into settlement. */
        val deepItmProb: Double = 0.97,
        /** Minimum locked profit / model beat before any profit-exit fires. */
        val lockMarginDollars: Double = 0.01,
        val feeRate: Double = 0.07,
        /** EMA weight for the newest fair-value slope sample. */
        val slopeEmaAlpha: Double = 0.35
    )

    data class State(
        val side: String,
        val entryPrice: Double,
        val openedAtMs: Long,
        val peakBid: Double,
        val lastFairSide: Double?,
        val slopePerSec: Double,
        val lastTickMs: Long,
        val fired: Reason?
    )

    fun initial(
        side: String,
        entryPrice: Double,
        openedAtMs: Long,
        firstBid: Double,
        fairSide: Double?,
        firstTickMs: Long
    ): State = State(
        side = side.uppercase().let { if (it == "NO") "NO" else "YES" },
        entryPrice = entryPrice,
        openedAtMs = openedAtMs,
        peakBid = firstBid,
        lastFairSide = fairSide?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
        slopePerSec = 0.0,
        lastTickMs = firstTickMs,
        fired = null
    )

    data class Decision(
        val action: Action,
        val reason: Reason?,
        val state: State,
        /** Bid − exit taker fee, per contract. -inf when there is no bid. */
        val sellNetPerContract: Double,
        /** Model value of holding to settlement, per contract ($1 × P). */
        val evHoldPerContract: Double,
        val drawdownFromPeak: Double,
        /** sellNet − entry: what selling now locks in, per contract. */
        val lockedPnlPerContract: Double,
        val note: String
    )

    fun key(ticker: String, side: String): String = "${ticker.uppercase()}:${side.uppercase()}"

    fun update(
        state: State,
        exitBid: Double?,
        fairSide: Double?,
        contracts: Int,
        nowMs: Long,
        closeTimeMs: Long?,
        config: Config = Config()
    ): Decision {
        val bid = exitBid?.takeIf { it.isFinite() && it > 0.0 }
        val fair = fairSide?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val evHold = fair ?: bid ?: state.entryPrice
        val stakeUsd = if (bid != null && contracts > 0) bid * contracts else 5.0
        val exitFee = if (bid != null) {
            KalshiFee.perContract(bid, config.feeRate, stakeUsd)
        } else {
            Double.POSITIVE_INFINITY
        }
        val sellNet = if (bid != null) bid - exitFee else Double.NEGATIVE_INFINITY

        // Fair-value momentum: EMA of d(prob)/dt in probability per second.
        val dtMs = (nowMs - state.lastTickMs).coerceAtLeast(0L)
        var slope = state.slopePerSec
        if (fair != null && state.lastFairSide != null && dtMs >= 200L) {
            val raw = (fair - state.lastFairSide) / (dtMs / 1000.0)
            val alpha = config.slopeEmaAlpha.coerceIn(0.05, 1.0)
            slope = alpha * raw + (1.0 - alpha) * state.slopePerSec
        }
        val peak = if (bid != null) maxOf(state.peakBid, bid) else state.peakBid
        val drawdown = if (bid != null) (peak - bid).coerceAtLeast(0.0) else 0.0
        val locked = if (bid != null) sellNet - state.entryPrice else Double.NEGATIVE_INFINITY

        val reason = state.fired ?: decide(
            state, bid, fair, sellNet, evHold, drawdown, locked, slope, nowMs, closeTimeMs, config
        )
        val next = state.copy(
            peakBid = peak,
            lastFairSide = fair ?: state.lastFairSide,
            slopePerSec = slope,
            lastTickMs = nowMs,
            fired = reason
        )
        val note = when (reason) {
            Reason.STOP_LOSS -> "stop-loss: bid ${pct(bid)} ≤ entry ${pct(state.entryPrice)} − ${pct(config.stopLossDollars)}"
            Reason.TIME_GUARD -> "closing soon — bank ${pct(bid)} vs ${pct(state.entryPrice)} entry"
            Reason.TRAILING_STOP -> "peak ${pct(peak)} gave back ${pct(drawdown)}"
            Reason.MOMENTUM_FLIP -> "model turning (${cents(slope)}¢/s), bid off peak ${pct(drawdown)}"
            Reason.LOCK_OVERPRICED -> "bid ${pct(bid)} beats model hold ${pct(evHold)}"
            null -> if (bid == null) "no exit bid" else "holding · peak ${pct(peak)} · locked ${signCents(locked)}¢"
        }
        return Decision(
            action = if (reason != null) Action.SELL else Action.HOLD,
            reason = reason,
            state = next,
            sellNetPerContract = sellNet,
            evHoldPerContract = evHold,
            drawdownFromPeak = drawdown,
            lockedPnlPerContract = locked,
            note = note
        )
    }

    private fun decide(
        state: State,
        bid: Double?,
        fair: Double?,
        sellNet: Double,
        evHold: Double,
        drawdown: Double,
        locked: Double,
        slope: Double,
        nowMs: Long,
        closeTimeMs: Long?,
        config: Config
    ): Reason? {
        if (bid == null) return null
        // 1. Hard stop-loss — armed from the first second.
        if (bid <= state.entryPrice - config.stopLossDollars) return Reason.STOP_LOSS
        // 2. Deep ITM: settlement is ~decided; selling just pays a fee.
        if (fair != null && fair >= config.deepItmProb) return null
        // 3. Final minute: bank a profit rather than sweat the print.
        if (closeTimeMs != null && nowMs >= closeTimeMs - config.exitBeforeCloseMs &&
            bid >= state.entryPrice
        ) {
            return Reason.TIME_GUARD
        }
        // Warm-up gate for the profit-taking rules below.
        if (nowMs - state.openedAtMs < config.minHoldMs) return null
        val profitable = locked >= config.lockMarginDollars
        if (!profitable) return null
        // 4. The turn: ran up, gave back the trailing amount.
        if (drawdown >= config.trailingStopDollars) return Reason.TRAILING_STOP
        // 5. Model momentum flipped down and the bid is slipping.
        if (slope <= config.momentumSlopePerSec &&
            drawdown >= config.momentumMinDrawdownDollars
        ) {
            return Reason.MOMENTUM_FLIP
        }
        // 6. Market overpays versus the model's hold value.
        if (sellNet >= evHold + config.lockMarginDollars) return Reason.LOCK_OVERPRICED
        return null
    }

    private fun pct(v: Double?): String =
        if (v == null || !v.isFinite()) "—" else String.format(java.util.Locale.US, "%.1f¢", v * 100.0)

    private fun cents(v: Double): String =
        String.format(java.util.Locale.US, "%.3f", v * 100.0)

    private fun signCents(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%+.1f", v * 100.0)
}
