package com.dirk.kalshiodds.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * In-app back stack. [MainActivity] used to flip a single `screen` string with
 * no [androidx.activity.compose.BackHandler], so system Back finished the
 * activity from every sub-screen.
 */
object AppRoutes {
    const val HOME = "odds"
    const val SETTINGS = "settings"
    const val SCORECARD = "scorecard"
    const val DATA = "data"
    const val HISTORY = "history"
    const val SIGNAL_HISTORY = "signal-history"
    const val CHART = "chart"
    const val LIVE = "live"
    const val MORE = "more"
    const val REAL_MONEY = "real-money"
    const val CALIBRATION = "calibration"
    const val LADDER = "ladder"
    const val SCALP = "scalp"
    const val SCALP_DATA = "scalp-data"
    const val CRASH_LOG = "crash-log"

    /** FieldOps bottom bar, left to right. */
    val TABS: List<String> = listOf(HOME, SCORECARD, LIVE, REAL_MONEY, DATA, MORE)

    val ALL: List<String> = listOf(
        HOME, SETTINGS, SCORECARD, DATA, HISTORY, SIGNAL_HISTORY, CHART, LIVE, REAL_MONEY, MORE,
        CALIBRATION, LADDER, SCALP, SCALP_DATA, CRASH_LOG
    )
}

class AppNavigator(initial: List<String> = listOf(AppRoutes.HOME)) {
    private val _stack: SnapshotStateList<String> = mutableStateListOf<String>().also { dest ->
        val seed = initial.ifEmpty { listOf(AppRoutes.HOME) }
        dest.addAll(seed)
    }

    val stack: List<String> get() = _stack.toList()
    val current: String get() = _stack.last()
    val canPop: Boolean get() = _stack.size > 1

    fun open(route: String) {
        require(route in AppRoutes.ALL) { "unknown route $route" }
        if (_stack.last() == route) return
        _stack.add(route)
    }

    /** Bottom-tab switch. The selected tab becomes the only root. */
    fun selectTab(route: String) {
        require(route in AppRoutes.TABS) { "not a tab $route" }
        if (_stack.size == 1 && _stack.last() == route) return
        _stack.clear()
        _stack.add(route)
    }

    /** @return true if a screen was popped; false on home (caller may finish). */
    fun back(): Boolean {
        if (_stack.size <= 1) return false
        _stack.removeAt(_stack.lastIndex)
        return true
    }

    fun isHome(): Boolean = current == AppRoutes.HOME && !canPop

    companion object {
        val Saver = listSaver<AppNavigator, String>(
            save = { it.stack },
            restore = { AppNavigator(it) }
        )
    }
}
