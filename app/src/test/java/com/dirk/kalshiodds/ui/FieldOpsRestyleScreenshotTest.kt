package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.trade.LimitPriceInput
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.DipBottomBar
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** 0.3.24 FieldOps chrome: tab bar, More, and the REAL MONEY confirm. */
class FieldOpsRestyleScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 2400),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun homeDarkWithTabs() = home("fieldops_home_dark", dark = true)

    @Test
    fun homeLightWithTabs() = home("fieldops_home_light", dark = false)

    @Test
    fun moreDark() {
        paparazzi.snapshot(name = "fieldops_more_dark") {
            KalshiOddsTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDipTabBar provides true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            MoreScreen(
                                onOpen = {},
                                syncText = "Synced",
                                syncFailed = false,
                                syncPending = false
                            )
                        }
                        DipBottomBar(current = AppRoutes.MORE, onSelect = {})
                    }
                }
            }
        }
    }

    @Test
    fun ticketConfirmDark() {
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
        val ticket = LimitPriceInput.apply(seed, 25, 0.07)
        paparazzi.snapshot(name = "fieldops_ticket_dark") {
            KalshiOddsTheme(darkTheme = true) {
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

    private fun home(name: String, dark: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDipTabBar provides true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            HomeScreen(
                                state = HomeFixtures.state(
                                    HomeFixtures.actionableBtc(),
                                    HomeFixtures.noBetEth(),
                                    HomeFixtures.noBetSol(),
                                    hasKey = true
                                ),
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
                                nowMs = HomeFixtures.NOW_MS
                            )
                        }
                        DipBottomBar(current = AppRoutes.HOME, onSelect = {})
                    }
                }
            }
        }
    }
}
