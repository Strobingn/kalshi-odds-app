package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.util.Locale

/** Kind of alert to emit. */
enum class AlertKind {
    /** Consecutive winning trades streak. */
    HOT_STREAK,
    /** Single trade with large loss. */
    BIG_LOSS,
    /** AI call with high confidence. */
    STRONG_AI
}

/** An alert to surface to the user. */
data class ScalpAlert(
    /** The category of alert. */
    val kind: AlertKind,
    /** A unique deduplication key for this alert. */
    val key: String,
    /** Short title displayed to the user. */
    val title: String,
    /** Detailed body text. */
    val body: String
)

/** Toggle configuration for alert channels. */
data class AlertToggles(
    val hotStreak: Boolean = true,
    val bigLoss: Boolean = true,
    val strongAi: Boolean = true,
    val bigLossUsd: Double = 5.0,
    val streakLen: Int = 5,
    val aiMinConfidence: Double = 0.70
)

/**
 * Policy engine that emits [ScalpAlert]s based on settled trades and AI calls.
 * Alerts are deduplicated by their `key` and bounded by [maxKeys].
 */
class ScalpAlertPolicy(private val maxKeys: Int = 1000) {
    private val seenKeys = LinkedHashSet<String>()

    /**
     * Process a batch of settled trades for a scalper and return any new alerts.
     *
     * @param scalperId caller-provided identifier for the scalper/strategy
     * @param recentSettled all settled trades for that scalper (any order)
     * @param toggles alert configuration
     * @return list of new alerts (deduped, respecting toggles)
     */
    @Synchronized
    fun onSettled(scalperId: String, recentSettled: List<ScalpTrade>, toggles: AlertToggles): List<ScalpAlert> {
        val alerts = mutableListOf<ScalpAlert>()

        // Filter to CLOSED trades with finite netUsd, sort by closedAtMs ascending
        val settled = recentSettled.filter {
            it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite()
        }.sortedBy { it.closedAtMs }

        if (settled.isEmpty()) return alerts

        // Big loss: newest trade with netUsd < -bigLossUsd
        if (toggles.bigLoss) {
            val newest = settled.last()
            val net = newest.netUsd ?: 0.0
            if (net < -toggles.bigLossUsd) {
                val key = "loss:${newest.id}"
                if (firstTime(key)) {
                    alerts.add(ScalpAlert(
                        kind = AlertKind.BIG_LOSS,
                        key = key,
                        title = "Big loss · $scalperId",
                        body = String.format(Locale.US, "Paper trade %s lost $%.2f after fees.", newest.ticker, -net)
                    ))
                }
            }
        }

        // Hot streak: last streakLen trades all have netUsd > 0
        if (toggles.hotStreak && toggles.streakLen > 0) {
            val streakLen = toggles.streakLen
            // Current run of consecutive wins, keyed by the trade that started it, so 6, 7… wins never re-alert.
            val run = settled.takeLastWhile { (it.netUsd ?: 0.0) > 0 }
            if (run.size >= streakLen) {
                val lastN = run
                val firstTradeInStreak = run.first()
                val key = "streak:$scalperId:${firstTradeInStreak.id}"
                if (firstTime(key)) {
                    val totalNet = lastN.sumOf { it.netUsd ?: 0.0 }
                    alerts.add(ScalpAlert(
                        kind = AlertKind.HOT_STREAK,
                        key = key,
                        title = "🔥 Hot streak · $scalperId",
                        body = String.format(Locale.US, "%d paper wins in a row · net +$%.2f after fees.", lastN.size, totalNet)
                    ))
                }
            }
        }

        return alerts
    }

    /**
     * Process an AI call and optionally return a new alert.
     *
     * @param callId unique identifier for the AI call
     * @param direction market direction string (UP/DOWN case-insensitive; others ignored)
     * @param confidence confidence score (0..1 or 0..100)
     * @param toggles alert configuration
     * @return a new alert if criteria met and not previously seen, else null
     */
    @Synchronized
    fun onAiCall(callId: String, direction: String, confidence: Double, toggles: AlertToggles): ScalpAlert? {
        if (!toggles.strongAi) return null

        val dir = direction.uppercase()
        if (dir != "UP" && dir != "DOWN") return null

        val conf = if (confidence > 1.0) confidence / 100.0 else confidence
        if (conf < toggles.aiMinConfidence) return null

        val key = "ai:$callId"
        if (!firstTime(key)) return null

        return ScalpAlert(
            kind = AlertKind.STRONG_AI,
            key = key,
            title = "Strong AI call · $dir",
            body = String.format(Locale.US, "%s: AI says %s at %.0f%% confidence (paper only).", callId, dir, conf * 100)
        )
    }

    /**
     * Check if this key has been seen before. If first time, record it and return true.
     * Bounded set evicts oldest when at capacity.
     */
    @Synchronized
    fun firstTime(key: String): Boolean {
        if (key in seenKeys) return false
        seenKeys.add(key)
        while (seenKeys.size > maxKeys) {
            seenKeys.remove(seenKeys.first())
        }
        return true
    }
}
