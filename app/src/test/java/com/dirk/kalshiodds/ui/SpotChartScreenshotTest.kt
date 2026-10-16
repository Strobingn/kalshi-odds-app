package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.ui.components.BidChart
import com.dirk.kalshiodds.ui.theme.*
import org.junit.Rule
import org.junit.Test

class SpotChartScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 1500, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")
    private fun snapshot(name: String, dark: Boolean, style: String) {
        val end = 1_791_850_500_000L
        val points = (0..20).map { i -> BidPoint(end - 900_000 + i * 30_000, 40f + i * 2, 59f - i * 2, 82_160.0 + i * 4) }
        paparazzi.snapshot(name) {
            KalshiOddsTheme(darkTheme = dark, colorStyle = style) {
                Surface { BidChart(points, modifier = Modifier.padding(16.dp), heightDp = 190, spotHeightDp = 180,
                    scrub = true, windowStartMs = end - 900_000, windowEndMs = end,
                    spotUsd = 82_240.0, strikeUsd = 82_180.0) }
            }
        }
    }
    @Test fun classicDark() = snapshot("spot_orange_classic_dark", true, ColorStyles.CLASSIC)
    @Test fun fieldOpsDark() = snapshot("spot_orange_fieldops_dark", true, ColorStyles.FIELDOPS)
    @Test fun classicLight() = snapshot("spot_orange_classic_light", false, ColorStyles.CLASSIC)
}
