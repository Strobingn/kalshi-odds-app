package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Kalshi public Trade API v2 market list response.
 * Fields match GET /markets?series_ticker=...&status=open|settled
 *
 * Live KXBTC15M / KXETH15M / KXSOL15M (2026-09-25) emit `*_dollars` only —
 * no legacy integer `yes_ask` / `last_price`. `price_level_structure` is
 * `tapered_deci_cent` and `price_ranges` is the tick grid.
 */
@Serializable
data class MarketsResponse(
    val cursor: String? = null,
    val markets: List<MarketDto> = emptyList()
)

@Serializable
data class PriceRangeDto(
    val start: String,
    val end: String,
    val step: String
)

@Serializable
data class MarketDto(
    val ticker: String,
    val title: String? = null,
    @SerialName("yes_sub_title") val yesSubTitle: String? = null,
    @SerialName("yes_bid_dollars")
    @Serializable(with = FixedPointDollarsSerializer::class)
    val yesBidDollars: String? = null,
    @SerialName("yes_ask_dollars")
    @Serializable(with = FixedPointDollarsSerializer::class)
    val yesAskDollars: String? = null,
    @SerialName("no_bid_dollars")
    @Serializable(with = FixedPointDollarsSerializer::class)
    val noBidDollars: String? = null,
    @SerialName("no_ask_dollars")
    @Serializable(with = FixedPointDollarsSerializer::class)
    val noAskDollars: String? = null,
    @SerialName("yes_bid_size_fp") val yesBidSizeFp: String? = null,
    @SerialName("yes_ask_size_fp") val yesAskSizeFp: String? = null,
    @SerialName("last_price_dollars")
    @Serializable(with = FixedPointDollarsSerializer::class)
    val lastPriceDollars: String? = null,
    @SerialName("volume_fp") val volumeFp: String? = null,
    @SerialName("volume_24h_fp") val volume24hFp: String? = null,
    @SerialName("open_interest_fp") val openInterestFp: String? = null,
    @SerialName("liquidity_dollars") val liquidityDollars: String? = null,
    @SerialName("close_time") val closeTime: String? = null,
    @SerialName("expiration_time") val expirationTime: String? = null,
    @SerialName("expected_expiration_time") val expectedExpirationTime: String? = null,
    @SerialName("latest_expiration_time") val latestExpirationTime: String? = null,
    @SerialName("settlement_ts") val settlementTs: String? = null,
    val status: String? = null,
    /** Determined/settled: `yes`, `no`, or `scalar` (docs.kalshi.com/getting_started/market_lifecycle). */
    val result: String? = null,
    @SerialName("floor_strike") val floorStrike: Double? = null,
    @SerialName("event_ticker") val eventTicker: String? = null,
    /** Human-readable grid label. Do not key tick logic off this name. */
    @SerialName("price_level_structure") val priceLevelStructure: String? = null,
    /** Source of truth: `{start,end,step}` bands in FixedPointDollars. */
    @SerialName("price_ranges") val priceRanges: List<PriceRangeDto> = emptyList()
)

/**
 * Accepts documented string FixedPointDollars (`"0.0150"`) or a JSON number
 * (`0.015`) so a typed payload cannot drop the ask.
 */
@OptIn(ExperimentalSerializationApi::class)
object FixedPointDollarsSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FixedPointDollars", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        if (decoder is JsonDecoder) {
            return when (val el = decoder.decodeJsonElement()) {
                is JsonNull -> null
                is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
        return runCatching { decoder.decodeString() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
