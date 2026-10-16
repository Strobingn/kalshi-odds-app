package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Simplified winning strategy based on backtest results.
 * 
 * Only trade when:
 * 1. App picks a cheap side (ask <= maxAskFilter)
 * 2. Within time window (last N minutes before close)
 * 3. Profit if win >= minProfitUsd
 * 
 * This is the strategy that showed positive results in OOS backtest:
 * - App pick, ask 32-50c: 92 trades, +$89.46, +$0.97/bet, 60.9% win
 * - App pick, time-left 11-9min: 593 trades, +$134.18, +$0.23/bet, 73.2% win
 */
object WinningStrategy {

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
        val enabled: Boolean = true,
        val maxAskFilter: Double = DEFAULT_MAX_ASK_FILTER,
        val timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS,
        val minTimeToCloseMs: Long = DEFAULT_MIN_TIME_TO_CLOSE_MS,
        val minProfitIfWinUsd: Double = SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD
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
    }

    /**
     * Check if a market passes the winning strategy filters
     */
    fun filterMarket(market: MarketUiModel, config: Config, nowMs: Long): Boolean {
        return config.passes(market, nowMs)
    }

    /**
     * Get the reason why a market was filtered out
     */
    fun filterReason(market: MarketUiModel, config: Config, nowMs: Long): String? {
        if (!config.enabled) return null
        
        val closeTime = market.closeTimeEpochMs ?: return "No close time"
        val ask = market.yesAsk ?: return "No ask"
        
        val timeLeft = closeTime - nowMs
        
        if (ask > config.maxAskFilter) {
            return "Ask ${"%.0f¢".format(ask * 100)} > max ${config.maxAskFilter * 100}¢"
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

/** Public convenience extensions used by the strategy audit and UI tests. */
fun MarketUiModel.passesWinningStrategy(config: WinningStrategy.Config, nowMs: Long): Boolean =
    WinningStrategy.filterMarket(this, config, nowMs)

fun MarketUiModel.winningStrategyReason(config: WinningStrategy.Config, nowMs: Long): String? =
    WinningStrategy.filterReason(this, config, nowMs)
