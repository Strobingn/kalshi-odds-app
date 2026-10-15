package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.scalp.*
import org.junit.Rule
import org.junit.Test

/** Synthetic fixtures exercise the real Compose dashboard; not trading-performance evidence. */
class PaperScalpScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar")

    @Test fun emptyPhone() {
        paparazzi.snapshot(name = "paper_empty_phone") { PaperScalpScreen(PaperScalpState(), {}, {}, {}) }
    }
    @Test fun populatedTall() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 6000))
        paparazzi.snapshot(name = "paper_dashboard_populated") { PaperScalpScreen(fixture(), {}, {}, {}) }
    }
    @Test fun narrowLargeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenWidth = 945, screenHeight = 2400, fontScale = 1.3f))
        paparazzi.snapshot(name = "paper_narrow_font13") { PaperScalpScreen(fixture(), {}, {}, {}) }
    }
    private fun fixture(): PaperScalpState {
        val now = 1_792_100_000_000L
        return PaperScalpState(lastQuoteMs = now, status = "Fixture · simulated book depth", accounts = PaperScalpCatalog.ALL.mapIndexed { i, spec ->
            val change = if (spec.family == "adaptive") 76.0 else -(i + 1) * 11.0
            val values = listOf(0.0, -18.0, -40.0, -15.0, 22.0, 11.0, 44.0, 35.0, change)
            var peak = 20_000.0
            val curve = values.mapIndexed { j, value ->
                peak = maxOf(peak, 20_000 + value)
                PaperCurvePoint(now - (values.lastIndex - j) * 15_000, 20_000 + value, value, j * 1.5, peak)
            }
            val trades = listOf(-18.0, 12.0, -9.0, 24.0, 31.0).mapIndexed { j, net ->
                PaperScalpResult("t$j", "KXBTC15M-FIXTURE-${j / 2}", "dip", "YES", 100, .40, net, 3.4, .08, .03, "Profit trailing stop", now - (5 - j) * 5000, policyVersion = 2)
            }
            PaperScalpAccount(spec.id, cash = 20_000 + change - 45.0, realized = change - 5.0,
                fees = 12.0, closed = 5, wins = 3, results = trades, curve = curve,
                policyStartedMs = curve.first().atMs, policyStartEquity = 20_000.0, peakEquity = peak, maxDrawdown = 40.0,
                positions = listOf(PaperScalpPosition("p", "KXBTC15M-FIXTURE", "YES", "dip", 100, 100, .40, 40.0, 1.68,
                    now - 2000, now + 60_000, .48, .39, .47, now, .46, policyVersion = 2,
                    riskDistance = .06, trailDistance = .02, trailArmed = true)))
        })
    }
}
