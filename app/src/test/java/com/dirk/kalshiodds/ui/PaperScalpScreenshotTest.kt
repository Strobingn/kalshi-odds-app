package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.scalp.*
import org.junit.Rule
import org.junit.Test

/** Synthetic rendering fixtures only. No screenshot is evidence of a strategy's profitability. */
class PaperScalpScreenshotTest {
    private val renderTime = 1_792_035_000_000L
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar")
    @Test fun emptyPhone() = paparazzi.snapshot(name = "performance_empty") { PaperScalpScreen(PaperScalpState(), {}, {}, {}, clock = { renderTime }) }
    @Test fun populatedPhone() = paparazzi.snapshot(name = "performance_phone") { PaperScalpScreen(fixture(), {}, {}, {}, clock = { renderTime }) }
    @Test fun populatedTall() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 5400))
        paparazzi.snapshot(name = "performance_full") { PaperScalpScreen(fixture(), {}, {}, {}, clock = { renderTime }) }
    }
    @Test fun narrowLargeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenWidth = 945, screenHeight = 2400, fontScale = 1.3f))
        paparazzi.snapshot(name = "performance_large_font") { PaperScalpScreen(fixture(), {}, {}, {}, clock = { renderTime }) }
    }
    @Test fun openPositions() = paparazzi.snapshot(name = "position_detail") { PaperScalpScreen(fixture(), {}, {}, {}, initialTab = 1, clock = { renderTime }) }
    @Test fun tradeLedger() = paparazzi.snapshot(name = "trade_ledger") { PaperScalpScreen(fixture(), {}, {}, {}, initialTab = 2, clock = { renderTime }) }
    @Test fun missingFeedGap() {
        val state = fixture().let { it.copy(accounts = it.accounts.map { a -> a.copy(curve = a.curve.filterIndexed { i, _ -> i !in 14..22 }) }) }
        paparazzi.snapshot(name = "performance_feed_gap") { PaperScalpScreen(state, {}, {}, {}, clock = { renderTime }) }
    }
    @Test fun quantComparisonNarrow() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenWidth = 945, screenHeight = 1800, fontScale = 1.3f))
        paparazzi.snapshot(name = "quant_comparison") {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xFF101010), contentColor = androidx.compose.ui.graphics.Color.White) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) {
                        androidx.compose.material3.Text("Synthetic render fixture", color = androidx.compose.ui.graphics.Color.LightGray)
                        QuantComparison(PaperQuantModel(testWindows = 30, selectedWindows = 12, baseline = -.035, filtered = -.012,
                            regimes = listOf(PaperQuantRegime("<5 min", 20, -.03, -.01))))
                    }
                }
            }
        }
    }
    private fun fixture(): PaperScalpState {
        val now = renderTime
        return PaperScalpState(lastQuoteMs = now, status = "Synthetic render fixture", monitoring = true,
            recorderNote = "Replay recording on · synthetic fixture", accounts = PaperScalpCatalog.ALL.map { spec ->
            val f = PaperPerformance.families.indexOfFirst { it.first == spec.family }
            var peak = 20000.0
            val values = (0..40).map { j -> -55.0 + j * (f - 1.2) * 1.7 + kotlin.math.sin(j / 3.0 + f) * (15 + f * 4) }
            val curve = values.mapIndexed { j, value ->
                peak = maxOf(peak, 20000 + value)
                PaperCurvePoint(now - (values.lastIndex - j) * 15000, 20000 + value, value, j * .7, peak, if (j < 10) 2 else 3)
            }
            val trades = listOf(-8.0, 12.0, -9.0, -14.0, 21.0).mapIndexed { j, net ->
                PaperScalpResult("t$j", "KXBTC15M-FIXTURE-${j / 2}", "cheap", "YES", 100, .40, net, 3.4, .08, .03,
                    if (net > 0) "Planned target reached" else "Initial protective stop", now - (5 - j) * 15000, 3)
            }
            PaperScalpAccount(spec.id, cash = curve.last().equity - 45.25, realized = trades.sumOf { it.netUsd },
                fees = 17.0, closed = 5, wins = 2, results = trades, curve = curve,
                policyStartedMs = curve[10].atMs, policyStartEquity = curve[10].equity, policyStartRealized = -10.0,
                policyStartFees = 7.0, peakEquity = peak, maxDrawdown = 50.0, policyVersion = 3,
                note = when (f) { 0 -> "Exploring small · 5 closes across 3 windows"; 1 -> "Waiting for a recovery toward the observed range"; 2 -> "Loss cooldown · protecting the remaining cash"; 3 -> "Trend signal rejected: reward is too small after fees"; else -> "Forecast below the required reward/risk threshold" },
                positions = listOf(PaperScalpPosition("p", "KXBTC15M-FIXTURE", "YES", "cheap", 100, 100, .40, 40.0, 1.68,
                    now - 2000, now + 60000, .54, .39, .47, now, .52, exitReason = "Profit trailing stop", triggerMs = now - 100,
                    triggerRevision = 1, policyVersion = 3, riskDistance = .04, trailDistance = .02, trailArmed = true,
                    targetBid = .62, plannedRiskUsd = 7.3)))
        })
    }
}
