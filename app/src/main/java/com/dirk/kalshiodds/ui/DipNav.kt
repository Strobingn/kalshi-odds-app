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
        AppRoutes.SCALP_TAB to "Scalp",
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
            "v060 · v150 · fav15 · scalp: paper → shadow (no live)",
            tab = false
        ),
        MoreDestination(
            AppRoutes.SCALP_TAB,
            "Records",
            "Scalp tab",
            "Six paper scalpers live: leaderboard, per-window winner, paper price ladder",
            tab = true
        ),
        MoreDestination(
            AppRoutes.SCALP_DATA,
            "Records",
            "Scalp Data",
            "Scalping results only: net after fees, wins vs losses, equity curve, every round trip",
            tab = false
        ),
        MoreDestination(
            AppRoutes.SCALP,
            "Records",
            "Scalp (paper)",
            "6 paper scalp models: rules, per-coin params, open scalps",
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
        ),
        MoreDestination(
            AppRoutes.CRASH_LOG,
            "App",
            "Crash log",
            "Saved crashes: view, share or export",
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
        AppRoutes.SCALP_TAB -> "Scalp tab"
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
        AppRoutes.SCALP -> "More → Scalp (paper)"
        AppRoutes.SCALP_DATA -> "More → Scalp Data"
        AppRoutes.CRASH_LOG -> "More → Crash log"
        else -> error("No path for $route")
    }

    fun tapsFromTab(path: String): Int = path.split(" → ").size - 1
}
