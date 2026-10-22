package com.dirk.kalshiodds.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.llm.AiLivePoint
import com.dirk.kalshiodds.llm.AiLiveSnapshot
import com.dirk.kalshiodds.llm.ChatMessage
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 1.0.15 regression against tag scalp-v1.0.13.
 * The audit of UI strings, settings copy, nav routes, and strategy codes found one
 * intentional removal: the window chart's UP/DOWN chip. These tests compose each
 * main screen with empty data and with a sample so a crash shows up here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreenRegressionSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveBoardIsTheTwelveKeptScalpers() {
        assertEquals(KeptScalpers.ALL.map { it.id }, ScalpModels.WINNERS.map { it.code })
        assertEquals(KeptScalpers.ALL.map { it.id }, ScalpModels.ALL.take(KeptScalpers.ALL.size).map { it.code })
        assertTrue(ScalpModels.ALL.size <= KeptScalpers.MAX_SLOTS)
        assertTrue(ScalpModels.REMOVED_CODES.containsAll(HIDDEN_SINCE_1010))
        assertTrue(ScalpModels.ALL.all { it.label.isNotBlank() })
        assertTrue(ScalpModels.ALL.none { it.code in CODES_1013 && it.code !in KeptScalpers.IDS })
    }

    @Test fun navRoutesFrom1013AreStillPresent() {
        val required = listOf(
            AppRoutes.HOME, AppRoutes.SETTINGS, AppRoutes.SCORECARD, AppRoutes.DATA,
            AppRoutes.HISTORY, AppRoutes.CHART, AppRoutes.SCALP, AppRoutes.SCALP_DATA,
            AppRoutes.SCALP_TAB, AppRoutes.SCALP_ALGO, AppRoutes.AI
        )
        val missing = required.filter { it !in AppRoutes.ALL }
        assertTrue(missing.toString(), missing.isEmpty())
        assertTrue(AppRoutes.HOME in DipNav.tabs && AppRoutes.SCALP_TAB in DipNav.tabs && AppRoutes.SCORECARD in DipNav.tabs)
    }

    @Test fun homeEmptyComposes() {
        show { HomeScreen(OddsUiState(), onOpenSettings = {}, onOpenScorecard = {}, onOpenHistory = {}, onOpenChart = {}, onRefresh = {}, onBuyMarket = { _, _ -> }, onPaperSide = { _, _ -> }, onSellMarket = {}, onSetPaperTrading = {}, onResetPaper = {}, onSellPosition = { _, _ -> }, onReviewTicket = {}, onDismissTicket = {}, onApproveTicket = {}, onApproveSellTicket = { _, _, _ -> }, onPaperTicket = {}, onPaperSellTicket = { _, _, _ -> }, onCancelApprove = {}, onCancelOrder = {}) }
        assertShown("Kalshi Scalper")
        assertShown("BTC")
    }

    @Test fun bitcoinSampleComposes() {
        val state = HomeFixtures.state(HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true)
        show { HomeScreen(state, onOpenSettings = {}, onOpenScorecard = {}, onOpenHistory = {}, onOpenChart = {}, onRefresh = {}, onBuyMarket = { _, _ -> }, onPaperSide = { _, _ -> }, onSellMarket = {}, onSetPaperTrading = {}, onResetPaper = {}, onSellPosition = { _, _ -> }, onReviewTicket = {}, onDismissTicket = {}, onApproveTicket = {}, onApproveSellTicket = { _, _, _ -> }, onPaperTicket = {}, onPaperSellTicket = { _, _, _ -> }, onCancelApprove = {}, onCancelOrder = {}, nowMs = HomeFixtures.NOW_MS) }
        assertShown("BTC")
        reveal("15m")
    }

    @Test fun marketDetailEmptyComposes() {
        val bare = HomeFixtures.actionableBtc().copy(bidHistory = emptyList(), oddsHistory = emptyList(), yesBid = null, yesAsk = null, noBid = null, noAsk = null, spotUsd = null, floorStrike = null, aiYesPercent = null)
        show { ChartDetailScreen(bare, emptyList(), onBack = {}, onBuyYes = {}, onBuyNo = {}, book = null) }
        assertShown("Details")
        assertShown("No live book yet")
    }

    @Test fun marketDetailSampleComposes() {
        val sample = HomeFixtures.actionableBtc()
        val points = listOf(
            BidPoint(HomeFixtures.NOW_MS - 60_000, 40f, 55f, 67_100.0),
            BidPoint(HomeFixtures.NOW_MS, 44f, 52f, 67_240.0)
        )
        show {
            ChartDetailScreen(
                sample.copy(bidHistory = points),
                points,
                onBack = {},
                onBuyYes = {},
                onBuyNo = {},
                book = BookLevelSnapshot(yes = listOf(0.44 to 12.0, 0.40 to 20.0), no = listOf(0.52 to 8.0, 0.48 to 15.0))
            )
        }
        reveal("UP and DOWN together")
        reveal("UP bids")
        reveal("DOWN bids")
    }

    @Test fun scalpEmptyComposes() {
        show { KashiArenaContent(ScalpTabState()) }
        assertShown("Scalping arena")
    }

    @Test fun scalpSampleComposes() {
        show { KashiArenaContent(ScalpTabFixtures.state(0)) }
        assertShown("Scalping arena")
        reveal("Manual ladder")
    }

    @Test fun scalpAnalysisEmptyComposes() {
        show { KashiArenaContent(ScalpTabState(), initialTab = KashiArenaTab.ANALYSIS) }
        assertShown("Strategy race")
        reveal("Equity · each scalper and combined")
        reveal("No data yet")
    }

    @Test fun scalpAnalysisSampleComposes() {
        show { KashiArenaContent(ScalpTabFixtures.state(2), initialTab = KashiArenaTab.ANALYSIS) }
        assertShown("Profit by strategy")
        reveal("What fees cost")
        reveal("Equity · each scalper and combined")
    }

    @Test fun strategyPageEmptyComposes() {
        show { ScalpAlgoContent(ScalpAlgoDetail()) }
        assertShown("Algorithm")
        assertShown("50¢")
        compose.onAllNodesWithText("UP / YES").assertCountEquals(0)
    }

    @Test fun strategyPageSampleComposes() {
        val model = ScalpModels.Model.FAIR_GAP
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = com.dirk.kalshiodds.decision.ScalpTicker.closeMs(ticker)!!
        val start = close - 900_000
        val trade = ScalpTrade(
            "t", ticker, "YES", ScalpState.CLOSED, start + 60_000, 0.40, 0.5,
            contracts = 10, entryPrice = 0.40, entryAtMs = start + 70_000, entryFeeUsd = 0.1, exitFeeUsd = 0.1,
            soldContracts = 10, proceedsUsd = 5.0, closedAtMs = start + 200_000, netUsd = 0.8,
            ruleVersion = "${ScalpRule.VERSION}|fair|P"
        )
        show {
            ScalpAlgoContent(
                ScalpAlgoDetail(
                    model = model,
                    allTime = ScalpModels.row(model, listOf(trade), resamples = 0),
                    trades = listOf(trade),
                    chart = ScalpWindowChartData(ticker, close, start + 300_000, points = listOf(BidPoint(start + 10_000, 42f, 55f), BidPoint(start + 20_000, 48f, 50f)), trades = listOf(trade))
                )
            )
        }
        assertShown("Fair-gap")
        assertShown("UP")
        assertShown("DOWN")
        reveal("Strategy flow")
    }

    @Test fun scalpDataEmptyComposes() {
        show { ScalpDataContent(emptyList(), emptyList(), emptyList(), {}, {}, {}, { _, _, _ -> }, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading scalp data…").fetchSemanticsNodes().isEmpty() }
        assertShown("Scalping results only")
    }

    @Test fun scalpDataSampleComposes() {
        val trade = ScalpTrade(
            "t", "KXBTC15M-26OCT122015-15", "NO", ScalpState.CLOSED, 1_000, 0.42, 0.5,
            contracts = 5, entryPrice = 0.40, entryAtMs = 2_000, closedAtMs = 90_000, netUsd = -0.2,
            entryFeeUsd = 0.05, exitFeeUsd = 0.05, soldContracts = 5, proceedsUsd = 1.5,
            ruleVersion = "${ScalpRule.VERSION}|fair|P"
        )
        show { ScalpDataContent(listOf(trade), listOf(trade), emptyList(), {}, {}, {}, { _, _, _ -> }, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading scalp data…").fetchSemanticsNodes().isEmpty() }
        assertShown("Scalping results only")
        reveal("Entry price")
    }

    @Test fun scorecardEmptyComposes() {
        show { ScorecardScreen(ScorecardUi.EMPTY, onBack = {}) }
        assertShown("Scorecard")
        reveal("No settled picks yet")
    }

    @Test fun scorecardSampleComposes() {
        show { ScorecardScreen(HomeFixtures.sampleScorecardUi(), onBack = {}) }
        reveal("Running P&L")
        reveal("Drawdown")
    }

    @Test fun aiChatEmptyComposes() {
        show { AiChatContent(emptyList(), busy = false, strategyStatus = "needs key", hasKey = false) }
        assertShown("Paper strategy: needs key")
    }

    @Test fun aiChatSampleComposes() {
        val live = AiLiveSnapshot(
            atMs = 1_000, windowKey = "w", ticker = "KXBTC15M-TEST", closeMs = 61_000,
            strike = 67_000.0, btc = 67_120.0, yesBid = 0.41, yesAsk = 0.43, noBid = 0.55, noAsk = 0.57,
            modelUp = 0.62,
            points = listOf(AiLivePoint(1_000, 67_000.0, 67_000.0, 0.40, 0.55), AiLivePoint(2_000, 67_120.0, 67_000.0, 0.43, 0.52))
        )
        show {
            AiChatContent(
                listOf(ChatMessage(1, ChatMessage.USER, "Why did Fair-gap buy?", 1)),
                busy = false, strategyStatus = "watching", hasKey = true, live = live, liveOn = true
            )
        }
        assertShown("Why did Fair-gap buy?")
        assertShown("AI live feed")
        assertShown("UP")
        assertShown("DOWN")
    }

    @Test fun settingsEmptyComposes() {
        show { SettingsContent(SettingsUiState(), onBack = {}, onOpenData = {}) }
        assertShown("Signal settings")
        assertShown("Scalping settings")
        assertShown("Live signals (keep odds alive)")
        assertShown("Notifications")
        assertShown("Paper trading (AI Kelly auto-fills)")
        assertShown("Kalshi demo environment")
    }

    @Test fun settingsSampleComposes() {
        show { SettingsContent(SettingsUiState(settings = HomeFixtures.settings(true), updateMessage = "You're on 1.0.13."), onBack = {}, onOpenData = {}) }
        assertShown("You're on 1.0.13.")
        assertShown("Auto-tune edge from settled history")
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { KalshiOddsTheme(darkTheme = true) { content() } }
        compose.waitForIdle()
    }

    private fun assertShown(text: String) {
        val n = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("missing '$text'", n > 0)
    }

    private fun reveal(text: String) {
        val lists = compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true)
        if (lists.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { lists[0].performScrollToNode(hasText(text, substring = true)) }
        }
        assertShown(text)
    }

    private companion object {
        /** Hidden before 1.0.13 (1.0.10). Still in the enum so old rows can be named; not on the board. */
        val HIDDEN_SINCE_1010 = setOf("sf30", "sf32", "mkdip", "diph60", "dipnotp", "r-kelly")

        /** Strategy codes shipped in scalp-v1.0.13. Later releases may add codes; they may not drop these. */
        val CODES_1013 = (
            "fair,dip,momo,xrev,cfr,mkdip,fcride,fcfade,diph60,momoh60,fairh60,dipnotp,momonotp," +
                "sf01,sf02,sf03,sf04,sf05,sf06,sf07,sf08,sf09,sf10,sf11,sf12,sf13,sf14,sf15,sf16,sf17,sf18,sf19,sf20,sf21,sf22,sf23,sf24,sf27,sf28,sf29,sf30,sf31,sf32," +
                "sf41,sf42,sf43,sf44,sf51,sf52,sf53,sf54,sf55,sf56,sf57,sf58,sf59,sf60,sf61," +
                "v2-fair,v2-dip,v2-momo,v2-xrev,v2-cfr,v2-mkdip,v2-fcride,r-late,r-hold,r-maker,r-kelly,v2-llm,v2-fcfade,v2-diph60,v2-momoh60,v2-fairh60,v2-dipnotp,v2-momonotp," +
                "v2-sf01,v2-sf02,v2-sf03,v2-sf04,v2-sf05,v2-sf06,v2-sf07,v2-sf08,v2-sf09,v2-sf10,v2-sf11,v2-sf12,v2-sf13,v2-sf14,v2-sf15,v2-sf16,v2-sf17,v2-sf18,v2-sf19,v2-sf20,v2-sf21,v2-sf22,v2-sf23,v2-sf24,v2-sf27,v2-sf28,v2-sf30,v2-sf31,v2-sf32," +
                "v2-sf41,v2-sf42,v2-sf43,v2-sf44,v2-sf51,v2-sf52,v2-sf53,v2-sf54,v2-sf55,v2-sf56,v2-sf57,v2-sf58,v2-sf59,v2-sf60,v2-sf61," +
                "v2-sf41t1,v2-sf41t2,v2-sf41x5,v2-sf41x10,v2-sf42t1,v2-sf42t2,v2-sf42x5,v2-sf42x10,v2-sf43t1,v2-sf43t2,v2-sf43x5,v2-sf43x10,v2-sf44t1,v2-sf44t2,v2-sf44x5,v2-sf44x10"
            ).split(",")
    }
}
