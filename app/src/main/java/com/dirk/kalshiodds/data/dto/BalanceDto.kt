package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /trade-api/v2/portfolio/balance
 * https://docs.kalshi.com/api-reference/portfolio/get-balance
 */
@Serializable
data class GetBalanceResponse(
    /** Available cash in cents. */
    val balance: Long? = null,
    @SerialName("balance_dollars") val balanceDollars: String? = null,
    @SerialName("portfolio_value") val portfolioValue: Long? = null,
    @SerialName("updated_ts") val updatedTs: Long? = null
) {
    /** Cash available to trade, in dollars. Prefers `balance_dollars`. */
    fun cashUsd(): Double? {
        val fromDollars = balanceDollars?.trim()?.toDoubleOrNull()
        if (fromDollars != null && fromDollars.isFinite() && fromDollars >= 0.0) return fromDollars
        val cents = balance ?: return null
        return cents / 100.0
    }
}
