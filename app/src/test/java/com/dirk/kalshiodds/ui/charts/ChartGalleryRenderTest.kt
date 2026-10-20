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

    @Test fun winHeatEmpty() {
        show { WinRateHeatmap(null) }
        compose.onNodeWithTag("chart_win_heat").assertExists()
        compose.onNodeWithText("Win rate by hour and coin").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun winHeatSample() {
        val row = (0 until 24).map { h ->
            ChartGallery.HeatCell("BTC", ChartShapes.hourLabel(h), if (h == 15) 0.75 else null, if (h == 15) 4 else 0)
        }
        val eth = (0 until 24).map { h ->
            ChartGallery.HeatCell("ETH", ChartShapes.hourLabel(h), if (h == 15) 0.25 else null, if (h == 15) 4 else 0)
        }
        show { WinRateHeatmap(ChartGallery.HeatGrid(listOf("BTC", "ETH"), (0 until 24).map { ChartShapes.hourLabel(it) }, listOf(row, eth))) }
        compose.onNodeWithText("BTC").assertExists()
        compose.onNodeWithText("ETH").assertExists()
        compose.onNodeWithText("BTC 3p · 75% · 4 trades").assertExists()
        compose.onNodeWithText("12a").assertExists()
        compose.onNodeWithText("12p").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun knobsEmpty() {
        show { KnobTimeline(emptyList()) }
        compose.onNodeWithTag("chart_knobs").assertExists()
        compose.onNodeWithText("Tuner knobs").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun knobsSample() {
        show {
            KnobTimeline(
                listOf(
                    ChartGallery.KnobTick(1_700_000_000_000, "v2-dip", "maxEntryCents", "50", "48"),
                    ChartGallery.KnobTick(1_700_000_060_000, "v2-fair", "stopCents", "20", "19")
                )
            )
        }
        compose.onNodeWithText("v2-dip · maxEntryCents").assertExists()
        compose.onNodeWithText("v2-fair · stopCents").assertExists()
        compose.onNodeWithText("50 → 48", substring = true).assertExists()
        compose.onNodeWithText("20 → 19", substring = true).assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun waterfallEmpty() {
        show { FeeWaterfall(null, null) }
        compose.onNodeWithTag("chart_waterfall").assertExists()
        compose.onNodeWithText("Fees").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun waterfallSample() {
        show {
            FeeWaterfall(
                ChartGallery.FeeLeg("Window", 4.0, 0.40, 3.60),
                ChartGallery.FeeLeg("Day", -1.0, 0.25, -1.25)
            )
        }
        compose.onNodeWithText("Window gross +$4.00 · fees −$0.40 · net +$3.60").assertExists()
        compose.onNodeWithText("Day gross −$1.00 · fees −$0.25 · net −$1.25").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun calibrationEmpty() {
        show { CalibrationPlot(emptyList()) }
        compose.onNodeWithTag("chart_calibration").assertExists()
        compose.onNodeWithText("AI UP calibration").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun calibrationSample() {
        show {
            CalibrationPlot(
                listOf(
                    ChartGallery.CalPoint("40–50%", 43.0, 50.0, 4),
                    ChartGallery.CalPoint("90–100%", 92.0, 40.0, 5)
                )
            )
        }
        compose.onNodeWithText("40–50% · predicted 43% · settled 50% · 4").assertExists()
        compose.onNodeWithText("90–100% · predicted 92% · settled 40% · 5").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun depthEmpty() {
        show { CumulativeDepthChart(emptyList()) }
        compose.onNodeWithTag("chart_depth").assertExists()
        compose.onNodeWithText("Order book depth").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun depthSample() {
        val series = ChartGallery.cumulativeDepth(listOf(0.60 to 10.0, 0.50 to 5.0), listOf(0.40 to 8.0))
        show { CumulativeDepthChart(series) }
        compose.onNodeWithText("UP bid").assertExists()
        compose.onNodeWithText("UP ask").assertExists()
        compose.onNodeWithText("DOWN bid").assertExists()
        compose.onNodeWithText("DOWN ask").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun familyStackEmpty() {
        show { FamilyStackChart(null) }
        compose.onNodeWithTag("chart_family_stack").assertExists()
        compose.onNodeWithText("Cumulative P&L by family").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun familyStackSample() {
        val model = ChartGallery.familyStacks(
            listOf(
                ChartGallery.FamilyEvent(10, "AI alert", 2.0),
                ChartGallery.FamilyEvent(20, "manual paper", -0.5)
            )
        )
        show { FamilyStackChart(model) }
        compose.onNodeWithText("AI alert +$2.00", substring = true).assertExists()
        compose.onNodeWithText("manual paper −$0.50", substring = true).assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun rollingEmpty() {
        show { RollingWinRateChart(emptyList()) }
        compose.onNodeWithTag("chart_rolling").assertExists()
        compose.onNodeWithText("Rolling 30-trade win rate").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun rollingSample() {
        show { RollingWinRateChart(listOf(ChartShapes.Sample(1_700_000_000_000, 40.0), ChartShapes.Sample(1_700_000_060_000, 63.0))) }
        compose.onNodeWithText("Win rate").assertExists()
        compose.onNodeWithText("50%").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun histogramEmpty() {
        show { PnlHistogram(emptyList()) }
        compose.onNodeWithTag("chart_pnl_hist").assertExists()
        compose.onNodeWithText("P&L histogram").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun histogramSample() {
        show {
            PnlHistogram(
                listOf(
                    ChartGallery.SignedBin("≤ −$2", 1, -1.0),
                    ChartGallery.SignedBin("±25¢", 4, 0.0),
                    ChartGallery.SignedBin("≥ $2", 2, 1.0)
                )
            )
        }
        compose.onNodeWithText("±25¢ · 4").assertExists()
        compose.onNodeWithText("≤ −$2 · 1").assertExists()
        compose.onNodeWithText("≥ $2 · 2").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun scatterEmpty() {
        show { EntryExitScatter(emptyList()) }
        compose.onNodeWithTag("chart_scatter").assertExists()
        compose.onNodeWithText("Entry versus exit").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun scatterSample() {
        show {
            EntryExitScatter(
                listOf(
                    ChartGallery.ScatterDot(40.0, 60.0, win = true),
                    ChartGallery.ScatterDot(55.0, 20.0, win = false)
                )
            )
        }
        compose.onNodeWithText("1 win · 1 loss").assertExists()
        compose.onNodeWithText("Win").assertExists()
        compose.onNodeWithText("Loss").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun correlationEmpty() {
        show { CorrelationHeatmap(null) }
        compose.onNodeWithTag("chart_corr").assertExists()
        compose.onNodeWithText("Daily P&L correlation").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun correlationSample() {
        show {
            CorrelationHeatmap(
                ChartGallery.Correlation(
                    listOf("Fair-gap", "Dip-hunter"),
                    listOf(listOf(1.0, -1.0), listOf(-1.0, 1.0))
                )
            )
        }
        compose.onNodeWithText("Fair-gap").assertExists()
        compose.onNodeWithText("Dip-hunter").assertExists()
        compose.onNodeWithText("Fair-gap × Dip-hunter · −1.00").assertExists()
        compose.onAllNodesWithText("No data yet").assertCountEquals(0)
    }

    @Test fun sparksEmpty() {
        show { EquitySparkGrid(emptyList()) }
        compose.onNodeWithTag("chart_sparks").assertExists()
        compose.onNodeWithText("Equity curves").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun sparksSample() {
        show {
            EquitySparkGrid(
                listOf(
                    ChartGallery.SparkLine("Peak ride", listOf(0.0, 1.2, 2.4), frozen = false),
                    ChartGallery.SparkLine("Fade", listOf(0.0, -1.0, -0.4), frozen = true),
                    ChartGallery.SparkLine("Flat", listOf(0.0), frozen = true)
                )
            )
        }
        compose.onNodeWithText("Peak ride").assertExists()
        compose.onNodeWithText("Fade · FROZEN").assertExists()
        compose.onNodeWithText("Flat · FROZEN").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun leaderboardEmpty() {
        show { StrategyLeaderboard(emptyList()) }
        compose.onNodeWithTag("chart_leaderboard").assertExists()
        compose.onNodeWithText("Strategy leaderboard").assertExists()
        compose.onNodeWithText("No data yet").assertExists()
    }

    @Test fun leaderboardSampleKeepsFrozen() {
        show {
            StrategyLeaderboard(
                listOf(
                    ChartGallery.LeaderRow("Fade", -4.5, true),
                    ChartGallery.LeaderRow("Peak ride", 12.84, false),
                    ChartGallery.LeaderRow("Flat", 0.0, true)
                )
            )
        }
        compose.onNodeWithText("Peak ride").assertExists()
        compose.onNodeWithText("Fade · FROZEN").assertExists()
        compose.onNodeWithText("Flat · FROZEN").assertExists()
        compose.onNodeWithText("+$12.84").assertExists()
        compose.onNodeWithText("−$4.50").assertExists()
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
