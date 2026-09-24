package com.dirk.kalshiodds.domain

/**
 * Parse Kalshi Trade API v2 price / size strings.
 *
 * Documented market fields (GET /markets, ticker WS): `yes_ask_dollars`,
 * `yes_bid_dollars`, `no_ask_dollars`, `no_bid_dollars` are FixedPointDollars
 * (`"0.0460"`). Live 15m books use `tapered_deci_cent` so 0.6¢ (`"0.0060"`)
 * is a real ask — not empty.
 *
 * `"0"`, `"0.0000"`, and `"1.0000"` are not tradable quotes (empty book or
 * a $1 ask that cannot fill a binary). Integer cents (`"45"`) are accepted
 * if a host still emits them without a decimal.
 */
object KalshiPrice {
    const val MIN_TICK_DOLLARS = 0.001
    const val MAX_TICK_DOLLARS = 0.999

    fun parseDollars(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val v = s.toDoubleOrNull() ?: return null
        if (!v.isFinite()) return null
        val dollars = when {
            s.contains('.') -> v
            v > 1.0 && v <= 100.0 -> v / 100.0
            else -> v
        }
        return usable(dollars)
    }

    fun parseCount(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val v = s.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return v
    }

    fun usable(price: Double?): Double? {
        val p = price ?: return null
        if (!p.isFinite()) return null
        if (p < MIN_TICK_DOLLARS - 1e-12) return null
        if (p > MAX_TICK_DOLLARS + 1e-12) return null
        return p
    }

    /** Binary complement: YES ask = 1 − NO bid (and the reverse). */
    fun impliedAskFromOppositeBid(oppositeBid: Double?): Double? {
        val bid = usable(oppositeBid) ?: return null
        return usable(1.0 - bid)
    }

    fun clipLimit(price: Double): Double =
        price.coerceIn(MIN_TICK_DOLLARS, MAX_TICK_DOLLARS)
}
