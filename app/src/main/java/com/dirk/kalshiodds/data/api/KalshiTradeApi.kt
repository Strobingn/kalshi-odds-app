package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.CancelOrderLegacyResponse
import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderLegacyRequest
import com.dirk.kalshiodds.data.dto.CreateOrderLegacyResponse
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Authenticated Kalshi Trade API (create + cancel only).
 *
 * Primary: V2 event-market path documented at
 * `POST /trade-api/v2/portfolio/events/orders`.
 * Legacy `/portfolio/orders` is kept as a 404 fallback (deprecated ≥ May 2026).
 *
 * Signing is applied by [KalshiAuthInterceptor] — never log PEM.
 */
interface KalshiTradeApi {

    @POST("portfolio/events/orders")
    suspend fun createOrderV2(@Body body: CreateOrderV2Request): Response<CreateOrderV2Response>

    @DELETE("portfolio/events/orders/{order_id}")
    suspend fun cancelOrderV2(
        @Path("order_id") orderId: String,
        @Query("market_ticker") marketTicker: String? = null,
        @Query("exchange_index") exchangeIndex: Int = -1
    ): Response<CancelOrderV2Response>

    @POST("portfolio/orders")
    suspend fun createOrderLegacy(@Body body: CreateOrderLegacyRequest): Response<CreateOrderLegacyResponse>

    @DELETE("portfolio/orders/{order_id}")
    suspend fun cancelOrderLegacy(@Path("order_id") orderId: String): Response<CancelOrderLegacyResponse>
}
