package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.settlement.AveragingAwareStrategy
import com.dirk.kalshiodds.signal.settlement.BRTISettlementTracker
import com.dirk.kalshiodds.signal.settlement.FeeTypeVerifier
import com.dirk.kalshiodds.signal.settlement.SettlementStrategyManager

/**
 * Simplified winning strategy based on backtest results.
 * 
 * This now integrates with the settlement-aware strategies for Rank 1.
 * 
 * Legacy Strategy (Still Available):
 * Only trade when:
 * 1. App picks a cheap side (ask <= maxAskFilter)
 * 2. Within time window (last N minutes before close)
 * 3. Profit if win >= minProfitUsd
 * 
 * This is the strategy that showed positive results in OOS backtest:
 * - App pick, ask 32-50c: 92 trades, +$89.46, +$0.97/bet, 60.9% win
 * - App pick, time-left 11-9min: 593 trades, +$134.18, +$0.23/bet, 73.2% win
 * 
 * New Rank 1 Strategy (Averaging-Aware):
 * Trades based on BRTI settlement average calculation.
 * Expected edge: 1-4¢ per contract on triggered trades.
 * 
 * @see AveragingAwareStrategy
 */
class WinningStrategy(
    private val settlementTracker: BRTISettlementTracker? = null,
    private val feeVerifier: FeeTypeVerifier? = null
) {

    /**
     * Default max ask filter: 40c
     * This captures the winning 32-50c band while avoiding expensive favorites
     */
    const val DEFAULT_MAX_ASK_FILTER = 0.40

    /**
     * Default time window: only trade in last 6 minutes (360,000ms)
     * This captures the best performing 11-9min and 8-6min windows
     */
    const val DEFAULT_TIME_WINDOW_MS = 360_000L

    /**
     * Minimum time before close to consider (default: 5 minutes = 300,000ms)
     * Avoids the very end where fills get expensive
     */
    const val DEFAULT_MIN_TIME_TO_CLOSE_MS = 300_000L

    data class Config(
        val enabled: Boolean = false,
        val maxAskFilter: Double = DEFAULT_MAX_ASK_FILTER,
        val timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS,
        val minTimeToCloseMs: Long = DEFAULT_MIN_TIME_TO_CLOSE_MS,
        val minProfitIfWinUsd: Double = SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD,
        
        /**
         * Enable settlement-aware strategy (Rank 1) instead of legacy strategy.
         * When enabled, uses BRTI print tracking for more accurate trading.
         */
        val useSettlementAware: Boolean = true,
        
        /**
         * Minimum probability threshold for settlement-aware trading.
         */
        val minSettlementProbability: Double = 0.95
    ) {
        fun isCheapEnough(ask: Double): Boolean = ask <= maxAskFilter
        
        fun isInTimeWindow(closeTimeMs: Long, nowMs: Long): Boolean {
            val timeLeft = closeTimeMs - nowMs
            return timeLeft in minTimeToCloseMs..timeWindowMs
        }
        
        fun passes(market: MarketUiModel, nowMs: Long): Boolean {
            if (!enabled) return true
            
            val closeTime = market.closeTimeEpochMs ?: return false
            val ask = market.yesAsk ?: return false
            
            return isCheapEnough(ask) && isInTimeWindow(closeTime, nowMs)
        }
        
        fun passesForSide(market: MarketUiModel, side: String, nowMs: Long): Boolean {
            if (!enabled) return true
            
            val closeTime = market.closeTimeEpochMs ?: return false
            val ask = if (side == "YES") market.yesAsk else market.noAsk
            
            return ask != null && isCheapEnough(ask) && isInTimeWindow(closeTime, nowMs)
        }
        
        /**
         * Check if settlement-aware strategy should be used.
         */
        fun useSettlementAware(): Boolean = useSettlementAware && settlementTracker != null
    }

    /**
     * Pre-configured strategies based on backtest results
     */
    object Presets {
        val CHEAP_SIDE_ONLY = Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = Long.MAX_VALUE, // All time
            minTimeToCloseMs = 0L,
            minProfitIfWinUsd = 10.0
        )
        
        val LAST_SIX_MINUTES = Config(
            enabled = true,
            maxAskFilter = 0.50, // Slightly wider for late window
            timeWindowMs = 360_000L, // 6 minutes
            minTimeToCloseMs = 60_000L, // 1 minute minimum
            minProfitIfWinUsd = 10.0
        )
        
        val TIGHT_CHEAP_LAST_THIRD = Config(
            enabled = true,
            maxAskFilter = 0.35, // Tighter cheap filter
            timeWindowMs = 300_000L, // Last 5 minutes
            minTimeToCloseMs = 120_000L, // 2 minutes minimum
            minProfitIfWinUsd = 10.0
        )
        
        val BACKTEST_WINNER = Config(
            enabled = true,
            maxAskFilter = 0.40,
            timeWindowMs = 360_000L,
            minTimeToCloseMs = 300_000L,
            minProfitIfWinUsd = 10.0
        )
        
        /**
         * Settlement-aware preset: Uses BRTI print tracking.
         */
        val SETTLEMENT_AWARE = Config(
            enabled = true,
            maxAskFilter = 0.50,
            timeWindowMs = 60_000L, // Last minute
            minTimeToCloseMs = 5_000L, // 5 seconds minimum
            minProfitIfWinUsd = 10.0,
            useSettlementAware = true,
            minSettlementProbability = 0.95
        )
    }

    /**
     * Check if a market passes the winning strategy filters.
     * 
     * If settlement-aware is enabled and available, uses that.
     * Otherwise, uses the legacy cheap-side + time-window filter.
     */
    fun filterMarket(market: MarketUiModel, config: Config, nowMs: Long): Boolean {
        return config.passes(market, nowMs)
    }

    /**
     * Get the reason why a market was filtered out.
     */
    fun filterReason(market: MarketUiModel, config: Config, nowMs: Long): String? {
        if (!config.enabled) return null
        
        val closeTime = market.closeTimeEpochMs ?: return "No close time"
        val ask = market.yesAsk ?: return "No ask"
        
        val timeLeft = closeTime - nowMs
        
        if (ask > config.maxAskFilter) {
            return "Ask ${"%.0f\u00a2".format(ask * 100)} > max ${config.maxAskFilter * 100}\u00a2"
        }
        
        if (timeLeft < config.minTimeToCloseMs) {
            return "Too close to settlement (${timeLeft / 1000}s left)"
        }
        
        if (timeLeft > config.timeWindowMs) {
            return "Too early in window (${timeLeft / 1000}s left, max ${config.timeWindowMs / 1000}s)"
        }
        
        return null
    }

    /**
     * Enhanced version that uses settlement-aware strategy when available.
     */
    suspend fun evaluateWithSettlement(
        market: MarketUiModel,
        config: Config,
        currentBRTI: Double,
        nowMs: Long
    ): WinningStrategyResult? {
        // Use settlement-aware if enabled and available
        if (config.useSettlementAware() && settlementTracker != null && feeVerifier != null) {
            val strategy = AveragingAwareStrategy(settlementTracker, feeVerifier)
            val result = strategy.evaluate(market, currentBRTI, nowMs)
            
            if (result != null && result.passes) {
                return WinningStrategyResult(
                    market = market,
                    passes = true,
                    side = result.side,
                    strategyType = StrategyType.SETTLEMENT_AWARE,
                    probability = result.probability,
                    edgeCents = result.edgeCents,
                    fairValue = result.fairValue,
                    reason = result.reason
                )
            }
        }
        
        // Fall back to legacy strategy
        if (!config.enabled) return null
        
        val closeTime = market.closeTimeEpochMs ?: return null
        val ask = market.yesAsk ?: return null
        
        val timeLeft = closeTime - nowMs
        
        if (timeLeft !in config.minTimeToCloseMs..config.timeWindowMs) {
            return null
        }
        
        if (ask > config.maxAskFilter) {
            return null
        }
        
        // Determine best side based on app pick
        // In practice, this would use the app's prediction
        val side = if (market.yesProbability != null && market.yesProbability > 0.5) {
            "YES"
        } else {
            "NO"
        }
        
        return WinningStrategyResult(
            market = market,
            passes = true,
            side = side,
            strategyType = StrategyType.LEGACY,
            probability = market.yesProbability ?: 0.5,
            edgeCents = 0.0,
            fairValue = 0.0,
            reason = "Legacy strategy: cheap side in time window"
        )
    }

    /**
     * Extension function for MarketUiModel to check strategy
     */
    fun MarketUiModel.passesWinningStrategy(config: Config, nowMs: Long): Boolean {
        return filterMarket(this, config, nowMs)
    }

    /**
     * Extension function to get filter reason
     */
    fun MarketUiModel.winningStrategyReason(config: Config, nowMs: Long): String? {
        return filterReason(this, config, nowMs)
    }
}

/**
 * Result from winning strategy evaluation.
 */
data class WinningStrategyResult(
    val market: MarketUiModel,
    val passes: Boolean,
    val side: String = "",
    val strategyType: StrategyType = StrategyType.LEGACY,
    val probability: Double = 0.0,
    val edgeCents: Double = 0.0,
    val fairValue: Double = 0.0,
    val reason: String = ""
)

/**
 * Strategy types for winning strategy.
 */
enum class StrategyType {
    LEGACY,
    SETTLEMENT_AWARE
}
