package com.dirk.kalshiodds.decision

/** Price stop, distinct from a net-P&L limit: spread, fees and slippage can exceed the trigger percentage. */
object ScalpRisk {
    const val DEFAULT_PERCENT = 1
    const val POLICY_VERSION = 1
    const val EXIT_DELAY_MS = 250L
    private const val EPS = 1e-9
    fun stopPrice(fill: Double, percent: Double): Double {
        require(fill.isFinite() && fill > 0.0 && fill <= 1.0)
        require(percent.isFinite() && percent > 0.0 && percent < 100.0)
        return fill * (1.0 - percent / 100.0)
    }
    fun hit(fill: Double, bid: Double?, percent: Double): Boolean =
        bid != null && bid.isFinite() && bid in 0.0..1.0 && bid <= stopPrice(fill, percent) + EPS
    fun managedExit(t: ScalpTrade): Boolean = ScalpV2.savedNumber(t.note, "riskExit", 0.0) == 1.0
    /** A running position can be tightened by the global cap, never loosened by a later settings change. */
    fun percent(t: ScalpTrade, controls: ScalpControls): Double {
        val model = ScalpModels.modelOf(t)?.code
        val cap = controls.protectivePercent(model).toDouble()
        val saved = ScalpV2.savedNumber(t.note, "riskStopPct", cap).coerceIn(1.0, 99.0)
        val v2 = if (ScalpV2.peakRule(t)) ScalpV2.stopPercent(t) else cap
        return minOf(cap, saved, v2)
    }
}
