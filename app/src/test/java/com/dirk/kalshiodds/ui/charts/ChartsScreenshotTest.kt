package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** Renders every chart with data and empty. testDebugUnitTest records PNGs; it does not diff goldens. */
class ChartsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 2400),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun withData() = paparazzi.snapshot(name = "charts_fieldops_dark_data") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
            Column(Modifier.fillMaxWidth().background(DipTheme.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PnlLineChart(listOf(0.0, 1.2, 0.4, -0.8, 1.9, 3.4, 2.7, 4.1))
                PnlSparkline(listOf(0.0, -0.4, -1.1, -0.6, -1.8))
                WinLossBars(listOf(WinLossRow("AI picks", 14, 9), WinLossRow("Manual", 3, 5), WinLossRow("Autopilot", 7, 2)))
                WinRateGauge(0.62, 40, breakEven = 0.55)
                RankedNetBars(listOf(NetBar("Peak ride", 12.84), NetBar("Fade", -4.5), NetBar("Trail", 3.42), NetBar("Shock", -1.25)))
                DailyNetBars(listOf(DayNet("10/13", 2.1), DayNet("10/14", -1.4), DayNet("10/15", 0.0), DayNet("10/16", 3.8), DayNet("10/17", -0.6)))
                ShareDonut(listOf(DonutSlice("UP", 58.0, DipTheme.colors.upButton), DonutSlice("DOWN", 42.0, DipTheme.colors.downButton)))
            }
        }
    }

    @Test fun empty() = paparazzi.snapshot(name = "charts_fieldops_dark_empty") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
            Column(Modifier.fillMaxWidth().background(DipTheme.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PnlLineChart(emptyList())
                PnlSparkline(listOf(1.0))
                WinLossBars(listOf(WinLossRow("AI picks", 0, 0)))
                WinRateGauge(null, 0)
                RankedNetBars(emptyList())
                DailyNetBars(emptyList())
                ShareDonut(listOf(DonutSlice("UP", 0.0, DipTheme.colors.upButton)))
            }
        }
    }
}
