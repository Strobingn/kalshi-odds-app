package com.dirk.kalshiodds.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Screen switcher with a real back stack. System Back pops; on home it is
 * not intercepted so the activity finishes. An open ticket/sell sheet wins
 * over screen pop and only cancels — never Approve.
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
    BackHandler(enabled = sheetOpen || navigator.canPop) {
        if (sheetOpen) {
            onCancelSheet()
            return@BackHandler
        }
        navigator.back()
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
