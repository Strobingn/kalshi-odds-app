package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import java.time.ZoneId
import java.time.ZonedDateTime
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.components.workingOrderDetail
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkingOrderDetailTest {
    @Test
    fun workingCardShowsTheFullOrderId() {
        val order = PlacedOrder(
            ticket = TradeTicket(
                id = "adopted-no",
                ticker = "KXBTC15M-26SEP251530-30",
                side = "NO",
                bookSide = "ask",
                stakeUsd = 7.60,
                limitPrice = 0.76,
                yesLimitPrice = 0.24,
                contracts = 10,
                estimatedFillUsd = 7.60,
                maxPayoutUsd = 10.0,
                estimatedAvgFill = 0.76,
                sizingNote = "existing order",
                kind = TicketKind.MANUAL,
                clientOrderId = "cid-no-adopted"
            ),
            clientOrderId = "cid-no-adopted",
            orderId = "ord-no-99",
            fillCount = 0.0,
            remainingCount = 10.0,
            averageFillPrice = 0.76,
            placedAtMs = 1L
        )
        val line = workingOrderDetail(order)
        assertTrue(line, line.contains("id ord-no-99"))
        assertFalse(line, line.endsWith("id ord-no-9"))
    }
}

/** 0.3.23: cached odds on an offline cold start, and a NO adopted working order. */
class Release0323ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 2400),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun offlineColdStartShowsCachedOdds() {
        val base = HomeFixtures.state(
            HomeFixtures.screenshotPhoneBtc(),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol(),
            hasKey = true
        )
        snap("offline_cold_start_home") {
            KalshiOddsTheme(darkTheme = true) {
                HomeScreen(
                    state = base.copy(
                        userMessage = KalshiRequestStatus.OFFLINE,
                        snapshot = base.snapshot!!.copy(fromCache = true)
                    ),
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

    @Test
    fun adoptedNoWorkingOrder() {
        val ticket = TradeTicket(
            id = "adopted-no",
            ticker = "KXBTC15M-26SEP251530-30",
            side = "NO",
            bookSide = "ask",
            stakeUsd = 7.60,
            limitPrice = 0.76,
            yesLimitPrice = 0.24,
            contracts = 10,
            estimatedFillUsd = 7.60,
            maxPayoutUsd = 10.0,
            estimatedAvgFill = 0.76,
            sizingNote = "existing order",
            kind = TicketKind.MANUAL,
            title = "Bitcoin",
            clientOrderId = "cid-no-adopted",
            feeUsd = 0.18,
            allInUsd = 7.60,
            profitIfWinUsd = 2.40
        )
        val order = PlacedOrder(
            ticket = ticket,
            clientOrderId = ticket.clientOrderId,
            orderId = "ord-no-99",
            fillCount = 0.0,
            remainingCount = 10.0,
            averageFillPrice = 0.76,
            placedAtMs = HomeFixtures.NOW_MS
        )
        val state = TicketUiState(
            phase = TicketPhase.Submitted(order),
            working = listOf(order)
        )
        snap("adopted_no_working_order") {
            KalshiOddsTheme(darkTheme = true) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(com.dirk.kalshiodds.ui.theme.DipTheme.colors.bg)
                        .padding(16.dp)
                ) {
                    TradeTicketsSection(
                        tickets = state,
                        credentialsConfigured = true,
                        paperTradingEnabled = false,
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
    fun settingsSyncSuccess() = snapSync(
        "settings_sync_success",
        message = "Synced · pulled 4 · merged 4 · pushed 2"
    )

    @Test
    fun settingsSyncError() = snapSync(
        "settings_sync_error",
        message = "401 bad key"
    )

    private fun snapSync(name: String, message: String) {
        val zone = ZoneId.of("America/New_York")
        val at = ZonedDateTime.of(2026, 10, 3, 13, 14, 0, 0, zone).toInstant().toEpochMilli()
        snap(name) {
            KalshiOddsTheme(darkTheme = false) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(com.dirk.kalshiodds.ui.theme.DipTheme.colors.bg)
                        .padding(16.dp)
                ) {
                    androidx.compose.material3.Text(
                        "Data",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                    CloudSyncStatusBlock(
                        settings = DataHubSettings(
                            supabaseUrl = "https://example.supabase.co",
                            supabaseAnonKey = "a".repeat(32),
                            syncEnabled = true,
                            lastSyncAtMs = at,
                            lastSyncMessage = message
                        ),
                        zone = zone
                    )
                }
            }
        }
    }

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot(name = name, composable = content)
        copyLatest(name)
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
