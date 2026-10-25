package com.dirk.kalshiodds.decision

import kotlinx.serialization.Serializable

/** Optional per-V2 overrides. Null inherits the global/learned value. Filled trades keep saved risk. */
@Serializable
data class ScalpStrategySettings(
    val contracts: Int? = null, val minEntryCents: Int = 1, val maxEntryCents: Int? = null,
    val stopLossPercent: Int? = null, val trailCents: Int? = null, val stopCents: Int? = null,
    val takeProfitCents: Int? = null, val maxHoldSeconds: Int? = null,
    val untilMinute: Int = 14, val cadenceSeconds: Int = 0, val maxOpenPerSide: Int = 0,
    /** Research strategies: edge needed after fees (¢/contract). */
    val edgeCents: Int? = null,
    /** "AI learns": the strategy gently moves its own slider from its own paper results. Manual changes reset the baseline. */
    val aiLearns: Boolean = true, val aiNote: String = "", val aiBaseCents: Int? = null, val aiN: Int = 0, val aiNet: Double = 0.0
) {
    /** A manual slider change by Dirk wins: the learner restarts from the values he set. */
    fun manual(): ScalpStrategySettings = copy(aiBaseCents = null, aiN = 0, aiNet = 0.0, aiNote = if (aiLearns) "set by you · AI restarts from here" else "")
    fun normalized(): ScalpStrategySettings {
        val high = maxEntryCents?.coerceIn(1, 99)
        return copy(edgeCents = edgeCents?.coerceIn(0, 30), contracts = contracts?.let { PaperClip.sized(it).coerceAtMost(1000) }, minEntryCents = minEntryCents.coerceIn(1, high ?: 99),
            maxEntryCents = high, stopLossPercent = stopLossPercent?.coerceIn(1, 99), trailCents = trailCents?.coerceIn(1, 20),
            stopCents = stopCents?.coerceIn(1, 99), takeProfitCents = takeProfitCents?.coerceIn(0, 99),
            maxHoldSeconds = maxHoldSeconds?.coerceIn(0, 3600), untilMinute = untilMinute.coerceIn(1, 14),
            cadenceSeconds = cadenceSeconds.coerceIn(0, 30), maxOpenPerSide = maxOpenPerSide.coerceIn(0, 20))
    }
    fun plan(base: ScalpV2.Plan): ScalpV2.Plan = base.copy(spec = base.spec.copy(
        // A user-specified floor outranks an inherited learned ceiling; never buy below that floor.
        maxAsk = minOf(ScalpRule.ENTRY_HI, maxOf(minEntryCents / 100.0, maxEntryCents?.div(100.0) ?: base.spec.maxAsk)),
        clip = PaperClip.sized((contracts ?: base.spec.clip).coerceAtLeast(1))), stop = stopCents?.div(100.0) ?: base.stop)
    fun timeOk(now: Long, close: Long): Boolean = now >= close - 900_000 && now < close - 900_000 + untilMinute * 60_000L
}
