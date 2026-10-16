package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.decision.ScalpControlSpec
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

class ScalpLabScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar")

    @Test fun darkLab() = lab(true)
    @Test fun lightLab() = lab(false)
    @Test fun emptyLab() = lab(true, empty = true)

    private fun lab(dark: Boolean, empty: Boolean = false) {
        paparazzi.snapshot {
            KalshiOddsTheme(darkTheme = dark) {
                ScalpTabContent(if (empty) ScalpTabState() else ScalpTabFixtures.state(10), initialPage = ScalpLabUi.Page.MODELS)
            }
        }
    }

    @Test fun comparisonAndPausedCardWithLargerText() {
        val state = ScalpTabFixtures.state(10)
        val m = ScalpModels.Model.FAIR_GAP
        paparazzi.snapshot {
            KalshiOddsTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScalpLabModelCard(1, m, state.algoAllTime[m], state.algoAllTime[m],
                            ScalpControlSpec.defaults(m.code).copy(enabled = false)) {}
                        ScalpLabComparison(state.algoAllTime) {}
                    }
                }
            }
        }
    }
}
