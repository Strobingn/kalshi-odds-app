package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Quality gate for automatic paper fills. Auto fills should only happen in
 * windows where the book is tight enough and deep enough that the modeled
 * edge is plausibly real, not an artifact of a stale or thin quote.
 * Manual paper taps are never gated.
 */
object PaperAutoGate {

    /** Skip auto fills when the touch spread is wider than this. */
    const val MAX_SPREAD = 0.05

    /** Skip auto fills in the final seconds of a window (thin books). */
    const val MIN_SECONDS_LEFT = 60L

    data class Quote(
        val bestBid: Double?,
        val bestAsk: Double?,
        val secondsLeft: Long? = null
    ) {
        val spread: Double?
            get() = bestBid?.takeIf { it > 0.0 }?.let { b ->
                bestAsk?.let { a -> if (a >= b) a - b else null }
            }
    }

    fun passes(quote: Quote?): Boolean {
        if (quote == null) return true
        quote.secondsLeft?.let { if (it < MIN_SECONDS_LEFT) return false }
        val spread = quote.spread ?: return true
        return spread <= MAX_SPREAD
    }

    fun reason(quote: Quote?): String? {
        if (quote == null) return null
        quote.secondsLeft?.let {
            if (it < MIN_SECONDS_LEFT) {
                return "Auto-fill skipped — under ${MIN_SECONDS_LEFT}s left in the window"
            }
        }
        val spread = quote.spread ?: return null
        if (spread > MAX_SPREAD) {
            return String.format(
                java.util.Locale.US,
                "Auto-fill skipped — spread %.1f¢ too wide (max %.0f¢)",
                spread * 100.0,
                MAX_SPREAD * 100.0
            )
        }
        return null
    }

    /** Fee-adjusted edge reference for documentation of the gate math. */
    fun perContractFee(price: Double): Double =
        com.dirk.kalshiodds.signal.trade.KalshiFee.perContract(
            price,
            SignalConstants.DEFAULT_FEE_RATE,
            1.0
        )
}
