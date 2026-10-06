package com.dirk.kalshiodds.decision

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Transparent fill heuristic (not a trained model) and a queue-aware replay.
 *
 * expected_net = P(fill) * [P(win) * payout - entry cost - fees - adverse selection].
 */
object FillModel {
    const val MIN_P_FILL = 0.35

    data class Quote(
        val marketable: Boolean,
        val price: Double,
        val size: Double,
        val depthAhead: Double,
        val spread: Double,
        val imbalance: Double,
        val bookAgeSec: Double,
        val quoteChurn: Double,
        val secondsRemaining: Double
    )

    fun pFill(q: Quote): Double {
        if (q.size <= 0.0 || !q.price.isFinite()) return 0.0
        val freshness = exp(-q.bookAgeSec.coerceAtLeast(0.0) / 30.0)
        val churn = (1.0 - q.quoteChurn.coerceIn(0.0, 1.0) * 0.5).coerceIn(0.0, 1.0)
        if (q.marketable) {
            val ratio = if (q.depthAhead <= 0.0) 0.0 else min(1.0, q.depthAhead / q.size)
            return (ratio * freshness * churn).coerceIn(0.0, 1.0)
        }
        val queue = q.depthAhead / q.size
        val x = 0.4 - 1.8 * queue - 6.0 * q.spread + 0.9 * q.imbalance -
            0.04 * q.bookAgeSec - 1.2 * q.quoteChurn +
            0.15 * ln(1.0 + q.secondsRemaining.coerceAtLeast(0.0))
        return (DecisionMath.sigmoid(x) * freshness).coerceIn(0.0, 1.0)
    }

    fun expectedFilledSize(q: Quote): Double {
        val p = pFill(q)
        val cap = if (q.marketable) min(q.size, q.depthAhead.coerceAtLeast(0.0)) else q.size
        return p * cap
    }

    fun feePerContract(price: Double, feeRate: Double = 0.07): Double {
        val px = price.coerceIn(0.0, 1.0)
        return feeRate * px * (1.0 - px)
    }

    /**
     * Adverse selection is half the spread when lifting the offer,
     * a quarter of the spread when resting.
     */
    fun adversePerContract(q: Quote): Double {
        val half = if (q.marketable) 0.5 else 0.25
        return half * q.spread.coerceAtLeast(0.0)
    }
}

object ExpectedNet {
    fun of(
        pFill: Double,
        pWin: Double,
        filledSize: Double,
        price: Double,
        feePerContract: Double,
        adversePerContract: Double,
        payoutPerContract: Double = 1.0
    ): Double {
        val edge = pWin * payoutPerContract - price - feePerContract - adversePerContract
        return pFill * filledSize * edge
    }
}

/**
 * A resting order fills only after the queue ahead trades, or a print
 * trades through the price. Conservative requires the whole queue plus
 * our size. Optimistic fills on a through-trade alone.
 */
object QueueReplay {
    enum class Mode { CONSERVATIVE, OPTIMISTIC }

    data class Print(val price: Double, val size: Double, val atMs: Long)

    data class Result(
        val filled: Boolean,
        val fillSize: Double,
        val timeToFillMs: Long?,
        val adverseMove: Double?
    )

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
        var seen = 0.0
        var through = false
        var filledAt: Long? = null
        for (p in prints.sortedBy { it.atMs }) {
            if (p.price <= limit + 1e-12) seen += p.size
            if (p.price + 1e-12 < limit) through = true
            val queueCleared = seen + 1e-9 >= depthAhead + size
            val optimistic = mode == Mode.OPTIMISTIC && (through || queueCleared)
            val conservative = mode == Mode.CONSERVATIVE && queueCleared
            if (optimistic || conservative) {
                filledAt = p.atMs
                break
            }
        }
        if (filledAt == null) return Result(false, 0.0, null, null)
        val adverse = if (laterMid != null && decisionMid.isFinite()) decisionMid - laterMid else null
        return Result(true, size, filledAt - decisionMs, adverse)
    }
}

data class UncertaintyInterval(val lo: Double, val hi: Double, val ready: Boolean) {
    fun supportsOneSide(): Boolean {
        if (!ready) return false
        return hi < 0.5 || lo > 0.5
    }
}

object RollingBlockInterval {
    const val MIN_SAMPLES = 30

    fun fit(residualsAbs: List<Double>, probability: Double, alpha: Double = 0.10): UncertaintyInterval {
        val recent = residualsAbs.takeLast(80).filter { it.isFinite() }.sorted()
        if (recent.size < MIN_SAMPLES) return UncertaintyInterval(probability, probability, ready = false)
        val idx = kotlin.math.ceil((1.0 - alpha) * (recent.size + 1)).toInt().coerceIn(1, recent.size) - 1
        val q = recent[idx]
        return UncertaintyInterval(
            lo = (probability - q).coerceIn(0.0, 1.0),
            hi = (probability + q).coerceIn(0.0, 1.0),
            ready = true
        )
    }
}

/**
 * Separate from direction. Every failing condition is a NO BET with a reason.
 * Decision inputs are true probabilities — never a display floor.
 */
object TradeEligibility {
    const val LONGSHOT_ASK = 0.15
    const val TINY_LO = 0.02
    const val TINY_HI = 0.98
    const val LOTTERY_ASK = 0.04

    data class Input(
        val calibratedProbability: Double?,
        val settlementSourceFresh: Boolean,
        val bookFresh: Boolean,
        val bookNonEmpty: Boolean,
        val visibleDepth: Double,
        val orderSize: Double,
        val pFill: Double,
        val minPFill: Double = FillModel.MIN_P_FILL,
        val uncertainty: UncertaintyInterval,
        val regimeApproved: Boolean,
        val expectedNet: Double,
        val secondsRemaining: Double?,
        val finalWindowReady: Boolean,
        val ask: Double?,
        val verifiedDepth: Boolean,
        val validatedResidual: Boolean,
        val longshotResidualPositive: Boolean = false,
        val balanceRequired: Boolean = false,
        val balanceAvailable: Boolean = true,
        val stakeUsd: Double? = null,
        val enforceMinStake: Boolean = false
    )

    data class Verdict(val allow: Boolean, val reason: String?) {
        val headline: String get() = if (allow) "BET" else reason ?: "NO BET"
    }

    fun evaluate(input: Input): Verdict {
        if (input.calibratedProbability == null || !input.calibratedProbability.isFinite()) {
            return no("NO BET — calibrated probability unavailable")
        }
        if (!input.settlementSourceFresh) return no("NO BET — settlement source is stale")
        if (!input.bookFresh || !input.bookNonEmpty || input.visibleDepth + 1e-9 < input.orderSize) {
            return no("NO BET — order book is missing, stale, or smaller than the order")
        }
        if (input.pFill + 1e-12 < input.minPFill) return no("NO BET — fill probability below the minimum")
        val finalWindow = input.secondsRemaining != null && input.secondsRemaining <= 60.0
        if (!input.uncertainty.supportsOneSide()) {
            return if (finalWindow) {
                no("NO BET — model uncertainty too high for this final-window regime")
            } else {
                no("NO BET — uncertainty interval covers both sides")
            }
        }
        if (!input.regimeApproved) {
            return no("NO BET — regime is not calibrated or the model is worse than the market there")
        }
        if (finalWindow && !input.finalWindowReady) {
            return no("NO BET — final 60 seconds are not calibrated or the model is worse than the market there")
        }
        val ask = input.ask
        if (ask != null && (ask <= TINY_LO + 1e-12 || ask >= TINY_HI - 1e-12)) {
            if (!input.verifiedDepth || !input.validatedResidual) {
                return no("NO BET — tiny quote needs verified depth and a validated residual")
            }
        }
        if (ask != null && ask <= LONGSHOT_ASK + 1e-12) {
            if (!input.longshotResidualPositive || !input.verifiedDepth) {
                return no("NO BET — longshot not validated")
            }
        }
        if (input.expectedNet <= 0.0) return no("NO BET — expected net is not positive after fees and spread")
        if (input.balanceRequired && !input.balanceAvailable) return no("NO BET — balance unavailable")
        if (input.enforceMinStake && input.stakeUsd != null && input.stakeUsd + 1e-9 < AutopilotMinStake.USD) {
            return no(AutopilotMinStake.REASON)
        }
        return Verdict(true, null)
    }

    private fun no(reason: String) = Verdict(false, reason)
}

object AutopilotMinStake {
    const val USD = 5.0
    const val REASON = "NO BET — below \$5 minimum"
    fun below(allInUsd: Double): Boolean = !allInUsd.isFinite() || allInUsd + 1e-9 < USD
}

/**
 * Live Autopilot sizes from the cached Kalshi available balance.
 * Paper keeps the paper bankroll. A missing or stale balance is not a guess.
 */
object LiveBalancePolicy {
    const val FRESH_MS = 15L * 60L * 1000L
    const val REASON = "NO BET — balance unavailable"

    fun fresh(balanceUsd: Double?, fetchedAtMs: Long?, nowMs: Long): Boolean {
        if (balanceUsd == null || !balanceUsd.isFinite() || balanceUsd < 0.0) return false
        val at = fetchedAtMs ?: return false
        return nowMs >= at && nowMs - at <= FRESH_MS
    }
}

/** The stored daily-cap preference is ignored. It is not a safety limit. */
object DailyCapPolicy {
    const val BLOCKS_ORDERS = false
    const val SHOWN_ON_SCREEN = false
}

/**
 * Favourite is the side priced above 50¢. A longshot (ask ≤ 15¢) needs a
 * validated positive residual and verified depth.
 */
object FavouritePolicy {
    fun favouriteSide(yesAsk: Double?, noAsk: Double?): String? {
        val y = yesAsk?.takeIf { it.isFinite() }
        val n = noAsk?.takeIf { it.isFinite() }
        return when {
            y == null && n == null -> null
            y == null -> "NO"
            n == null -> "YES"
            y <= n -> "YES"
            else -> "NO"
        }
    }

    fun isLongshot(ask: Double?): Boolean = ask != null && ask <= TradeEligibility.LONGSHOT_ASK + 1e-12
}
