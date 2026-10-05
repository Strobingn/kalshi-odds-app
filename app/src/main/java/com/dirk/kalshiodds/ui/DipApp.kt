package com.dirk.kalshiodds.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.dirk.kalshiodds.ui.theme.DipTheme

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
    val palette = DipTheme.colors
    val showBottomNavigation = navigator.current != AppRoutes.CHART
    Scaffold(
        containerColor = palette.bg,
        bottomBar = {
            if (showBottomNavigation) {
                AppBottomNavigation(
                    selectedRoute = navigator.current,
                    onSelect = navigator::selectPrimary
                )
            }
        }
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
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
    }
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Mirrors the compact, always-visible six-tab layout in the supplied design.
 * "Signals" and "History" name the actual screens; calling either "Live" or
 * "Real" would imply real-money order execution, which this navigation does
 * not do.
 */
private val bottomDestinations = listOf(
    BottomDestination(AppRoutes.HOME, "Home", Icons.Default.Home),
    BottomDestination(AppRoutes.SCORECARD, "Scorecard", Icons.Default.Assessment),
    BottomDestination(AppRoutes.SIGNAL_HISTORY, "Signals", Icons.Default.Notifications),
    BottomDestination(AppRoutes.HISTORY, "History", Icons.Default.History),
    BottomDestination(AppRoutes.DATA, "Data", Icons.Default.Storage),
    BottomDestination(AppRoutes.SETTINGS, "More", Icons.Default.MoreHoriz)
)

@Composable
private fun AppBottomNavigation(
    selectedRoute: String,
    onSelect: (String) -> Unit
) {
    val palette = DipTheme.colors
    NavigationBar(
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
        tonalElevation = 0.dp
    ) {
        bottomDestinations.forEach { item ->
            NavigationBarItem(
                selected = item.route == selectedRoute,
                onClick = { onSelect(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.accentBlue,
                    selectedTextColor = palette.accentBlue,
                    indicatorColor = palette.accentBlue.copy(alpha = 0.15f),
                    unselectedIconColor = palette.textSecondary,
                    unselectedTextColor = palette.textSecondary
                )
            )
        }
    }
}
