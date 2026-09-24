package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kalshi Trade API **Create Order (V2)** body.
 * POST /trade-api/v2/portfolio/events/orders
 *
 * Documented fields (https://docs.kalshi.com/api-reference/orders/create-order-v2):
 * ticker, client_order_id, side (`bid`/`ask` on the YES book), count (fixed-point
 * contracts), price (fixed-point dollars), time_in_force, self_trade_prevention_type.
 *
 * `bid` = buy YES; `ask` = sell YES (economically buy NO at `1 − price`).
 * Do **not** send v1 `yes_price` / `no_price` / `action` on this path.
 */
@Serializable
data class CreateOrderV2Request(
    val ticker: String,
    val side: String,
    val count: String,
    val price: String,
    @SerialName("time_in_force") val timeInForce: String = "good_till_canceled",
    @SerialName("self_trade_prevention_type") val selfTradePreventionType: String = "taker_at_cross",
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("post_only") val postOnly: Boolean = false,
    @SerialName("cancel_order_on_pause") val cancelOrderOnPause: Boolean = true,
    @SerialName("reduce_only") val reduceOnly: Boolean = false
)

@Serializable
data class CreateOrderV2Response(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("client_order_id") val clientOrderId: String? = null,
    @SerialName("fill_count") val fillCount: String? = null,
    @SerialName("remaining_count") val remainingCount: String? = null,
    @SerialName("average_fill_price") val averageFillPrice: String? = null,
    @SerialName("average_fee_paid") val averageFeePaid: String? = null,
    @SerialName("ts_ms") val tsMs: Long? = null,
    val error: KalshiErrorBody? = null
)

@Serializable
data class CancelOrderV2Response(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("client_order_id") val clientOrderId: String? = null,
    @SerialName("reduced_by") val reducedBy: String? = null,
    @SerialName("ts_ms") val tsMs: Long? = null,
    val error: KalshiErrorBody? = null
)

@Serializable
data class KalshiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null
)

@Serializable
data class KalshiErrorEnvelope(
    val error: KalshiErrorBody? = null
)
