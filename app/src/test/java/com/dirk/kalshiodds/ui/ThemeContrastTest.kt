package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.DarkPalette
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.LightPalette
import com.dirk.kalshiodds.ui.theme.ThemeRoles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG AA (4.5:1) for every text/background pair the screens actually
 * paint: OddsScreen, MarketCard, hero / AI / Past / BidChart, buy
 * buttons, paper book, tickets, History, Data, Scorecard, Settings.
 *
 * Compose is not rendered on the JVM (no Robolectric / Paparazzi in
 * this module). These pairs are the same [DipPalette] fields those
 * composables read through [com.dirk.kalshiodds.ui.theme.DipTheme.colors].
 */
class ThemeContrastTest {

    @Test
    fun lightPaletteOnColorsMeetAaAgainstSurfaces() {
        val p = LightPalette
        assertAa("light primary / bg", p.textPrimary, p.bg)
        assertAa("light secondary / bg", p.textSecondary, p.bg)
        assertAa("light primary / surface", p.textPrimary, p.surface)
        assertAa("light secondary / surface", p.textSecondary, p.surface)
        assertAa("light primary / surfaceAlt", p.textPrimary, p.surfaceAlt)
        assertAa("light secondary / surfaceAlt", p.textSecondary, p.surfaceAlt)
        assertAa("light onAccentGreen / green", p.onAccentGreen, p.accentGreen)
        assertAa("light onAccentRed / red", p.onAccentRed, p.accentRed)
        assertAa("light onAccentBlue / blue", p.onAccentBlue, p.accentBlue)
        assertAa("light onAccentOrange / orange", p.onAccentOrange, p.accentOrange)
    }

    @Test
    fun darkPaletteOnColorsMeetAaAgainstSurfaces() {
        val p = DarkPalette
        assertAa("dark primary / bg", p.textPrimary, p.bg)
        assertAa("dark secondary / bg", p.textSecondary, p.bg)
        assertAa("dark primary / surface", p.textPrimary, p.surface)
        assertAa("dark secondary / surface", p.textSecondary, p.surface)
        assertAa("dark primary / surfaceAlt", p.textPrimary, p.surfaceAlt)
        assertAa("dark secondary / surfaceAlt", p.textSecondary, p.surfaceAlt)
        assertAa("dark onAccentGreen / green", p.onAccentGreen, p.accentGreen)
        assertAa("dark onAccentRed / red", p.onAccentRed, p.accentRed)
        assertAa("dark onAccentBlue / blue", p.onAccentBlue, p.accentBlue)
        assertAa("dark onAccentOrange / orange", p.onAccentOrange, p.accentOrange)
    }

    @Test
    fun lightAndDarkScreenRolesMeetAa() {
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for (pair in DipTheme.contrastPairs(dark)) {
                assertAa("$mode ${pair.screen}/${pair.role}", pair.foreground, pair.background)
            }
        }
    }

    @Test
    fun keyComposablesUseThemedPairsInBothSchemes() {
        val expectedScreens = setOf(
            "odds", "card", "button", "paper", "ticket",
            "history", "settings", "data", "scorecard", "chart"
        )
        val found = ThemeRoles.forPalette(LightPalette).map { it.screen }.toSet()
        assertTrue("missing screen roles: ${expectedScreens - found}", found.containsAll(expectedScreens))
        for (dark in listOf(false, true)) {
            val p = DipTheme.palette(dark)
            val pairs = listOf(
                Triple("OddsScreen title", p.textPrimary, p.bg),
                Triple("OddsScreen header UP/DOWN", p.accentGreen, p.bg),
                Triple("MarketCard bid/ask", p.textPrimary, p.surface),
                Triple("MarketCard AI line", p.accentBlue, p.surface),
                Triple("Past arrows UP", p.accentGreen, p.bg),
                Triple("BidChart axis", p.textSecondary, p.surface),
                Triple("UpDownBuyButtons UP", p.onAccentGreen, p.accentGreen),
                Triple("UpDownBuyButtons DOWN", p.onAccentRed, p.accentRed),
                Triple("PaperBookCard", p.accentGreen, p.surface),
                Triple("TradeTicketCard body", p.textPrimary, p.surface),
                Triple("History row", p.textPrimary, p.surfaceAlt),
                Triple("Data stats", p.textPrimary, p.surface),
                Triple("Scorecard title", p.textPrimary, p.bg),
                Triple("Settings hint", p.textSecondary, p.bg)
            )
            for ((name, fg, bg) in pairs) {
                assertAa("${if (dark) "dark" else "light"} $name", fg, bg)
            }
        }
    }

    private fun assertAa(label: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
        val r = Contrast.ratio(fg, bg)
        assertTrue("$label contrast $r < ${Contrast.AA}", r >= Contrast.AA)
    }
}
