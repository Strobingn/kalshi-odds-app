package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCopyTest {

    private val nowMs = 1_700_000_000_000L

    @Test
    fun scorecardHidesBrierUntilTwentyResults() {
        assertEquals("Picked side: — · need 20+ results", HomeCopy.scorecardLine(null, null, 0.2))
        assertEquals("Picked side: 0/5 correct (0%) · need 20+ results", HomeCopy.scorecardLine(0, 5, 0.893))
        assertEquals("Picked side: 12/18 correct (66%) · need 20+ results", HomeCopy.scorecardLine(12, 18, 0.211))
        assertFalse(HomeCopy.scorecardLine(0, 5, 0.893).contains("Brier"))
        assertEquals("need 20+ results", HomeCopy.pickedSideBrierLine(5, 0.893))
        assertEquals("need 20+ results", HomeCopy.pUpBrierLine(5, 0.003))
        assertEquals(
            "Picked side: 14/20 correct (70%) · Picked-side Brier 0.180",
            HomeCopy.scorecardLine(14, 20, 0.180)
        )
        assertEquals("Picked-side Brier 0.180", HomeCopy.pickedSideBrierLine(20, 0.180))
        assertEquals("P(UP) Brier 0.003", HomeCopy.pUpBrierLine(20, 0.003))
    }

    @Test
    fun thisWindowShowsBetUpThenFallsBackToNoBetReason() {
        val up = HomeFixtures.market(
            ticker = "KXBTC15M-WIN-50",
            seriesLabel = "Bitcoin",
            yesAsk = 0.20,
            aiYes = 80.0,
            predicted = "YES",
            closeMs = nowMs + 372_000L
        )
        val upCall = BetCall.decide(up, SignalSettings(), nowMs)
        assertTrue(upCall.isActionable)
        val line = HomeCopy.thisWindowHeadline(upCall, up, nowMs)
        assertTrue(line.startsWith("BET UP  BTC"))
        assertTrue(line.contains("\$5 wins"))
        assertTrue(line.contains("closes in 6:12"))

        val dead = HomeFixtures.market(
            ticker = "KXETH15M-WIN-40",
            seriesLabel = "Ethereum",
            yesAsk = 0.63,
            aiYes = 70.0,
            predicted = "YES",
            closeMs = nowMs + 372_000L
        )
        val no = BetCall.decide(dead, SignalSettings(), nowMs)
        assertFalse(no.isActionable)
        val noLine = HomeCopy.thisWindowHeadline(no, dead, nowMs)
        assertTrue(noLine.startsWith("NO BET this window"))
        assertTrue(noLine.contains(no.noBetReason!!.take(12)))
    }

    @Test
    fun modelVsMarketUsesExistingPercents() {
        val market = HomeFixtures.market(
            ticker = "KXBTC15M-WIN-50",
            seriesLabel = "Bitcoin",
            yesAsk = 0.20,
            aiYes = 62.0,
            predicted = "YES",
            closeMs = nowMs + 372_000L
        ).copy(yesProbabilityPercent = 58.0, importedModelPp = 62.0, edgePp = 4.0)
        val call = BetCall.Decision(
            headline = BetCall.Headline.BET_UP,
            side = "YES",
            ticket = null,
            ask = 0.20,
            profitIfWinUsd = 6.0,
            allInUsd = 5.0,
            contracts = 24,
            noBetReason = null
        )
        assertEquals("Model 62% UP vs market 58% · edge +4 pts", HomeCopy.modelVsMarket(market, call))
    }

    @Test
    fun coinShortMapsSeries() {
        assertEquals("BTC", HomeCopy.coinShort(HomeFixtures.market("KXBTC15M-A", "Bitcoin", 0.4, 50.0, "YES", nowMs)))
        assertEquals("ETH", HomeCopy.coinShort(HomeFixtures.market("KXETH15M-A", "Ethereum", 0.4, 50.0, "YES", nowMs)))
        assertEquals("SOL", HomeCopy.coinShort(HomeFixtures.market("KXSOL15M-A", "Solana", 0.4, 50.0, "YES", nowMs)))
    }
}
