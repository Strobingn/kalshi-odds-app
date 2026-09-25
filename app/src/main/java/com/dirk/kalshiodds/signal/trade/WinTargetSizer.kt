package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor
import kotlin.math.min

/**
 * Size a ticket so profit-if-win ≥ [targetProfitUsd], walking the ask book
 * (VWAP, not top-of-book only). Caps by bankroll % and optional absolute $.
 * Never places an order.
 */
object WinTargetSizer {

    data class Result(
        val contracts: Int,
        val vwap: Double,
        val stakeUsd: Double,
        val feesUsd: Double,
        val profitIfWin: Double,
        val impliedChance: Double,
        val fillableContracts: Int,
        val capped: Boolean,
        val insufficientDepth: Boolean,
        val note: String
    )

    fun bankrollCap(bankrollUsd: Double, pct: Double, absCapUsd: Double?): Double {
        val frac = (bankrollUsd * (pct / 100.0)).coerceAtLeast(0.0)
        val abs = absCapUsd?.takeIf { it.isFinite() && it > 0.0 }
        return when {
            abs != null -> min(frac, abs)
            else -> frac
        }.coerceIn(SignalConstants.TICKET_STAKE_MIN_USD, SignalConstants.TICKET_STAKE_HARD_CAP_USD * 20)
    }

    fun size(
        askLevels: List<Pair<Double, Double>>,
        targetProfitUsd: Double,
        bankrollUsd: Double,
        bankrollPct: Double = 10.0,
        absCapUsd: Double? = null,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        fallbackAsk: Double? = null
    ): Result {
        val cap = bankrollCap(bankrollUsd, bankrollPct, absCapUsd)
        val target = targetProfitUsd.takeIf { it.isFinite() && it > 0.0 } ?: 50.0
        val levels = askLevels
            .mapNotNull { (p, s) ->
                val px = KalshiPrice.usable(p) ?: return@mapNotNull null
                if (!s.isFinite() || s <= 0.0) null else px to s
            }
            .sortedBy { it.first }
            .ifEmpty {
                KalshiPrice.usable(fallbackAsk)?.let { listOf(it to 10_000.0) }.orEmpty()
            }
        if (levels.isEmpty()) {
            return Result(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false, true, "No ask depth")
        }

        var filled = 0
        var cost = 0.0
        var hitTarget = false
        var capped = false
        for ((price, size) in levels) {
            val room = floor(((cap - cost) / price) + 1e-9).toInt().coerceAtLeast(0)
            if (room <= 0) {
                capped = true
                break
            }
            val available = min(floor(size + 1e-9).toInt().coerceAtLeast(0), room)
            if (available <= 0) continue
            val take = contractsToHitTarget(
                filled = filled,
                cost = cost,
                price = price,
                available = available,
                target = target,
                feeRate = feeRate
            )
            if (take <= 0) continue
            filled += take
            cost += take * price
            val vwap = cost / filled
            val profit = KalshiFee.netProfit(filled, vwap, feeRate)
            if (profit + 1e-9 >= target) {
                hitTarget = true
                break
            }
            if (cost >= cap - 1e-9) {
                capped = true
                break
            }
        }
        if (filled <= 0) {
            return Result(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, capped, true, "Book too thin")
        }
        val vwap = cost / filled
        val fees = KalshiFee.total(filled, vwap, feeRate)
        val profit = KalshiFee.netProfit(filled, vwap, feeRate)
        val insufficient = !hitTarget && !capped
        val note = when {
            hitTarget -> String.format(
                java.util.Locale.US,
                "%d ct @ %.1f¢ VWAP · stake $%.2f · Wins $%.0f",
                filled, vwap * 100.0, cost, profit
            )
            else -> String.format(
                java.util.Locale.US,
                "Capped: wins $%.0f",
                profit
            )
        }
        return Result(
            contracts = filled,
            vwap = vwap,
            stakeUsd = cost,
            feesUsd = fees,
            profitIfWin = profit,
            impliedChance = vwap,
            fillableContracts = filled,
            capped = !hitTarget,
            insufficientDepth = insufficient,
            note = note
        )
    }

    /**
     * Smallest add-on at [price] that reaches [target], or all of [available]
     * if the level still falls short. Walks the book one level at a time so a
     * deep top-of-book does not oversize past the $50 win target.
     */
    private fun contractsToHitTarget(
        filled: Int,
        cost: Double,
        price: Double,
        available: Int,
        target: Double,
        feeRate: Double
    ): Int {
        if (available <= 0) return 0
        fun profitAfter(take: Int): Double {
            val n = filled + take
            if (n <= 0) return 0.0
            val nextCost = cost + take * price
            return KalshiFee.netProfit(n, nextCost / n, feeRate)
        }
        if (profitAfter(1) + 1e-9 >= target) return 1
        if (profitAfter(available) + 1e-9 < target) return available
        var lo = 1
        var hi = available
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (profitAfter(mid) + 1e-9 >= target) hi = mid else lo = mid + 1
        }
        return lo
    }
}
