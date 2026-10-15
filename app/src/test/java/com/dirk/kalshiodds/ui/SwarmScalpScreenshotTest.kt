package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

class SwarmScalpScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")
    @Test fun overview() { paparazzi.snapshot("scalp_swarm_overview") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { ScalpTabContent(ScalpTabFixtures.state(10)) }
    } }
    @Test fun settings() { paparazzi.snapshot("scalp_swarm_settings") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { SwarmScalpSettingsContent(ScalpControls(manual = true)) }
    } }
}
