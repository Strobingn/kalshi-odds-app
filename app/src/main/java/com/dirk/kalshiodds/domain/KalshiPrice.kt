package com.dirk.kalshiodds.domain

import java.math.BigDecimal

/**
 * Parse Kalshi Trade API v2 price / size strings.
 *
 * Documented market fields (GET /markets, ticker WS): `yes_ask_dollars`,
 * `yes_bid_dollars`, `no_ask_dollars`, `no_bid_dollars` are FixedPointDollars
 * (`"0.0460"`, up to 6 dp on responses). Live 15m books use
 * `tapered_deci_cent` so 0.1¢ (`"0.0010"`) and 1.5¢ (`"0.0150"`) are real
 * asks — not empty. Integer-cent fields cannot represent sub-cent ticks;
 * always prefer `*_dollars` (https://docs.kalshi.com/getting_started/fixed_point_migration).
 *
 * `"0"`, `"0.0000"`, and `"1.0000"` are not tradable quotes (empty book or
 * a $1 ask that cannot fill a binary). Bare integers `"1"`–`"99"` are
 * legacy **cents** (`"1"` → 1¢, never $1.00).
 *
 * Recovery (not documented wire, but the 10¢ cutoff the device reported):
 * - `"1.5"` / `"25.00"` (decimal ≥ $1 and < $100) is cents, not dollars.
 *   Treating `"25.00"` as $25 dropped every 10¢+ ask; `"1.5"` as $1.50
 *   dropped 1.5¢.
 * - Bare integers `101`–`999` are deci-cents (`150` → 15¢). Integer-cent
 *   fields stop at 99, so those values were previously rejected — the same
 *   10¢ boundary as `tapered_deci_cent`'s $0.001 → $0.01 step change.
 */
object KalshiPrice {
    const val MIN_TICK_DOLLARS = 0.001
    const val MAX_TICK_DOLLARS = 0.999

    fun parseDollars(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bd = try {
            BigDecimal(s)
        } catch (_: NumberFormatException) {
            return null
        }
        if (bd.signum() < 0) return null
        val stripped = bd.stripTrailingZeros()
        val dollars = when {
            // Documented FixedPointDollars, including 0.1¢ / 1.5¢ / 9.9¢.
            s.contains('.') && bd.compareTo(BigDecimal.ONE) < 0 -> bd
            // "$1.00" placeholder on ticker / REST — not a tradable ask.
            s.contains('.') && bd.compareTo(BigDecimal.ONE) == 0 -> bd
            // Cents written with a decimal ("1.5", "1.80", "25.00", "10.5").
            s.contains('.') && bd.compareTo(BigDecimal.ONE) > 0 && bd.compareTo(HUNDRED) < 0 ->
                bd.movePointLeft(2)
            // Legacy integer-cent fields: 1–99 cents. "1" is 1¢, not $1.
            !s.contains('.') && bd.compareTo(BigDecimal.ONE) >= 0 && bd.compareTo(HUNDRED) < 0 &&
                stripped.scale() <= 0 -> bd.movePointLeft(2)
            !s.contains('.') && bd.compareTo(HUNDRED) == 0 -> BigDecimal.ONE
            // Deci-cent integers 101–999 (10.1¢–99.9¢). Otherwise they vanish.
            !s.contains('.') && bd.compareTo(HUNDRED) > 0 && bd.compareTo(THOUSAND) < 0 &&
                stripped.scale() <= 0 -> bd.movePointLeft(3)
            else -> bd
        }
        return usable(dollars.toDouble())
    }

    fun parseCount(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val v = try {
            BigDecimal(s).toDouble()
        } catch (_: NumberFormatException) {
            return null
        }
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

    /**
     * V2 Create Order `price` FixedPointDollars string (4 dp).
     * https://docs.kalshi.com/api-reference/orders/create-order-v2
     * 0.1¢ → `"0.0010"`, 1.5¢ → `"0.0150"`.
     */
    fun toWireDollars(price: Double): String? {
        val p = usable(price) ?: return null
        return BigDecimal.valueOf(p).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    private val HUNDRED = BigDecimal("100")
    private val THOUSAND = BigDecimal("1000")
}
