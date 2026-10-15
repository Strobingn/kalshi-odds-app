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
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { val original = ScalpTabFixtures.state(10)
            val v2 = original.algoAllTime.values.associate { row -> val m = com.dirk.kalshiodds.decision.ScalpModels.v2Of(row.model)!!; m to row.copy(model = m) }
            val at = 1_791_850_500_000L
            val windows = (0..8).map { i -> com.dirk.kalshiodds.decision.ScalpModels.WindowRow("w$i", at - (8 - i) * 900_000,
                mapOf(v2.keys.first() to listOf(1.5, -0.5, 2.0, -1.0, .25, 1.2, -.75, 1.5, .5)[i]), emptyMap(), null) }
            ScalpTabContent(original.copy(algoAllTime = v2, algoToday = v2, windows = windows, updatedAtMs = at)) }
    } }
    @Test fun settings() { paparazzi.snapshot("scalp_swarm_settings") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { SwarmScalpSettingsContent(ScalpControls(manual = true)) }
    } }
}
