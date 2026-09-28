package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlinx.serialization.Serializable

/**
 * Streak / drawdown circuit breaker for **alerts only**.
 *
 * Tracks consecutive wrong settled alerts and a one-contract P&L
 * proxy from logged outcomes:
 *
 *     YES bet, win:  +(1 − mid)     YES bet, lose: −mid
 *     NO  bet, win:  +mid           NO  bet, lose:  −(1 − mid)
 *
 * Pause when consecutive wrong ≥ N **or** peak-to-trough drawdown
 * of the proxy exceeds the Settings threshold. Alerts stay paused
 * until the next process session (if enabled) or a manual resume.
 *
 * Scoring / cards keep running — only notifications + alert emit pause.
 */
object Guardrails {

    @Serializable
    data class State(
        val consecutiveWrong: Int = 0,
        val rollingPnl: Double = 0.0,
        val peakPnl: Double = 0.0,
        val paused: Boolean = false,
        val pauseReason: String? = null,
        val lastSettledAtMs: Long = 0L,
        val sessionId: Long = 0L,
        val lastResumeMs: Long = 0L,
        val processed: Int = 0,
        /** Old stored state included NO BET rows in streak and proxy P&L. */
        val accountingVersion: Int = 1
    ) {
        val drawdown: Double get() = (peakPnl - rollingPnl).coerceAtLeast(0.0)

        val banner: String?
            get() = if (paused) {
                pauseReason ?: "alerts paused — streak guard"
            } else {
                null
            }
    }

    data class Thresholds(
        val streakN: Int = SignalConstants.DEFAULT_STREAK_PAUSE_N,
        val drawdownUsd: Double = SignalConstants.DEFAULT_DRAWDOWN_USD,
        val resumeOnNewSession: Boolean = SignalConstants.DEFAULT_RESUME_ON_NEW_SESSION
    )

    fun identity(): State = State(accountingVersion = 2)

    fun migrateIfNeeded(
        state: State,
        entries: List<PredictionLogEntry>,
        thresholds: Thresholds,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        if (state.accountingVersion >= 2) return state
        // Rebuild from retained settled rows. Historical no-bets were charged
        // as trades in the persisted running total and cannot be subtracted
        // accurately from that aggregate alone.
        return update(identity(), entries, thresholds, nowMs).copy(
            sessionId = state.sessionId,
            lastResumeMs = state.lastResumeMs
        )
    }

    fun onNewSession(state: State, thresholds: Thresholds, sessionId: Long, nowMs: Long): State {
        if (state.sessionId == sessionId) return state
        return if (thresholds.resumeOnNewSession && state.paused) {
            state.copy(
                paused = false,
                pauseReason = null,
                sessionId = sessionId,
                lastResumeMs = nowMs,
                consecutiveWrong = 0
            )
        } else {
            state.copy(sessionId = sessionId)
        }
    }

    fun resume(state: State, nowMs: Long = System.currentTimeMillis()): State =
        state.copy(
            paused = false,
            pauseReason = null,
            consecutiveWrong = 0,
            lastResumeMs = nowMs
        )

    /**
     * Fold newly settled rows (watermarked) into the running guard.
     * Wrong = predicted side missed. Voids are ignored.
     */
    fun update(
        state: State,
        entries: List<PredictionLogEntry>,
        thresholds: Thresholds,
        nowMs: Long = System.currentTimeMillis()
    ): State {
        val fresh = entries
            .filter { e ->
                val y = e.outcome?.lowercase()
                (y == "yes" || y == "no") && ForecastUnits.isScoredPick(e) &&
                    (e.settledAtMs ?: e.timestampMs) > state.lastSettledAtMs
            }
            .sortedBy { it.settledAtMs ?: eTimestamp(it) }
        if (fresh.isEmpty()) return maybeTrip(state, thresholds)

        var next = state
        for (e in fresh) {
            val hit = isHit(e)
            val pnl = oneContractPnl(e)
            val rolling = next.rollingPnl + pnl
            val peak = maxOf(next.peakPnl, rolling)
            val streak = if (hit) 0 else next.consecutiveWrong + 1
            next = next.copy(
                consecutiveWrong = streak,
                rollingPnl = rolling,
                peakPnl = peak,
                lastSettledAtMs = maxOf(next.lastSettledAtMs, e.settledAtMs ?: e.timestampMs),
                processed = next.processed + 1
            )
        }
        return maybeTrip(next, thresholds)
    }

    fun maybeTrip(state: State, thresholds: Thresholds): State {
        if (state.paused) return state
        val n = thresholds.streakN.coerceIn(2, 20)
        if (state.consecutiveWrong >= n) {
            return state.copy(
                paused = true,
                pauseReason = "alerts paused — streak guard (${state.consecutiveWrong} wrong in a row)"
            )
        }
        val dd = state.drawdown
        if (dd >= thresholds.drawdownUsd && state.processed > 0) {
            return state.copy(
                paused = true,
                pauseReason = "alerts paused — streak guard (drawdown ${'$'}${formatMoney(dd)})"
            )
        }
        return state
    }

    fun oneContractPnl(e: PredictionLogEntry): Double {
        if (!ForecastUnits.isScoredPick(e)) return 0.0
        val mid = e.marketMid.coerceIn(0.01, 0.99)
        val predYes = when (e.predictedSide?.uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> e.predictedYes > 0.5
        }
        val actualYes = e.outcome.equals("yes", true)
        return if (predYes) {
            if (actualYes) 1.0 - mid else -mid
        } else {
            if (!actualYes) mid else -(1.0 - mid)
        }
    }

    fun isHit(e: PredictionLogEntry): Boolean {
        return ForecastUnits.hit(e)
    }

    private fun eTimestamp(e: PredictionLogEntry): Long = e.settledAtMs ?: e.timestampMs

    private fun formatMoney(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
