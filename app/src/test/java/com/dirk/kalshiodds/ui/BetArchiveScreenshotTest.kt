package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.archive.BetRow
import com.dirk.kalshiodds.signal.archive.BetRows
import com.dirk.kalshiodds.signal.latefav.LateFavoriteEntry
import com.dirk.kalshiodds.ui.components.FlowFadeCard
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.util.Random

/** JVM screenshots of the bet-archive card and a tracker card with its running P&L (made-up paper bets). */
class BetArchiveScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 1000, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    private val t0 = 1_791_550_800_000L

    private fun entries(n: Int): List<LateFavoriteEntry> {
        val rnd = Random(5)
        return (0 until n).map { i ->
            val at = t0 + i * 900_000L * 3
            val ask = 0.80 + rnd.nextInt(10) / 100.0
            val won = rnd.nextDouble() < 0.86
            val contracts = 5
            val cost = contracts * ask + 0.06
            LateFavoriteEntry(
                ticker = "KXBTC15M-T$i", entryAtMs = at, tteSeconds = 420, z = ask * 100, side = if (i % 2 == 0) "YES" else "NO",
                ask = ask, contracts = contracts, costUsd = cost, feeUsd = 0.06, worseAsk = ask + 0.01,
                result = if (won) "yes" else "no", won = won, pnlUsd = if (won) contracts - cost else -cost, settledAtMs = at + 400_000L
            )
        }
    }

    private fun rows(): List<BetRow> =
        entries(60).map { BetRows.ofTracker(BetRows.CLEAR_LEAD, it) } +
            entries(25).mapIndexed { i, e -> BetRows.ofTracker(BetRows.CENT_BETTER, e.copy(ticker = "KXBTC15M-C$i")) }

    private fun snap(name: String, dark: Boolean) {
        val state = LateFavoriteState(entries = entries(40))
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                Column(Modifier.background(DipTheme.colors.bg).padding(16.dp)) {
                    BetArchiveCard(rows())
                    FlowFadeCard(state, summary = com.dirk.kalshiodds.signal.trend.ClearLeadSummary.of(state))
                }
            }
        }
    }

    @Test
    fun darkArchive() = snap("bet_archive_dark", true)

    @Test
    fun lightArchive() = snap("bet_archive_light", false)
}
