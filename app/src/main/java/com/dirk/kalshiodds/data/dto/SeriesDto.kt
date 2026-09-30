package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesResponse(
    val series: SeriesDto? = null
)

@Serializable
data class SeriesDto(
    val ticker: String? = null,
    val title: String? = null,
    @SerialName("fee_type") val feeType: String? = null,
    @SerialName("fee_multiplier") val feeMultiplier: Double? = null,
    @SerialName("maker_fee_multiplier") val makerFeeMultiplier: Double? = null,
    val frequency: String? = null
)

@Serializable
data class TradesResponse(
    val cursor: String? = null,
    val trades: List<TradeDto> = emptyList()
)

@Serializable
data class TradeDto(
    @SerialName("trade_id") val tradeId: String? = null,
    val ticker: String? = null,
    val count: Double? = null,
    @SerialName("yes_price_dollars") val yesPriceDollars: String? = null,
    @SerialName("no_price_dollars") val noPriceDollars: String? = null,
    @SerialName("yes_price") val yesPrice: Double? = null,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("created_time_ts") val createdTimeTs: Long? = null
)
