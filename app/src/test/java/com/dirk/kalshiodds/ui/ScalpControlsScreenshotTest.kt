package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.decision.ScalpControlSpec
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

class ScalpControlsScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 4800, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar"
    )
    @Test fun controlsDark() = shot(true)
    @Test fun controlsLight() = shot(false)
    private fun shot(dark: Boolean) {
        paparazzi.snapshot(name = if (dark) "scalp_controls_dark" else "scalp_controls_light") {
            KalshiOddsTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ScalpControlsCard("sf41", ScalpControlSpec.cheapEarly("sf41"), {}, {})
                }
            }
        }
    }
}
