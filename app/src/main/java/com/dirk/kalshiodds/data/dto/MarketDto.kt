package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kalshi public Trade API v2 market list response.
 * Fields match GET /markets?series_ticker=...&status=open|settled
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
    @SerialName("yes_bid_size_fp") val yesBidSizeFp: String? = null,
    @SerialName("yes_ask_size_fp") val yesAskSizeFp: String? = null,
    @SerialName("last_price_dollars") val lastPriceDollars: String? = null,
    @SerialName("volume_fp") val volumeFp: String? = null,
    @SerialName("volume_24h_fp") val volume24hFp: String? = null,
    @SerialName("open_interest_fp") val openInterestFp: String? = null,
    @SerialName("liquidity_dollars") val liquidityDollars: String? = null,
    @SerialName("close_time") val closeTime: String? = null,
    @SerialName("expiration_time") val expirationTime: String? = null,
    @SerialName("expected_expiration_time") val expectedExpirationTime: String? = null,
    val status: String? = null,
    /** Settled markets: "yes" or "no". */
    val result: String? = null,
    @SerialName("floor_strike") val floorStrike: Double? = null,
    @SerialName("event_ticker") val eventTicker: String? = null
)
