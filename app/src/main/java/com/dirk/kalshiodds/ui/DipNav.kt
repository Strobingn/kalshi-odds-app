package com.dirk.kalshiodds.ui

/**
 * FieldOps navigation for DipHunter. Five tabs, everything else one tap from
 * More (or one tap from the list that owns it). No screen is dropped.
 */
data class MoreDestination(
    val route: String,
    val group: String,
    val label: String,
    val blurb: String,
    /** True when the row is also a bottom tab. Opening it switches tabs. */
    val tab: Boolean
)

object DipNav {
    const val MAX_TAPS_FROM_TAB = 2

    val tabs: List<String> = AppRoutes.TABS

    val tabLabel: Map<String, String> = mapOf(
        AppRoutes.HOME to "Home",
        AppRoutes.SCORECARD to "Scorecard",
        AppRoutes.LIVE to "Live",
        AppRoutes.REAL_MONEY to "Real Money",
        AppRoutes.DATA to "Data",
        AppRoutes.MORE to "More"
    )

    val moreDestinations: List<MoreDestination> = listOf(
        MoreDestination(
            AppRoutes.LIVE,
            "Today",
            "Live Approve",
            "Tickets wait for Approve and the REAL MONEY confirm",
            tab = true
        ),
        MoreDestination(
            AppRoutes.REAL_MONEY,
            "Today",
            "Real Money",
            "Kalshi cash, Approve, and real orders — never automatic",
            tab = true
        ),
        MoreDestination(
            AppRoutes.SCORECARD,
            "Records",
            "Scorecard",
            "Every settled call stays on the card",
            tab = true
        ),
        MoreDestination(
            AppRoutes.HISTORY,
            "Records",
            "History",
            "Settled windows and their charts",
            tab = false
        ),
        MoreDestination(
            AppRoutes.SIGNAL_HISTORY,
            "Records",
            "Signal history",
            "Recent alerts",
            tab = false
        ),
        MoreDestination(
            AppRoutes.CALIBRATION,
            "Records",
            "Calibration report",
            "Reliability buckets, Brier and logloss vs the market",
            tab = false
        ),
        MoreDestination(
            AppRoutes.LADDER,
            "Records",
            "Strategy ladder",
            "v060 · v150 · fav15: paper → shadow → limited live",
            tab = false
        ),
        MoreDestination(
            AppRoutes.DATA,
            "Data",
            "Data",
            "Import, backfill, and the offline model",
            tab = true
        ),
        MoreDestination(
            AppRoutes.SETTINGS,
            "App",
            "Settings",
            "Keys, paper mode, and updates",
            tab = false
        )
    )

    val groupsInOrder: List<String> = moreDestinations.map { it.group }.distinct()

    /** Primary More actions. They stay separate; neither is folded into the other. */
    val primaryActions: List<MoreDestination> = listOf(
        moreDestinations.first { it.route == AppRoutes.SETTINGS },
        moreDestinations.first { it.route == AppRoutes.HISTORY }
    )

    fun howToReach(route: String): String = when (route) {
        AppRoutes.HOME -> "Home tab"
        AppRoutes.SCORECARD -> "Scorecard tab"
        AppRoutes.LIVE -> "Live tab"
        AppRoutes.REAL_MONEY -> "Real Money tab"
        AppRoutes.DATA -> "Data tab"
        AppRoutes.MORE -> "More tab"
        AppRoutes.SETTINGS -> "More → Settings"
        AppRoutes.HISTORY -> "More → History"
        AppRoutes.SIGNAL_HISTORY -> "More → Signal history"
        AppRoutes.CHART -> "Home → a market"
        AppRoutes.CALIBRATION -> "More → Calibration report"
        AppRoutes.LADDER -> "More → Strategy ladder"
        else -> error("No path for $route")
    }

    fun tapsFromTab(path: String): Int = path.split(" → ").size - 1
}
