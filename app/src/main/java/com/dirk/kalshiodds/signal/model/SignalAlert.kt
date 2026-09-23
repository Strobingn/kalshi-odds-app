package com.dirk.kalshiodds.signal.model

/**
 * Advisory-only mispricing alert. Never an order.
 */
data class SignalAlert(
    val id: String,
    val ticker: String,
    val series: String,
    val deltaPp: Double,
    val fairValuePp: Double,
    val marketMidPp: Double,
    val reason: String,
    val createdAtMs: Long,
    val receiveElapsedNanos: Long,
    val notifyElapsedNanos: Long? = null,
    val regime: String? = null,
    val tteRegime: String? = null,
    val confidence: Double? = null,
    val passedFilter: Boolean = true
) {
    val stance: String
        get() = if (deltaPp >= 0) "Lean YES vs market" else "Lean NO vs market"

    val latencyToNotifyMs: Double?
        get() = notifyElapsedNanos?.let { (it - receiveElapsedNanos) / 1_000_000.0 }
}
