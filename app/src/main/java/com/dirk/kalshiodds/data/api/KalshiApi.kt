package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.MarketsResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Public Kalshi Trade API v2 (no auth).
 * Base: https://api.elections.kalshi.com/trade-api/v2
 */
interface KalshiApi {

    @GET("markets")
    suspend fun getMarkets(
        @Query("series_ticker") seriesTicker: String,
        @Query("status") status: String = "open"
    ): MarketsResponse

    companion object {
        const val BASE_URL = "https://api.elections.kalshi.com/trade-api/v2/"
        const val SERIES_BTC = "KXBTC15M"
        const val SERIES_WTI = "KXWTI15M"
    }
}
