package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.ui.GrokChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcScalpChatTest {

    @Test
    fun bitcoinForecastSignIsTheUpDownCall() {
        val up = DoubleArray(11) { 0.0 }
        up[0] = 1.0
        up[6] = 0.4
        up[7] = 0.2
        val forecast = LeadForecaster.predict("BTC", 600, up)!!
        assertTrue(forecast > 0.0)
        assertEquals(BtcDirection.Call.UP, BtcDirection.call(forecast))
        assertEquals("YES", BtcDirection.rideSide(forecast))
        val down = LeadForecaster.predict("BTC", 600, LeadForecaster.mirror(up))!!
        assertEquals(-forecast, down, 1e-12)
        assertEquals(BtcDirection.Call.DOWN, BtcDirection.call(down))
        assertEquals("NO", BtcDirection.rideSide(down))
        assertEquals(BtcDirection.Call.FLAT, BtcDirection.call(0.0))
        assertNull(BtcDirection.rideSide(Double.NaN))
        assertEquals(600, BtcDirection.config().horizonS)
        assertEquals(0.02, BtcDirection.config().marginUsd, 1e-12)
    }

    @Test
    fun entryRequiresTheMoveToClearSpreadAndBothFees() {
        assertFalse(ForecastScalp.wantsEntry(0.50, 0.02, 0.50, 0.001, 120.0, 0.02))
        assertTrue(ForecastScalp.wantsEntry(0.50, 0.02, 0.50, 0.20, 120.0, 0.02))
        assertFalse(ForecastScalp.wantsEntry(0.50, 0.02, 0.50, 0.20, 30.0, 0.02))
        val roundTrip = ScalpRule.roundTripCost(0.50, 0.02)
        assertTrue(roundTrip > 0.02)
    }

    @Test
    fun tunerTurnsOffLosingBitcoinScalpsAndNeverRaisesSize() {
        val current = ScalpControls(maxAskCents = 55, contracts = 10, stopLossPercent = 8, trailCents = 3)
        val trades = (1..8).map { BtcScalpTuner.Trade("KXBTC15M-T$it", "v2-sf51", -1.0) } +
            BtcScalpTuner.Trade("KXETH15M-T", "v2-sf52", -5.0)
        val patch = BtcScalpTuner.propose(trades, current)
        assertEquals(setOf("v2-sf51"), patch.disable)
        assertEquals(50, patch.maxAskCents)
        assertEquals(8, patch.contracts)
        assertTrue(patch.stopLossPercent!! < current.stopLossPercent)
        val next = BtcScalpTuner.apply(current, patch)
        assertTrue("v2-sf51" in next.disabledStrategies)
        assertTrue(next.contracts <= current.contracts)
        assertTrue(next.maxAskCents <= current.maxAskCents)
        val looser = BtcScalpTuner.fromChat(current, maxAskCents = 60, contracts = 20, stopLossPercent = 1, trailCents = 1, disable = setOf("not-v2"))
        assertNull(looser.maxAskCents)
        assertNull(looser.contracts)
        assertTrue(looser.disable.isEmpty())
        assertEquals(1, looser.stopLossPercent)
        assertEquals(1, looser.trailCents)
    }

    @Test
    fun bitcoinBuysTheForecastSideUnderFiftyAndStopsTwoCentsDown() {
        assertEquals("YES", BtcDirection.rideSide(0.04))
        assertEquals("NO", BtcDirection.rideSide(-0.04))
        assertTrue(BtcAiScalp.wantsEntry(0.42, 0.04, 200.0))
        assertFalse(BtcAiScalp.wantsEntry(0.50, 0.04, 200.0))
        assertFalse(BtcAiScalp.wantsEntry(0.42, 0.04, 60.0))
        assertFalse(BtcAiScalp.wantsEntry(0.42, 0.0, 200.0))
        assertEquals("stop −2¢", BtcAiScalp.exitReason(0.05, 0.40, 0.42, 200.0))
        assertEquals("forecast turning down", BtcAiScalp.exitReason(-0.01, 0.45, 0.42, 200.0))
        assertEquals("time left under 60s", BtcAiScalp.exitReason(0.05, 0.45, 0.42, 45.0))
        assertNull(BtcAiScalp.exitReason(0.05, 0.45, 0.42, 200.0))
        assertNull(BtcAiScalp.exitReason(Double.NaN, 0.45, 0.42, 200.0))
    }

    @Test
    fun settledNegativeBitcoinScalpsDoNotOpenAndRideStays() {
        val losers = G14Copies.SOURCES
        assertEquals(15, losers.size)
        losers.forEach { (code, row) ->
            assertTrue("$code trades", row.trades >= BtcScalpTuner.MIN_TRADES)
            assertTrue("$code net ${row.netUsd}", row.netUsd < 0.0)
            if (code == BtcRedo.KEPT_RIDE) assertFalse(BtcRedo.blocksNewBitcoinEntry(code))
            else assertTrue(code, BtcRedo.blocksNewBitcoinEntry(code))
        }
        assertEquals(-85.13, losers.getValue("fcride").netUsd, 1e-9)
        assertEquals(-57.6, losers.getValue("v2-fcride").netUsd, 1e-9)
        assertEquals(-53.59, losers.getValue("v2-fcfade").netUsd, 1e-9)
        assertEquals(-12.74, losers.getValue("sf03").netUsd, 1e-9)
        assertEquals(-10.18, losers.getValue("sf29").netUsd, 1e-9)
        assertEquals(-7.52, losers.getValue("sf13").netUsd, 1e-9)
        assertTrue(BtcRedo.blocksNewBitcoinEntry("fcride-g14"))
        assertTrue(BtcRedo.blocksNewBitcoinEntry(G14Copies.variantId("fcride")))
        assertTrue(BtcRedo.blocksNewBitcoinEntry("v2-fcfade"))
        assertTrue(BtcRedo.blocksShock(ShockFav.v("sf28")))
        assertTrue(BtcRedo.blocksShock(ShockFav.v("sf03")))
        assertTrue(BtcRedo.blocksShock(ShockFav.v("sf29")))
        assertFalse(BtcRedo.blocksShock(ShockFav.v("sf19")))
        assertFalse(BtcRedo.blocksNewBitcoinEntry("v2-sf01"))
        assertFalse(BtcRedo.blocksNewBitcoinEntry("fairh60"))
    }

    @Test
    fun bitcoinRideDoesNotSpawnTheLosingV2OrSettlementCopy() {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val book = ScalpBook(variants = emptyList(), liveExtras = true)
        val at = close - 400_000L
        book.onQuote(ScalpRule.Quote(ticker, at, close, at, 0.40, 20.0, 0.42, 20.0, null, null, null), false)
        val ride = ScalpTrade(
            "ride", ticker, "YES", ScalpState.PENDING_ENTRY, at, 0.42, 0.5,
            ruleVersion = "${ScalpRule.VERSION}|${ForecastScalp.variantId(true, 600)}|P"
        )
        assertEquals(1, book.importRestored(listOf(ride)))
        assertTrue(book.allTrades().any { it.id == "ride" })
        assertTrue(book.allTrades().none { it.variantId.startsWith("v2-fcride") || G14Copies.isCopyVariant(it.variantId) })
    }

    @Test
    fun openLateFavouriteExitsOnTheTwoCentStopInsteadOfSettlement() {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val at = close - 400_000L
        val open = ScalpTrade(
            "fav", ticker, "YES", ScalpState.OPEN, at - 5_000L, 0.40, 0.5,
            contracts = 10, entryPrice = 0.40, entryFeeUsd = 0.2, entryAtMs = at - 4_000L,
            ruleVersion = "${ScalpRule.VERSION}|sf29f|P"
        )
        val book = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(open)) }, variants = emptyList(), liveExtras = true)
        book.onShockFavQuote(ScalpRule.Quote(ticker, at, close, at, 0.38, 20.0, 0.39, 20.0, null, null, null), false)
        val row = book.allTrades().single { it.id == "fav" }
        assertEquals("stop −2¢", row.exitReason)
        assertEquals(ScalpState.CLOSED, row.state)
        val held = open.copy(id = "held")
        val quiet = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(held)) }, variants = emptyList(), liveExtras = true)
        quiet.onShockFavQuote(ScalpRule.Quote(ticker, at, close, at, 0.39, 20.0, 0.40, 20.0, null, null, null), false)
        val still = quiet.allTrades().single { it.id == "held" }
        assertEquals(ScalpState.OPEN, still.state)
        val lateAt = close - 45_000L
        quiet.onShockFavQuote(ScalpRule.Quote(ticker, lateAt, close, lateAt, 0.39, 20.0, 0.40, 20.0, null, null, null), false)
        val timed = quiet.allTrades().single { it.id == "held" }
        assertEquals("time left under 60s", timed.exitReason)
        assertEquals(ScalpState.CLOSED, timed.state)
    }

    @Test
    fun grokFastIsTheDefaultPublicModel() {
        assertEquals("grok-4.7", GrokChat.MODEL)
        assertEquals("Grok 4.7 Fast", GrokChat.DISPLAY_NAME)
        assertEquals("low", GrokChat.REASONING_EFFORT)
        val body = GrokChat.requestBody(listOf(GrokChat.Turn("user", "Tune bitcoin")))
        assertTrue(body.contains("\"model\":\"grok-4.7\""))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        val patch = GrokChat.parsePatch("Tighten it.\n{\"maxAskCents\":40,\"disable\":[\"v2-sf51\"]}")
        assertEquals(40, patch!!.getInt("maxAskCents"))
        assertNull(GrokChat.parsePatch("No change."))
    }
}
