package com.dirk.kalshiodds.signal.paper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaperPositionManagerTest {
    private fun fill(high: Double = 0.0) = PaperFill(
        id = "p1", ticker = "KXBTC15M-TEST", side = "YES", stakeUsd = 20.0,
        contracts = 100, limitPrice = 0.20, source = "AI scalping research",
        createdAtMs = 1_000L, feeUsd = 0.0, highWaterMarkPrice = high, note = "test"
    )

    @Test
    fun holdsWhilePriceIsStillMakingHighs() {
        val result = PaperPositionManager.decide(
            fill(high = 0.38), executableBid = 0.38, fairSideProbability = 0.45, nowMs = 10_000L
        )
        assertNull(result)
    }

    @Test
    fun exitsAProfitableTwoCentRetraceFromPeak() {
        val result = PaperPositionManager.decide(
            fill(high = 0.40), executableBid = 0.37, fairSideProbability = 0.45, nowMs = 10_000L
        )
        assertNotNull(result)
        assertEquals(0.37, result!!.bid, 0.0)
        org.junit.Assert.assertTrue(result.reason.contains("trailing exit"))
    }

    @Test
    fun exitsWhenModelFairValueTurnsBelowBid() {
        val result = PaperPositionManager.decide(
            fill(high = 0.35), executableBid = 0.34, fairSideProbability = 0.30, nowMs = 10_000L
        )
        assertNotNull(result)
        org.junit.Assert.assertTrue(result!!.reason.contains("model fair"))
    }

    @Test
    fun autoExitUsesTheExecutableBidChargesExitFeeAndDoesNotReenterSameWindow() {
        val book = PaperBook(initial = PaperBookState(cashUsd = 0.0), nowMs = { 10_000L })
        val candidate = PaperOpportunity.Candidate(
            ticker = "KXBTC15M-TEST", side = "YES", fairSideProbability = 0.80,
            ask = 0.20, visibleContracts = 100, feePerContractUsd = 0.0112,
            expectedNetPerContractUsd = 0.5888, source = "scalping research"
        )
        val opened = book.considerUnboundedOpportunity(candidate, enabled = true)!!
        book.updateAutoPositionHighWater(opened.ticker, opened.side, 0.40)

        val sold = book.autoSell(opened.ticker, opened.side, 0.37, "trailing test")!!

        val expected = 100 * 0.37 -
            com.dirk.kalshiodds.signal.trade.KalshiFee.total(100, 0.37) -
            opened.stakeUsd - opened.feeUsd
        assertEquals(expected, sold.pnlUsd!!, 1e-9)
        org.junit.Assert.assertTrue(sold.exitFeeUsd > 0.0)
        assertEquals(0.40, sold.highWaterMarkPrice, 1e-9)
        assertNull(book.considerUnboundedOpportunity(candidate, enabled = true))
    }
}
