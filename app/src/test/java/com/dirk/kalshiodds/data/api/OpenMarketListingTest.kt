package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.EventDto
import com.dirk.kalshiodds.data.dto.EventsResponse
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.data.dto.SeriesResponse
import com.dirk.kalshiodds.data.dto.TradesResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMarketListingTest {
    @Test fun statusOpenWinsWhenKalshiReturnsIt() = runBlocking {
        val api = FakeApi(open = listOf(MarketDto(ticker = "KXBTC15M-OPEN", status = "active")))
        val listed = OpenMarketListing.fetch(api, "KXBTC15M", 10)
        assertEquals("KXBTC15M-OPEN", listed.markets.single().ticker)
        assertEquals(0, api.eventCalls)
    }

    @Test fun emptyFifteenMinutePageUsesTheOpenEvent() = runBlocking {
        val api = FakeApi(
            open = emptyList(),
            event = "KXBTC15M-26OCT181400",
            byEvent = listOf(MarketDto(ticker = "KXBTC15M-26OCT181400-00", status = "active"))
        )
        val listed = OpenMarketListing.fetch(api, KalshiApi.SERIES_BTC, 10)
        assertEquals("KXBTC15M-26OCT181400-00", listed.markets.single().ticker)
        assertEquals(1, api.eventCalls)
        assertEquals("KXBTC15M-26OCT181400", api.eventTicker)
    }

    @Test fun dailySeriesStaysOnTheStatusOpenPage() = runBlocking {
        val api = FakeApi(open = emptyList(), event = "KXBTCD-SHOULD-NOT")
        val listed = OpenMarketListing.fetch(api, KalshiApi.SERIES_BTCD, 10)
        assertTrue(listed.markets.isEmpty())
        assertEquals(0, api.eventCalls)
    }

    @Test fun eventLookupFailureKeepsTheEmptyOpenPage() = runBlocking {
        val api = FakeApi(open = emptyList(), eventsFail = true)
        val listed = OpenMarketListing.fetch(api, "KXETH15M", 10)
        assertTrue(listed.markets.isEmpty())
    }

    private class FakeApi(
        private val open: List<MarketDto>,
        private val event: String? = null,
        private val byEvent: List<MarketDto> = emptyList(),
        private val eventsFail: Boolean = false
    ) : KalshiApi {
        var eventCalls = 0
        var eventTicker: String? = null

        override suspend fun getMarkets(
            seriesTicker: String,
            status: String,
            limit: Int?,
            cursor: String?,
            ticker: String?,
            eventTicker: String?
        ): MarketsResponse = MarketsResponse(markets = open)

        override suspend fun getMarketsForEvent(
            seriesTicker: String,
            eventTicker: String,
            limit: Int?
        ): MarketsResponse {
            this.eventTicker = eventTicker
            return MarketsResponse(markets = byEvent)
        }

        override suspend fun getEvents(
            seriesTicker: String,
            status: String,
            limit: Int?,
            cursor: String?
        ): EventsResponse {
            eventCalls += 1
            if (eventsFail) throw IOException("events down")
            return EventsResponse(events = listOfNotNull(event?.let { EventDto(eventTicker = it) }))
        }

        override suspend fun getSeries(seriesTicker: String): SeriesResponse = SeriesResponse()

        override suspend fun getTrades(
            ticker: String,
            limit: Int?,
            minTs: Long?,
            cursor: String?
        ): TradesResponse = TradesResponse()
    }
}
