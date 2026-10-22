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
    @Suppress("UNUSED_PARAMETER")
    fun decide(
        scalperId: String,
        wasBenched: Boolean,
        realTrades: List<ScalpTrade>,
        shadowTrades: List<ScalpTrade>,
        window: Int = DEFAULT_WINDOW,
        override: BenchOverride = BenchOverride.AUTO
    ): BenchDecision {
        val coercedWindow = window.coerceAtLeast(1)
        val settledReal = realTrades
            .filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
        val net = settledReal.take(coercedWindow).sumOf { it.netUsd ?: 0.0 }

        // Automatic bench is off. A losing streak, a shadow book, and a previous bench
        // all stay trading. Force bench is the only way a scalper stops opening.
        return when (override) {
            BenchOverride.FORCE_ACTIVE -> BenchDecision(scalperId, BenchStatus.FORCED_ACTIVE, 0, 0.0, 0.0, "forced active")
            BenchOverride.FORCE_BENCHED -> BenchDecision(scalperId, BenchStatus.FORCED_BENCHED, 0, 0.0, 0.0, "forced benched")
            BenchOverride.AUTO -> BenchDecision(scalperId, BenchStatus.ACTIVE, settledReal.size, net, 0.0, "trading")
        }
    }
}
