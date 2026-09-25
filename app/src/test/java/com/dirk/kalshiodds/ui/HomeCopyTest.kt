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
        assertEquals("Picked side: 12/18 correct (67%) · need 20+ results", HomeCopy.scorecardLine(12, 18, 0.211))
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
    fun pickedSideLineRoundsPercentInsteadOfTruncating() {
        assertEquals("Picked side: 12/18 correct (67%)", HomeCopy.pickedSideLine(12, 18))
        assertEquals("Picked side: 1/3 correct (33%)", HomeCopy.pickedSideLine(1, 3))
        assertEquals("Picked side: 2/3 correct (67%)", HomeCopy.pickedSideLine(2, 3))
        assertEquals("Picked side: 0/5 correct (0%)", HomeCopy.pickedSideLine(0, 5))
        assertEquals("Picked side: 5/5 correct (100%)", HomeCopy.pickedSideLine(5, 5))
    }

    @Test
    fun confirmApproveLabelMatchesTradeMode() {
        assertEquals("LIVE $5.00", HomeCopy.confirmApproveLabel("LIVE $", 5.0, isSell = false))
        assertEquals("LIVE $ sell", HomeCopy.confirmApproveLabel("LIVE $", 5.0, isSell = true))
        assertEquals("PAPER", HomeCopy.confirmApproveLabel("PAPER", 5.0, isSell = false))
        assertEquals("PAPER sell", HomeCopy.confirmApproveLabel("PAPER", 5.0, isSell = true))
        assertEquals("NO KEY", HomeCopy.confirmApproveLabel("NO KEY", 5.0, isSell = false))
    }

    @Test
    fun actionableDownFixtureIsBetDown() {
        val down = HomeFixtures.actionableDownBtc()
        val call = BetCall.decide(down, SignalSettings(), nowMs)
        assertEquals(BetCall.Headline.BET_DOWN, call.headline)
        assertTrue(call.isActionable)
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
        assertEquals("Model 62% vs market 58% · edge +4 pts", HomeCopy.modelVsMarket(market, call))
    }

    @Test
    fun coinShortMapsSeries() {
        assertEquals("BTC", HomeCopy.coinShort(HomeFixtures.market("KXBTC15M-A", "Bitcoin", 0.4, 50.0, "YES", nowMs)))
        assertEquals("ETH", HomeCopy.coinShort(HomeFixtures.market("KXETH15M-A", "Ethereum", 0.4, 50.0, "YES", nowMs)))
        assertEquals("SOL", HomeCopy.coinShort(HomeFixtures.market("KXSOL15M-A", "Solana", 0.4, 50.0, "YES", nowMs)))
    }

    @Test
    fun positionsAndTicketsHelpHasNoDeveloperJargon() {
        val copy = listOf(HomeHelp.TICKETS_BODY, HomeHelp.POSITIONS_BODY)
        for (line in copy) {
            assertFalse(line, line.contains("GET /portfolio"))
            assertFalse(line, line.contains("reduce-only"))
            assertFalse(line, line.contains("retired v1"))
            assertFalse(line, line.contains("Paper fills never block"))
            assertFalse(line, line.contains("Hunter cards still appear"))
            assertFalse(line, line.contains("approve-gated"))
        }
        assertEquals("Tickets wait for your Approve. Nothing is sent until you confirm.", HomeHelp.TICKETS_BODY)
        assertEquals("Open Kalshi positions. Sell closes them at the current bid.", HomeHelp.POSITIONS_BODY)
    }

    @Test
    fun topBarKeeps0312ActionsPlusScorecard() {
        assertEquals(listOf("Scorecard", "Settings", "Refresh"), HomeCopy.TOP_BAR_ACTIONS)
    }

    @Test
    fun homeHasNoSignalListOnlyAHistoryLink() {
        assertFalse(HomeCopy.SHOWS_SIGNAL_LIST)
        assertEquals("Signal history", HomeCopy.signalHistoryLink())
        assertTrue(HomeCopy.signalCardsOnHome(HomeFixtures.sampleAlerts()).isEmpty())
        assertTrue(HomeHelp.HOME_BODY.contains("no Signals list"))
    }

    @Test
    fun scorecardSummaryLineUsesRecordWinRateAndPaperPnl() {
        assertEquals(
            "12-6 · 67% · paper +$12.40",
            HomeCopy.scorecardSummaryLine(HomeFixtures.SAMPLE_SCORECARD)
        )
        assertEquals(
            HomeScorecardSummary.NO_SETTLED,
            HomeCopy.scorecardSummaryLine(HomeScorecardSummary.EMPTY)
        )
        assertEquals("No settled picks yet", HomeCopy.NO_SETTLED_PICKS)
        assertEquals(
            "0-3 · 0% · paper −$3.10",
            HomeCopy.scorecardSummaryLine(
                HomeScorecardSummary(wins = 0, losses = 3, hitRate = 0.0, paperPnlUsd = -3.10, settledCount = 3)
            )
        )
        assertEquals(
            "2-0 · 100% · paper $0.00",
            HomeCopy.scorecardSummaryLine(
                HomeScorecardSummary(wins = 2, losses = 0, hitRate = 1.0, paperPnlUsd = 0.0, settledCount = 2)
            )
        )
    }
}
