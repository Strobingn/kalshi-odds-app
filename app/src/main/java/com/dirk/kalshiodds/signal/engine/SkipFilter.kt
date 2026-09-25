package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalSettings
import kotlin.math.abs

/**
 * Gate alerts and “opportunities” on confidence, liquidity, and spread.
 * Weak edges that fail are deprioritized / hidden — never alerted.
 */
object SkipFilter {
    data class Result(
        val passed: Boolean,
        val reason: String?
    )

    fun evaluate(
        confidence: Double,
        spreadDollars: Double?,
        volume: Double?,
        openInterest: Double?,
        depthNearMid: Double?,
        settings: SignalSettings
    ): Result {
        val reasons = mutableListOf<String>()
        if (confidence < settings.minConfidence) {
            reasons += "low confidence ${pct(confidence)} < ${pct(settings.minConfidence)}"
        }
        val spreadCents = spreadDollars?.times(100.0)
        if (spreadCents != null && spreadCents > settings.maxSpreadCents) {
            reasons += "wide spread ${spreadCents.toInt()}¢ > ${settings.maxSpreadCents.toInt()}¢"
        }
        val observed = listOfNotNull(volume, openInterest, depthNearMid).maxOrNull()
        if (observed != null && observed < settings.minLiquidity) {
            reasons += "thin liquidity ${observed.toInt()} < ${settings.minLiquidity.toInt()}"
        }
        return if (reasons.isEmpty()) {
            Result(true, null)
        } else {
            Result(false, reasons.joinToString(" · "))
        }
    }

    fun shouldShowOpportunity(
        passedFilter: Boolean,
        edgePp: Double?,
        settings: SignalSettings,
        netEdgePp: Double? = null,
        muted: Boolean = false
    ): Boolean {
        if (muted && settings.autoMute) return false
        val rank = if (settings.rankByNetEv) netEdgePp ?: edgePp else edgePp
        if (rank == null) return false
        if (!passedFilter && settings.hideWeakOpportunities) return false
        if (settings.hideWeakOpportunities && abs(rank) < settings.effectiveEdgeThresholdPp()) return false
        return true
    }

    private fun pct(v: Double): String = "${(v * 100.0).toInt()}%"
}
