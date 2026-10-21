package com.dirk.kalshiodds.decision

/**
 * 1.0.21 paper retune notes shown on each changed scalper's card.
 * Frozen scalpers stay on the board. Nothing here places a live order.
 */
object ScalpRetune {
    const val VERSION = "1.0.21"

    private const val OWN_FAV = "Retuned 1.0.21: own late-favourite rule, not the 24–42¢ gate"

    private val NOTES: Map<String, String> = mapOf(
        "r-late" to "Retuned 1.0.21: default band 80–95¢, no entry with under 180s left",
        "r-late-t1" to "Retuned 1.0.21: no entry with under 180s left",
        "r-late-t2" to "Retuned 1.0.21: no entry under 180s, max 42¢, edge 10¢",
        "sf16" to OWN_FAV,
        "sf17" to OWN_FAV,
        "sf18" to OWN_FAV,
        "sf19" to OWN_FAV,
        "sf20" to OWN_FAV,
        "sf21" to OWN_FAV,
        "sf22" to OWN_FAV,
        "sf23" to OWN_FAV,
        "sf24" to OWN_FAV,
        "sf29" to OWN_FAV,
        "sf31" to "Retuned 1.0.21: own late-favourite rule, including off-US hours",
        "fair" to "Retuned 1.0.21: default max entry 40¢; stops unchanged",
        "fair-t1" to "Retuned 1.0.21: default max entry 40¢; stops unchanged",
        "fair-t2" to "Retuned 1.0.21: default max entry 40¢; stops unchanged",
        "fairh60" to "Retuned 1.0.21: default min entry 40¢",
        "fcfade" to "Retuned 1.0.21: own 40–49¢ band, not the 24–42¢ gate",
        "sf30" to "Frozen 1.0.21: was hidden; book imbalance stays visible, no new paper trades",
        "sf32" to "Frozen 1.0.21: was hidden; net-TP copy stays visible, no new paper trades",
        "mkdip" to "Frozen 1.0.21: was hidden and still booking; maker dip stays visible, no new paper trades",
        "diph60" to "Frozen 1.0.21: was hidden; dip hold stays visible, no new paper trades",
        "dipnotp" to "Frozen 1.0.21: was hidden; dip no-TP stays visible, no new paper trades",
        "r-kelly" to "Frozen 1.0.21: was hidden; Kelly stays visible, no new paper trades",
        "xrev" to "Frozen 1.0.21: buys ≤15¢, so the 24–42¢ gate can never fill it",
        "sf51" to "Frozen 1.0.21: Open-50 siblings are frozen and every Open-50 code lost",
        "v2-sf51" to "Frozen 1.0.21: Open-50 siblings are frozen and every Open-50 code lost"
    )

    /** Short card line for a 1.0.21 retune or freeze. Null when this release did not change [code]. */
    fun cardNote(code: String?): String? = code?.let { NOTES[it] }

    /** Net after fees divided by closed round trips. Null until there is a closed trade. */
    fun netPerBetUsd(netUsd: Double, roundTrips: Int): Double? =
        if (roundTrips > 0 && netUsd.isFinite()) netUsd / roundTrips else null
}
