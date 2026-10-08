package com.dirk.kalshiodds.domain.strategy

/**
 * Runs the advisory strategy set for the KXBTC15M window. Analysis-only:
 * emits signals, never touches order placement or live approve paths.
 */
class StrategyEngine(
    val strategies: List<Strategy> = listOf(
        ConsensusTrend,
        MeanReversionFade,
        SqueezeBreakout,
        LateWindowDislocation,
        SignReversalTilt,
        SettlementAverage,
        BoundaryFade
    )
) {

    /** Evaluates every strategy; strategies with no edge simply drop out. */
    fun evaluateAll(candles: List<Candle>, ctx: WindowContext): List<StrategySignal> =
        strategies.mapNotNull { strategy ->
            runCatching { strategy.evaluate(candles, ctx) }.getOrNull()
        }

    /** The highest-confidence emitted signal, for compact display. */
    fun topSignal(signals: List<StrategySignal>): StrategySignal? =
        signals.filter { it.direction != Direction.NONE }.maxByOrNull { it.confidence }

    /** Majority of non-NONE directions; ties (or no signals) → NONE. */
    fun combinedDirection(signals: List<StrategySignal>): Direction {
        val up = signals.count { it.direction == Direction.UP }
        val down = signals.count { it.direction == Direction.DOWN }
        return when {
            up > down -> Direction.UP
            down > up -> Direction.DOWN
            else -> Direction.NONE
        }
    }
}
