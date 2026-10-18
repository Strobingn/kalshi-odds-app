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
