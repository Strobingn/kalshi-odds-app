package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.*
import org.junit.Rule
import org.junit.Test

class KashiArenaScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")
    private fun fixture(): ScalpTabState {
        val original = ScalpTabFixtures.state(0)
        val v2 = ScalpModels.V2_MODELS.mapIndexed { i, m ->
            val net = listOf(12.84, 8.25)[i]
            m to ScalpModels.rowFromStat(ScalpStat(m.code, n = 20 + i, wins = 12, netUsd = net)).copy(losses = 8 + i, winRate = 12.0 / (20 + i))
        }.toMap()
        val ticker = "KXBTC15M-26OCT122015-15"; val close = ScalpTicker.closeMs(ticker)!!; val start = close - 900_000
        val rows = v2.values.mapIndexed { i, r -> r.copy(netUsd = listOf(12.84, 8.25, 3.42, -1.25, -4.5, -8.0)[i], roundTrips = 20 + i, wins = 12, losses = 8 + i, winRate = 12.0 / (20 + i)) }.associateBy { it.model }
        val model = rows.keys.first()
        val bought = ScalpTrade("t", ticker, "YES", ScalpState.OPEN, start + 150_000, .38, .5, contracts = 10, entryPrice = .4, entryAtMs = start + 152_000, entryFeeUsd = .17, ruleVersion = "v|${model.code}|P", note = "v2peakrule=1 · v2stopPct=20 · v2trail=.03")
        val sold = ScalpFills.sell(bought, start + 450_000, 10, 6.50, .16).copy(state = ScalpState.CLOSED, closedAtMs = start + 450_000, netUsd = 2.17, exitReason = "3¢ trail")
        val points = (0..540).map { i -> val price = (40 + 20 * kotlin.math.sin(i / 70.0)).toFloat(); BidPoint(start + i * 1000L, price, 99f-price) }
        val windows = (0..8).map { i -> ScalpModels.WindowRow("w$i", start - (8-i)*900_000, rows.keys.mapIndexed { j, m -> m to (if ((i+j)%3 == 0) -.5 else .8 + j*.1) }.toMap(), emptyMap(), null) }
        return original.copy(algoAllTime = rows, algoToday = rows, windows = windows, updatedAtMs = start + 540_000,
            arenaChart = ScalpWindowChartData(ticker, close, start + 540_000, start + 540_000, points, listOf(sold)))
    }
    private fun shot(name: String, tab: KashiArenaTab, dark: Boolean = true) {
        paparazzi.snapshot(name) { KalshiOddsTheme(darkTheme = dark, colorStyle = ColorStyles.CLASSIC) {
            KashiArenaContent(fixture(), initialTab = tab)
        } }
    }
    @Test fun overviewDark() = shot("claude_arena_overview_dark", KashiArenaTab.OVERVIEW)
    @Test fun overviewLight() = shot("claude_arena_overview_light", KashiArenaTab.OVERVIEW, false)
    @Test fun strategies() = shot("claude_arena_strategies", KashiArenaTab.STRATEGIES)
    @Test fun analysis() = shot("claude_arena_analysis", KashiArenaTab.ANALYSIS)
    @Test fun trades() = shot("claude_arena_trades", KashiArenaTab.TRADES)
}
