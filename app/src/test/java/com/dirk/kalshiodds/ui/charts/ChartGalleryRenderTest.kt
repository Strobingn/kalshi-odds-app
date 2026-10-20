package com.dirk.kalshiodds.ui.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Renders each gallery chart with empty input and with a sample. FieldOps dark. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChartGalleryRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sideBarsEmpty() {
        show { StrategySideBars(emptyList()) }
        compose.onNodeWithTag("chart_side_bars").assertExists()
        compose.onNodeWithText("UP and DOWN by strategy").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun sideBarsSample() {
        show {
            StrategySideBars(
                listOf(
                    ChartGallery.SideGroup("v2 · Fair-gap", 4, 2, 3.25, -1.10),
                    ChartGallery.SideGroup("v2 · Dip-hunter", 1, 3, -0.40, 0.80)
                )
            )
        }
        compose.onNodeWithText("v2 · Fair-gap").assertExists()
        compose.onNodeWithText("UP 4 · +$3.25").assertExists()
        compose.onNodeWithText("DOWN 2 · −$1.10").assertExists()
        compose.onNodeWithText("UP 1 · −$0.40").assertExists()
        compose.onNodeWithText("DOWN 3 · +$0.80").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
                Column { content() }
            }
        }
        compose.waitForIdle()
    }
}
