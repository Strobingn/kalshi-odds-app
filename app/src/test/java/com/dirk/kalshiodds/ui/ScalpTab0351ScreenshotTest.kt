package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.decision.PriceLadder
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.components.DipBottomBar
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 0.3.51 Scalp tab redesign at 1080×2340 (420 dpi ≈ 411 dp wide). */
class ScalpTab0351ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun scalpTabDarkHolding() = shot("scalp_tab_dark_holding", dark = true, held = 10, tall = false)
    @Test fun scalpTabDarkFlat() = shot("scalp_tab_dark_flat", dark = true, held = 0, tall = false)
    @Test fun scalpTabLadderHolding() = shot("scalp_tab_ladder_holding", dark = true, held = 10, tall = true)
    @Test fun scalpTabLadderFlat() = shot("scalp_tab_ladder_flat", dark = true, held = 0, tall = true)
    @Test fun scalpTabLight() = shot("scalp_tab_light", dark = false, held = 10, tall = false)

    private fun shot(name: String, dark: Boolean, held: Int, tall: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark, colorStyle = ColorStyles.CLASSIC) {
                CompositionLocalProvider(LocalDipTabBar provides true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { ScalpTabContent(st = ScalpTabFixtures.state(held), showCoinCards = !tall) }
                        DipBottomBar(current = AppRoutes.SCALP_TAB, onSelect = {})
                    }
                }
            }
        }
        val dest = File("/opt/cursor/artifacts").apply { mkdirs() }
        File("build/reports/paparazzi/debug/images").listFiles().orEmpty()
            .filter { it.name.contains(name) }.maxByOrNull { it.lastModified() }
            ?.copyTo(File(dest, "$name.png"), overwrite = true)
    }
}

object ScalpTabFixtures {
    private fun row(m: ScalpModels.Model, net: Double, rt: Int, win: Double?) = ScalpModels.Row(
        m, net, rt, ((win ?: 0.0) * rt).toInt(), win, null, null, null, null, 0, 0.0, 0
    )

    fun board(seed: Int): List<ScalpModels.Row> = listOf(
        row(ScalpModels.Model.EXTREME_REVERSION, -2.84 + seed, 11, 0.45),
        row(ScalpModels.Model.MAKER_DIP, 0.0, 0, null),
        row(ScalpModels.Model.FAIR_GAP, -3.86, 9, 0.33),
        row(ScalpModels.Model.CF_REPRICE, -14.52, 14, 0.29),
        row(ScalpModels.Model.MOMENTUM, -68.30, 30, 0.20),
        row(ScalpModels.Model.DIP_HUNTER, -81.05, 41, 0.17)
    ).sortedByDescending { if (it.roundTrips == 0) Double.NEGATIVE_INFINITY else it.netUsd }

    fun state(held: Int): ScalpTabState {
        val close = com.dirk.kalshiodds.decision.ScalpTicker.closeMs("KXBTC15M-26OCT101100-00")!!
        val tickers = listOf("KXBTC15M-26OCT101100-00", "KXETH15M-26OCT101100-00", "KXSOL15M-26OCT101100-00")
        val yesBids = (40..65).map { it / 100.0 to (500.0 + it * 37 % 900) }
        val noBids = (30..32).map { it / 100.0 to (1200.0 + it * 53 % 700) } + (5..29).map { it / 100.0 to (800.0 + it * 91 % 1500) }
        val orders = listOf(PriceLadder.Order("o1", true, 0.62, 5), PriceLadder.Order("o2", false, 0.72, 10))
        val all = board(0).associateBy { it.model }.mapValues { (_, r) ->
            if (r.roundTrips == 0) r else r.copy(grossWinUsd = 12.40, grossLossUsd = r.netUsd - 12.40, losses = r.roundTrips - r.wins, avgWinUsd = 0.62, avgLossUsd = -0.88)
        }.toMutableMap()
        all[ScalpModels.Model.FAIR_GAP] = all.getValue(ScalpModels.Model.FAIR_GAP).copy(open = 1, openContracts = 10, unrealizedUsd = 0.30)
        return ScalpTabState(
            algoAllTime = all,
            algoToday = all,
            algoByCoin = all.mapValues { (_, r) -> mapOf("BTC" to r, "ETH" to r.copy(netUsd = r.netUsd / 2), "SOL" to r.copy(roundTrips = 0)) },
            leaderTodayByCoin = mapOf("BTC" to board(0), "ETH" to board(4), "SOL" to board(1)),
            markets = tickers.map { ScalpTabMarket(it, com.dirk.kalshiodds.decision.ScalpParams.coinOf(it), close, ScalpModels.chipLabel(it, close)) },
            selectedTicker = tickers[0],
            side = "YES",
            ladder = PriceLadder.rows("YES", yesBids, noBids, orders),
            heldContracts = held,
            heldEntry = if (held > 0) 0.61 else null,
            openOrders = orders,
            bookAgeMs = 180L,
            secondsLeft = 757L
        )
    }
}

/** Full-length render (same 1080 px width) so the ladder and sell row are visible in one image. */
class ScalpTab0351TallScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 5600, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun scalpTabTall() {
        paparazzi.snapshot(name = "scalp_tab_tall") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) {
                ScalpTabContent(st = ScalpTabFixtures.state(10))
            }
        }
    }
}

class ScalpAlgo0352ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun algoCardsDark() {
        paparazzi.snapshot(name = "scalp_algo_cards") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) { ScalpTabContent(st = ScalpTabFixtures.state(10)) }
        }
    }

    @Test fun algoPageDark() {
        val st = ScalpTabFixtures.state(10)
        val m = ScalpModels.Model.EXTREME_REVERSION
        val t0 = com.dirk.kalshiodds.decision.ScalpTicker.closeMs("KXBTC15M-26OCT101100-00")!! - 600_000L
        val trades = (0 until 8).map { i ->
            com.dirk.kalshiodds.decision.ScalpTrade(
                "t$i", "KXBTC15M-26OCT101100-00", if (i % 2 == 0) "YES" else "NO", com.dirk.kalshiodds.decision.ScalpState.CLOSED,
                t0 + i * 60_000L, 0.12, 0.2, contracts = 10, entryPrice = 0.12, entryFeeUsd = 0.08, entryAtMs = t0 + i * 60_000L + 300,
                soldContracts = 10, proceedsUsd = if (i % 3 == 0) 0.9 else 1.6, exitFeeUsd = 0.07, closedAtMs = t0 + i * 60_000L + 40_000,
                netUsd = if (i % 3 == 0) -0.45 else 0.25, exitReason = if (i % 3 == 0) "stop" else "target"
            )
        }
        val d = ScalpAlgoDetail(m, st.algoAllTime[m], st.algoToday[m], st.algoByCoin[m].orEmpty(), trades,
            trades.runningFold(0.0) { a, t -> a + t.netUsd!! }.drop(1))
        paparazzi.snapshot(name = "scalp_algo_page") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) { ScalpAlgoContent(d) }
        }
    }
}
