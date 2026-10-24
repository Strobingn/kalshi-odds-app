package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.*
import org.junit.Rule
import org.junit.Test

class ScalpWindowScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")
    private fun detail(): ScalpAlgoDetail {
        val ticker = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 900_000
        val bought = ScalpTrade("t", ticker, "YES", ScalpState.OPEN, start + 150_000, .38, .5, contracts = 10,
            entryPrice = .40, entryAtMs = start + 152_000, entryFeeUsd = .17, ruleVersion = "v|v2-sf41|P", note = "v2peakrule=1 · v2stopPct=20 · v2trail=.03")
        val sold = ScalpFills.sell(bought, start + 450_000, 10, 6.50, .16).copy(state = ScalpState.CLOSED, closedAtMs = start + 450_000, netUsd = 2.17, exitReason = "3¢ trail")
        val live = bought.copy(id = "open", signalAtMs = start + 490_000, entryAtMs = start + 492_000, signalAsk = .44, entryPrice = .44)
        val prices = listOf(42f, 37f, 40f, 45f, 54f, 63f, 68f, 65f, 50f, 44f, 46f)
        val points = (0..540).map { i -> val p = prices[minOf(i / 54, prices.lastIndex)]; BidPoint(start + i * 1000L, p, 99f-p) }
        return ScalpAlgoDetail(model = ScalpModels.Model.V2_SF41, chart = ScalpWindowChartData(ticker, close, start + 540_000, start + 540_000, points, listOf(sold, live)),
            chartMarkets = listOf(ScalpTabMarket(ticker, "BTC", close, "BTC · 8:00–8:15 PM")))
    }
    @Test fun liveIndividualStrategy() { paparazzi.snapshot("scalp_live_strategy_window") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { ScalpAlgoContent(detail()) }
    } }
    @Test fun waitingWindow() { paparazzi.snapshot("scalp_live_strategy_waiting") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) { ScalpAlgoContent(ScalpAlgoDetail(model = ScalpModels.Model.V2_SF41)) }
    } }

    @Test fun settleExtremesKeepLastPricesReadable() {
        val ticker = "KXBTC15M-26OCT241300-00"
        val close = ScalpTicker.closeMs(ticker) ?: (1_700_000_000_000L + 900_000L)
        val start = close - 900_000L
        val points = (0..90).map { i ->
            val up = 42f + (57.9f * i / 90f)
            BidPoint(start + i * 10_000L, up, 100f - up)
        }
        val buy = ScalpTrade(
            "up", ticker, "YES", ScalpState.OPEN, start + 120_000L, 0.48, 0.55,
            contracts = 8, entryPrice = 0.48, entryAtMs = start + 130_000L,
            ruleVersion = "v|v2-sf41|P"
        )
        val sell = ScalpFills.sell(buy.copy(id = "dn", side = "NO", signalAsk = 0.40, entryPrice = 0.40), start + 400_000L, 4, 1.2, 0.04)
            .copy(state = ScalpState.CLOSED, closedAtMs = start + 400_000L, netUsd = -1.05)
        val chart = ScalpWindowChartData(ticker, close, close, close, points, listOf(buy, sell))
        paparazzi.snapshot("scalp_window_settle_extremes") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
                ScalpWindowChart(
                    ScalpAlgoDetail(model = ScalpModels.Model.V2_SF41, chart = chart, followLive = true),
                    title = "Active window"
                )
            }
        }
    }
}
