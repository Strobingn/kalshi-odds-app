package com.dirk.kalshiodds.signal.lastminute

/**
 * 0.3.39: the last-minute / final-window play is retired (owner decision). Autopilot focuses on
 * Scalp (paper). Nothing new is evaluated, proposed, paper-filled, sent live or notified, and its
 * UI cards are hidden. Stored last-minute history (LastMinuteStore, scorecard rows) is kept as-is.
 * The BRTI spot / minute-vol feed still runs because Scalp's fair value uses it.
 */
object LastMinuteRetired {
    /** Always true in the app. Only legacy unit tests flip it (internal setter) to keep the dormant code covered. */
    @Volatile
    var retired: Boolean = true
        internal set
    const val VERSION = "0.3.39"
    const val LINE = "No bet call — the last-minute play is retired. Autopilot runs Scalp (paper)."
}
