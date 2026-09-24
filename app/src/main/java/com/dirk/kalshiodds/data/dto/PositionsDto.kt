package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /trade-api/v2/portfolio/positions
 * https://docs.kalshi.com/api-reference/portfolio/get-positions
 */
@Serializable
data class PositionsResponse(
    val cursor: String? = null,
    @SerialName("market_positions") val marketPositions: List<MarketPositionDto> = emptyList(),
    @SerialName("event_positions") val eventPositions: List<EventPositionDto> = emptyList()
)

@Serializable
data class MarketPositionDto(
    val ticker: String,
    @SerialName("exchange_index") val exchangeIndex: Int? = null,
    @SerialName("position_fp") val positionFp: String? = null,
    @SerialName("total_traded_dollars") val totalTradedDollars: String? = null,
    @SerialName("market_exposure_dollars") val marketExposureDollars: String? = null,
    @SerialName("realized_pnl_dollars") val realizedPnlDollars: String? = null,
    @SerialName("fees_paid_dollars") val feesPaidDollars: String? = null,
    @SerialName("last_updated_ts") val lastUpdatedTs: String? = null
)

@Serializable
data class EventPositionDto(
    @SerialName("event_ticker") val eventTicker: String,
    @SerialName("total_cost_dollars") val totalCostDollars: String? = null,
    @SerialName("total_cost_shares_fp") val totalCostSharesFp: String? = null,
    @SerialName("event_exposure_dollars") val eventExposureDollars: String? = null,
    @SerialName("realized_pnl_dollars") val realizedPnlDollars: String? = null,
    @SerialName("fees_paid_dollars") val feesPaidDollars: String? = null
)
