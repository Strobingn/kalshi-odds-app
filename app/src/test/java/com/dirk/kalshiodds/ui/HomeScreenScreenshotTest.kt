package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.LiveSellConfirmSheet
import com.dirk.kalshiodds.ui.components.PositionsCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.signal.feedback.Allowlist
import com.dirk.kalshiodds.signal.feedback.Guardrails
import com.dirk.kalshiodds.signal.feedback.OnlineAdapter
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * JVM (no emulator) home-screen screenshots via Paparazzi.
 * Each PNG is also copied to /opt/cursor/artifacts/ for the redesign report.
 */
class HomeScreenScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false,
            screenHeight = 4200
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun lightActionable() = snap("home_light_actionable", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun darkActionable() = snap("home_dark_actionable", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun lightActionableDown() = snap("home_light_actionable_down", dark = false, HomeFixtures.state(
        HomeFixtures.actionableDownBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun darkActionableDown() = snap("home_dark_actionable_down", dark = true, HomeFixtures.state(
        HomeFixtures.actionableDownBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun lightAllNoBet() = snap("home_light_all_nobet", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(
            yesAsk = 0.63,
            noAsk = 0.37,
            yesBid = 0.61,
            noBid = 0.35,
            yesProbabilityPercent = 63.0,
            noProbabilityPercent = 37.0,
            aiYesPercent = 70.0,
            importedModelPp = 70.0,
            edgePp = 7.0
        ),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
    ))

    @Test
    fun darkAllNoBet() = snap("home_dark_all_nobet", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(
            yesAsk = 0.63,
            noAsk = 0.37,
            yesBid = 0.61,
            noBid = 0.35,
            yesProbabilityPercent = 63.0,
            noProbabilityPercent = 37.0,
            aiYesPercent = 70.0,
            importedModelPp = 70.0,
            edgePp = 7.0
        ),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
    ))

    @Test
    fun lightNoKey() = snap("home_light_nokey", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = false
    ))

    @Test
    fun darkNoKey() = snap("home_dark_nokey", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = false
    ))

    @Test
    fun lightDisagreement() = snap("home_light_disagreement", dark = false, HomeFixtures.state(
        HomeFixtures.disagreementBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun darkDisagreement() = snap("home_dark_disagreement", dark = true, HomeFixtures.state(
        HomeFixtures.disagreementBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun lightScorecardEmpty() = snap("home_light_scorecard_empty", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(),
        hasKey = true,
        scorecard = HomeScorecardSummary.EMPTY
    ))

    @Test
    fun darkScorecardEmpty() = snap("home_dark_scorecard_empty", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(),
        hasKey = true,
        scorecard = HomeScorecardSummary.EMPTY
    ))

    @Test
    fun lightScorecard() = snapScorecard("scorecard_light", dark = false)

    @Test
    fun darkScorecard() = snapScorecard("scorecard_dark", dark = true)

    @Test
    fun lightSignals() = snapSignals("signals_light", dark = false)

    @Test
    fun darkSignals() = snapSignals("signals_dark", dark = true)

    @Test
    fun lightSignalHistory() = snapSignals("signal_history_light", dark = false)

    @Test
    fun darkSignalHistory() = snapSignals("signal_history_dark", dark = true)

    @Test
    fun lightPositionsOpen() = snapPositions("positions_light_open", dark = false)

    @Test
    fun darkPositionsOpen() = snapPositions("positions_dark_open", dark = true)

    @Test
    fun lightSellConfirmBid() = snapSell("sell_confirm_light_bid", dark = false, HomeFixtures.sellTicketWithBid())

    @Test
    fun darkSellConfirmBid() = snapSell("sell_confirm_dark_bid", dark = true, HomeFixtures.sellTicketWithBid())

    @Test
    fun lightSellConfirmNoBid() = snapSell("sell_confirm_light_nobid", dark = false, HomeFixtures.sellTicketNoBid())

    @Test
    fun darkSellConfirmNoBid() = snapSell("sell_confirm_dark_nobid", dark = true, HomeFixtures.sellTicketNoBid())

    @Test
    fun lightSettings() = snapSettings("settings_light", dark = false)

    @Test
    fun darkSettings() = snapSettings("settings_dark", dark = true)

    @Test
    fun beforeLightActionable() = snapBefore("before_0_3_11_light_actionable", dark = false, keyed = true)

    @Test
    fun beforeDarkActionable() = snapBefore("before_0_3_11_dark_actionable", dark = true, keyed = true)

    @Test
    fun beforeLightNoKey() = snapBefore("before_0_3_11_light_nokey", dark = false, keyed = false)

    private fun snap(name: String, dark: Boolean, state: OddsUiState) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                HomeScreen(
                    state = state,
                    onOpenSettings = {},
                    onOpenScorecard = {},
                    onOpenHistory = {},
                    onOpenSignalHistory = {},
                    onOpenChart = {},
                    onRefresh = {},
                    onBuyMarket = { _, _ -> },
                    onSellMarket = {},
                    onSetPaperTrading = {},
                    onResetPaper = {},
                    onSellPosition = { _, _ -> },
                    onReviewTicket = {},
                    onDismissTicket = {},
                    onApproveTicket = {},
                    onApproveSellTicket = { _, _, _ -> },
                    onPaperTicket = {},
                    onPaperSellTicket = { _, _, _ -> },
                    onCancelApprove = {},
                    onCancelOrder = {},
                    nowMs = HomeFixtures.NOW_MS,
                    versionLabel = "DipHunter v0.3.13 (28)"
                )
            }
        }
        copyLatest(name)
    }

    private fun snapBefore(name: String, dark: Boolean, keyed: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                LegacyHome0311(
                    dark = dark,
                    hasKey = keyed,
                    btc = HomeFixtures.actionableBtc(),
                    eth = HomeFixtures.noBetEth(),
                    sol = HomeFixtures.noBetSol(),
                    nowMs = HomeFixtures.NOW_MS
                )
            }
        }
        copyLatest(name)
    }

    private fun snapScorecard(name: String, dark: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                ScorecardScreen(ui = scorecardUi(), onBack = {})
            }
        }
        copyLatest(name)
    }

    private fun snapPositions(name: String, dark: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .fillMaxSize()
                        .background(DipTheme.colors.bg)
                        .padding(16.dp)
                ) {
                    PositionsCard(
                        positions = listOf(HomeFixtures.openPosition()),
                        note = null,
                        onSell = { _, _ -> },
                        homeMode = false
                    )
                }
            }
        }
        copyLatest(name)
    }

    private fun snapSell(name: String, dark: Boolean, ticket: TradeTicket) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(DipTheme.colors.bg)
                ) {
                    LiveSellConfirmSheet(
                        ticket = ticket,
                        credentialsConfigured = true,
                        paperTradingEnabled = false
                    )
                }
            }
        }
        copyLatest(name)
    }

    private fun snapSignals(name: String, dark: Boolean) {
        val cards = listOf(
            SignalCopy.card(HomeFixtures.sampleAlerts()[0]),
            SignalCopy.card(
                ticker = "KXBTC15M-26SEP251345-45",
                side = "YES",
                modelYes = 68.0,
                marketYes = 64.0,
                settled = null,
                fairYes = 68.0,
                details = "TREND/EARLY · cal · adapt · AI 68% vs mkt 64% · flow NO · Δ +4.0pp"
            ),
            SignalCopy.card(
                ticker = "KXETH15M-26SEP251400-40",
                side = "NO",
                modelYes = 30.0,
                marketYes = 48.0,
                settled = "no",
                fairYes = 30.0,
                details = "QUIET/MID · AI 30% vs mkt 48%"
            )
        )
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                SignalHistoryScreen(cards = cards, onBack = {})
            }
        }
        copyLatest(name)
    }

    private fun snapSettings(name: String, dark: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                SettingsContent(
                    state = SettingsUiState(
                        settings = SignalSettings(
                            apiKeyId = "key-id",
                            hasPrivateKey = true,
                            paperTradingEnabled = true,
                            ticketsEnabled = true
                        ),
                        connectionTestOk = true,
                        batteryUnrestricted = true,
                        lastOrderError = "example reject — not a DOWN button"
                    ),
                    onBack = {},
                    onOpenData = {}
                )
            }
        }
        copyLatest(name)
    }

    private fun scorecardUi(): ScorecardUi {
        val window = ScorecardMetrics.WindowStats(
            hits = 12,
            total = 18,
            hitRate = 12.0 / 18.0,
            brier = 0.211,
            pUpBrier = 0.250,
            avgEdgeWhenRight = 4.0,
            avgEdgeWhenWrong = -2.0
        )
        return ScorecardUi(
            metrics = ScorecardMetrics.Snapshot(
                daily = window,
                rolling = window,
                allTime = window,
                perSeries = emptyList(),
                sampleCount = 18,
                openCount = 2,
                voidCount = 0,
                calibrationReady = false,
                temperature = null,
                calibrationSamples = 18,
                honest = ScorecardMetrics.Honest(
                    n = 18,
                    modelBrier = 0.250,
                    marketBrier = 0.280,
                    hitRate = 12.0 / 18.0,
                    avgEdgeWhenRight = 4.0,
                    avgEdgeWhenWrong = -2.0,
                    enoughData = false,
                    perAsset = emptyList(),
                    perCoin = listOf(
                        ScorecardMetrics.Breakdown("BTC", "Bitcoin", 24, 0.67, 0.200, 0.220, 8.40, true),
                        ScorecardMetrics.Breakdown("ETH", "Ethereum", 22, 0.59, 0.230, 0.250, 2.10, true),
                        ScorecardMetrics.Breakdown("SOL", "Solana", 8, null, null, null, null, false)
                    ),
                    perTimeOfDay = listOf(
                        ScorecardMetrics.Breakdown("08-12", "8–12 ET", 20, 0.70, 0.180, 0.210, 6.00, true),
                        ScorecardMetrics.Breakdown("12-16", "12–16 ET", 21, 0.62, 0.220, 0.240, 3.50, true),
                        ScorecardMetrics.Breakdown("16-20", "16–20 ET", 5, null, null, null, null, false)
                    ),
                    sideBrier = 0.211,
                    hits = 12
                )
            ),
            allowlist = Allowlist.State(),
            adapter = OnlineAdapter.State(),
            guardrails = Guardrails.State()
        )
    }

    @After
    fun flushArtifacts() {
        listOf(
            "home_light_actionable", "home_dark_actionable",
            "home_light_actionable_down", "home_dark_actionable_down",
            "home_light_all_nobet", "home_dark_all_nobet",
            "home_light_nokey", "home_dark_nokey",
            "home_light_disagreement", "home_dark_disagreement",
            "home_light_scorecard_empty", "home_dark_scorecard_empty",
            "scorecard_light", "scorecard_dark",
            "settings_light", "settings_dark",
            "signals_light", "signals_dark",
            "signal_history_light", "signal_history_dark",
            "positions_light_open", "positions_dark_open",
            "sell_confirm_light_bid", "sell_confirm_dark_bid",
            "sell_confirm_light_nobid", "sell_confirm_dark_nobid",
            "before_0_3_11_light_actionable", "before_0_3_11_dark_actionable",
            "before_0_3_11_light_nokey"
        ).forEach(::copyLatest)
    }

    private fun copyLatest(name: String) {
        val destDir = File("/opt/cursor/artifacts").apply { mkdirs() }
        val runDirs = listOf(
            File("build/reports/paparazzi/debug/runs"),
            File("app/build/reports/paparazzi/debug/runs")
        ).filter { it.isDirectory }
        val latest = runDirs.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.name }
        }
        for (js in latest) {
            val text = js.readText()
            if (!text.contains("\"name\": \"$name\"")) continue
            val rel = Regex("\"file\": \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
            val parent = js.parentFile?.parentFile ?: continue
            val src = File(parent, rel)
            if (src.isFile) {
                try {
                    src.copyTo(File(destDir, "$name.png"), overwrite = true)
                } catch (_: Exception) {
                    // /opt/cursor/artifacts can flake on close; the PNG is still in paparazzi/images.
                }
                return
            }
        }
    }
}
