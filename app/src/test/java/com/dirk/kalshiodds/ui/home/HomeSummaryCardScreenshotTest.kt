package com.dirk.kalshiodds.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** Renders the Home paper cards. testDebugUnitTest records the PNG; it does not diff goldens. */
class HomeSummaryCardScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 1600),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun fieldOpsDark() = paparazzi.snapshot(name = "home_summary_fieldops_dark") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(DipTheme.colors.bg)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HomeBestScalperCard(best24 = BEST_24, best7 = BEST_7)
                HomeDailySummaryCard(summary = TODAY)
            }
        }
    }

    private companion object {
        val BEST_24 = RankedScalper("sf42", "sf42", netUsd = 12.40, wins = 8, trades = 12)
        val BEST_7 = RankedScalper("sf41", "sf41", netUsd = -3.10, wins = 4, trades = 10)
        val TODAY = DailySummary(
            dayEt = "2026-10-18",
            netUsd = 4.25,
            trades = 6,
            wins = 4,
            best = RankedScalper("sf42", "sf42", netUsd = 8.10, wins = 3, trades = 4),
            worst = RankedScalper("sf01", "sf01", netUsd = -2.40, wins = 0, trades = 2),
            settings = listOf(SettingsChange(createdAtMs = 1L, key = "greenContracts", oldValue = "10", newValue = "20")),
            settingsTracked = true
        )
    }
}
