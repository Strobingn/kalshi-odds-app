package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlin.math.max

/** A held-side executable bid plus the displayed size available at that bid. */
data class PaperExitQuote(
    val price: Double,
    val availableContracts: Int? = null,
    val observedAtMs: Long = 0L,
    val source: String = "quote",
    /** Identifies one immutable depth observation; null means depth is unknown. */
    val sourceSnapshotId: String? = null
)

/**
 * Paper-only stop calculation. The trailing distance is a percentage of the
 * actual filled buy-in price, not a percentage of the peak. The peak is
 * persisted on [PaperFill] by the stop manager and is the held-side bid.
 */
class TrailingStop(
    private val paperState: PaperBookState,
    /** Legacy fixed-cent override; null means use the percentage model. */
    private val trailingStopCents: Double? = null,
    private val stopLossPct: Double = SignalConstants.DEFAULT_STOP_LOSS_PCT,
    @Suppress("UNUSED_PARAMETER") private val minProfitCents: Double = 0.0,
    private val trailingStopPct: Double = SignalConstants.DEFAULT_TRAILING_STOP_PCT,
    private val trailActivationPct: Double = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT,
    private val stopLossDelayMs: Long = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS,
    private val maxHoldMs: Long = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS,
    private val settlementFallbackMs: Long = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS,
    private val earlyLossTimeRemainingMs: Long = SignalConstants.DEFAULT_EARLY_LOSS_TIME_REMAINING_MS,
    private val recoveryTrendLookback: Int = SignalConstants.DEFAULT_RECOVERY_TREND_LOOKBACK,
    private val recoveryTrendStep: Double = SignalConstants.DEFAULT_RECOVERY_TREND_STEP,
    private val recoveryTrendWindowMs: Long = SignalConstants.DEFAULT_RECOVERY_TREND_WINDOW_MS,
    private val maxQuoteAgeMs: Long = SignalConstants.DEFAULT_MAX_EXIT_QUOTE_AGE_MS,
    private val enabledFlag: Boolean = true
) {

    data class StopConfig(
        val trailingStopPct: Double = SignalConstants.DEFAULT_TRAILING_STOP_PCT,
        val stopLossPct: Double = SignalConstants.DEFAULT_STOP_LOSS_PCT,
        val trailActivationPct: Double = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT,
        val stopLossDelayMs: Long = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS,
        val maxHoldMs: Long = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS,
        val settlementFallbackMs: Long = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS,
        val earlyLossTimeRemainingMs: Long = SignalConstants.DEFAULT_EARLY_LOSS_TIME_REMAINING_MS,
        val recoveryTrendLookback: Int = SignalConstants.DEFAULT_RECOVERY_TREND_LOOKBACK,
        val recoveryTrendStep: Double = SignalConstants.DEFAULT_RECOVERY_TREND_STEP,
        val recoveryTrendWindowMs: Long = SignalConstants.DEFAULT_RECOVERY_TREND_WINDOW_MS,
        val maxQuoteAgeMs: Long = SignalConstants.DEFAULT_MAX_EXIT_QUOTE_AGE_MS,
        val enabled: Boolean = true,
        /** Source compatibility for explicit legacy fixed-cent callers. */
        val trailingStopCents: Double? = null,
        val minProfitCents: Double = 0.0
    )

    data class StopOrder(
        val fillId: String,
        val ticker: String,
        val side: String,
        val entryPrice: Double,
        val stopPrice: Double,
        val isTrailing: Boolean,
        val isStopLoss: Boolean,
        val isTimeFallback: Boolean = false,
        val availableContracts: Int? = null,
        val triggerPrice: Double? = null,
        val activatedAt: Long? = null,
        val reason: String = "stop"
    )

    data class PositionState(
        val fill: PaperFill,
        val currentPrice: Double?,
        val entryPrice: Double,
        val pnl: Double,
        val pnlPct: Double,
        val trailingStopPrice: Double?,
        val stopLossPrice: Double?,
        val stopHit: Boolean,
        val stopType: String?,
        val peakPrice: Double?,
        val trailingDistanceCents: Double?,
        val trailingDistancePct: Double,
        val trailActivated: Boolean,
        val timeFallback: Boolean,
        val earlyLossHit: Boolean = false
    )

    fun checkPositions(currentPrices: Map<String, Double>): List<StopOrder> =
        checkPositions(
            currentPrices.mapValues { PaperExitQuote(it.value, observedAtMs = System.currentTimeMillis()) },
            System.currentTimeMillis()
        )

    fun checkPositions(quotes: Map<String, PaperExitQuote>, nowMs: Long): List<StopOrder> {
        if (!enabled()) return emptyList()
        val stops = mutableListOf<StopOrder>()
        paperState.fills.filter { !it.settled }.forEach { fill ->
            val quote = quotes[fill.ticker] ?: quotes[fill.ticker.uppercase()] ?: return@forEach
            if (quote.observedAtMs <= 0L || nowMs - quote.observedAtMs > maxQuoteAgeMs) return@forEach
            val current = quote.price
            if (!current.isFinite()) return@forEach
            val state = calculatePositionState(fill, current, nowMs, quote.observedAtMs)
            if (!state.stopHit) return@forEach
            // PositionState.stopType is the authoritative trigger. Re-testing
            // raw price thresholds here can relabel a trend-risk exit as a
            // stop loss or trailing stop after the state was evaluated.
            val timeFallback = state.stopType == "TIME FALLBACK"
            val stopLoss = state.stopType == "STOP LOSS"
            val trailing = state.stopType == "TRAILING STOP"
            val stopPrice = when {
                timeFallback -> current
                stopLoss -> state.stopLossPrice ?: current
                trailing -> state.trailingStopPrice ?: current
                else -> current
            }
            stops += StopOrder(
                fillId = fill.id,
                ticker = fill.ticker,
                side = fill.side,
                entryPrice = fill.limitPrice,
                stopPrice = stopPrice,
                isTrailing = trailing && !timeFallback,
                isStopLoss = stopLoss && !timeFallback,
                isTimeFallback = timeFallback,
                availableContracts = quote.availableContracts,
                triggerPrice = current,
                activatedAt = nowMs,
                reason = when {
                    timeFallback -> "time fallback"
                    stopLoss -> "stop loss"
                    trailing -> "trailing stop"
                    state.stopType == "TREND RISK EXIT" -> "trend risk exit"
                    else -> "risk fallback"
                }
            )
        }
        return stops
    }

    fun calculatePositionState(fill: PaperFill, currentPrice: Double): PositionState =
        calculatePositionState(fill, currentPrice, System.currentTimeMillis())

    fun calculatePositionState(
        fill: PaperFill,
        currentPrice: Double,
        nowMs: Long,
        observationAtMs: Long = nowMs
    ): PositionState {
        val entryPrice = fill.limitPrice
        val pnl = (currentPrice - entryPrice) * fill.contracts
        val pnlPct = if (fill.stakeUsd > 0.0) (pnl / fill.stakeUsd) * 100.0 else 0.0
        val ageMs = (nowMs - fill.createdAtMs).coerceAtLeast(0L)
        val closeFallback = fill.closeTimeMs?.let { it - nowMs <= settlementFallbackMs } == true
        val timeFallback = ageMs >= maxHoldMs || closeFallback
        val stopLossPrice = if (ageMs >= stopLossDelayMs || timeFallback) {
            calculateStopLossPrice(entryPrice, stopLossPct)
        } else null
        val peak = max(entryPrice, max(fill.peakPrice ?: entryPrice, currentPrice))
        val distance = effectiveDistance(entryPrice)
        val activationPrice = entryPrice * (1.0 + trailActivationPct.coerceAtLeast(0.0) / 100.0)
        val trailActivated = peak + 1e-9 >= activationPrice
        val trailingStopPrice = if (trailActivated) {
            (peak - distance).coerceIn(0.0, 1.0)
        } else null
        // A trailing stop is a profit-protection rule, not a disguised loss
        // stop. A dip below entry may only be exited by the explicit
        // time/settlement fallback below; there is no calibrated recovery
        // model in this paper path to justify an earlier below-entry exit.
        val stopLossHit = timeFallback && stopLossPrice != null && currentPrice <= stopLossPrice
        val trailingHit = trailActivated && peak > entryPrice + 1e-9 &&
            currentPrice >= entryPrice - 1e-9 &&
            trailingStopPrice != null && currentPrice <= trailingStopPrice
        val closeRemainingMs = fill.closeTimeMs?.minus(nowMs)
        val earlyLossHit = currentPrice < entryPrice - 1e-9 &&
            closeRemainingMs != null &&
            closeRemainingMs in 0L..earlyLossTimeRemainingMs &&
            sustainedDecline(fill, currentPrice, observationAtMs)
        val stopHit = timeFallback || stopLossHit || trailingHit || earlyLossHit
        val stopType = when {
            timeFallback -> "TIME FALLBACK"
            stopLossHit -> "STOP LOSS"
            trailingHit -> "TRAILING STOP"
            earlyLossHit -> "TREND RISK EXIT"
            else -> null
        }
        return PositionState(
            fill = fill,
            currentPrice = currentPrice,
            entryPrice = entryPrice,
            pnl = pnl,
            pnlPct = pnlPct,
            trailingStopPrice = trailingStopPrice,
            stopLossPrice = stopLossPrice,
            stopHit = stopHit,
            stopType = stopType,
            peakPrice = peak,
            trailingDistanceCents = distance * 100.0,
            trailingDistancePct = trailingStopPct,
            trailActivated = trailActivated,
            timeFallback = timeFallback,
            earlyLossHit = earlyLossHit
        )
    }

    /**
     * Deterministic recovery proxy: only a short, monotonic sequence of fresh
     * executable bids qualifies. This is not a probability of recovery.
     */
    private fun sustainedDecline(fill: PaperFill, currentPrice: Double, observationAtMs: Long): Boolean {
        val current = com.dirk.kalshiodds.signal.paper.PaperPriceObservation(currentPrice, observationAtMs)
        // StopOrderManager records the source observation before evaluating.
        // The fallback append keeps direct deterministic callers compatible,
        // but the runtime path consumes the already-recorded source history
        // instead of appending once per polling clock.
        val alreadyRecorded = fill.quoteHistory.any {
            it.observedAtMs == current.observedAtMs && kotlin.math.abs(it.price - current.price) <= 1e-9
        }
        val points = (if (alreadyRecorded) fill.quoteHistory else fill.quoteHistory + current)
            .filter { it.observedAtMs in (observationAtMs - recoveryTrendWindowMs)..observationAtMs }
            .distinctBy { it.observedAtMs to it.price }
            .takeLast(recoveryTrendLookback)
        if (points.size < recoveryTrendLookback) return false
        return points.zipWithNext().all { (a, b) -> a.price - b.price >= recoveryTrendStep }
    }

    private fun effectiveDistance(entryPrice: Double): Double =
        if (trailingStopCents != null) {
            trailingStopCents.coerceAtLeast(0.0) / 100.0
        } else {
            entryPrice * trailingStopPct.coerceIn(
                SignalConstants.MIN_TRAILING_STOP_PCT,
                SignalConstants.MAX_TRAILING_STOP_PCT
            ) / 100.0
        }

    private fun calculateStopLossPrice(entryPrice: Double, pct: Double): Double =
        (entryPrice * (1.0 - pct.coerceAtLeast(0.0) / 100.0)).coerceIn(0.0, 1.0)

    fun getPositionsWithStops(currentPrices: Map<String, Double>): List<PositionState> =
        paperState.fills.filter { !it.settled }.mapNotNull { fill ->
            currentPrices[fill.ticker.uppercase()]?.let { calculatePositionState(fill, it) }
        }

    fun enabled(): Boolean = enabledFlag

    fun getConfig(): StopConfig = StopConfig(
        trailingStopPct = trailingStopPct,
        stopLossPct = stopLossPct,
        trailActivationPct = trailActivationPct,
        stopLossDelayMs = stopLossDelayMs,
        maxHoldMs = maxHoldMs,
        settlementFallbackMs = settlementFallbackMs,
        earlyLossTimeRemainingMs = earlyLossTimeRemainingMs,
        recoveryTrendLookback = recoveryTrendLookback,
        recoveryTrendStep = recoveryTrendStep,
        recoveryTrendWindowMs = recoveryTrendWindowMs,
        maxQuoteAgeMs = maxQuoteAgeMs,
        enabled = enabledFlag,
        trailingStopCents = trailingStopCents,
        minProfitCents = minProfitCents
    )

    fun updateConfig(config: StopConfig): TrailingStop = TrailingStop(
        paperState = paperState,
        trailingStopCents = config.trailingStopCents,
        stopLossPct = config.stopLossPct,
        minProfitCents = config.minProfitCents,
        trailingStopPct = config.trailingStopPct,
        trailActivationPct = config.trailActivationPct,
        stopLossDelayMs = config.stopLossDelayMs,
        maxHoldMs = config.maxHoldMs,
        settlementFallbackMs = config.settlementFallbackMs,
        earlyLossTimeRemainingMs = config.earlyLossTimeRemainingMs,
        recoveryTrendLookback = config.recoveryTrendLookback,
        recoveryTrendStep = config.recoveryTrendStep,
        recoveryTrendWindowMs = config.recoveryTrendWindowMs,
        maxQuoteAgeMs = config.maxQuoteAgeMs,
        enabledFlag = config.enabled
    )
}

fun PaperBookState.getTrailingStopManager(
    trailingStopCents: Double? = null,
    stopLossPct: Double = SignalConstants.DEFAULT_STOP_LOSS_PCT,
    minProfitCents: Double = 0.0,
    trailingStopPct: Double = SignalConstants.DEFAULT_TRAILING_STOP_PCT,
    trailActivationPct: Double = SignalConstants.DEFAULT_TRAIL_ACTIVATION_PCT,
    stopLossDelayMs: Long = SignalConstants.DEFAULT_STOP_LOSS_DELAY_MS,
    maxHoldMs: Long = SignalConstants.DEFAULT_MAX_PAPER_HOLD_MS,
    settlementFallbackMs: Long = SignalConstants.DEFAULT_SETTLEMENT_FALLBACK_MS,
    earlyLossTimeRemainingMs: Long = SignalConstants.DEFAULT_EARLY_LOSS_TIME_REMAINING_MS,
    recoveryTrendLookback: Int = SignalConstants.DEFAULT_RECOVERY_TREND_LOOKBACK,
    recoveryTrendStep: Double = SignalConstants.DEFAULT_RECOVERY_TREND_STEP,
    recoveryTrendWindowMs: Long = SignalConstants.DEFAULT_RECOVERY_TREND_WINDOW_MS,
    maxQuoteAgeMs: Long = SignalConstants.DEFAULT_MAX_EXIT_QUOTE_AGE_MS,
    enabled: Boolean = true
): TrailingStop = TrailingStop(
    paperState = this,
    trailingStopCents = trailingStopCents,
    stopLossPct = stopLossPct,
    minProfitCents = minProfitCents,
    trailingStopPct = trailingStopPct,
    trailActivationPct = trailActivationPct,
    stopLossDelayMs = stopLossDelayMs,
    maxHoldMs = maxHoldMs,
    settlementFallbackMs = settlementFallbackMs,
    earlyLossTimeRemainingMs = earlyLossTimeRemainingMs,
    recoveryTrendLookback = recoveryTrendLookback,
    recoveryTrendStep = recoveryTrendStep,
    recoveryTrendWindowMs = recoveryTrendWindowMs,
    maxQuoteAgeMs = maxQuoteAgeMs,
    enabledFlag = enabled
)
