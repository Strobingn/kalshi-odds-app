package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

class ExecutionWindowScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(
        screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")

    @Test fun liveWindowWithExactPartialFills() {
        val end = 1_792_114_200_000L
        val start = end - 900_000
        val data = ExecutionWindowData("KXBTC15M · paper", end, start + 600_000, start + 600_000,
            points = (0..600).map { i -> BidPoint(start + i * 1_000L,
                (38 + (i / 30) % 20).toFloat(), (61 - (i / 30) % 20).toFloat()) },
            executions = listOf(
                ExecutionWindowData.Execution("buy", "position", "YES", start + 100_000, .40, 10, ExecutionWindowData.Kind.BUY, feeUsd = .18),
                ExecutionWindowData.Execution("partial-1", "position", "YES", start + 350_000, .49, 3, ExecutionWindowData.Kind.SELL, feeUsd = .06),
                ExecutionWindowData.Execution("partial-2", "position", "YES", start + 550_000, .55, 7, ExecutionWindowData.Kind.SELL, feeUsd = .13)))
        paparazzi.snapshot("execution_window_live") {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    Text("Live paper window · 15 minutes", color = MaterialTheme.colorScheme.onBackground)
                    ExecutionWindowChart(data)
                }
            }
        }
    }

    @Test fun waitingForRecordedQuotes() { paparazzi.snapshot("execution_window_empty") {
        KalshiOddsTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                ExecutionWindowChart(ExecutionWindowData())
            }
        }
    } }
}
