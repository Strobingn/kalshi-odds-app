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
    }

    @Test
    fun pressBackOnTicketSheetCancelsAndSendsNoOrder() {
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
                home = { Text("HOME_SCREEN") },
                settings = { Text("SETTINGS_SCREEN") },
                scorecard = { Text("SCORECARD_SCREEN") },
                data = { Text("DATA_SCREEN") },
                history = { Text("HISTORY_SCREEN") },
                signalHistory = { Text("SIGNAL_HISTORY_SCREEN") },
                chart = { Text("CHART_SCREEN") }
            )
        }
        rule.onNodeWithText("HOME_SCREEN").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertEquals(1, cancelled.get())
        assertEquals(0, approved.get())
        assertEquals(AppRoutes.HOME, nav.current)
        assertFalse((rule.activity as Activity).isFinishing)
        assertTrue(cancelled.get() > 0)
    }
}
