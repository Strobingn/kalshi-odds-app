package com.dirk.kalshiodds.domain.strategy

/** Direction a strategy leans for the current 15-minute window. */
enum class Direction { UP, DOWN, NONE }

/**
 * One advisory strategy signal. Analysis-only — never an order.
 *
 * @param strategy short stable key (e.g. "Trend", "Fade"); used as the
 *   signal-history key for per-strategy win rates.
 * @param confidence 0–1 conviction of the emitting strategy.
 * @param rationale short human-readable reason shown on the home card.
 * @param windowCloseMs close time of the window the signal applies to.
 */
data class StrategySignal(
    val strategy: String,
    val direction: Direction,
    val confidence: Double,
    val rationale: String,
    val windowCloseMs: Long
)
