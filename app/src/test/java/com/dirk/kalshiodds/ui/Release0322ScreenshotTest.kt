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
import com.dirk.kalshiodds.signal.trade.LimitPriceInput
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** At most a handful of 0.3.22 shots: limit field, offline, rate-limit, settings. */
class Release0322ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 2400),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun ticketLimitPrice() {
        val ticket = pricedTicket()
        snap("ticket_limit_price") {
            KalshiOddsTheme(darkTheme = true) {
                Column(
                    Modifier.fillMaxSize().background(com.dirk.kalshiodds.ui.theme.DipTheme.colors.bg).padding(16.dp)
                ) {
                    TradeTicketsSection(
                        tickets = TicketUiState(phase = TicketPhase.Proposed(listOf(ticket)), proposals = listOf(ticket)),
                        credentialsConfigured = true,
                        paperTradingEnabled = true,
                        onReview = {},
                        onDismiss = {},
                        onApprove = {},
                        onPaper = {},
                        onCancelApprove = {},
                        onCancelOrder = {}
                    )
                }
            }
        }
    }

    @Test
    fun ticketLimitConfirm() {
        val ticket = pricedTicket()
        snap("ticket_limit_confirm") {
            KalshiOddsTheme(darkTheme = false) {
                TradeTicketsSection(
                    tickets = TicketUiState(
                        phase = TicketPhase.AwaitingApprove(ticket, emptyList(), ticket.clientOrderId),
                        proposals = listOf(ticket)
                    ),
                    credentialsConfigured = true,
                    paperTradingEnabled = false,
                    listVisible = false,
                    onReview = {},
                    onDismiss = {},
                    onApprove = {},
                    onPaper = {},
                    onCancelApprove = {},
                    onCancelOrder = {}
                )
            }
        }
    }

    @Test
    fun homeOffline() = snapHome("home_offline", "Offline")

    @Test
    fun homeRateLimited() = snapHome("home_rate_limited", "Rate-limited, retrying in 8s")

    @Test
    fun settingsKashiUpdate() {
        snap("settings_kashi_update") {
            KalshiOddsTheme(darkTheme = false) {
                SettingsContent(
                    state = SettingsUiState(
                        settings = SignalSettings(
                            apiKeyId = "key-id",
                            hasPrivateKey = true,
                            paperTradingEnabled = true,
                            ticketsEnabled = true
                        ),
                        updateMessage = "Kashi only · tags v*-debug from branch kashi · DipHunter-debug.apk"
                    ),
                    onBack = {},
                    onOpenData = {}
                )
            }
        }
    }

    private fun snapHome(name: String, message: String) {
        snap(name) {
            KalshiOddsTheme(darkTheme = true) {
                HomeScreen(
                    state = HomeFixtures.state(
                        HomeFixtures.actionableBtc(),
                        HomeFixtures.noBetEth(),
                        HomeFixtures.noBetSol(),
                        hasKey = true
                    ).copy(userMessage = message),
                    onOpenSettings = {},
                    onOpenScorecard = {},
                    onOpenHistory = {},
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
                    nowMs = HomeFixtures.NOW_MS
                )
            }
        }
    }

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot(name = name, composable = content)
        copyLatest(name)
    }

    private fun pricedTicket(): TradeTicket {
        val seed = TradeTicket(
            id = "limit-1",
            ticker = "KXBTC15M-26SEP251530-30",
            side = "YES",
            bookSide = "bid",
            stakeUsd = 9.0,
            limitPrice = 0.25,
            yesLimitPrice = 0.25,
            contracts = 30,
            estimatedFillUsd = 9.0,
            maxPayoutUsd = 30.0,
            estimatedAvgFill = 0.25,
            sizingNote = "seed",
            kind = TicketKind.MANUAL,
            title = "Bitcoin 3:30 PM",
            clientOrderId = "cid-limit",
            feeUsd = 0.2,
            allInUsd = 9.0,
            profitIfWinUsd = 21.0
        )
        return LimitPriceInput.apply(seed, 25, 0.07)
    }

    private fun copyLatest(name: String) {
        val destDir = File("/opt/cursor/artifacts").apply { mkdirs() }
        val runDirs = listOf(
            File("build/reports/paparazzi/debug/runs"),
            File("app/build/reports/paparazzi/debug/runs")
        ).filter { it.isDirectory }
        val latest = runDirs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile } }
            .sortedByDescending { it.name }
        for (js in latest) {
            val text = js.readText()
            if (!text.contains("\"name\": \"$name\"")) continue
            val rel = Regex("\"file\": \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
            val parent = js.parentFile?.parentFile ?: continue
            val src = File(parent, rel)
            if (!src.isFile) continue
            src.copyTo(File(destDir, "$name.png"), overwrite = true)
            return
        }
    }
}
