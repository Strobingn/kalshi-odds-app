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
        assertEquals(setOf(market.ticker), book.openTickers())
        assertTrue(open.netEvAtEntry >= V3ShadowExperiment.SAFETY_MARGIN_USD)

        book.settle(market.ticker, "yes")

        val settled = book.snapshot().fills.single()
        assertTrue(settled.settled)
        assertTrue(book.openTickers().isEmpty())
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

    @Test
    fun `interleaved market updates replace only the matching market observation`() {
        val book = V3ShadowBook()
        val first = market("KXBTC15M-FIRST", 0.50, 0.53, 0.53, 0.53)
        val second = market("KXBTC15M-SECOND", 0.50, 0.53, 0.53, 0.53)
        book.observe(first, 1_000L)
        book.observe(second, 1_100L)
        book.observe(first, 1_200L)
        assertEquals(listOf(second.ticker, first.ticker), book.snapshot().observations.map { it.ticker })
        assertEquals(listOf(1_100L, 1_200L), book.snapshot().observations.map { it.observedAtMs })
    }

    @Test
    fun `closed market cannot open a new shadow entry`() {
        val book = V3ShadowBook()
        val m = market("KXBTC15M-CLOSED", 0.50, 0.99, 0.51, 0.51)
        book.observe(m, 10_000L)
        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(book.snapshot().lastMessage.contains("closed"))
    }

    @Test
    fun `chart uses latest market only and ignores invalid points`() {
        fun point(ticker: String, time: Long, value: Double) = V3Observation(
            ticker = ticker, observedAtMs = time, marketYes = value, fairYes = value,
            priorLogitAdjustment = 0.0, lagLogitAdjustment = 0.0, gateReason = "test"
        )
        val points = listOf(point("A", 4, 0.4), point("B", 2, 0.2), point("A", 3, 0.3), point("B", 5, Double.NaN))
        assertEquals(listOf(3L, 4L), V3ChartData.currentMarket(points).map { it.observedAtMs })
    }

    @Test
    fun `pnl chart excludes open and void records and orders by settlement`() {
        val fill = V3ShadowFill("id", "KXBTC15M-X", "YES", 0.5, 0.02, 0.5, 0.6, 0.08, 1L)
        val first = fill.copy(id = "one", settled = true, outcome = "yes", pnlPerContract = 0.48, settledAtMs = 20L)
        val second = fill.copy(id = "two", settled = true, outcome = "no", pnlPerContract = -0.52, settledAtMs = 10L)
        val void = fill.copy(id = "void", settled = true, outcome = "void", pnlPerContract = 0.0, settledAtMs = 30L)
        assertEquals(listOf(0.0, -0.52, -0.040000000000000036), V3ChartData.settledPnl(listOf(first, second, void, fill)))
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
