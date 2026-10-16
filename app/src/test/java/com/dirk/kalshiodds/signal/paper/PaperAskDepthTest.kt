package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperAskDepthTest {

    @Test
    fun bookWalksAskLevelsAtOrBelowPaidPrice() {
        // YES asks = NO bids at (1 − noPrice). 0.60 NO bid → 0.40 YES ask.
        val book = BookLevelSnapshot(
            yes = emptyList(),
            no = listOf(0.60 to 7.0, 0.55 to 10.0)
        )
        assertEquals(7, PaperAskDepth.contracts("YES", 0.40, book, market(yesAskSize = 99.0)))
        assertEquals(17, PaperAskDepth.contracts("YES", 0.45, book, market(yesAskSize = 99.0)))
        assertEquals(0, PaperAskDepth.contracts("YES", 0.30, book, market(yesAskSize = 99.0)))
    }

    @Test
    fun emptyBookFallsBackToDisplayedYesAskSize() {
        assertEquals(
            12,
            PaperAskDepth.contracts("YES", 0.40, book = null, market = market(yesAskSize = 12.0))
        )
        assertEquals(
            12,
            PaperAskDepth.contracts(
                "YES",
                0.40,
                book = BookLevelSnapshot(),
                market = market(yesAskSize = 12.0)
            )
        )
    }

    @Test
    fun unknownDepthIsNullNeverUnlimited() {
        assertNull(PaperAskDepth.contracts("YES", 0.40, book = null, market = null))
        assertNull(PaperAskDepth.contracts("NO", 0.40, book = null, market = market(yesAskSize = 50.0)))
        assertNull(PaperAskDepth.contracts("YES", 0.40, book = null, market = market(yesAskSize = null)))
    }

    @Test
    fun presentBookWithNoSizeAtAskIsZeroNotDisplayedFallback() {
        val book = BookLevelSnapshot(yes = listOf(0.50 to 100.0), no = emptyList())
        assertEquals(0, PaperAskDepth.contracts("YES", 0.40, book, market(yesAskSize = 99.0)))
    }

    @Test
    fun unknownDepthSkipsKellyFill() {
        val sized = PaperKellySizer.size(0.80, 0.20, bankrollUsd = 5_000.0, kellyFraction = 1.0)
        assertTrue(sized.skip)
        assertTrue(sized.reason!!.contains("depth", ignoreCase = true))
        assertEquals(0, sized.contracts)
    }

    @Test
    fun displayedAskSizeCapsKellyContracts() {
        val depth = PaperAskDepth.contracts("YES", 0.20, null, market(yesAskSize = 3.0))
        val sized = PaperKellySizer.size(
            0.80,
            0.20,
            bankrollUsd = 5_000.0,
            kellyFraction = 1.0,
            depthContracts = depth
        )
        assertTrue(sized.ok)
        assertEquals(3, sized.contracts)
    }

    private fun market(yesAskSize: Double?) = MarketUiModel(
        ticker = "KXBTC15M-DEPTH",
        title = "BTC",
        subtitle = null,
        floorStrike = 90_000.0,
        yesBid = 0.39,
        yesAsk = 0.40,
        noBid = 0.59,
        noAsk = 0.60,
        yesAskSize = yesAskSize,
        lastPrice = 0.40,
        yesProbabilityPercent = 40.0,
        noProbabilityPercent = 60.0,
        volume = 50_000.0,
        volume24h = 50_000.0,
        openInterest = 10_000.0,
        liquidityDollars = 80_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = 1L,
        status = "active",
        seriesLabel = "Bitcoin"
    )
}
