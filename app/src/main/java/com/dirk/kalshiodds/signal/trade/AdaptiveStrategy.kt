package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlin.math.abs

/**
 * Adaptive learning for paper trading.
 * Tracks performance and adjusts strategy parameters based on results.
 * 
 * This is for experimentation only - no real money involved.
 */
class AdaptiveStrategy(
    private val paperState: PaperBookState,
    private val minSamplesForLearning: Int = 10
) {

    data class AdaptiveConfig(
        val maxAskFilter: Double,
        val timeWindowMs: Long,
        val minTimeToCloseMs: Long,
        val stakeUsd: Double,
        val confidenceThreshold: Double,
        val reason: String
    ) {
        fun toWinningStrategyConfig(): WinningStrategy.Config = WinningStrategy.Config(
            enabled = true,
            maxAskFilter = maxAskFilter,
            timeWindowMs = timeWindowMs,
            minTimeToCloseMs = minTimeToCloseMs,
            minProfitIfWinUsd = 10.0
        )
    }

    data class PerformanceMetrics(
        val totalTrades: Int,
        val winningTrades: Int,
        val losingTrades: Int,
        val totalPnl: Double,
        val avgPnl: Double,
        val winRate: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val maxDrawdown: Double,
        val currentStreak: Int,
        val bestAskRange: Pair<Double, Double>?,
        val bestTimeRange: Pair<Long, Long>?
    ) {
        val isProfitable: Boolean get() = totalPnl > 0
        val hasEnoughData: Boolean get() = totalTrades >= minSamplesForLearning
    }

    /**
     * Calculate current performance metrics from paper book
     */
    fun calculateMetrics(): PerformanceMetrics {
        val fills = paperState.fills.filter { it.settled }
        if (fills.isEmpty()) {
            return PerformanceMetrics(
                totalTrades = 0,
                winningTrades = 0,
                losingTrades = 0,
                totalPnl = 0.0,
                avgPnl = 0.0,
                winRate = 0.0,
                avgWin = 0.0,
                avgLoss = 0.0,
                maxDrawdown = 0.0,
                currentStreak = 0,
                bestAskRange = null,
                bestTimeRange = null
            )
        }

        val winningTrades = fills.count { it.won == true }
        val losingTrades = fills.count { it.won == false }
        val totalPnl = fills.sumOf { it.pnlUsd ?: 0.0 }
        val avgPnl = if (fills.isNotEmpty()) totalPnl / fills.size else 0.0
        val winRate = if (fills.isNotEmpty()) winningTrades.toDouble() / fills.size else 0.0
        
        val wins = fills.filter { it.won == true }.mapNotNull { it.pnlUsd }
        val losses = fills.filter { it.won == false }.mapNotNull { it.pnlUsd?.takeIf { it < 0 } ?: 0.0 }
        
        val avgWin = if (wins.isNotEmpty()) wins.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.average() else 0.0
        
        // Calculate max drawdown
        var runningPnl = 0.0
        var maxPnl = 0.0
        var maxDrawdown = 0.0
        fills.sortedBy { it.createdAtMs }.forEach { fill ->
            val pnl = fill.pnlUsd ?: 0.0
            runningPnl += pnl
            maxPnl = maxOf(maxPnl, runningPnl)
            maxDrawdown = maxOf(maxDrawdown, maxPnl - runningPnl)
        }
        
        // Find best performing ask ranges (by win rate)
        val bestAskRange = findBestAskRange(fills)
        
        // Find best performing time ranges (by win rate)
        val bestTimeRange = findBestTimeRange(fills)
        
        // Calculate current streak
        val streak = calculateCurrentStreak(fills)
        
        return PerformanceMetrics(
            totalTrades = fills.size,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            totalPnl = totalPnl,
            avgPnl = avgPnl,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            maxDrawdown = maxDrawdown,
            currentStreak = streak,
            bestAskRange = bestAskRange,
            bestTimeRange = bestTimeRange
        )
    }

    /**
     * Find the ask range with best win rate
     */
    private fun findBestAskRange(fills: List<PaperFill>): Pair<Double, Double>? {
        if (fills.size < minSamplesForLearning) return null
        
        // Group by ask price ranges: 0-20c, 20-40c, 40-60c, 60-80c, 80-100c
        val ranges = listOf(
            0.0 to 0.20,
            0.20 to 0.40,
            0.40 to 0.60,
            0.60 to 0.80,
            0.80 to 1.00
        )
        
        val rangeStats = ranges.map { range ->
            val inRange = fills.filter { fill ->
                val ask = fill.limitPrice
                ask in range.first..range.second
            }
            
            if (inRange.isEmpty()) null
            else {
                val wins = inRange.count { it.won == true }
                val total = inRange.size
                val winRate = wins.toDouble() / total
                Triple(range, winRate, inRange.size)
            }
        }.filterNotNull()
        
        if (rangeStats.isEmpty()) return null
        
        // Find range with highest win rate and at least 3 samples
        val bestRange = rangeStats
            .filter { it.third >= 3 }
            .maxByOrNull { it.second }
        
        return bestRange?.first
    }

    /**
     * Find the time range with best win rate
     */
    private fun findBestTimeRange(fills: List<PaperFill>): Pair<Long, Long>? {
        if (fills.size < minSamplesForLearning) return null
        
        // We don't have closeTime in PaperFill, so we can't calculate this
        // For now, return null - could be enhanced later
        return null
    }

    /**
     * Calculate current win/loss streak
     */
    private fun calculateCurrentStreak(fills: List<PaperFill>): Int {
        if (fills.isEmpty()) return 0
        
        val sorted = fills.sortedByDescending { it.createdAtMs }
        var streak = 0
        var isWinning = sorted.first().won == true
        
        for (fill in sorted) {
            val won = fill.won == true
            if (won == isWinning) {
                streak++
            } else {
                break
            }
        }
        
        return if (isWinning) streak else -streak
    }

    /**
     * Adapt strategy based on current performance
     */
    fun adapt(): AdaptiveConfig {
        val metrics = calculateMetrics()
        
        // If we don't have enough data, use defaults
        if (!metrics.hasEnoughData) {
            return AdaptiveConfig(
                maxAskFilter = SignalConstants.DEFAULT_WINNING_STRATEGY_MAX_ASK,
                timeWindowMs = SignalConstants.DEFAULT_WINNING_STRATEGY_TIME_WINDOW_MS,
                minTimeToCloseMs = SignalConstants.DEFAULT_WINNING_STRATEGY_MIN_TIME_MS,
                stakeUsd = SignalConstants.PAPER_STAKE_USD,
                confidenceThreshold = 0.5,
                reason = "Not enough data (need ${minSamplesForLearning}+ trades)"
            )
        }
        
        val reasons = mutableListOf<String>()
        
        // Adjust based on performance
        var maxAsk = SignalConstants.DEFAULT_WINNING_STRATEGY_MAX_ASK
        var timeWindow = SignalConstants.DEFAULT_WINNING_STRATEGY_TIME_WINDOW_MS
        var minTime = SignalConstants.DEFAULT_WINNING_STRATEGY_MIN_TIME_MS
        var stake = SignalConstants.PAPER_STAKE_USD
        
        // If profitable, be more aggressive
        // If losing, be more conservative
        when {
            metrics.isProfitable && metrics.winRate > 0.6 -> {
                // Doing great - be more aggressive
                maxAsk = minOf(maxAsk + 0.10, 0.60)
                reasons.add("High win rate (${"%.1f%%".format(metrics.winRate * 100)}) - increasing max ask")
            }
            metrics.isProfitable && metrics.winRate > 0.55 -> {
                // Doing well - slightly more aggressive
                maxAsk = minOf(maxAsk + 0.05, 0.55)
                reasons.add("Good performance - slightly increasing max ask")
            }
            !metrics.isProfitable && metrics.winRate < 0.45 -> {
                // Doing poorly - be more conservative
                maxAsk = maxOf(maxAsk - 0.05, 0.20)
                reasons.add("Low win rate (${"%.1f%%".format(metrics.winRate * 100)}) - decreasing max ask")
            }
            !metrics.isProfitable && metrics.maxDrawdown > 50.0 -> {
                // Big drawdown - reduce risk
                stake = maxOf(stake - 1.0, 1.0)
                reasons.add("High drawdown (\$${"%.1f".format(metrics.maxDrawdown)}) - reducing stake")
            }
        }
        
        // Use best performing ask range if we have data
        metrics.bestAskRange?.let { range ->
            maxAsk = range.second
            reasons.add("Best ask range: ${"%.0f".format(range.first * 100)}-${"%.0f".format(range.second * 100)}¢")
        }
        
        // Adjust stake based on avg win/loss ratio
        if (metrics.avgWin > 0 && abs(metrics.avgLoss) > 0) {
            val ratio = metrics.avgWin / abs(metrics.avgLoss)
            if (ratio > 1.5) {
                stake = minOf(stake + 1.0, 10.0)
                reasons.add("Good win/loss ratio (${"%.2f".format(ratio)}) - increasing stake")
            } else if (ratio < 0.8) {
                stake = maxOf(stake - 1.0, 1.0)
                reasons.add("Poor win/loss ratio (${"%.2f".format(ratio)}) - decreasing stake")
            }
        }
        
        // Streak-based adjustments
        when {
            metrics.currentStreak >= 3 -> {
                // On a winning streak - increase aggression
                maxAsk = minOf(maxAsk + 0.05, 0.60)
                reasons.add("Winning streak (${metrics.currentStreak}) - increasing aggression")
            }
            metrics.currentStreak <= -3 -> {
                // On a losing streak - reduce risk
                maxAsk = maxOf(maxAsk - 0.05, 0.20)
                stake = maxOf(stake - 0.5, 1.0)
                reasons.add("Losing streak (${abs(metrics.currentStreak)}) - reducing risk")
            }
        }
        
        // Build reason string
        val reason = if (reasons.isEmpty()) "Using default settings" else reasons.joinToString(" | ")
        
        return AdaptiveConfig(
            maxAskFilter = maxAsk.coerceIn(0.10, 0.70),
            timeWindowMs = timeWindow,
            minTimeToCloseMs = minTime,
            stakeUsd = stake.coerceIn(1.0, 10.0),
            confidenceThreshold = 0.5,
            reason = reason
        )
    }

    /**
     * Get current adaptive config as a winning strategy config
     */
    fun getAdaptiveConfig(): WinningStrategy.Config {
        return adapt().toWinningStrategyConfig()
    }

    /**
     * Check if we should trade based on adaptive metrics
     */
    fun shouldTrade(market: MarketUiModel): Boolean {
        val metrics = calculateMetrics()
        
        // If we don't have enough data, allow trading
        if (!metrics.hasEnoughData) return true
        
        // If on a losing streak of 4+, pause
        if (metrics.currentStreak <= -4) {
            return false
        }
        
        // If total PnL is very negative, pause
        if (metrics.totalPnl < -100.0) {
            return false
        }
        
        return true
    }

    /**
     * Get learning summary for display
     */
    fun getLearningSummary(): String {
        val metrics = calculateMetrics()
        
        if (!metrics.hasEnoughData) {
            return "Learning: Need ${minSamplesForLearning}+ settled trades to adapt"
        }
        
        val config = adapt()
        val parts = mutableListOf<String>()
        
        parts.add("Trades: ${metrics.totalTrades}")
        parts.add("P&L: \$${"%.2f".format(metrics.totalPnl)}")
        parts.add("Win rate: ${"%.1f%%".format(metrics.winRate * 100)}")
        
        if (metrics.currentStreak > 0) {
            parts.add("Streak: +${metrics.currentStreak}")
        } else if (metrics.currentStreak < 0) {
            parts.add("Streak: ${metrics.currentStreak}")
        }
        
        parts.add("Adapted: ${config.reason}")
        
        return parts.joinToString(" | ")
    }
}
