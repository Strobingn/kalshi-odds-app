package com.dirk.kalshiodds.domain

import kotlin.math.abs

/**
 * One consistent YES/NO book. Binary identity:
 * `yes_bid = 1 − no_ask`, `no_bid = 1 − yes_ask`.
 *
 * Mixing a live tick field-by-field with a stale REST snapshot is what
 * produced the crossed 55/63 vs 37/50 book on KXETH15M.
 */
object ConsistentBook {

    data class Quad(
        val yesBid: Double?,
        val yesAsk: Double?,
        val noBid: Double?,
        val noAsk: Double?
    )

    fun overlay(
        restYesBid: Double?,
        restYesAsk: Double?,
        restNoBid: Double?,
        restNoAsk: Double?,
        tickYesBid: Double?,
        tickYesAsk: Double?,
        tickNoBid: Double?,
        tickNoAsk: Double?
    ): Quad {
        val tickYb = KalshiPrice.usable(tickYesBid)
        val tickYa = KalshiPrice.usable(tickYesAsk)
        val tickNb = KalshiPrice.usable(tickNoBid)
        val tickNa = KalshiPrice.usable(tickNoAsk)

        var yb = tickYb ?: KalshiPrice.usable(restYesBid)
        var ya = tickYa ?: KalshiPrice.usable(restYesAsk)
        var nb = tickNb ?: KalshiPrice.usable(restNoBid)
        var na = tickNa ?: KalshiPrice.usable(restNoAsk)

        if (yb == null && na != null) yb = complement(na)
        if (na == null && yb != null) na = complement(yb)
        if (ya == null && nb != null) ya = complement(nb)
        if (nb == null && ya != null) nb = complement(ya)

        if (conflict(yb, na)) {
            when {
                tickNa == null -> na = complement(yb)
                tickYb == null -> yb = complement(na)
                else -> Unit
            }
        }
        if (conflict(ya, nb)) {
            when {
                tickNb == null -> nb = complement(ya)
                tickYa == null -> ya = complement(nb)
                else -> Unit
            }
        }
        return Quad(yb, ya, nb, na)
    }

    private fun complement(price: Double?): Double? {
        val p = KalshiPrice.usable(price) ?: return null
        return KalshiPrice.usable(1.0 - p)
    }

    private fun conflict(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return false
        return abs(a + b - 1.0) > 1e-6
    }
}
