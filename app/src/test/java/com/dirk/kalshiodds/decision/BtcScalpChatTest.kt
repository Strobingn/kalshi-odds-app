package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.ui.GrokChat
import org.json.JSONObject
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
    fun settledLosersUseTheUnderFiftyRedoAndRideStaysOnItsOwnPath() {
        val losers = G14Copies.SOURCES
        assertEquals(15, losers.size)
        losers.forEach { (code, row) ->
            assertTrue("$code trades", row.trades >= BtcScalpTuner.MIN_TRADES)
            assertTrue("$code net ${row.netUsd}", row.netUsd < 0.0)
            if (code == BtcRedo.KEPT_RIDE) assertFalse(BtcRedo.applies(code))
            else assertTrue(code, BtcRedo.applies(code))
        }
        assertEquals(-85.13, losers.getValue("fcride").netUsd, 1e-9)
        assertEquals(-57.6, losers.getValue("v2-fcride").netUsd, 1e-9)
        assertEquals(-53.59, losers.getValue("v2-fcfade").netUsd, 1e-9)
        assertEquals(-1602.48, losers.getValue("sf32").netUsd, 1e-9)
        assertEquals(-1579.7, losers.getValue("sf30").netUsd, 1e-9)
        assertTrue(BtcRedo.entryOk(0.42, 200.0))
        assertFalse(BtcRedo.entryOk(0.50, 200.0))
        assertFalse(BtcRedo.entryOk(0.42, 60.0))
        assertEquals("YES", BtcRedo.rideMoveSide(4.0))
        assertEquals("NO", BtcRedo.rideMoveSide(-4.0))
        assertNull(BtcRedo.rideMoveSide(0.0))
        assertTrue(BtcRedo.applies("fcride-g14"))
        assertTrue(BtcRedo.applies(G14Copies.variantId("fcride")))
        assertTrue(BtcRedo.applies("v2-fcfade"))
        assertTrue(BtcRedo.appliesShock(ShockFav.v("sf28")))
        assertTrue(BtcRedo.appliesShock(ShockFav.v("sf03")))
        assertTrue(BtcRedo.appliesShock(ShockFav.v("sf32")))
        assertTrue(BtcRedo.appliesShock(ShockFav.v("sf41")))
        assertTrue(BtcRedo.applies("v2-sf30"))
        assertTrue(BtcRedo.applies("v2-sf32"))
        assertTrue(BtcRedo.applies("v2-sf41"))
        assertTrue(BtcRedo.applies("v2-sf42"))
        assertTrue(BtcRedo.applies("v2-sf41t2"))
        assertTrue(BtcRedo.applies("v2-sf42x10"))
        assertTrue(BtcRedo.applies("v2-sf28"))
        assertTrue(BtcRedo.applies("v2-sf51"))
        assertTrue(BtcRedo.applies("v2-sf27"))
        assertTrue(BtcRedo.applies("v2-sf02"))
        assertTrue(BtcRedo.applies("v2-fcride"))
        assertFalse(BtcRedo.appliesShock(ShockFav.v("sf19")))
        assertFalse(BtcRedo.applies("sf19"))
        assertFalse(BtcRedo.applies("sf21"))
        assertFalse(BtcRedo.applies("sf23"))
        assertFalse(BtcRedo.applies("v2-sf19"))
        assertFalse(BtcRedo.applies("v2-sf01"))
        assertFalse(BtcRedo.applies("fairh60"))
        assertTrue(BtcRedo.agrees("YES", 0.04, 0.42, 200.0))
        assertTrue(BtcRedo.agrees("NO", -0.04, 0.42, 200.0))
        assertFalse(BtcRedo.agrees("NO", 0.04, 0.42, 200.0))
        assertFalse(BtcRedo.agrees("YES", Double.NaN, 0.42, 200.0))
        assertFalse(BtcRedo.agrees("YES", 0.04, 0.50, 200.0))
        assertFalse(BtcRedo.agrees("YES", 0.04, 0.42, 60.0))
        assertEquals("forecast turning down", BtcRedo.exitFor("YES", -0.02, 0.41, 0.42, 200.0))
        assertEquals("stop −2¢", BtcRedo.exitFor("NO", -0.04, 0.40, 0.42, 200.0))
    }

    @Test
    fun redoneBitcoinCopyOpensUnderFiftyAndStopsTwoCentsDown() {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val book = ScalpBook(variants = emptyList(), liveExtras = true)
        val at = close - 400_000L
        book.onQuote(ScalpRule.Quote(ticker, at, close, at, 0.40, 20.0, 0.42, 20.0, null, null, null), false)
        book.noteBtcForecast(ticker, 0.04)
        val ride = ScalpTrade(
            "ride", ticker, "YES", ScalpState.PENDING_ENTRY, at, 0.42, 0.5,
            ruleVersion = "${ScalpRule.VERSION}|${ForecastScalp.variantId(true, 600)}|P"
        )
        assertEquals(1, book.importRestored(listOf(ride)))
        val spawned = book.allTrades().filter { it.id != "ride" && (it.variantId.startsWith("v2-fcride") || G14Copies.isCopyVariant(it.variantId)) }
        assertTrue("redone copies ${spawned.map { it.variantId }}", spawned.isNotEmpty())
        assertTrue(spawned.all { it.state == ScalpState.PENDING_ENTRY })
        val expensiveAt = close - 400_000L
        val blocked = ScalpBook(variants = emptyList(), liveExtras = true)
        blocked.onQuote(ScalpRule.Quote(ticker, expensiveAt, close, expensiveAt, 0.58, 20.0, 0.60, 20.0, null, null, null), false)
        blocked.noteBtcForecast(ticker, 0.04)
        val expensive = ride.copy(id = "expensive", signalAsk = 0.60)
        blocked.importRestored(listOf(expensive))
        assertTrue(blocked.allTrades().none { it.id != "expensive" && (it.variantId.startsWith("v2-fcride") || G14Copies.isCopyVariant(it.variantId)) })
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
        val body = GrokChat.requestBody(listOf(GrokChat.Turn("user", "Why is the forecast flat?")))
        assertTrue(body.contains("\"model\":\"grok-4.7\""))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
        assertTrue(body.contains("Answer the question in plain sentences"))
        val patch = GrokChat.parsePatch("Tighten it.\n{\"maxAskCents\":40,\"disable\":[\"v2-sf51\"]}")
        assertEquals(40, patch!!.getInt("maxAskCents"))
        assertNull(GrokChat.parsePatch("No change."))
        assertNull(GrokChat.parsePatch("The forecast is flat because the move is under a tenth of a cent."))
        val answer = "The forecast is flat because the move is under a tenth of a cent."
        val reply = GrokChat.replyFrom(
            """{"choices":[{"message":{"role":"assistant","content":"$answer"}}]}"""
        )
        assertEquals(answer, reply.text)
        assertNull(reply.patch)
        val parts = GrokChat.replyFrom(
            """{"choices":[{"message":{"content":[{"type":"reasoning","text":"hidden"},{"type":"output_text","text":"$answer"}]}}]}"""
        )
        assertEquals(answer, parts.text)
        assertNull(parts.patch)
        val tuned = GrokChat.replyFrom(
            """{"choices":[{"message":{"content":${JSONObject.quote("Lowering the ask cap.\n{\"maxAskCents\":40}")}}}]}"""
        )
        assertTrue(tuned.text.contains("Lowering the ask cap."))
        assertEquals(40, tuned.patch!!.getInt("maxAskCents"))
    }

    @Test
    fun bigLosersStayQuietUntilTheForecastAgreesThenStopTwoCentsDown() {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val at = close - 400_000L
        fun book() = ScalpBook(variants = emptyList(), liveExtras = true, bankrollUsd = { Double.POSITIVE_INFINITY })
        fun warm(b: ScalpBook) {
            for (k in 200 downTo 1) b.tickSpot("BTC", 100_000.0 * (1 + (if (k % 2 == 0) 5e-5 else -5e-5)), at - k * 1000L)
        }
        fun imb(ms: Long, bid: Double, ask: Double) = ScalpRule.Quote(
            ticker, ms, close, ms, bid, 90.0, ask, 10.0, 100_000.0, 100_000.0, 3e-5
        )
        val missing = book()
        warm(missing)
        missing.onShockFavQuote(imb(at, 0.40, 0.42), true)
        assertTrue(missing.allTrades().none { it.variantId == "sf30" || it.variantId == "sf32" })

        val against = book()
        warm(against)
        against.noteBtcForecast(ticker, -0.04)
        against.onShockFavQuote(imb(at, 0.40, 0.42), true)
        assertTrue(against.allTrades().none { it.variantId == "sf30" && it.state != ScalpState.NO_FILL })

        val agreed = book()
        warm(agreed)
        agreed.noteBtcForecast(ticker, 0.04)
        agreed.onShockFavQuote(imb(at, 0.40, 0.42), true)
        val pending = agreed.allTrades().filter { it.variantId == "sf30" && it.state == ScalpState.PENDING_ENTRY }
        assertEquals(1, pending.size)
        assertEquals("YES", pending.single().side)
        agreed.onShockFavQuote(imb(at + 3_000L, 0.40, 0.42), true)
        val open = agreed.allTrades().single { it.variantId == "sf30" && it.id == pending.single().id }
        assertEquals(ScalpState.OPEN, open.state)
        assertEquals(0.42, open.entryPrice!!, 1e-9)
        agreed.onShockFavQuote(imb(at + 4_000L, 0.40, 0.42), true)
        val closed = agreed.allTrades().single { it.id == open.id }
        assertEquals(ScalpState.CLOSED, closed.state)
        assertEquals("stop −2¢", closed.exitReason)

        fun dip(b: ScalpBook, forecast: Double?) {
            if (forecast != null) b.noteBtcForecast(ticker, forecast)
            val start = at - 250_000L
            for (sec in 0..24) {
                val ms = start + sec * 10_000L
                b.onShockFavQuote(ScalpRule.Quote(ticker, ms, close, ms, 0.48, 20.0, 0.49, 20.0, null, null, null), true)
            }
            b.onShockFavQuote(ScalpRule.Quote(ticker, at, close, at, 0.39, 20.0, 0.40, 20.0, null, null, null), true)
        }
        val dipMissing = book()
        dip(dipMissing, null)
        assertTrue(dipMissing.allTrades().none { it.variantId == "sf41" && it.state != ScalpState.NO_FILL })
        val dipAgainst = book()
        dip(dipAgainst, -0.04)
        assertTrue(dipAgainst.allTrades().none { it.variantId == "sf41" && it.state != ScalpState.NO_FILL })
        val dipYes = book()
        dip(dipYes, 0.04)
        val dipRow = dipYes.allTrades().single { it.variantId == "sf41" }
        assertEquals("YES", dipRow.side)
        assertEquals(ScalpState.PENDING_ENTRY, dipRow.state)
    }
}
