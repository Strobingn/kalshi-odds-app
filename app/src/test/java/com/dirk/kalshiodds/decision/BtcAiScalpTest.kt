package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paper-only Bitcoin scalp. One tick around the entry book must not stop.
 * A real 2¢ drop must. Entry requires positive value after spread and both taker fees.
 * That gate is not evidence the strategy wins.
 */
class BtcAiScalpTest {

    @Test fun oneTickNoiseDoesNotStopAndARealTwoCentDropDoes() {
        assertFalse(BtcAiScalp.stopHit(0.31, 0.30, 0.30))
        assertFalse(BtcAiScalp.stopHit(0.31, 0.29, 0.30))
        assertTrue(BtcAiScalp.stopHit(0.31, 0.28, 0.30))
        assertFalse(BtcAiScalp.stopHit(0.42, 0.41, null))
        assertTrue(BtcAiScalp.stopHit(0.42, 0.40, null))
        assertEquals("stop −2¢", BtcAiScalp.exitReason(0.05, 0.40, 0.42, 200.0))
        assertEquals("stop −2¢", BtcAiScalp.exitReason(Double.NaN, 0.28, 0.31, 600.0, 0.30))
        assertNull(BtcAiScalp.exitReason(Double.NaN, 0.29, 0.31, 600.0, 0.30))
        assertEquals("forecast turning down", BtcAiScalp.exitReason(-0.01, 0.45, 0.42, 200.0))
        assertEquals("time left under 60s", BtcAiScalp.exitReason(0.05, 0.45, 0.42, 45.0))
        assertNull(BtcAiScalp.exitReason(0.05, 0.45, 0.42, 200.0))
    }

    @Test fun entryRequiresPositiveValueAfterSpreadAndBothTakerFees() {
        val ask = 0.42
        val spread = 0.01
        val tooSmall = 0.03
        assertTrue(BtcAiScalp.expectedValueAfterCosts(ask, spread, tooSmall) < 0.0)
        assertFalse(BtcAiScalp.wantsEntry(ask, spread, tooSmall, 200.0))
        val clear = BtcAiScalp.expectedValueAfterCosts(ask, spread, tooSmall).let { -it + 0.01 }
        val move = tooSmall + clear
        assertTrue(BtcAiScalp.expectedValueAfterCosts(ask, spread, move) > 0.0)
        assertTrue(BtcAiScalp.wantsEntry(ask, spread, move, 200.0))
        assertFalse(BtcAiScalp.wantsEntry(0.50, spread, move, 200.0))
        assertFalse(BtcAiScalp.wantsEntry(ask, spread, move, 60.0))
        assertFalse(BtcAiScalp.wantsEntry(ask, spread, 0.0, 200.0))
        assertEquals("YES", BtcDirection.rideSide(0.04))
        assertEquals("NO", BtcDirection.rideSide(-0.04))
        assertNull(BtcDirection.rideSide(Double.NaN))
    }

    @Test fun centCeilFeeMatchesTheDocumentedNonDirectRounding() {
        for (cents in listOf(1, 20, 31, 42, 50, 80, 99)) {
            val price = cents / 100.0
            for (contracts in listOf(1, 10)) {
                assertEquals(
                    KalshiFee.total(contracts, price),
                    ScalpRule.orderFee(contracts, price),
                    1e-9
                )
            }
        }
    }

    @Test fun openBitcoinPositionIgnoresOneTickNoiseAndStopsTwoCentsUnderTheEntryBid() {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val at = close - 600_000L
        val t = ScalpTrade(
            "fc", ticker, "YES", ScalpState.OPEN, at - 2000, .31, .5, contracts = 10,
            entryPrice = .31, entryFeeUsd = .16, entryAtMs = at - 1000,
            note = "riskRefBid=.30 · riskSpread=.01",
            ruleVersion = "v|${ForecastScalp.variantId(true, 600)}|P"
        )
        val book = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(t)) }, variants = emptyList(), liveExtras = true)
        fun quote(bid: Double, ms: Long) = ScalpRule.Quote(ticker, ms, close, ms, bid, 50.0, bid + .01, 50.0, null, null, null)
        book.onForecastQuote(quote(0.29, at), 10.0, 10.0, false)
        assertFalse((book.allTrades().single().exitReason ?: "").contains("stop"))
        book.onForecastQuote(quote(0.28, at + 2_000L), 10.0, 10.0, false)
        val stopped = book.allTrades().single()
        assertTrue("reason=${stopped.exitReason}", (stopped.exitReason ?: "").contains("stop"))
    }

    @Test fun decisionSourcesDoNotCallTheOrderApi() {
        val root = listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/decision"), java.io.File("src/main/java/com/dirk/kalshiodds/decision")).first { it.isDirectory }
        val banned = listOf("createOrderV2", "placeOrder(")
        root.walk().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            banned.forEach { needle ->
                assertFalse("${file.name} contains $needle", text.contains(needle))
            }
        }
    }
}
