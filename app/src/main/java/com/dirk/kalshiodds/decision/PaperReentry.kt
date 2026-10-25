package com.dirk.kalshiodds.decision

/**
 * Paper clip clock. A signal that stays true may open another clip on the same
 * scalper, ticker, and side without waiting for the previous clip to close.
 *
 * The gap applies only while that side already has a pending or open clip.
 * A flat side takes the next quote immediately, so a close does not add idle time.
 * [WindowLossGate] is a separate brake and is not consulted here.
 */
object PaperReentry {
    /**
     * Stacking rate while a clip is already working. Matches the quote lane
     * (the market conflator runs at 150 ms) so a live signal is about one new
     * paper clip per evaluation, not one clip per 15-minute window.
     */
    const val GAP_MS = 200L

    /**
     * Pending plus open clips on one side. 10 contracts near 40¢ is about $4,
     * so this cap is about $1,600 and cannot pin the whole paper bankroll.
     * A user max-open slider, when set above zero, is the tighter of the two.
     */
    const val MAX_OPEN_PER_SIDE = 400

    fun cap(sideCap: Int): Int =
        if (sideCap > 0) minOf(MAX_OPEN_PER_SIDE, sideCap) else MAX_OPEN_PER_SIDE

    fun allowed(openOrPending: Int, lastClipAtMs: Long?, nowMs: Long, sideCap: Int = 0): Boolean {
        if (openOrPending >= cap(sideCap)) return false
        if (openOrPending <= 0) return true
        val last = lastClipAtMs ?: return true
        return nowMs - last >= GAP_MS
    }

    /** Null when another paper clip may be placed. */
    fun refusal(openOrPending: Int, lastClipAtMs: Long?, nowMs: Long, sideCap: Int = 0): String? {
        if (allowed(openOrPending, lastClipAtMs, nowMs, sideCap)) return null
        return if (openOrPending >= cap(sideCap)) "open clips full on this side" else "paper re-entry gap"
    }
}
