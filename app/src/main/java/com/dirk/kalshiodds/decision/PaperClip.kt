package com.dirk.kalshiodds.decision

/**
 * Paper clip floor. A saved size of 1 and a 1-lot touch both used to book one contract.
 * New paper entries take at least [MIN]. A touch that shows any size does not shrink the clip.
 * Live orders do not read this. A clip that does not fit free cash is skipped, not cut to 1.
 */
object PaperClip {
    const val MIN = 50

    /** Strategy size. Zero stays zero (no order). Any positive size is at least [MIN]. */
    fun sized(raw: Int): Int = if (raw <= 0) 0 else raw.coerceAtLeast(MIN)

    /**
     * Contracts to book against a displayed touch.
     * No size on the touch still skips. A touch of 1 does not shrink [want].
     */
    fun taker(want: Int, displayed: Double): Int {
        if (want <= 0 || !displayed.isFinite() || displayed < 1.0) return 0
        return sized(want)
    }
}
