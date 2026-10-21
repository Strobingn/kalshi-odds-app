package com.dirk.kalshiodds.decision

/**
 * 1.0.21 paper retune notes shown on each changed scalper's card.
 * Frozen scalpers stay on the board. Nothing here places a live order.
 */
object ScalpRetune {
    const val VERSION = "1.0.21"

    /** 10-21: frozen and retune notes for removed scalpers are gone. Kept cards show net per bet, n, and win rate. */
    private val NOTES: Map<String, String> = emptyMap()

    /** Short card line for a 1.0.21 retune or freeze. Null when this release did not change [code]. */
    fun cardNote(code: String?): String? = code?.let { NOTES[it] }

    /** Net after fees divided by closed round trips. Null until there is a closed trade. */
    fun netPerBetUsd(netUsd: Double, roundTrips: Int): Double? =
        if (roundTrips > 0 && netUsd.isFinite()) netUsd / roundTrips else null
}
