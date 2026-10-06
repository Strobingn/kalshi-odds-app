package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.data.dto.SeriesResponse
import com.dirk.kalshiodds.data.dto.TradesResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Public Kalshi Trade API v2 (no auth).
 * Base: https://api.elections.kalshi.com/trade-api/v2
 */
interface KalshiApi {

    @GET("markets")
    suspend fun getMarkets(
        @Query("series_ticker") seriesTicker: String,
        @Query("status") status: String = "open",
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
        @Query("ticker") ticker: String? = null,
        @Query("event_ticker") eventTicker: String? = null
    ): MarketsResponse

    @GET("series/{series_ticker}")
    suspend fun getSeries(
        @Path("series_ticker") seriesTicker: String
    ): SeriesResponse

    @GET("markets/trades")
    suspend fun getTrades(
        @Query("ticker") ticker: String,
        @Query("limit") limit: Int? = 100,
        @Query("min_ts") minTs: Long? = null,
        @Query("cursor") cursor: String? = null
    ): TradesResponse

    companion object {
        /** Public market-data host (also listed as a shared Trade API server). */
        const val BASE_URL = "https://api.elections.kalshi.com/trade-api/v2/"
        /**
         * Official production Trade API host for authenticated writes.
         * Docs (Create Order V2): https://docs.kalshi.com/api-reference/orders/create-order-v2
         */
        const val TRADE_BASE_URL = "https://external-api.kalshi.com/trade-api/v2/"
        /**
         * Demo (play-money) Trade API — recommended host.
         * https://docs.kalshi.com/getting_started/demo_env
         * https://docs.kalshi.com/getting_started/api_environments
         */
        const val DEMO_TRADE_BASE_URL = "https://external-api.demo.kalshi.co/trade-api/v2/"
        /** Demo shared host, also supported. */
        const val DEMO_SHARED_BASE_URL = "https://demo-api.kalshi.co/trade-api/v2/"

        fun tradePrimary(demo: Boolean): String = if (demo) DEMO_TRADE_BASE_URL else TRADE_BASE_URL
        fun tradeFallback(demo: Boolean): String = if (demo) DEMO_SHARED_BASE_URL else BASE_URL
        /** Unauthenticated GET /markets + orderbook. Shared hosts; paper Buy never calls these. */
        fun publicBase(demo: Boolean): String = if (demo) DEMO_SHARED_BASE_URL else BASE_URL

        const val SERIES_BTC = "KXBTC15M"
        const val SERIES_BTCD = "KXBTCD"
        const val SERIES_ETH = "KXETH15M"
        const val SERIES_ETHD = "KXETHD"
        const val SERIES_SOL = "KXSOL15M"
        const val SERIES_SOLD = "KXSOLD"
        /** @deprecated Removed from the live watchlist — crypto-only app. Kept so old cache/tests compile. */
        @Deprecated("WTI dropped — Dip Hunter is crypto-only")
        const val SERIES_WTI = "KXWTI15M"
    }
}
