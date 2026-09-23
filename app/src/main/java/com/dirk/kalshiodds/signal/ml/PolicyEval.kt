package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import kotlin.math.floor

/**
 * Offline counterfactual: if every alert-like settlement had been taken
 * at stake [stakeUsd], what ROI / Brier / hit-rate would the policy
 * have shown? Analysis only — never places an order.
 */
object PolicyEval {

    data class Line(
        val n: Int = 0,
        val hits: Int = 0,
        val hitRate: Double? = null,
        val brier: Double? = null,
        val totalPnl: Double = 0.0,
        val staked: Double = 0.0,
        val roi: Double? = null,
        val avgContracts: Double? = null
    )

    data class Scorecard(
        val stakeUsd: Double,
        val allAlerts: Line,
        val gated: Line,
        val note: String
    )

    data class Trade(
        val hit: Boolean,
        val brier: Double,
        val pnl: Double,
        val staked: Double,
        val contracts: Int
    )

    fun evaluate(
        entries: List<PredictionLogEntry>,
        stakeUsd: Double,
        edgeThresholdPp: Double = 5.0,
        minConfidence: Double = 0.45,
        requireUncertaintyPass: Boolean = false,
        maxUncertainty: Double = 0.12
    ): Scorecard {
        val stake = stakeUsd.coerceIn(1.0, 25.0)
        val settled = entries.filter { it.outcome.equals("yes", true) || it.outcome.equals("no", true) }
        val alerts = settled.filter { wouldAlert(it, edgeThresholdPp, minConfidence) }
        val gated = alerts.filter { uncertaintyOk(it, requireUncertaintyPass, maxUncertainty) }
        return Scorecard(
            stakeUsd = stake,
            allAlerts = summarize(alerts.map { simulate(it, stake) }),
            gated = summarize(gated.map { simulate(it, stake) }),
            note = if (settled.isEmpty()) {
                "No settled samples yet. Counterfactual fills in after the first YES/NO expiries."
            } else {
                "If every alert had been taken at \$${stake.toInt()} (limit ≈ mid). Fees ignored. Not a live P&L."
            }
        )
    }

    fun wouldAlert(
        e: PredictionLogEntry,
        edgeThresholdPp: Double,
        minConfidence: Double
    ): Boolean {
        if (e.wouldAlert == true) return true
        if (e.wouldAlert == false) return false
        val edge = e.edgePp ?: ((e.fairValuePp ?: e.predictedYes * 100.0) - e.marketMid * 100.0)
        val conf = e.confidence ?: 0.50
        return kotlin.math.abs(edge) >= edgeThresholdPp && conf >= minConfidence
    }

    fun simulate(e: PredictionLogEntry, stakeUsd: Double): Trade {
        val sideYes = when (e.predictedSide?.uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> e.predictedYes > 0.5
        }
        val mid = e.marketMid.coerceIn(0.01, 0.99)
        val price = if (sideYes) mid else (1.0 - mid).coerceIn(0.01, 0.99)
        val contracts = floor(stakeUsd / price).toInt().coerceAtLeast(1)
        val staked = contracts * price
        val actualYes = e.outcome.equals("yes", true)
        val hit = sideYes == actualYes
        val pnl = if (hit) contracts * (1.0 - price) else -staked
        val y = if (actualYes) 1.0 else 0.0
        val brier = (e.predictedYes - y) * (e.predictedYes - y)
        return Trade(hit = hit, brier = brier, pnl = pnl, staked = staked, contracts = contracts)
    }

    fun summarize(trades: List<Trade>): Line {
        if (trades.isEmpty()) return Line()
        val hits = trades.count { it.hit }
        val staked = trades.sumOf { it.staked }
        val pnl = trades.sumOf { it.pnl }
        return Line(
            n = trades.size,
            hits = hits,
            hitRate = hits.toDouble() / trades.size,
            brier = trades.map { it.brier }.average(),
            totalPnl = pnl,
            staked = staked,
            roi = if (staked > 1e-9) pnl / staked else null,
            avgContracts = trades.map { it.contracts.toDouble() }.average()
        )
    }

    private fun uncertaintyOk(
        e: PredictionLogEntry,
        require: Boolean,
        maxUnc: Double
    ): Boolean {
        if (!require) return true
        val u = e.uncertainty ?: return true
        return u <= maxUnc
    }
}
