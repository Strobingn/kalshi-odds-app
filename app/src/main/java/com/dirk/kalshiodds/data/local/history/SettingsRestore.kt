package com.dirk.kalshiodds.data.local.history

import com.dirk.kalshiodds.signal.config.SignalSettings
import org.json.JSONObject

/**
 * Compact settings snapshot for History → Restore. Stakes, hunter,
 * win-target, and bankroll only — never credentials.
 */
object SettingsRestore {
    fun snapshot(s: SignalSettings): String {
        val o = JSONObject()
        o.put("hunterValueStakeUsd", s.hunterValueStakeUsd)
        o.put("hunterValuePayoutUsd", s.hunterValuePayoutUsd)
        o.put("longShotMaxAsk", s.longShotMaxAsk)
        o.put("winTargetEnabled", s.winTargetEnabled)
        o.put("winTargetUsd", s.winTargetUsd)
        o.put("winTargetBankrollPct", s.winTargetBankrollPct)
        if (s.winTargetAbsCapUsd != null) o.put("winTargetAbsCapUsd", s.winTargetAbsCapUsd)
        o.put("ticketStakeUsd", s.ticketStakeUsd)
        o.put("bankrollUsd", s.bankrollUsd)
        o.put("edgeThresholdPp", s.edgeThresholdPp)
        o.put("paperTradingEnabled", s.paperTradingEnabled)
        o.put("aiPaperAutopilotEnabled", s.aiPaperAutopilotEnabled)
        o.put("autopilotMode", s.autopilotMode)
        o.put("liveAutopilotDailyCapUsd", s.liveAutopilotDailyCapUsd)
        o.put("paperBankrollStartUsd", s.paperBankrollStartUsd)
        o.put("kellyFraction", s.kellyFraction)
        o.put("paperKellyFraction", s.paperKellyFraction)
        o.put("minConfidence", s.minConfidence)
        o.put("maxSpreadCents", s.maxSpreadCents)
        o.put("minProfitIfWinUsd", s.minProfitIfWinUsd)
        return o.toString()
    }

    fun parse(json: String): RestoredSettings {
        val o = runCatching { JSONObject(json) }.getOrElse { return RestoredSettings() }
        return RestoredSettings(
            hunterValueStakeUsd = o.optDoubleOrNull("hunterValueStakeUsd"),
            hunterValuePayoutUsd = o.optDoubleOrNull("hunterValuePayoutUsd"),
            longShotMaxAsk = o.optDoubleOrNull("longShotMaxAsk")
                ?: run {
                    val stake = o.optDoubleOrNull("hunterValueStakeUsd")
                    val payout = o.optDoubleOrNull("hunterValuePayoutUsd")
                    if (stake != null && payout != null && payout > 0.0) (stake / payout).coerceIn(0.05, 0.40) else null
                },
            winTargetEnabled = if (o.has("winTargetEnabled")) o.optBoolean("winTargetEnabled") else null,
            winTargetUsd = o.optDoubleOrNull("winTargetUsd"),
            winTargetBankrollPct = o.optDoubleOrNull("winTargetBankrollPct"),
            winTargetAbsCapUsd = o.optDoubleOrNull("winTargetAbsCapUsd"),
            ticketStakeUsd = o.optDoubleOrNull("ticketStakeUsd"),
            bankrollUsd = o.optDoubleOrNull("bankrollUsd"),
            edgeThresholdPp = o.optDoubleOrNull("edgeThresholdPp"),
            paperTradingEnabled = if (o.has("paperTradingEnabled")) o.optBoolean("paperTradingEnabled") else null,
            aiPaperAutopilotEnabled = if (o.has("aiPaperAutopilotEnabled")) o.optBoolean("aiPaperAutopilotEnabled") else null,
            autopilotMode = o.optString("autopilotMode").takeIf { o.has("autopilotMode") && it.isNotBlank() },
            liveAutopilotDailyCapUsd = o.optDoubleOrNull("liveAutopilotDailyCapUsd"),
            paperBankrollStartUsd = o.optDoubleOrNull("paperBankrollStartUsd"),
            kellyFraction = o.optDoubleOrNull("kellyFraction"),
            paperKellyFraction = o.optDoubleOrNull("paperKellyFraction"),
            minConfidence = o.optDoubleOrNull("minConfidence"),
            maxSpreadCents = o.optDoubleOrNull("maxSpreadCents"),
            minProfitIfWinUsd = o.optDoubleOrNull("minProfitIfWinUsd")
        )
    }

    fun label(s: SignalSettings): String =
        "stake $${s.ticketStakeUsd.toInt()} · long-shot ≤${(s.longShotMaxAsk * 100.0).toInt()}¢ · " +
            "$10 all-in · no min-profit gate"

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isFinite()) v else null
    }
}

data class RestoredSettings(
    val hunterValueStakeUsd: Double? = null,
    val hunterValuePayoutUsd: Double? = null,
    val longShotMaxAsk: Double? = null,
    val winTargetEnabled: Boolean? = null,
    val winTargetUsd: Double? = null,
    val winTargetBankrollPct: Double? = null,
    val winTargetAbsCapUsd: Double? = null,
    val ticketStakeUsd: Double? = null,
    val bankrollUsd: Double? = null,
    val edgeThresholdPp: Double? = null,
    val paperTradingEnabled: Boolean? = null,
    val aiPaperAutopilotEnabled: Boolean? = null,
    val autopilotMode: String? = null,
    val liveAutopilotDailyCapUsd: Double? = null,
    val paperBankrollStartUsd: Double? = null,
    val kellyFraction: Double? = null,
    val paperKellyFraction: Double? = null,
    val minConfidence: Double? = null,
    val maxSpreadCents: Double? = null,
    val minProfitIfWinUsd: Double? = null
) {
    val isEmpty: Boolean
        get() = hunterValueStakeUsd == null && hunterValuePayoutUsd == null &&
            longShotMaxAsk == null &&
            winTargetEnabled == null && winTargetUsd == null &&
            ticketStakeUsd == null && bankrollUsd == null
}
