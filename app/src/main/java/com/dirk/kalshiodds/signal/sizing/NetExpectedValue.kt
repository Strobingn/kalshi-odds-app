package com.dirk.kalshiodds.signal.sizing

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs

/**
 * Expected value **net of Kalshi fees and half-spread**.
 *
 * ## Fee model (documented assumption — configurable)
 *
 * Kalshi has long published a binary-contract taker fee of the form:
 *
 *     fee_dollars ≈ feeRate × C × P × (1 − P)
 *
 * where `P` is the contract price in dollars (0–1) and `C` is contracts.
 * The historical published coefficient is **0.07**. We use the smooth
 * (unrounded) formula so ranking stays continuous; the 1¢ round-up is
 * ignored. Users can change [feeRate] in Settings if Kalshi updates fees.
 *
 * This is **not** an order ticket. The app never calls trade endpoints.
 *
 * ## Half-spread
 *
 * Lifting the offer (or hitting the bid) versus mid costs about
 * `½ × (yes_ask − yes_bid)`. We subtract that immediacy cost from EV.
 *
 * ## Per-contract net EV (chosen side)
 *
 *     P_paid = mid + halfSpread     (YES)  or  (1 − mid) + halfSpread (NO)
 *     fee    = feeRate × P_paid × (1 − P_paid)
 *     net    = p_side − P_paid − fee
 *
 * [netEdgePp] = net × 100 so it is comparable to raw fair−mid edge.
 */
object NetExpectedValue {

    data class Result(
        val side: String,
        val contractPrice: Double,
        val feePerContract: Double,
        val halfSpread: Double,
        val grossEv: Double,
        val netEv: Double,
        val netEdgePp: Double,
        val rawEdgePp: Double,
        val feeRate: Double
    )

    fun compute(
        fairYes: Double,
        mid: Double,
        spreadDollars: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        preferSide: String? = null
    ): Result {
        val pYes = fairYes.coerceIn(0.02, 0.98)
        val m = mid.coerceIn(0.02, 0.98)
        val half = ((spreadDollars ?: 0.0).coerceAtLeast(0.0)) / 2.0
        val rawEdgePp = (pYes - m) * 100.0
        val side = when (preferSide?.uppercase()) {
            "YES" -> "YES"
            "NO" -> "NO"
            else -> if (rawEdgePp >= 0.0) "YES" else "NO"
        }
        val pSide = if (side == "YES") pYes else 1.0 - pYes
        val paid = (if (side == "YES") m else 1.0 - m) + half
        val clippedPaid = paid.coerceIn(0.01, 0.99)
        val fee = feePerContract(clippedPaid, feeRate)
        val gross = pSide - clippedPaid
        val net = gross - fee
        return Result(
            side = side,
            contractPrice = clippedPaid,
            feePerContract = fee,
            halfSpread = half,
            grossEv = gross,
            netEv = net,
            netEdgePp = net * 100.0,
            rawEdgePp = rawEdgePp,
            feeRate = feeRate
        )
    }

    /**
     * Kalshi-style taker fee on one contract at price [p].
     * `feeRate * P * (1-P)` — see class KDoc.
     */
    fun feePerContract(p: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val x = p.coerceIn(0.01, 0.99)
        return (feeRate.coerceIn(0.0, 0.25) * x * (1.0 - x)).coerceAtLeast(0.0)
    }

    /** True when ranking/alerting on net EV is at least as selective as raw |edge|. */
    fun preferNetForFilter(netEdgePp: Double, rawEdgePp: Double): Boolean =
        abs(netEdgePp) <= abs(rawEdgePp) + 1e-9
}
