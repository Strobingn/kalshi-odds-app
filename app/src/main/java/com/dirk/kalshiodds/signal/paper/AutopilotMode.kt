package com.dirk.kalshiodds.signal.paper

/**
 * What Autopilot does when the paper decision fires.
 *
 * [LIVE] stays disarmed until the Real Money tab's Approve + REAL MONEY
 * confirm. Selecting the mode does not arm it and does not place an order.
 */
enum class AutopilotMode {
    PAPER,
    SHADOW,
    LIVE;

    val label: String
        get() = when (this) {
            PAPER -> "Paper"
            SHADOW -> "Shadow"
            LIVE -> "Limited live"
        }

    companion object {
        fun parse(raw: String?): AutopilotMode {
            val s = raw?.trim()?.uppercase() ?: return PAPER
            return entries.firstOrNull { it.name == s } ?: PAPER
        }
    }
}
