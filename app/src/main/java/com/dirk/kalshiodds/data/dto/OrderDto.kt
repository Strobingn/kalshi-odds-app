package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kalshi Trade API **Create Order (V2)** body.
 * POST /trade-api/v2/portfolio/events/orders
 *
 * Documented fields (https://docs.kalshi.com/api-reference/orders/create-order-v2):
 * ticker, client_order_id, side (`bid`/`ask` on the YES book), count (fixed-point
 * contracts), price (fixed-point dollars), time_in_force, self_trade_prevention_type,
 * reduce_only.
 *
 * `bid` = buy YES; `ask` = sell YES (economically buy NO at `1 − price`).
 * `time_in_force` enum: `fill_or_kill` | `good_till_canceled` | `immediate_or_cancel`.
 * Official schema: "Orders with reduce_only set to true will be rejected unless
 * time_in_force is immediate_or_cancel."
 * Do **not** send v1 `yes_price` / `no_price` / `action` / sell-floor / max-cost
 * fields — they are not on CreateOrderV2Request.
 */
@Serializable
data class CreateOrderV2Request(
    val ticker: String,
    val side: String,
    val count: String,
    val price: String,
    @SerialName("time_in_force") val timeInForce: String = TIME_IN_FORCE_GTC,
    @SerialName("self_trade_prevention_type") val selfTradePreventionType: String = "taker_at_cross",
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("post_only") val postOnly: Boolean = false,
    /** Official Create Order V2 example is `false`. */
    @SerialName("cancel_order_on_pause") val cancelOrderOnPause: Boolean = false,
    @SerialName("reduce_only") val reduceOnly: Boolean = false
) {
    companion object {
        const val TIME_IN_FORCE_GTC = "good_till_canceled"
        const val TIME_IN_FORCE_IOC = "immediate_or_cancel"
        const val TIME_IN_FORCE_FOK = "fill_or_kill"
    }
}

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
data class OrdersListResponse(
    val orders: List<PortfolioOrderDto> = emptyList(),
    val cursor: String? = null
)

@Serializable
data class PortfolioOrderDto(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("client_order_id") val clientOrderId: String? = null,
    val ticker: String? = null,
    val status: String? = null,
    @SerialName("fill_count") val fillCount: String? = null,
    @SerialName("fill_count_fp") val fillCountFp: String? = null,
    @SerialName("remaining_count") val remainingCount: String? = null,
    @SerialName("remaining_count_fp") val remainingCountFp: String? = null,
    @SerialName("yes_price_dollars") val yesPriceDollars: String? = null
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
