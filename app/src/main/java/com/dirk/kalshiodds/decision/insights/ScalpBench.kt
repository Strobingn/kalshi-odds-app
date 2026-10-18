package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade

/** Status of a scalper's bench state. */
enum class BenchStatus {
    /** Actively trading. */
    ACTIVE,
    /** Benched (not trading). */
    BENCHED,
    /** Forcibly active regardless of performance. */
    FORCED_ACTIVE,
    /** Forcibly benched regardless of performance. */
    FORCED_BENCHED
}

/** Decision result for whether a scalper should be trading or benched. */
data class BenchDecision(
    /** The scalper identifier (provided by caller, not computed). */
    val scalperId: String,
    /** The resulting bench status. */
    val status: BenchStatus,
    /** Number of settled trades considered. */
    val settled: Int,
    /** Total net USD from considered settled trades. */
    val netUsd: Double,
    /** Total net USD from shadow trades (when unbenching). */
    val shadowNetUsd: Double,
    /** Human-readable reason for the decision. */
    val reason: String
) {
    /** Whether this decision allows trading. */
    val trading: Boolean
        get() = status == BenchStatus.ACTIVE || status == BenchStatus.FORCED_ACTIVE
}

/** Override mode for bench decision logic. */
enum class BenchOverride {
    /** Use normal bench rules. */
    AUTO,
    /** Force scalper to be active. */
    FORCE_ACTIVE,
    /** Force scalper to be benched. */
    FORCE_BENCHED
}

/**
 * Bench decision logic for scalpers based on trade outcomes.
 * 
 * Evaluates whether a scalper should be trading or benched based on
 * the net profit/loss of their most recent settled trades.
 */
object ScalpBench {
    /** Default window size for considering recent trades. */
    const val DEFAULT_WINDOW = 30

    /**
     * Determine if a scalper should be trading or benched.
     *
     * @param scalperId The scalper identifier (used in result, not for computation).
     * @param wasBenched Whether the scalper was previously benched.
     * @param realTrades All real (executed) trades for this scalper.
     * @param shadowTrades Hypothetical/shadow trades for this scalper (used when unbenching).
     * @param window Number of most-recent settled trades to consider. Coerced to at least 1.
     * @param override Override mode: AUTO uses normal rules, FORCE_ACTIVE/FORCE_BENCHED return forced status.
     * @return BenchDecision with status, counts, net values, and reason.
     */
    fun decide(
        scalperId: String,
        wasBenched: Boolean,
        realTrades: List<ScalpTrade>,
        shadowTrades: List<ScalpTrade>,
        window: Int = DEFAULT_WINDOW,
        override: BenchOverride = BenchOverride.AUTO
    ): BenchDecision {
        val coercedWindow = window.coerceAtLeast(1)

        // Handle overrides first
        when (override) {
            BenchOverride.FORCE_ACTIVE -> {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.FORCED_ACTIVE,
                    settled = 0,
                    netUsd = 0.0,
                    shadowNetUsd = 0.0,
                    reason = "forced active"
                )
            }
            BenchOverride.FORCE_BENCHED -> {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.FORCED_BENCHED,
                    settled = 0,
                    netUsd = 0.0,
                    shadowNetUsd = 0.0,
                    reason = "forced benched"
                )
            }
            BenchOverride.AUTO -> {
                // Normal logic
            }
        }

        // Filter and sort settled real trades
        val settledReal = realTrades
            .filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
            .sortedByDescending { it.closedAtMs }

        // Filter and sort all settled trades (real + shadow) for unbench logic
        val allSettled = (realTrades + shadowTrades)
            .filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
            .sortedByDescending { it.closedAtMs }

        if (!wasBenched) {
            // Not currently benched - evaluate real trades
            val considered = settledReal.take(coercedWindow)
            val settledCount = considered.size
            val net = considered.sumOf { it.netUsd!! }

            if (settledCount < coercedWindow) {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.ACTIVE,
                    settled = settledCount,
                    netUsd = net,
                    shadowNetUsd = 0.0,
                    reason = "warming up $settledCount/$coercedWindow"
                )
            } else if (net < 0) {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.BENCHED,
                    settled = settledCount,
                    netUsd = net,
                    shadowNetUsd = 0.0,
                    reason = "net negative over last $coercedWindow trades"
                )
            } else {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.ACTIVE,
                    settled = settledCount,
                    netUsd = net,
                    shadowNetUsd = 0.0,
                    reason = "net non-negative over last $coercedWindow trades"
                )
            }
        } else {
            // Currently benched - evaluate shadow trades for unbenching
            val shadowConsidered = allSettled.take(coercedWindow)
            val shadowCount = shadowConsidered.size
            val shadowNet = shadowConsidered.sumOf { it.netUsd!! }

            if (shadowCount >= coercedWindow && shadowNet > 0) {
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.ACTIVE,
                    settled = shadowCount,
                    netUsd = shadowNet,
                    shadowNetUsd = shadowNet,
                    reason = "unbenched: shadow net positive over last $coercedWindow trades"
                )
            } else {
                // Stay benched - provide reason based on why
                val reason = if (shadowCount < coercedWindow) {
                    "stay benched: only $shadowCount shadow trades available"
                } else {
                    "stay benched: shadow net <= 0 over last $coercedWindow trades"
                }
                return BenchDecision(
                    scalperId = scalperId,
                    status = BenchStatus.BENCHED,
                    settled = shadowCount,
                    netUsd = shadowNet,
                    shadowNetUsd = shadowNet,
                    reason = reason
                )
            }
        }
    }
}
