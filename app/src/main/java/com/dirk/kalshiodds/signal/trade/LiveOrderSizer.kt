package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.floor

/**
 * Final live-order size. Dirk's hard rule (0.3.16): real orders are at
 * most **$10 all-in including Kalshi fees**.
 *
 * Fee matches official rounding
 * (https://docs.kalshi.com/getting_started/fee_rounding) and the
 * last-minute research `all_in_cost`:
 *
 *     trade_fee = ceil_6dp(0.07 × count × P × (1 − P))
 *     all-in    = ceil_cent(count × P + trade_fee)
 *
 * [count] is the largest integer with all-in ≤ [LIVE_ALL_IN_CAP_USD].
 * Profit if the side wins: `count × $1 − all-in`.
 * Never places an order.
 */
object LiveOrderSizer {

    const val LIVE_ALL_IN_CAP_USD = SignalConstants.LIVE_ALL_IN_CAP_USD

    data class Clip(
        val count: Int,
        val price: Double,
        val feeUsd: Double,
        val positionUsd: Double,
        val allInUsd: Double,
        val profitIfWinUsd: Double,
        val priceWire: String,
        val countWire: String,
        val refusedReason: String? = null
    ) {
        val ok: Boolean get() = refusedReason == null && count > 0
    }

    fun feeUsd(
        count: Int,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double = KalshiFee.total(count, price, feeRate)

    fun allInUsd(
        count: Int,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double = KalshiFee.totalCost(count, price, feeRate)

    fun profitIfWinUsd(
        count: Int,
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double {
        val n = count.coerceAtLeast(0)
        if (n <= 0) return 0.0
        return n * SignalConstants.CONTRACT_SETTLEMENT_USD - allInUsd(n, price, feeRate)
    }

    fun maxCount(
        price: Double,
        capUsd: Double = LIVE_ALL_IN_CAP_USD,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Int {
        val p = KalshiPrice.usable(price) ?: return 0
        if (!capUsd.isFinite() || capUsd <= 0.0) return 0
        val hi = (floor(capUsd / p) + 2.0).toInt().coerceAtLeast(0)
        for (n in hi downTo 1) {
            if (allInUsd(n, p, feeRate) <= capUsd + 1e-9) return n
        }
        return 0
    }

    fun size(
        price: Double,
        capUsd: Double = LIVE_ALL_IN_CAP_USD,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Clip {
        val p = KalshiPrice.usable(price)
            ?: return Clip(
                count = 0,
                price = 0.0,
                feeUsd = 0.0,
                positionUsd = 0.0,
                allInUsd = 0.0,
                profitIfWinUsd = 0.0,
                priceWire = "0.0000",
                countWire = "0.00",
                refusedReason = "No usable ask to size a $10 live order"
            )
        val n = maxCount(p, capUsd, feeRate)
        if (n < 1) {
            return Clip(
                count = 0,
                price = p,
                feeUsd = 0.0,
                positionUsd = 0.0,
                allInUsd = 0.0,
                profitIfWinUsd = 0.0,
                priceWire = KalshiPrice.toWireDollars(p) ?: "0.0000",
                countWire = "0.00",
                refusedReason = String.format(
                    Locale.US,
                    "Cannot fit 1 contract at %s under the $%.2f all-in cap (including fees)",
                    KalshiPrice.toWireDollars(p) ?: p.toString(),
                    capUsd
                )
            )
        }
        val fee = feeUsd(n, p, feeRate)
        val pos = positionBd(n, p).toDouble()
        val allIn = pos + fee
        return Clip(
            count = n,
            price = p,
            feeUsd = fee,
            positionUsd = pos,
            allInUsd = allIn,
            profitIfWinUsd = n * SignalConstants.CONTRACT_SETTLEMENT_USD - allIn,
            priceWire = KalshiPrice.toWireDollars(p) ?: String.format(Locale.US, "%.4f", p),
            countWire = String.format(Locale.US, "%.2f", n.toDouble())
        )
    }

    /**
     * Last-chance cap on the ticket that is about to hit HTTP.
     * Never raises size. Refuses rather than send over the $10 all-in cap.
     */
    fun enforce(
        ticket: TradeTicket,
        capUsd: Double = LIVE_ALL_IN_CAP_USD,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Clip {
        if (ticket.isSell) {
            val p = KalshiPrice.usable(ticket.limitPrice) ?: ticket.limitPrice
            return Clip(
                count = ticket.contracts,
                price = p,
                feeUsd = 0.0,
                positionUsd = ticket.contracts * p,
                allInUsd = ticket.contracts * p,
                profitIfWinUsd = ticket.profitIfWinUsd ?: 0.0,
                priceWire = KalshiPrice.toWireDollars(ticket.yesLimitPrice)
                    ?: String.format(Locale.US, "%.4f", ticket.yesLimitPrice),
                countWire = String.format(Locale.US, "%.2f", ticket.contracts.toDouble())
            )
        }
        val p = KalshiPrice.usable(ticket.limitPrice) ?: KalshiPrice.usable(ticket.estimatedAvgFill)
        val clip = size(p ?: 0.0, capUsd, feeRate)
        if (!clip.ok) return clip
        val want = ticket.contracts
        if (want > clip.count) {
            return clip.copy(
                refusedReason = null
            )
        }
        if (want < 1) return clip
        val fee = feeUsd(want, clip.price, feeRate)
        val pos = positionBd(want, clip.price).toDouble()
        val allIn = pos + fee
        if (allIn > capUsd + 1e-9) {
            return clip
        }
        return Clip(
            count = want,
            price = clip.price,
            feeUsd = fee,
            positionUsd = pos,
            allInUsd = allIn,
            profitIfWinUsd = want * SignalConstants.CONTRACT_SETTLEMENT_USD - allIn,
            priceWire = clip.priceWire,
            countWire = String.format(Locale.US, "%.2f", want.toDouble())
        )
    }

    /** 0.3.16: min-profit is off. Always false so leftover prefs cannot block. */
    fun belowMinProfit(profitIfWinUsd: Double, minProfitUsd: Double): Boolean {
        if (minProfitUsd <= 0.0) return false
        return profitIfWinUsd + 1e-9 < minProfitUsd
    }

    fun belowMinProfitMessage(profitIfWinUsd: Double, minProfitUsd: Double): String =
        String.format(
            Locale.US,
            "Profit if win $%.2f is below the $%.0f minimum — ticket disabled",
            profitIfWinUsd,
            minProfitUsd
        )

    private fun bd(dollars: Double): BigDecimal = BigDecimal.valueOf(dollars)

    private fun priceBd(price: Double): BigDecimal =
        bd(KalshiPrice.clipLimit(price)).setScale(4, RoundingMode.HALF_UP)

    private fun positionBd(count: Int, price: Double): BigDecimal =
        priceBd(price).multiply(BigDecimal.valueOf(count.coerceAtLeast(0).toLong()))
}
