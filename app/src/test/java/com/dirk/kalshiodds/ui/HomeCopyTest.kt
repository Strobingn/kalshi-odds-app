package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val down = HomeFixtures.withLastMinuteFire(HomeFixtures.actionableDownBtc(), "NO")
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
        val fired = HomeFixtures.withLastMinuteFire(up, "YES")
        val upCall = BetCall.decide(fired, SignalSettings(), nowMs)
        assertTrue(upCall.isActionable)
        val line = HomeCopy.thisWindowHeadline(upCall, fired, nowMs)
        assertTrue(line.startsWith("BET UP  BTC"))
        assertTrue(line.contains("BUY UP") || line.contains("\$10 wins"))
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
    fun deadWindowHeadlineIsFlipChanceNoBet() {
        val dead = HomeFixtures.deadWindowLotteryBtc()
        val call = BetCall.decide(dead, SignalSettings(), nowMs)
        assertEquals(BetCall.Headline.NO_BET, call.headline)
        assertFalse(call.isActionable)
        val line = HomeCopy.thisWindowHeadline(call, dead, nowMs)
        assertTrue(line.startsWith("NO BET this window"))
        assertTrue(line.contains("Flip chance"))
        assertNull(HomeCopy.allInProfit(call))
        assertEquals("PAPER  NO BET", HomeCopy.primaryButtonLabel("PAPER", call))
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
        val tiles = HomeCopy.tileAiPercents(market)
        assertEquals("AI 62%", tiles.up)
        assertEquals("AI 38%", tiles.down)
        assertEquals(100, tiles.upPct!! + tiles.downPct!!)
    }

    @Test
    fun tenDollarWinsUsesLiveAskAndKnownCents() {
        val at31 = HomeCopy.tenDollarWins(0.31)
        assertEquals(30, at31.contracts)
        assertEquals(0.45, at31.feeUsd, 1e-9)
        assertEquals(9.75, at31.costUsd, 1e-9)
        assertEquals(20.25, at31.profitUsd!!, 1e-9)
        assertEquals("\$10 wins +\$20.25", at31.line)
        assertEquals(0.31, at31.ask!!, 1e-9)

        val at70 = HomeCopy.tenDollarWins(0.70)
        assertEquals(13, at70.contracts)
        assertEquals(0.20, at70.feeUsd, 1e-9)
        assertEquals(9.30, at70.costUsd, 1e-9)
        assertEquals(3.70, at70.profitUsd!!, 1e-9)
        assertEquals("\$10 wins +\$3.70", at70.line)

        assertEquals(HomeCopy.TEN_WINS_DASH, HomeCopy.tenDollarWins(null).line)
        assertEquals(HomeCopy.TEN_WINS_DASH, HomeCopy.tenDollarWins(0.0).line)
        assertEquals(10.0, HomeCopy.TILE_STAKE_USD, 1e-9)
        assertEquals(10.0, com.dirk.kalshiodds.signal.config.SignalConstants.LIVE_ALL_IN_CAP_USD, 1e-9)
        assertEquals(10.0, com.dirk.kalshiodds.signal.trade.LiveOrderSizer.LIVE_ALL_IN_CAP_USD, 1e-9)

        val market = HomeFixtures.actionableBtc()
        val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
        assertEquals(HomeCopy.tenDollarWins(quotes.yesAsk).line, HomeCopy.tileTenDollarUp(market))
        assertEquals(HomeCopy.tenDollarWins(quotes.noAsk).line, HomeCopy.tileTenDollarDown(market))
        val moved = market.copy(yesAsk = 0.31, yesBid = 0.30, noAsk = 0.70, noBid = 0.69)
        assertEquals("\$10 wins +\$20.25", HomeCopy.tileTenDollarUp(moved))
        assertEquals("\$10 wins +\$3.70", HomeCopy.tileTenDollarDown(moved))
        val missing = market.copy(yesAsk = null, noAsk = null, yesBid = null, noBid = null)
        assertEquals(HomeCopy.TEN_WINS_DASH, HomeCopy.tileTenDollarUp(missing))
        assertEquals(HomeCopy.TEN_WINS_DASH, HomeCopy.tileTenDollarDown(missing))
        assertEquals(HomeCopy.PAPER_UP, "Paper UP")
        assertEquals(HomeCopy.PAPER_DOWN, "Paper DOWN")
        assertTrue(HomeCopy.paperUpEnabled(moved))
        assertTrue(HomeCopy.paperDownEnabled(moved))
        assertEquals(HomeCopy.paperDisabledReason(missing, "YES"), "No ask to paper UP")
        assertEquals(
            "Paper UP · 30 ct · $9.75 · +$20.25 if it wins",
            HomeCopy.paperConfirmSnackbar("YES", at31)
        )
    }

    @Test
    fun tileAiPercentsMatchModelAndSumTo100() {
        val up = HomeFixtures.actionableBtc()
        assertEquals("AI 80%", HomeCopy.tileAiUp(up))
        assertEquals("AI 20%", HomeCopy.tileAiDown(up))
        val down = HomeFixtures.actionableDownBtc()
        assertEquals("AI 10%", HomeCopy.tileAiUp(down))
        assertEquals("AI 90%", HomeCopy.tileAiDown(down))
        val none = up.copy(importedModelPp = null, aiYesPercent = null)
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiUp(none))
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiDown(none))
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
    fun sitOutHomeCopyIsPlainLanguage() {
        val jargon = "Sit out — model loses to market on Brier/log-loss and EV is -1.20"
        assertTrue(HomeCopy.isSitOutJargon(jargon))
        assertEquals(HomeCopy.SIT_OUT_HOME, HomeCopy.noBetHeadline(jargon))
        assertEquals(
            "NO BET this window. The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative.",
            HomeCopy.SIT_OUT_HOME
        )
        assertFalse(HomeCopy.SIT_OUT_HOME.contains("Brier", ignoreCase = true))
        assertFalse(HomeCopy.SIT_OUT_HOME.contains("log-loss", ignoreCase = true))
        assertFalse(HomeCopy.SIT_OUT_HOME.contains("log loss", ignoreCase = true))
        val tuner = "The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative."
        assertTrue(HomeCopy.isSitOutJargon(tuner))
        assertEquals(HomeCopy.SIT_OUT_HOME, HomeCopy.noBetHeadline(tuner))
        val dead = HomeFixtures.actionableBtc()
        val sit = com.dirk.kalshiodds.signal.trade.BetCall.Decision(
            headline = com.dirk.kalshiodds.signal.trade.BetCall.Headline.NO_BET,
            side = null,
            ticket = null,
            ask = null,
            profitIfWinUsd = null,
            allInUsd = null,
            contracts = 0,
            noBetReason = jargon
        )
        val line = HomeCopy.thisWindowHeadline(sit, dead, nowMs)
        assertEquals(HomeCopy.SIT_OUT_HOME, line)
        assertFalse(line.contains("Brier"))
        assertFalse(line.contains("log-loss"))
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
            "12-6 · 67% · lifetime paper +$12.40",
            HomeCopy.scorecardSummaryLine(HomeFixtures.SAMPLE_SCORECARD)
        )
        assertEquals(
            HomeScorecardSummary.NO_SETTLED,
            HomeCopy.scorecardSummaryLine(HomeScorecardSummary.EMPTY)
        )
        assertEquals("No settled picks yet", HomeCopy.NO_SETTLED_PICKS)
        assertEquals(
            "0-3 · 0% · lifetime paper −$3.10",
            HomeCopy.scorecardSummaryLine(
                HomeScorecardSummary(wins = 0, losses = 3, hitRate = 0.0, paperPnlUsd = -3.10, settledCount = 3)
            )
        )
        assertEquals(
            "2-0 · 100% · lifetime paper $0.00",
            HomeCopy.scorecardSummaryLine(
                HomeScorecardSummary(wins = 2, losses = 0, hitRate = 1.0, paperPnlUsd = 0.0, settledCount = 2)
            )
        )
    }
}
