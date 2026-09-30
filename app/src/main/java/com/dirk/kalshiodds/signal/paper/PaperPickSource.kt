package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.trade.TicketKind
import java.util.Locale

/**
 * Canonical paper-pick source for the Scorecard "By source" breakdown.
 * Stored on [PaperFill.pickSource] at fill time so leftover fills
 * (no prediction-log row for that ticker) still classify.
 */
enum class PaperPickSource {
    AI_ALERT,
    AUTOPILOT,
    LAST_MINUTE,
    D3,
    LONG_SHOT,
    MANUAL,
    TICKET;

    val label: String
        get() = when (this) {
            AI_ALERT -> "AI alert"
            AUTOPILOT -> PaperAutopilot.SOURCE
            LAST_MINUTE -> "last-minute strategy"
            D3 -> com.dirk.kalshiodds.signal.d3.D3Constants.STRATEGY_SOURCE
            LONG_SHOT -> "long-shot finder"
            MANUAL -> "manual paper"
            TICKET -> "ticket"
        }

    /** Auto paper picks must persist an AI %; manual paper may omit it. */
    val requiresAiPct: Boolean get() = this != MANUAL

    companion object {
        val SCORECARD_ORDER: List<PaperPickSource> = entries.toList()

        fun fromTicketKind(kind: TicketKind): PaperPickSource = when (kind) {
            TicketKind.HUNTER_VALUE -> LONG_SHOT
            TicketKind.LAST_MINUTE -> LAST_MINUTE
            TicketKind.D3 -> D3
            TicketKind.MANUAL, TicketKind.SELL -> MANUAL
            TicketKind.HUNTER, TicketKind.CONFIGURED -> TICKET
        }

        @JvmStatic
        fun parse(raw: String?): PaperPickSource? {
            val s = raw?.trim()?.lowercase(Locale.US) ?: return null
            if (s.isEmpty()) return null
            entries.firstOrNull { it.label.equals(raw.trim(), ignoreCase = true) }?.let { return it }
            return when {
                s == "ai signal" || s.contains("alert") -> AI_ALERT
                s.contains("autopilot") || s == PaperAutopilot.SOURCE.lowercase(Locale.US) -> AUTOPILOT
                s.contains("last-minute") || s.contains("last minute") || s.contains("last_minute") -> LAST_MINUTE
                s.contains("d3") || s.contains("daily favourite") || s.contains("daily favorite") -> D3
                s.contains("long-shot") || s.contains("long shot") || s.contains("hunter_value") ||
                    s.contains("hunter value") -> LONG_SHOT
                s == PaperTileBuy.SOURCE || s.startsWith("tile") || s.contains("manual") -> MANUAL
                s.startsWith("paper buy") && (s.contains("manual") || s.contains("sell")) -> MANUAL
                s.startsWith("ai hunter") || s.startsWith("ai ticket") || s.contains("hunter") ||
                    s.contains("ticket") -> TICKET
                else -> null
            }
        }

        fun of(fill: PaperFill): PaperPickSource? = parse(fill.pickSource) ?: parse(fill.source)

        fun of(source: String?, pickSource: String? = null): PaperPickSource? =
            parse(pickSource) ?: parse(source)
    }
}

/** Snapshot of AI / market numbers stamped onto a fill at creation. */
data class PaperFillMeta(
    val aiPct: Double? = null,
    val aiConfidence: Double? = null,
    val marketPct: Double? = null,
    val pickSource: PaperPickSource? = null,
    val kellyF: Double? = null,
    val kellyFraction: Double? = null,
    val evUsd: Double? = null
)
