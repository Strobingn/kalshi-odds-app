package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCopyTest {
    @Test
    fun displayedEdgeIsModelMinusMarketForTheShownSide() {
        assertEquals(4.0, SignalCopy.displayedEdgePts(68.0, 64.0, "YES")!!, 1e-9)
        assertEquals(14.0, SignalCopy.displayedEdgePts(58.0, 44.0, "UP")!!, 1e-9)
        assertEquals(-14.0, SignalCopy.displayedEdgePts(58.0, 44.0, "NO")!!, 1e-9)
        assertEquals(
            "Model 68% vs market 64% · edge +4 pts",
            SignalCopy.modelVsMarketLine(68.0, 64.0, "YES")
        )
        assertEquals(
            "Model 42% vs market 56% · edge -14 pts",
            SignalCopy.modelVsMarketLine(58.0, 44.0, "NO")
        )
    }

    @Test
    fun storedDeltaIsNotUsedOnTheCardLine() {
        val card = SignalCopy.card(
            ticker = "KXBTC15M-26SEP251345-45",
            side = "YES",
            modelYes = 58.0,
            marketYes = 44.0,
            settled = null,
            details = "TREND/EARLY · AI 58% vs mkt 44% · Δ -11.4pp"
        )
        assertEquals("BTC · 1:45 PM window", card.title)
        assertEquals("UP", card.call)
        assertEquals(BetCall.Headline.BET_UP, SignalCopy.headline(card.call))
        assertEquals("Model 58% vs market 44% · edge +14 pts", card.modelLine)
        assertEquals("Pending", card.outcome)
        assertEquals("TREND/EARLY · AI 58% vs mkt 44% · Δ -11.4pp", card.details)
        assertEquals("Won", SignalCopy.outcomeLabel("yes"))
        assertEquals("Lost", SignalCopy.outcomeLabel("no"))
        assertEquals("DOWN", SignalCopy.callLabel("NO"))
        assertEquals("NO BET", SignalCopy.callLabel(null))
    }

    @Test
    fun a1FixtureIsNoBetBecauseModelEdgeContradictsPickedSide() {
        val a1 = HomeFixtures.sampleAlerts()[0]
        assertEquals("NO", a1.predictedSide)
        assertEquals(32.6, a1.fairValuePp, 1e-9)
        assertEquals(44.0, a1.marketMidPp, 1e-9)
        assertTrue(a1.reason.contains("AI 58% vs mkt 44%"))
        assertEquals(58.0 to 44.0, SignalCopy.parseAiVsMarket(a1.reason))
        assertEquals(-14.0, SignalCopy.displayedEdgePts(58.0, 44.0, "NO")!!, 1e-9)

        val card = SignalCopy.card(a1)
        assertEquals("NO BET", card.call)
        assertEquals(BetCall.Headline.NO_BET, SignalCopy.headline(card.call))
        assertEquals("Model 58% vs market 44% · edge +14 pts", card.modelLine)
        assertTrue(card.details!!.contains(SignalStance.DISAGREE_NOTE))
        assertTrue(card.details!!.contains("Stored Δ -11.4 pp"))
        assertFalse(SignalCopy.shouldNotify(a1))
        assertNull(SignalCopy.parseAiVsMarket("mkt 44% · Δ -11.4pp"))
    }

    @Test
    fun consistentDownHasPositiveEdge() {
        val alert = SignalAlert(
            id = "down",
            ticker = "KXETH15M-26SEP251400-40",
            series = "KXETH15M",
            deltaPp = -18.0,
            fairValuePp = 30.0,
            marketMidPp = 48.0,
            reason = "QUIET/MID · AI 30% vs mkt 48%",
            createdAtMs = 0L,
            receiveElapsedNanos = 0L,
            predictedSide = "NO"
        )
        val card = SignalCopy.card(alert)
        assertEquals("DOWN", card.call)
        assertEquals(BetCall.Headline.BET_DOWN, SignalCopy.headline(card.call))
        assertEquals("Model 70% vs market 52% · edge +18 pts", card.modelLine)
        assertEquals(18.0, SignalCopy.displayedEdgePts(30.0, 48.0, "NO")!!, 1e-9)
        assertTrue(SignalCopy.shouldNotify(alert))
        assertFalse(card.details!!.contains(SignalStance.DISAGREE_NOTE))
    }

    @Test
    fun consistentUpHasPositiveEdge() {
        val a2 = HomeFixtures.sampleAlerts()[1]
        val card = SignalCopy.card(a2)
        assertEquals("UP", card.call)
        assertEquals(BetCall.Headline.BET_UP, SignalCopy.headline(card.call))
        assertEquals("Model 68% vs market 64% · edge +4 pts", card.modelLine)
        assertTrue(SignalCopy.shouldNotify(a2))
        assertFalse(card.details!!.contains(SignalStance.DISAGREE_NOTE))
    }

    @Test
    fun noBetDoesNotFirePushAlert() {
        val a1 = HomeFixtures.sampleAlerts()[0]
        assertEquals("NO BET", SignalCopy.card(a1).call)
        assertFalse(SignalCopy.shouldNotify(a1))
        assertFalse(SignalCopy.shouldNotify(a1.copy(predictedSide = SignalStance.NO_BET)))
    }
}
