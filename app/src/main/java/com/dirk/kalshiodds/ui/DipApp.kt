package com.dirk.kalshiodds.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Screen switcher with a real back stack. Official guidance
 * (developer.android.com predictive-back-gesture, Navigation Compose /
 * BackHandler): one enabled [BackHandler] per responsibility, disabled when
 * that UI state is gone so the next callback — or the system — runs.
 *
 * Ticket/sell sheet: cancel only, never Approve. In-app destination: pop.
 * Home with no sheet: neither callback is enabled, so Android 14+ gesture
 * Back finishes the activity (predictive back-to-home).
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
    chart: @Composable () -> Unit
) {
    BackHandler(enabled = navigator.canPop && !sheetOpen) {
        navigator.back()
    }
    BackHandler(enabled = sheetOpen) {
        onCancelSheet()
    }
    when (navigator.current) {
        AppRoutes.SETTINGS -> settings()
        AppRoutes.SCORECARD -> scorecard()
        AppRoutes.DATA -> data()
        AppRoutes.HISTORY -> history()
        AppRoutes.SIGNAL_HISTORY -> signalHistory()
        AppRoutes.CHART -> chart()
        else -> home()
    }
}
