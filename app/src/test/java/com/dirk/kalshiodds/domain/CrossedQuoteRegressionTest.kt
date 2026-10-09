package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Dirk's KXETH15M-26SEP251230-30 screenshot: REST 50/63 vs 37/50 plus a
 * live yesBid of 55 produced the impossible crossed 55/63 vs 37/50 book.
 */
class CrossedQuoteRegressionTest {

    @Test
    fun dirkFiftyFiveSixtyThreeVersusThirtySevenFiftyReconcilesToYesBook() {
        val rest = MarketUiModel(
            ticker = "KXETH15M-26SEP251230-30",
            title = "ETH",
            subtitle = null,
            floorStrike = 4000.0,
            yesBid = 0.50,
            yesAsk = 0.63,
            noBid = 0.37,
            noAsk = 0.50,
            lastPrice = 0.55,
            yesProbabilityPercent = 55.0,
            noProbabilityPercent = 45.0,
            volume = 1.0,
            volume24h = 1.0,
            openInterest = 1.0,
            liquidityDollars = 100.0,
            closeTimeLocal = null,
            closeTimeEpochMs = 1L,
            status = "active",
            seriesLabel = "Ethereum"
        )
        val tick = MarketTick(
            ticker = "KXETH15M-26SEP251230-30",
            series = "KXETH15M",
            yesBid = 0.55,
            yesAsk = 0.63,
            lastPrice = 0.55,
            volume = null,
            openInterest = null,
            closeTimeEpochMs = 1L,
            source = TickSource.WS_TICKER,
            receiveElapsedNanos = 1L
        )
        val live = rest.withLiveQuote(tick)
        assertEquals(0.55, live.yesBid!!, 1e-12)
        assertEquals(0.63, live.yesAsk!!, 1e-12)
        assertEquals(0.37, live.noBid!!, 1e-12)
        assertEquals(0.45, live.noAsk!!, 1e-12)
        assertTrue(abs(live.yesBid!! + live.noAsk!! - 1.0) < 1e-9)
        assertTrue(abs(live.yesAsk!! + live.noBid!! - 1.0) < 1e-9)
        assertTrue("must not keep the crossed 37/50 NO book", live.noAsk != 0.50)
    }
}
