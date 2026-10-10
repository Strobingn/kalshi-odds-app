package com.dirk.kalshiodds.signal.fair

/**
 * Which side is actually likely to win.
 *
 * The on-device blend and the learned residual both lose to Kalshi's own
 * price on settled calls (model Brier higher than the mid). Fading that
 * price — picking NO because the model says 70 when the mid says 80 — is
 * how the app takes the wrong side.
 *
 * The decision probability starts at the mid. A model may pull it only
 * when its walk-forward holdout beat the market, and only a quarter of
 * the way. In the last five minutes the settlement-average fair (the 60s
 * index average Kalshi pays, ties YES) takes over, because that is
 * information a stale book has not printed yet.
 */
object WinningSide {
    const val MODEL_WEIGHT = 0.25
    /** Settlement math starts to matter inside this many seconds. */
    const val SETTLE_RAMP_START_S = 300.0
    const val SETTLE_RAMP_END_S = 30.0
    const val SETTLE_MAX_WEIGHT = 0.90

    /**
     * True only while a settlement-derived probability has non-zero weight.
     * This keeps callers from overwriting the settlement ramp with a
     * point-spot direction lock.
     */
    fun settlementRampActive(settlePp: Double?, tteSeconds: Double?): Boolean {
        if (settlePp?.isFinite() != true) return false
        val t = (tteSeconds ?: 900.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: return false
        return t < SETTLE_RAMP_START_S
    }

    fun fairYesPp(
        marketPp: Double,
        settlePp: Double?,
        tteSeconds: Double?,
        modelPp: Double?
    ): Double {
        var fair = marketPp.coerceIn(2.0, 98.0)
        val model = modelPp?.takeIf { it.isFinite() }
        if (model != null) {
            val m = model.coerceIn(2.0, 98.0)
            fair = ((1.0 - MODEL_WEIGHT) * fair + MODEL_WEIGHT * m).coerceIn(2.0, 98.0)
        }
        val settle = settlePp?.takeIf { it.isFinite() }
        if (settle != null) {
            val t = (tteSeconds ?: 900.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 900.0
            val span = SETTLE_RAMP_START_S - SETTLE_RAMP_END_S
            val w = when {
                t >= SETTLE_RAMP_START_S -> 0.0
                t <= SETTLE_RAMP_END_S -> SETTLE_MAX_WEIGHT
                else -> (SETTLE_RAMP_START_S - t) / span * SETTLE_MAX_WEIGHT
            }
            if (w > 0.0) {
                fair = ((1.0 - w) * fair + w * settle.coerceIn(0.0, 100.0)).coerceIn(2.0, 98.0)
            }
        }
        return fair
    }

    /** Likely winner, not the underpriced side. */
    fun side(fairYesPp: Double): String = if (fairYesPp >= 50.0) "YES" else "NO"
}
