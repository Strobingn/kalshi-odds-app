package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun liveAlertUsesAiVsMarketNotStoredDelta() {
        val alert = SignalAlert(
            id = "a",
            ticker = "KXBTC15M-26SEP251400-00",
            series = "KXBTC15M",
            deltaPp = -11.4,
            fairValuePp = 32.6,
            marketMidPp = 44.0,
            reason = "QUIET/EARLY · AI 58% vs mkt 44% · Δ -11.4pp (fv 33%)",
            createdAtMs = 0L,
            receiveElapsedNanos = 0L,
            predictedSide = "NO"
        )
        assertEquals(58.0 to 44.0, SignalCopy.parseAiVsMarket(alert.reason))
        val card = SignalCopy.card(alert)
        assertEquals("BTC · 2:00 PM window", card.title)
        assertEquals("DOWN", card.call)
        assertEquals("Model 42% vs market 56% · edge -14 pts", card.modelLine)
        assertEquals("Pending", card.outcome)
        assertEquals(-14.0, SignalCopy.displayedEdgePts(58.0, 44.0, "NO")!!, 1e-9)
        assertEquals(-11.4, alert.deltaPp, 1e-9)
        assert(card.details!!.contains("Stored Δ -11.4 pp"))
        assert(card.details!!.contains("QUIET/EARLY"))
        assertNull(SignalCopy.parseAiVsMarket("mkt 44% · Δ -11.4pp"))
    }
}
