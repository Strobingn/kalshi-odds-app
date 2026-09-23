package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kalshi public Trade API v2 market list response.
 * Fields match GET /markets?series_ticker=...&status=open
 */
@Serializable
data class MarketsResponse(
    val cursor: String? = null,
    val markets: List<MarketDto> = emptyList()
)

@Serializable
data class MarketDto(
    val ticker: String,
    val title: String? = null,
    @SerialName("yes_sub_title") val yesSubTitle: String? = null,
    @SerialName("yes_bid_dollars") val yesBidDollars: String? = null,
    @SerialName("yes_ask_dollars") val yesAskDollars: String? = null,
    @SerialName("no_bid_dollars") val noBidDollars: String? = null,
    @SerialName("no_ask_dollars") val noAskDollars: String? = null,
    @SerialName("last_price_dollars") val lastPriceDollars: String? = null,
    @SerialName("volume_fp") val volumeFp: String? = null,
    @SerialName("volume_24h_fp") val volume24hFp: String? = null,
    @SerialName("close_time") val closeTime: String? = null,
    val status: String? = null,
    @SerialName("floor_strike") val floorStrike: Double? = null,
    @SerialName("event_ticker") val eventTicker: String? = null
)
