package com.dirk.kalshiodds.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.dirk.kalshiodds.ui.components.DipBottomBar

/**
 * True while the five-tab bar is on screen, so inner scaffolds skip the
 * system navigation-bar inset (the tab bar already clears it).
 */
val LocalDipTabBar = staticCompositionLocalOf { false }

@Composable
fun dipContentInsets(): WindowInsets {
    val sides = if (LocalDipTabBar.current) {
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    } else {
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    }
    return WindowInsets.safeDrawing.only(sides)
}

/**
 * Screen switcher with a real back stack. Official guidance
 * (developer.android.com predictive-back-gesture, Navigation Compose /
 * BackHandler): one enabled [BackHandler] per responsibility, disabled when
 * that UI state is gone so the next callback — or the system — runs.
 *
 * Ticket/sell sheet: cancel only, never Approve. In-app destination: pop.
 * A root tab with no sheet: neither callback is enabled, so Android 14+
 * gesture Back finishes the activity (predictive back-to-home).
 */
@Composable
fun DipApp(
    navigator: AppNavigator,
    sheetOpen: Boolean,
    onCancelSheet: () -> Unit,
    home: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    scorecard: @Composable () -> Unit,
    data: @Composable () -> Unit,
    history: @Composable () -> Unit,
    signalHistory: @Composable () -> Unit,
    chart: @Composable () -> Unit,
    live: @Composable () -> Unit = {},
    more: @Composable () -> Unit = {},
    realMoney: @Composable () -> Unit = {}
) {
    BackHandler(enabled = navigator.canPop && !sheetOpen) {
        navigator.back()
    }
    BackHandler(enabled = sheetOpen) {
        onCancelSheet()
    }
    val showBar = navigator.current in AppRoutes.TABS
    CompositionLocalProvider(LocalDipTabBar provides showBar) {
        if (!showBar) {
            RouteBody(navigator, home, settings, scorecard, data, history, signalHistory, chart, live, more, realMoney)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Box(Modifier.weight(1f)) {
                    RouteBody(navigator, home, settings, scorecard, data, history, signalHistory, chart, live, more, realMoney)
                }
                DipBottomBar(
                    current = navigator.current,
                    onSelect = navigator::selectTab,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun RouteBody(
    navigator: AppNavigator,
    home: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    scorecard: @Composable () -> Unit,
    data: @Composable () -> Unit,
    history: @Composable () -> Unit,
    signalHistory: @Composable () -> Unit,
    chart: @Composable () -> Unit,
    live: @Composable () -> Unit,
    more: @Composable () -> Unit,
    realMoney: @Composable () -> Unit
) {
    when (navigator.current) {
        AppRoutes.SETTINGS -> settings()
        AppRoutes.SCORECARD -> scorecard()
        AppRoutes.DATA -> data()
        AppRoutes.HISTORY -> history()
        AppRoutes.SIGNAL_HISTORY -> signalHistory()
        AppRoutes.CHART -> chart()
        AppRoutes.LIVE -> live()
        AppRoutes.REAL_MONEY -> realMoney()
        AppRoutes.MORE -> more()
        else -> home()
    }
}
