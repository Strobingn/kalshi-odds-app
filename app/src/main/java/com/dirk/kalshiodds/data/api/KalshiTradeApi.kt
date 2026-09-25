package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.CancelOrderV2Response
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.GetBalanceResponse
import com.dirk.kalshiodds.data.dto.PositionsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Authenticated Kalshi Trade API — **V2 event-order writes only**.
 *
 * 0.3.4 and earlier posted [createOrderV2] then, on HTTP 404, fell back to
 * `POST /portfolio/orders`. Kalshi retired that legacy write path
 * (`deprecated_v1_order_endpoint`, HTTP 410). This interface no longer
 * exposes `/portfolio/orders`.
 *
 * Create: `POST /trade-api/v2/portfolio/events/orders`
 * Cancel: `DELETE /trade-api/v2/portfolio/events/orders/{order_id}`
 *
 * Signing is applied by [KalshiAuthInterceptor] — never log PEM.
 */
interface KalshiTradeApi {

    @GET("portfolio/balance")
    suspend fun getBalance(): Response<GetBalanceResponse>

    @GET("portfolio/positions")
    suspend fun getPositions(
        @Query("count_filter") countFilter: String = "position",
        @Query("limit") limit: Int = 200,
        @Query("cursor") cursor: String? = null
    ): Response<PositionsResponse>

    @POST("portfolio/events/orders")
    suspend fun createOrderV2(@Body body: CreateOrderV2Request): Response<CreateOrderV2Response>

    @DELETE("portfolio/events/orders/{order_id}")
    suspend fun cancelOrderV2(
        @Path("order_id") orderId: String,
        @Query("market_ticker") marketTicker: String? = null,
        @Query("exchange_index") exchangeIndex: Int = -1
    ): Response<CancelOrderV2Response>
}
