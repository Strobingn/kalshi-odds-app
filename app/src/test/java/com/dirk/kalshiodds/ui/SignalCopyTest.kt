package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
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
}
