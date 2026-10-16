package com.dirk.kalshiodds.signal.v3

import com.dirk.kalshiodds.domain.MarketUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V3ShadowExperimentTest {

    @Test
    fun `v3 opens only a normalized shadow row and settles against Kalshi result`() {
        val book = V3ShadowBook(idFactory = { "v3-row" })
        val market = market(
            ticker = "KXBTC15M-V3TEST",
            marketYes = 0.50,
            digitalFairYes = 0.99,
            yesAsk = 0.51,
            noAsk = 0.51
        )

        book.observe(market, nowMs = 1_000L)

        val open = book.snapshot().fills.single()
        assertEquals("YES", open.side)
        assertEquals(1, book.snapshot().openCount)
        assertTrue(open.netEvAtEntry >= V3ShadowExperiment.SAFETY_MARGIN_USD)

        book.settle(market.ticker, "yes")

        val settled = book.snapshot().fills.single()
        assertTrue(settled.settled)
        assertEquals(true, settled.won)
        assertEquals(1.0 - settled.entryAsk - settled.entryFee, settled.pnlPerContract ?: Double.NaN, 1e-9)
        assertNotNull(book.snapshot().modelBrier)
        assertNotNull(book.snapshot().marketBrier)
    }

    @Test
    fun `v3 refuses a shadow entry when edge cannot clear the exact ask costs`() {
        val book = V3ShadowBook()
        book.observe(
            market(
                ticker = "KXBTC15M-V3NOEDGE",
                marketYes = 0.50,
                digitalFairYes = 0.53,
                yesAsk = 0.53,
                noAsk = 0.53
            ),
            nowMs = 2_000L
        )

        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(book.snapshot().lastMessage.contains("Observe only"))
    }

    private fun market(
        ticker: String,
        marketYes: Double,
        digitalFairYes: Double,
        yesAsk: Double,
        noAsk: Double
    ) = MarketUiModel(
        ticker = ticker,
        title = ticker,
        subtitle = null,
        floorStrike = 100_000.0,
        yesBid = yesAsk - 0.02,
        yesAsk = yesAsk,
        noBid = noAsk - 0.02,
        noAsk = noAsk,
        lastPrice = marketYes,
        yesProbabilityPercent = marketYes * 100.0,
        noProbabilityPercent = (1.0 - marketYes) * 100.0,
        spreadDollars = 0.02,
        volume = null,
        volume24h = null,
        closeTimeLocal = null,
        closeTimeEpochMs = 10_000L,
        status = "open",
        seriesLabel = "Bitcoin",
        digitalFairPp = digitalFairYes * 100.0,
        spotUsd = 100_000.0
    )
}
