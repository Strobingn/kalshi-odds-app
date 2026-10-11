package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalperEngine
import com.dirk.kalshiodds.signal.scalper.ScalperLedger
import com.dirk.kalshiodds.signal.scalper.ScalperState
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.util.Random

/** JVM screenshots of the scalper results screen (made-up paper scalps through the real engine and ledger). */
class ScalperResultsScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        // One pixel per dp, so the report's 1,000-pixel images are full size and readable.
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 1000, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    private fun fixture(): Pair<ScalperState, List<ScalpTrade>> {
        val trades = ArrayList<ScalpTrade>()
        // 2026-10-09 13:00 UTC; the hour label uses UTC so the picture does not depend on the machine's zone.
        val t0 = 1_791_550_800_000L
        val ledger = ScalperLedger(
            hourOf = { ((it / 3_600_000L) % 24).toInt() },
            record = { a, _ -> trades += a },
            run = 1L
        )
        val e = ScalperEngine()
        val rnd = Random(11)
        val none: (String, Double) -> Double? = { _, _ -> null }
        val ticker = "KXBTC15M-26OCT091315-15"
        for (i in 0 until 160) {
            val at = t0 + i * 95_000L
            val st = ScalpStrategy.values()[rnd.nextInt(ScalpStrategy.values().size)]
            val side = if (rnd.nextBoolean()) "YES" else "NO"
            val yes = side == "YES"
            val p = (30 + rnd.nextInt(40)) / 100.0
            if (st.taker) {
                ledger.apply(e.buyNow(st, ticker, side, p, none, at))
            } else {
                ledger.apply(listOfNotNull(e.post(st, ticker, side, p, 80.0, 0.4, 0.2, at)))
                // A taker sells our side through our bid: filled.
                ledger.apply(e.onTrade(ticker, !yes, if (yes) p - 0.01 else 1.0 - (p - 0.01), 20.0, at + 2_000L, none))
            }
            if (rnd.nextDouble() < (if (st.taker) 0.42 else 0.74)) {
                val hit = p + st.targetDollars + 0.01
                ledger.apply(e.onTrade(ticker, yes, if (yes) hit else 1.0 - hit, 20.0, at + 9_000L, none))
            } else {
                val bid = p - st.stopDollars
                ledger.apply(e.onClock(ticker, at + 12_000L, if (yes) bid else null, if (yes) null else bid))
            }
        }
        return ledger.snapshot() to trades.asReversed()
    }

    @Test
    fun darkTop() = snap("scalper_results_dark_top", true, 0)

    @Test
    fun darkBreakdowns() = snap("scalper_results_dark_breakdowns", true, 3)

    @Test
    fun darkTrades() = snap("scalper_results_dark_trades", true, 6)

    @Test
    fun lightTop() = snap("scalper_results_light_top", false, 0)

    @Test
    fun empty() {
        paparazzi.snapshot(name = "scalper_results_empty") {
            KalshiOddsTheme(darkTheme = true) {
                ScalperResultsScreen(state = ScalperState.fresh(), trades = emptyList(), onBack = {})
            }
        }
    }

    private fun snap(name: String, dark: Boolean, firstItem: Int) {
        val (state, trades) = fixture()
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                ScalperResultsScreen(
                    state = state, trades = trades.take(40), onBack = {},
                    listState = androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = firstItem)
                )
            }
        }
    }
}
