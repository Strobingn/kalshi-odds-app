package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.chart.*
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

object ScalpLiveFixtures {
    fun state(): ScalpTabState {
        val base = ScalpTabFixtures.state(0)
        val ticker = base.selectedTicker!!
        val close = base.markets.first().closeMs!!
        val start = close - 900_000
        val now = start + 700_000
        val points = (0..700).map { sec -> BidPoint(start + sec * 1000, (43 + 7 * sin(sec / 60.0) + sec / 80.0).toFloat(),
            (55 - 7 * sin(sec / 60.0) - sec / 80.0).toFloat(), 100_000 + 50 * sin(sec / 80.0) + sec / 10.0) }
        val pending = ScalpTrade("entry", ticker, "YES", ScalpState.PENDING_ENTRY, start + 99_000, .48, .6,
            ruleVersion = "${ScalpRule.VERSION}|sf41|P")
        val buy = ScalpFillLog.capture(pending.copy(state = ScalpState.OPEN, entryAtMs = start + 102_000,
            entryPrice = .48, contracts = 10, entryFeeUsd = .18), pending, start + 102_000)
        val partial = ScalpFillLog.capture(buy.copy(state = ScalpState.PENDING_EXIT, soldContracts = 4,
            proceedsUsd = 2.0, exitFeeUsd = .07, exitReason = "profit trail"), buy, start + 180_000)
        val sold = ScalpFillLog.capture(partial.copy(state = ScalpState.CLOSED, soldContracts = 10, proceedsUsd = 4.94,
            exitFeeUsd = .18, closedAtMs = start + 200_000, netUsd = -.22), partial, start + 200_000)
        val q = ScalpRule.Quote(ticker, now, close, now - 100, .46, 100.0, .47, 90.0, 100_030.0, 100_000.0, 3e-5)
        return base.copy(secondsLeft = 200, bookAgeMs = 100, window = ScalpWindow(ticker, start, close, now, points, listOf(sold), q))
    }
}

class ScalpLiveChartScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 3000, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar")
    @Test fun darkLive() = shot(true)
    @Test fun lightLive() = shot(false)
    private fun shot(dark: Boolean) = paparazzi.snapshot {
        KalshiOddsTheme(darkTheme = dark) { ScalpTabContent(ScalpLiveFixtures.state()) }
    }
    @Test fun selectedTradeAndLargerText() {
        val state = ScalpLiveFixtures.state()
        paparazzi.snapshot {
            KalshiOddsTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.25f)) {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScalpLiveWindow(state.window, state.window.trades, "entry") {}
                        ScalpWindowTradeCard(state.window.trades.single(), true, state.window.quote) {}
                    }
                }
            }
        }
    }
    @Test fun staleAndEmptyDoesNotInventAPath() = paparazzi.snapshot {
        KalshiOddsTheme(darkTheme = true) { ScalpTabContent(ScalpTabState()) }
    }
}
