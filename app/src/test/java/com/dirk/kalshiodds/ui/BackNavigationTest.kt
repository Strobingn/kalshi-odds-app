package com.dirk.kalshiodds.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.ui.components.TradeTicketsSection
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pressBackFromEachScreenReturnsPreviousAndDoesNotFinish() {
        val nav = AppNavigator()
        var sheetOpen by mutableStateOf(false)
        val cancelled = AtomicInteger(0)
        rule.setContent {
            DipApp(
                navigator = nav,
                sheetOpen = sheetOpen,
                onCancelSheet = {
                    cancelled.incrementAndGet()
                    sheetOpen = false
                },
                home = { Text("HOME_SCREEN") },
                settings = { Text("SETTINGS_SCREEN") },
                scorecard = { Text("SCORECARD_SCREEN") },
                scalp = { Text("SCALP_SCREEN") },
                data = { Text("DATA_SCREEN") },
                history = { Text("HISTORY_SCREEN") },
                signalHistory = { Text("SIGNAL_HISTORY_SCREEN") },
                chart = { Text("CHART_SCREEN") }
            )
        }
        rule.onNodeWithText("HOME_SCREEN").assertExists()

        val path = listOf(
            AppRoutes.SCORECARD to "SCORECARD_SCREEN",
            AppRoutes.SIGNAL_HISTORY to "SIGNAL_HISTORY_SCREEN",
            AppRoutes.SETTINGS to "SETTINGS_SCREEN",
            AppRoutes.DATA to "DATA_SCREEN",
            AppRoutes.HISTORY to "HISTORY_SCREEN",
            AppRoutes.CHART to "CHART_SCREEN"
        )
        for ((route, label) in path) {
            rule.runOnIdle { nav.open(route) }
            rule.onNodeWithText(label).assertExists()
            rule.activity.onBackPressedDispatcher.onBackPressed()
            rule.waitForIdle()
            assertFalse(rule.activity.isFinishing)
            assertEquals(AppRoutes.HOME, nav.current)
            rule.onNodeWithText("HOME_SCREEN").assertExists()
        }

        rule.runOnIdle { nav.open(AppRoutes.SETTINGS); nav.open(AppRoutes.DATA) }
        rule.onNodeWithText("DATA_SCREEN").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertEquals(AppRoutes.SETTINGS, nav.current)
        rule.onNodeWithText("SETTINGS_SCREEN").assertExists()
        assertFalse(rule.activity.isFinishing)
        assertEquals(0, cancelled.get())
    }

    @Test
    fun pressBackOnTicketSheetCancelsAndSendsNoOrder() {
        assertSheetBackCancelsWithoutOrder(sell = false)
    }

    @Test
    fun pressBackOnSellSheetCancelsAndSendsNoOrder() {
        assertSheetBackCancelsWithoutOrder(sell = true)
    }

    @Test
    fun pressBackOnTicketDialogSendsNoOrder() {
        assertTicketSectionBackCancels(sell = false)
    }

    @Test
    fun pressBackOnSellDialogSendsNoOrder() {
        assertTicketSectionBackCancels(sell = true)
    }

    @Test
    fun pressBackOnHomeFinishesActivity() {
        val nav = AppNavigator()
        rule.setContent {
            DipApp(
                navigator = nav,
                sheetOpen = false,
                onCancelSheet = {},
                home = { Text("HOME_SCREEN") },
                settings = { Text("SETTINGS_SCREEN") },
                scorecard = { Text("SCORECARD_SCREEN") },
                scalp = { Text("SCALP_SCREEN") },
                data = { Text("DATA_SCREEN") },
                history = { Text("HISTORY_SCREEN") },
                signalHistory = { Text("SIGNAL_HISTORY_SCREEN") },
                chart = { Text("CHART_SCREEN") }
            )
        }
        rule.onNodeWithText("HOME_SCREEN").assertExists()
        assertTrue(nav.isHome())
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
    }

    private fun assertSheetBackCancelsWithoutOrder(sell: Boolean) {
        val nav = AppNavigator()
        var sheetOpen by mutableStateOf(true)
        val cancelled = AtomicInteger(0)
        val approved = AtomicInteger(0)
        rule.setContent {
            DipApp(
                navigator = nav,
                sheetOpen = sheetOpen,
                onCancelSheet = {
                    cancelled.incrementAndGet()
                    sheetOpen = false
                },
                home = { Text(if (sell) "HOME_WITH_SELL_SHEET" else "HOME_SCREEN") },
                settings = { Text("SETTINGS_SCREEN") },
                scorecard = { Text("SCORECARD_SCREEN") },
                scalp = { Text("SCALP_SCREEN") },
                data = { Text("DATA_SCREEN") },
                history = { Text("HISTORY_SCREEN") },
                signalHistory = { Text("SIGNAL_HISTORY_SCREEN") },
                chart = { Text("CHART_SCREEN") }
            )
        }
        rule.onNodeWithText(if (sell) "HOME_WITH_SELL_SHEET" else "HOME_SCREEN").assertExists()
        rule.runOnIdle { nav.open(AppRoutes.SCORECARD) }
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertEquals(1, cancelled.get())
        assertEquals(0, approved.get())
        assertEquals(AppRoutes.SCORECARD, nav.current)
        assertFalse((rule.activity as Activity).isFinishing)
        assertTrue(sheetOpen.not())
    }

    private fun assertTicketSectionBackCancels(sell: Boolean) {
        val placed = AtomicInteger(0)
        val session = TicketSession(placeOrder = { _, _ ->
            placed.incrementAndGet()
            error("Back must not place")
        })
        val ticket = if (sell) {
            HomeFixtures.sellTicketWithBid()
        } else {
            TicketBuilder.proposeManual(
                HomeFixtures.actionableBtc(),
                "YES",
                TicketBuilder.Context(
                    settings = SignalSettings(ticketsEnabled = true),
                    alertsPaused = false,
                    nowMs = HomeFixtures.NOW_MS
                )
            )!!
        }
        if (sell) assertTrue(ticket.isSell) else assertFalse(ticket.isSell)
        session.addManual(ticket)
        var tickets by mutableStateOf(session.snapshot())
        rule.setContent {
            KalshiOddsTheme {
                TradeTicketsSection(
                    tickets = tickets,
                    credentialsConfigured = true,
                    paperTradingEnabled = false,
                    listVisible = false,
                    onReview = {},
                    onDismiss = {},
                    onApprove = { error("Approve must not run on Back") },
                    onPaper = { error("Paper must not run on Back") },
                    onCancelApprove = {
                        session.cancelApprove()
                        tickets = session.snapshot()
                    },
                    onCancelOrder = {}
                )
            }
        }
        assertTrue(tickets.phase is TicketPhase.AwaitingApprove)
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertEquals(0, placed.get())
        assertFalse(session.snapshot().phase is TicketPhase.AwaitingApprove)
        assertFalse((rule.activity as Activity).isFinishing)
    }
}
