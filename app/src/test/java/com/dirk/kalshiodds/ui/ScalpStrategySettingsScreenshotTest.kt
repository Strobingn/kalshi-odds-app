package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.*
import org.junit.Rule
import org.junit.Test

class ScalpStrategySettingsScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")
    @Test fun strategySettingsDark() = shot(true)
    @Test fun strategySettingsLight() = shot(false)
    private fun shot(dark: Boolean) { paparazzi.snapshot(name = "strategy_slider_settings_${if (dark) "dark" else "light"}") {
        KalshiOddsTheme(darkTheme = dark, colorStyle = ColorStyles.CLASSIC) {
            ScalpStrategySettingsContent(ScalpModels.Model.V2_SF41, ScalpControls(strategySettings = mapOf("v2-sf41" to ScalpStrategySettings(untilMinute = 10, contracts = 10, cadenceSeconds = 10))))
        }
    } }
}
