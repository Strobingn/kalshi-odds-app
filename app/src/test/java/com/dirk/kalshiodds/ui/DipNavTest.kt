package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DipNavTest {
    @Test
    fun fiveTabsMatchFieldOpsOrder() {
        assertEquals(
            listOf(
                AppRoutes.HOME,
                AppRoutes.SCORECARD,
                AppRoutes.LIVE,
                AppRoutes.DATA,
                AppRoutes.MORE
            ),
            DipNav.tabs
        )
        assertEquals(listOf("Home", "Scorecard", "Live", "Data", "More"), DipNav.tabs.map { DipNav.tabLabel.getValue(it) })
    }

    @Test
    fun everyRouteIsAtMostTwoTapsFromATab() {
        for (route in AppRoutes.ALL) {
            val path = DipNav.howToReach(route)
            val taps = DipNav.tapsFromTab(path)
            assertTrue("$route path '$path' is $taps taps", taps <= DipNav.MAX_TAPS_FROM_TAB)
        }
    }

    @Test
    fun moreKeepsSettingsAndHistoryAsSeparateActions() {
        val labels = DipNav.primaryActions.map { it.label }
        assertEquals(listOf("Settings", "History"), labels)
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.SIGNAL_HISTORY })
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.SCORECARD })
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.DATA })
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.LIVE })
    }
}
