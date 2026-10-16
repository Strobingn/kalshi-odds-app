package com.dirk.kalshiodds.signal.paper

/**
 * What Autopilot does when the paper decision fires. 0.3.40 (owner decision): Autopilot is PAPER-ONLY.
 * There is no live mode, no armed state and no code path from Autopilot to an order. A stored "LIVE"
 * from an older version parses as [PAPER]. Manual tickets (Approve + typed REAL MONEY) are separate.
 */
enum class AutopilotMode {
    PAPER,
    /** Records the order Autopilot WOULD have sent. Never sent. */
    SHADOW;

    val label: String
        get() = when (this) {
            PAPER -> "Paper"
            SHADOW -> "Shadow (never sent)"
        }

    companion object {
        fun parse(raw: String?): AutopilotMode {
            val s = raw?.trim()?.uppercase() ?: return PAPER
            return entries.firstOrNull { it.name == s } ?: PAPER
        }
    }
}
