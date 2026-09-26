package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
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
    fun lightS24ScorecardVisibleWithoutScroll() {
        val tall = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 4200)
        try {
            paparazzi.unsafeUpdateConfig(
                deviceConfig = DeviceConfig.PIXEL_6.copy(
                    softButtons = false,
                    screenWidth = 1080,
                    screenHeight = 2340
                )
            )
            snap(
                "home_light_s24_scorecard_visible",
                dark = false,
                HomeFixtures.state(
                    HomeFixtures.actionableBtc(),
                    HomeFixtures.noBetEth(),
                    HomeFixtures.noBetSol(),
                    hasKey = true
                )
            )
        } finally {
            paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        }
    }

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
    fun darkPhoneScreenshotAfterFix() = snap(
        "home_after_fix",
        dark = true,
        HomeFixtures.state(
            HomeFixtures.screenshotPhoneBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        ).copy(
            settings = HomeFixtures.settings(true).copy(minProfitIfWinUsd = 20.0)
        )
    )

    @Test
    fun lightBtcOnly() = snap(
        "home_light_btc_only",
        dark = false,
        HomeFixtures.state(
            HomeFixtures.disagreementBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        )
    )

    @Test
    fun darkBtcOnly() = snap(
        "home_dark_btc_only",
        dark = true,
        HomeFixtures.state(
            HomeFixtures.disagreementBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        ).copy(
            settings = HomeFixtures.settings(true).copy(
                autoTuneEnabled = true,
                sitOut = true,
                autoTuneNote = "The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative."
            ),
            paper = com.dirk.kalshiodds.signal.paper.PaperBookState(
                fills = listOf(HomeFixtures.openPaperFill(HomeFixtures.disagreementBtc().ticker))
            )
        )
    )

    @Test
    fun lightBtcOnly360Font13() = snapNarrowFont13(
        "home_light_btc_only_360_font13",
        dark = false,
        HomeFixtures.state(
            HomeFixtures.actionableBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        )
    )

    @Test
    fun darkBtcOnly360Font13() = snapNarrowFont13(
        "home_dark_btc_only_360_font13",
        dark = true,
        HomeFixtures.state(
            HomeFixtures.actionableBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        )
    )

    @Test
    fun lightNextWindow() = snap("home_light_next_window", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(closeTimeEpochMs = HomeFixtures.NOW_MS - 1_000L),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
    ))

    @Test
    fun darkNextWindow() = snap("home_dark_next_window", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(closeTimeEpochMs = HomeFixtures.NOW_MS - 1_000L),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
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
    fun darkFullScorecardEmpty() = snapScorecard("scorecard_empty_dark", dark = true, empty = true)

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
                    onPaperSide = { _, _ -> },
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
                    versionLabel = "DipHunter v0.3.15 (30)"
                )
            }
        }
        copyLatest(name)
    }

    /** 360dp-wide phone at fontScale 1.3 — tiles must not overflow. */
    private fun snapNarrowFont13(name: String, dark: Boolean, state: OddsUiState) {
        val restore = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 4200)
        try {
            paparazzi.unsafeUpdateConfig(
                deviceConfig = DeviceConfig.PIXEL_6.copy(
                    softButtons = false,
                    screenWidth = 945,
                    screenHeight = 2400,
                    fontScale = 1.3f
                )
            )
            snap(name, dark, state)
        } finally {
            paparazzi.unsafeUpdateConfig(deviceConfig = restore)
        }
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

    private fun snapScorecard(name: String, dark: Boolean, empty: Boolean = false) {
        val tall = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 4200)
        try {
            paparazzi.unsafeUpdateConfig(
                deviceConfig = DeviceConfig.PIXEL_6.copy(
                    softButtons = false,
                    screenHeight = if (empty) 1400 else 1800
                )
            )
            paparazzi.snapshot(name = name) {
                KalshiOddsTheme(darkTheme = dark) {
                    ScorecardScreen(
                        ui = if (empty) ScorecardUi.EMPTY else HomeFixtures.sampleScorecardUi(),
                        onBack = {}
                    )
                }
            }
            copyLatest(name)
        } finally {
            paparazzi.unsafeUpdateConfig(deviceConfig = tall)
        }
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
                ticker = "KXBTC15M-26SEP251400-40",
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


    @After
    fun flushArtifacts() {
        // Each snap() already copies; avoid re-scanning every run file here
        // (/opt/cursor/artifacts File.delete can stall on overwrite).
    }

    private fun copyLatest(name: String) {
        val destDir = File("/opt/cursor/artifacts").apply { mkdirs() }
        val runDirs = listOf(
            File("build/reports/paparazzi/debug/runs"),
            File("app/build/reports/paparazzi/debug/runs")
        ).filter { it.isDirectory }
        val latest = runDirs.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile }
        }.sortedByDescending { it.name }
        for (js in latest) {
            val text = js.readText()
            if (!text.contains("\"name\": \"$name\"")) continue
            val rel = Regex("\"file\": \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
            val parent = js.parentFile?.parentFile ?: continue
            val src = File(parent, rel)
            if (!src.isFile) continue
            val dest = File(destDir, "$name.png")
            try {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // /opt/cursor/artifacts can flake; the PNG is still in paparazzi/images.
            }
            return
        }
    }
}
