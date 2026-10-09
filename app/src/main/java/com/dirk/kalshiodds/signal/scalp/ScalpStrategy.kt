package com.dirk.kalshiodds.signal.scalp

/**
 * Scalper strategies. In aggressive mode the engine runs one state machine
 * per strategy; each may hold ONE open position, and all share the global
 * guardrails (trades/hour, daily-loss breaker, kill switch).
 *
 * Edge hypotheses (paper — the 2,100-combo backtest in
 * docs/scalping-params.md says every variant loses money after taker fees;
 * these run to track behavior, not to make money):
 *
 * - **DIP_HUNT** — buy a dip that has stopped falling. Price ran
 *   ≥ dipMinDropPp below the fast EMA within the window and is still off
 *   the window high, but velocity has stopped/accelerated back up. The
 *   Kalshi mid overreacts to flow spikes; the stabilization is the signal.
 * - **MOMENTUM_SNIPER** — ride a live breakout. Enter while velocity is
 *   above threshold AND accelerating; exit the moment velocity decays
 *   below zero (momentum fades fast on 15-min markets) or a small TP
 *   prints. Needs room, so no entries in the last minute of a window.
 * - **EXTREME_REVERSAL** — longshot overreaction snap-back. At ≤8¢ the
 *   market has all but written YES off; when velocity flips back up the
 *   snap toward 50¢ is violent. Also fires at ≥92¢ after a pullback, where
 *   the favorable flip is upward (convergence to $1). Both buy YES — a
 *   downward snap from ≥92¢ is adverse and never entered.
 */
enum class ScalpStrategy(val label: String) {
    DIP_HUNT("Dip Hunt"),
    MOMENTUM_SNIPER("Momentum"),
    EXTREME_REVERSAL("Reversal");

    companion object {
        fun parse(raw: String?): ScalpStrategy =
            values().firstOrNull { it.name == raw } ?: DIP_HUNT
    }
}
