package com.dirk.kalshiodds.signal.config

/**
 * One-time remap of the stored min-profit-if-win setting.
 *
 * 0.3.14 phones showed a $20 minimum ("Profit if win $9.02 is below the
 * $20 minimum") even though the user's rule and [SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD]
 * are $10. A stored `20` that was never explicitly chosen (or that we
 * cannot tell was chosen) is treated as the old implicit default and
 * reset to $10. The Settings slider still writes any value the user picks.
 */
object MinProfitMigration {
    const val LEGACY_DEFAULT_USD = 20.0

    fun resolve(stored: Double?, userExplicitlySet: Boolean): Double {
        val fallback = SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD
        if (userExplicitlySet) {
            return stored?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: fallback
        }
        if (stored == null || !stored.isFinite()) return fallback
        if (kotlin.math.abs(stored - LEGACY_DEFAULT_USD) < 1e-9) return fallback
        return stored.coerceIn(0.0, 100.0)
    }

    fun isLegacyUnsetDefault(stored: Double?): Boolean {
        val v = stored?.takeIf { it.isFinite() } ?: return false
        return kotlin.math.abs(v - LEGACY_DEFAULT_USD) < 1e-9
    }
}
