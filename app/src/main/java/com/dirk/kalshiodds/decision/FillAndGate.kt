package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transparent, deterministic fill model (a heuristic, not a trained model).
 *
 *   expected_net = P(fill) × [P(win) × payout − price − fee − adverse selection]
 *
 * P(fill) for a taker at the displayed ask depends on book freshness and
 * whether the displayed size covers the order. For a resting bid it also
 * depends on the queue ahead, spread, imbalance, and time left.
 */
object FillModel {
    const val VERSION = "fill-heuristic-v2"
    const val MIN_P_FILL = 0.35
    /** A book older than this is stale: NO BET and no paper fill. */
    const val FRESH_BOOK_MS = 60_000L
    const val FRESHNESS_TAU_MS = 30_000.0

    data class Quote(
        val marketable: Boolean,
        val price: Double,
        val size: Double,
        /** Taker: displayed size at/through the price. Maker: queue ahead of us at our price. */
        val depth: Double,
        val spread: Double,
        val imbalance: Double = 0.0,
        val bookAgeMs: Long,
        val secondsRemaining: Double
    )

    fun freshness(bookAgeMs: Long): Double {
        if (bookAgeMs < 0L || bookAgeMs > FRESH_BOOK_MS) return 0.0
        return exp(-bookAgeMs / FRESHNESS_TAU_MS)
    }

    fun pFill(q: Quote): Double {
        if (q.size <= 0.0 || !q.price.isFinite() || q.price <= 0.0) return 0.0
        val fresh = freshness(q.bookAgeMs)
        if (fresh <= 0.0) return 0.0
        if (q.marketable) {
            val coverage = if (q.depth <= 0.0) 0.0 else min(1.0, q.depth / q.size)
            return (coverage * fresh).coerceIn(0.0, 1.0)
        }
        val queue = q.depth.coerceAtLeast(0.0) / q.size
        val x = 0.4 - 1.8 * queue - 6.0 * q.spread.coerceAtLeast(0.0) + 0.9 * q.imbalance.coerceIn(-1.0, 1.0) +
            0.15 * kotlin.math.ln(1.0 + q.secondsRemaining.coerceAtLeast(0.0))
        return (DecisionMath.sigmoid(x) * fresh).coerceIn(0.0, 1.0)
    }

    /** Paper fills never exceed the displayed size. */
    fun cappedContracts(want: Int, displayedDepth: Int?): Int {
        if (want <= 0) return 0
        val d = displayedDepth ?: return 0
        return min(want, d.coerceAtLeast(0))
    }

    /** Kalshi taker fee per contract, 7% × P × (1−P) with the order total rounded up to the cent. */
    fun feePerContract(price: Double, contracts: Int, feeRate: Double = KalshiFee.TAKER_COEFFICIENT): Double {
        val c = contracts.coerceAtLeast(1)
        return KalshiFee.total(c, price.coerceIn(0.0, 1.0), feeRate) / c
    }

    /** Adverse selection: half the spread lifting the offer, a quarter resting. */
    fun adversePerContract(marketable: Boolean, spread: Double): Double {
        val k = if (marketable) 0.5 else 0.25
        return k * spread.coerceAtLeast(0.0)
    }
}

object ExpectedNet {
    /** Per contract: P(fill) × [P(win)×payout − price − fee − adverse]. */
    fun perContract(
        pFill: Double,
        pWin: Double,
        price: Double,
        feePerContract: Double,
        adversePerContract: Double,
        payoutPerContract: Double = 1.0
    ): Double = pFill * (pWin * payoutPerContract - price - feePerContract - adversePerContract)

    fun of(
        pFill: Double,
        pWin: Double,
        filledSize: Double,
        price: Double,
        feePerContract: Double,
        adversePerContract: Double,
        payoutPerContract: Double = 1.0
    ): Double = filledSize * perContract(pFill, pWin, price, feePerContract, adversePerContract, payoutPerContract)
}

/**
 * Queue-aware replay of a resting bid against later trade prints.
 * CONSERVATIVE: the queue ahead plus our size must trade at our price, or a
 * print must trade strictly through our price. OPTIMISTIC assumes half the
 * queue ahead cancels.
 */
object QueueReplay {
    enum class Mode { CONSERVATIVE, OPTIMISTIC }

    data class Print(val price: Double, val size: Double, val atMs: Long)

    data class Result(val filled: Boolean, val fillSize: Double, val timeToFillMs: Long?, val adverseMove: Double?)

    fun judge(
        limit: Double,
        size: Double,
        depthAhead: Double,
        decisionMid: Double,
        decisionMs: Long,
        prints: List<Print>,
        laterMid: Double?,
        mode: Mode = Mode.CONSERVATIVE
    ): Result {
        if (size <= 0.0) return Result(false, 0.0, null, null)
        val ahead = if (mode == Mode.OPTIMISTIC) depthAhead * 0.5 else depthAhead
        var atPrice = 0.0
        var filledAt: Long? = null
        for (p in prints.filter { it.atMs >= decisionMs }.sortedBy { it.atMs }) {
            if (p.price + 1e-12 < limit) {
                filledAt = p.atMs
                break
            }
            if (p.price <= limit + 1e-12) atPrice += p.size
            if (atPrice + 1e-9 >= ahead + size) {
                filledAt = p.atMs
                break
            }
        }
        if (filledAt == null) return Result(false, 0.0, null, null)
        val adverse = if (laterMid != null && decisionMid.isFinite()) decisionMid - laterMid else null
        return Result(true, size, filledAt - decisionMs, adverse)
    }
}

/**
 * Conformal-style abstention. The interval half-width combines the standard
 * error of the calibrated bucket the prediction came from, the local
 * calibration error in that reliability bin, and the model ensemble spread.
 * No calibrated bucket → [COLD_START_HALF_WIDTH], which always abstains.
 */
object UncertaintyGate {
    const val Z90 = 1.645
    const val MAX_HALF_WIDTH = 0.08
    const val COLD_START_HALF_WIDTH = 0.25

    fun halfWidth(
        probability: Double,
        calibrationN: Int,
        localCalibrationError: Double? = null,
        ensembleSpread: Double? = null
    ): Double {
        if (calibrationN <= 0 || !probability.isFinite()) return COLD_START_HALF_WIDTH
        val p = probability.coerceIn(0.01, 0.99)
        val se = Z90 * sqrt(p * (1.0 - p) / calibrationN)
        val local = localCalibrationError?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) } ?: 0.0
        val spread = ensembleSpread?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        return maxOf(se + local, spread)
    }

    fun tooUncertain(halfWidth: Double): Boolean = !halfWidth.isFinite() || halfWidth > MAX_HALF_WIDTH + 1e-12
}

/**
 * The NO BET gate. Separate from direction. Every failing condition is a
 * NO BET with a reason the UI shows verbatim. Decisions use expected net
 * and P(fill), never gross payout. Deterministic; no LLM.
 */
object TradeEligibility {
    /** A side at or under this ask is a longshot. */
    const val LONGSHOT_ASK = 0.15
    /** Tiny-quote guard: no bet at ≤ 4¢ (and no hunter auto-fill there). */
    const val LOTTERY_ASK = 0.04
    const val TINY_HI = 0.97

    const val FINAL_WINDOW_REASON = "NO BET — model uncertainty too high for this final-window regime"
    const val UNCALIBRATED_REASON = "NO BET — model uncertainty too high (regime not calibrated yet)"
    const val UNCERTAIN_REASON = "NO BET — model uncertainty too high for this regime"
    const val NOT_BETTER_REASON = "NO BET — model is not better than the market in this regime"
    const val SETTLEMENT_STALE_REASON = "NO BET — settlement price feed is stale"
    const val BOOK_REASON = "NO BET — order book stale or thinner than the order"
    const val TINY_REASON = "NO BET — tiny quote (≤4¢ or ≥97¢)"
    const val LONGSHOT_REASON = "NO BET — longshot not validated"
    const val PFILL_REASON = "NO BET — fill probability too low"
    const val NET_REASON = "NO BET — expected net ≤ 0 after fees, fill odds, and adverse selection"
    const val BALANCE_REASON = "NO BET — Kalshi balance unavailable"

    data class Input(
        val calibratedProbability: Double?,
        val calibrationLevel: RegimeCalibration.Level? = null,
        val uncertaintyHalfWidth: Double,
        val regimeApproved: Boolean,
        val finalWindow: Boolean,
        val finalWindowReady: Boolean,
        val settlementSourceFresh: Boolean,
        val bookFresh: Boolean,
        val visibleDepth: Double,
        val orderSize: Double,
        val ask: Double?,
        val longshotValidated: Boolean,
        val pFill: Double,
        val minPFill: Double = FillModel.MIN_P_FILL,
        val expectedNetPerContract: Double,
        val balanceRequired: Boolean = false,
        val balanceAvailable: Boolean = true,
        val stakeUsd: Double? = null,
        val enforceMinStake: Boolean = false
    )

    data class Verdict(val allow: Boolean, val reason: String?) {
        val headline: String get() = if (allow) "BET" else reason ?: "NO BET"
        val decision: String get() = if (allow) "BET" else "NO BET"
    }

    fun evaluate(input: Input): Verdict {
        if (input.finalWindow && !input.finalWindowReady) return no(FINAL_WINDOW_REASON)
        if (input.calibratedProbability == null || !input.calibratedProbability.isFinite()) {
            return no(if (input.finalWindow) FINAL_WINDOW_REASON else UNCALIBRATED_REASON)
        }
        if (UncertaintyGate.tooUncertain(input.uncertaintyHalfWidth)) {
            return no(if (input.finalWindow) FINAL_WINDOW_REASON else UNCERTAIN_REASON)
        }
        if (!input.regimeApproved) return no(NOT_BETTER_REASON)
        if (!input.settlementSourceFresh) return no(SETTLEMENT_STALE_REASON)
        if (!input.bookFresh || input.visibleDepth <= 0.0 || input.visibleDepth + 1e-9 < input.orderSize) {
            return no(BOOK_REASON)
        }
        val ask = input.ask
        if (ask == null || ask <= LOTTERY_ASK + 1e-12 || ask >= TINY_HI - 1e-12) return no(TINY_REASON)
        if (ask <= LONGSHOT_ASK + 1e-12 && !input.longshotValidated) return no(LONGSHOT_REASON)
        if (input.pFill + 1e-12 < input.minPFill) return no(PFILL_REASON)
        if (!(input.expectedNetPerContract > 0.0)) return no(NET_REASON)
        if (input.balanceRequired && !input.balanceAvailable) return no(BALANCE_REASON)
        if (input.enforceMinStake && input.stakeUsd != null && AutopilotMinStake.below(input.stakeUsd)) {
            return no(AutopilotMinStake.REASON)
        }
        return Verdict(true, null)
    }

    private fun no(reason: String) = Verdict(false, reason)
}

object AutopilotMinStake {
    const val USD = 5.0
    const val REASON = "NO BET — Kelly stake below \$5 minimum (skipped, not rounded up)"
    fun below(allInUsd: Double): Boolean = !allInUsd.isFinite() || allInUsd + 1e-9 < USD
}

/**
 * Live Autopilot sizes from the real Kalshi available balance. A missing or
 * stale balance means no bet — never a guess, never the settings bankroll.
 */
object LiveBalancePolicy {
    const val FRESH_MS = 15L * 60L * 1000L
    const val REASON = TradeEligibility.BALANCE_REASON

    fun fresh(balanceUsd: Double?, fetchedAtMs: Long?, nowMs: Long): Boolean {
        if (balanceUsd == null || !balanceUsd.isFinite() || balanceUsd <= 0.0) return false
        val at = fetchedAtMs ?: return false
        return nowMs >= at && nowMs - at <= FRESH_MS
    }
}

/**
 * Favourite-first. The favourite is the side priced higher (above 50¢).
 * A longshot (ask ≤ 15¢) needs a validated favourite-longshot residual.
 */
object FavouritePolicy {
    fun favouriteSide(yesAsk: Double?, noAsk: Double?): String? {
        val y = yesAsk?.takeIf { it.isFinite() && it > 0.0 }
        val n = noAsk?.takeIf { it.isFinite() && it > 0.0 }
        return when {
            y == null && n == null -> null
            y == null -> if (n!! >= 0.5) "NO" else "YES"
            n == null -> if (y >= 0.5) "YES" else "NO"
            y >= n -> "YES"
            else -> "NO"
        }
    }

    fun isLongshot(ask: Double?): Boolean = ask != null && ask <= TradeEligibility.LONGSHOT_ASK + 1e-12
}
