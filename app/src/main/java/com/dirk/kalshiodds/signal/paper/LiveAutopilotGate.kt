package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Limited live Autopilot may send only when every gate passes.
 * Paper and shadow must agree on side and price band. There is no
 * live-only bypass. A failed send stays failed — callers must not retry
 * the same client_order_id.
 */
object LiveAutopilotGate {
    const val PRICE_BAND = PaperAutopilot.PRICE_DELTA

    data class Verdict(
        val send: Boolean,
        val reason: String,
        val allInUsd: Double = 0.0
    )

    fun dayKey(nowMs: Long, zone: ZoneId = AutopilotRegime.ET): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    fun agrees(paperSide: String?, paperPrice: Double?, shadowSide: String?, shadowPrice: Double?): Boolean {
        if (paperSide.isNullOrBlank() || shadowSide.isNullOrBlank()) return false
        if (!paperSide.equals(shadowSide, ignoreCase = true)) return false
        val a = paperPrice ?: return false
        val b = shadowPrice ?: return false
        if (!a.isFinite() || !b.isFinite()) return false
        return abs(a - b) + 1e-12 < PRICE_BAND
    }

    fun evaluate(
        mode: AutopilotMode,
        armed: Boolean,
        credentialsOk: Boolean,
        failClosed: Boolean,
        paperFilled: Boolean,
        paperSide: String?,
        paperPrice: Double?,
        shadowSide: String?,
        shadowPrice: Double?,
        shadowDepthFill: Boolean,
        shadowAllInUsd: Double,
        spentTodayUsd: Double,
        dailyCapUsd: Double,
        alreadyAttempted: Boolean
    ): Verdict {
        if (mode != AutopilotMode.LIVE) {
            return Verdict(false, "Limited live is not the selected Autopilot mode")
        }
        if (!armed) {
            return Verdict(false, "Limited live is off until you tap Approve and confirm REAL MONEY")
        }
        if (!credentialsOk) {
            return Verdict(false, "Kalshi key missing — live Autopilot will not send")
        }
        if (failClosed) {
            return Verdict(false, "Live Autopilot is stopped after an order error — no retry")
        }
        if (alreadyAttempted) {
            return Verdict(false, "This client_order_id was already attempted")
        }
        if (!paperFilled) {
            return Verdict(false, "Paper did not take the same clip")
        }
        if (!agrees(paperSide, paperPrice, shadowSide, shadowPrice)) {
            return Verdict(false, "Paper and shadow disagree on side or price band")
        }
        if (!shadowDepthFill) {
            return Verdict(false, "Shadow would not fill at the intended limit")
        }
        if (!shadowAllInUsd.isFinite() || shadowAllInUsd <= 0.0) {
            return Verdict(false, "Live order has no all-in cost")
        }
        if (shadowAllInUsd > LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-6) {
            return Verdict(
                false,
                "Live order all-in exceeds the $${LiveOrderSizer.LIVE_ALL_IN_CAP_USD.toInt()} cap"
            )
        }
        val cap = dailyCapUsd.coerceIn(
            SignalConstants.LIVE_AUTOPILOT_DAILY_CAP_MIN_USD,
            SignalConstants.LIVE_AUTOPILOT_DAILY_CAP_MAX_USD
        )
        val spent = spentTodayUsd.coerceAtLeast(0.0)
        if (spent + shadowAllInUsd > cap + 1e-6) {
            return Verdict(false, "Daily live Autopilot cap reached", shadowAllInUsd)
        }
        return Verdict(true, "Paper and shadow agree · under $10 and the daily cap", shadowAllInUsd)
    }
}
