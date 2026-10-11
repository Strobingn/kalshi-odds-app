package com.dirk.kalshiodds.signal.scalp

/**
 * Scalper strategy registry. In aggressive mode the engine runs one state
 * machine per strategy on the same tick stream; each may hold ONE open
 * position, and all share the global guardrails (trades/hour, daily-loss
 * breaker, kill switch). Every entry carries its own default TP / SL /
 * max-hold / feature window — the aggressive profile reads them from here
 * instead of the shared DIP-centric settings.
 *
 * Edge hypotheses (paper — the 2,100-combo backtest in
 * docs/scalping-params.md says every variant loses money after taker fees;
 * these run to track behavior, not to make money):
 *
 * - **DIP_HUNT** — buy a dip that has stopped falling. Price ran
 *   ≥ dipMinDropPp below the fast EMA within the window and is still off
 *   the window high, but velocity has stopped/accelerated back up.
 * - **MOMENTUM_SNIPER** — ride a live breakout. Enter while velocity is
 *   above threshold AND accelerating; exit the moment velocity decays
 *   below zero. Trades the ENTIRE window — late-window momentum is exactly
 *   where convergence moves get violent.
 * - **EXTREME_REVERSAL** — longshot overreaction snap-back. At ≤8¢ the
 *   market has all but written YES off; when velocity flips back up the
 *   snap toward 50¢ is violent. Also fires at ≥92¢ after a pullback, where
 *   the favorable flip is upward (convergence to $1). A downward snap from
 *   ≥92¢ is adverse and never entered (YES-long only).
 * - **VWAP_REVERT** — fade stretched moves back to the rolling VWAP. Enter
 *   when mid runs ≥ vwapK below the window VWAP and stabilizes; exit on
 *   VWAP touch (the mean IS the target, so TP is a backstop only).
 *   NOTE: the tick feed has no per-print size, so the "VWAP" is a uniform
 *   rolling mean of mids — a volume-free VWAP proxy, documented as such.
 * - **FIFTY_FLIP** — the owner's coin-flip-zone idea. When mid crosses UP
 *   through 50¢ (from within ±4¢) with velocity and bid-side book
 *   imbalance support, buy the crossing. YES-long only: a downward cross is
 *   a NO-side trade this engine cannot take. Tight TP, shortest max-hold.
 * - **BOOK_IMBALANCE** — depth pressure predicts drift. When near-mid
 *   bid depth dominates ask depth (imbalance ≥ +0.5) and the tape is not
 *   crashing, ride the drift. Exit when the imbalance decays/flips.
 *   Bid-heavy only — an ask-heavy book is a NO-side signal (untradeable).
 * - **PULSE_SNIPE** — fade cancel-spike overreactions. When the book
 *   reports an extreme YES-side cancel burst (bid support pulled) but
 *   quotes re-support (ask side pulled / bids rebuilt), the dip is
 *   liquidity-driven, not information-driven: buy it, exit fast.
 * - **SPOT_LEAD** — Coinbase spot leads the Kalshi mid by seconds. When the
 *   15s spot return blows through ±threshold and Kalshi hasn't moved yet,
 *   buy YES in the spot direction. Exit when the spot impulse decays.
 *   Spot-up only (YES-long cannot take spot-down).
 * - **RANGE_FADE** — quiet-regime mean reversion. When the window mid
 *   dispersion is tight (a ranging market), fade extensions ≥3¢ from the
 *   rolling range midpoint back to mid. Skipped entirely in volatile
 *   regimes — fading a trend is how accounts die.
 * - **LATE_DRIFT** — final-90s convergence trade, WITH the trend. Only
 *   when mid is ≥58¢ AND the whole-window velocity is positive (YES is
 *   winning and still drifting up — convergence pressure per
 *   docs/scalp-advantages.md). Never against the move; the WINDOW_CLOSE
 *   force-exit is the backstop.
 * - **OPEN_DRIVE** — opening drive. In the first 60s of a fresh window,
 *   if the opening velocity has been one-directional for ≥10s (and spot
 *   agrees when available), ride it. Hard 3-minute time stop.
 */
enum class ScalpStrategy(
    val label: String,
    /** Default take-profit in cents (aggressive profile). */
    val defaultTakeProfitPp: Double,
    /** Default stop-loss in cents (aggressive profile). */
    val defaultStopLossPp: Double,
    /** Default max hold in ms (aggressive profile). */
    val defaultMaxHoldMs: Long,
    /** Default feature window in seconds (aggressive profile). */
    val defaultWindowSeconds: Int
) {
    DIP_HUNT("Dip Hunt", 4.0, 6.0, 300_000L, 30),
    MOMENTUM_SNIPER("Momentum", 3.0, 5.0, 180_000L, 20),
    EXTREME_REVERSAL("Reversal", 5.0, 3.0, 480_000L, 30),
    VWAP_REVERT("VWAP", 3.0, 4.0, 240_000L, 60),
    FIFTY_FLIP("50 Flip", 3.0, 3.0, 90_000L, 15),
    BOOK_IMBALANCE("Imbalance", 3.0, 4.0, 180_000L, 20),
    PULSE_SNIPE("Pulse", 2.0, 4.0, 60_000L, 10),
    SPOT_LEAD("Spot", 3.0, 4.0, 120_000L, 15),
    RANGE_FADE("Range", 3.0, 3.0, 240_000L, 60),
    LATE_DRIFT("Late Drift", 3.0, 4.0, 90_000L, 120),
    OPEN_DRIVE("Open Drive", 4.0, 5.0, 180_000L, 30);

    companion object {
        fun parse(raw: String?): ScalpStrategy =
            values().firstOrNull { it.name == raw } ?: DIP_HUNT
    }
}
