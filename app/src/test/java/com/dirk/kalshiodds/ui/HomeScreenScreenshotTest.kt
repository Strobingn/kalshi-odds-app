package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * JVM (no emulator) home-screen screenshots via Paparazzi.
 * Each PNG is also copied to /opt/cursor/artifacts/ for the redesign report.
 */
class HomeScreenScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun lightActionable() = snap("home_light_actionable", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun darkActionable() = snap("home_dark_actionable", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = true
    ))

    @Test
    fun lightAllNoBet() = snap("home_light_all_nobet", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(
            yesAsk = 0.63,
            noAsk = 0.37,
            yesBid = 0.61,
            noBid = 0.35,
            yesProbabilityPercent = 63.0,
            noProbabilityPercent = 37.0,
            aiYesPercent = 70.0,
            importedModelPp = 70.0,
            edgePp = 7.0
        ),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
    ))

    @Test
    fun darkAllNoBet() = snap("home_dark_all_nobet", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc().copy(
            yesAsk = 0.63,
            noAsk = 0.37,
            yesBid = 0.61,
            noBid = 0.35,
            yesProbabilityPercent = 63.0,
            noProbabilityPercent = 37.0,
            aiYesPercent = 70.0,
            importedModelPp = 70.0,
            edgePp = 7.0
        ),
        HomeFixtures.noBetEth(),
        HomeFixtures.noBetSol(),
        hasKey = true
    ))

    @Test
    fun lightNoKey() = snap("home_light_nokey", dark = false, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = false
    ))

    @Test
    fun darkNoKey() = snap("home_dark_nokey", dark = true, HomeFixtures.state(
        HomeFixtures.actionableBtc(), HomeFixtures.noBetEth(), HomeFixtures.noBetSol(), hasKey = false
    ))

    @Test
    fun beforeLightActionable() = snapBefore("before_0_3_11_light_actionable", dark = false, keyed = true)

    @Test
    fun beforeDarkActionable() = snapBefore("before_0_3_11_dark_actionable", dark = true, keyed = true)

    @Test
    fun beforeLightNoKey() = snapBefore("before_0_3_11_light_nokey", dark = false, keyed = false)

    private fun snap(name: String, dark: Boolean, state: OddsUiState) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                HomeScreen(
                    state = state,
                    onOpenSettings = {},
                    onOpenScorecard = {},
                    onOpenHistory = {},
                    onOpenChart = {},
                    onRefresh = {},
                    onBuyMarket = { _, _ -> },
                    onSellMarket = {},
                    onSetPaperTrading = {},
                    onResetPaper = {},
                    onSellPosition = { _, _ -> },
                    onReviewTicket = {},
                    onDismissTicket = {},
                    onApproveTicket = {},
                    onApproveSellTicket = { _, _, _ -> },
                    onPaperTicket = {},
                    onPaperSellTicket = { _, _, _ -> },
                    onCancelApprove = {},
                    onCancelOrder = {},
                    nowMs = HomeFixtures.NOW_MS,
                    versionLabel = "DipHunter v0.3.12 (27)"
                )
            }
        }
        copyLatest(name)
    }

    private fun snapBefore(name: String, dark: Boolean, keyed: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                LegacyHome0311(
                    dark = dark,
                    hasKey = keyed,
                    btc = HomeFixtures.actionableBtc(),
                    eth = HomeFixtures.noBetEth(),
                    sol = HomeFixtures.noBetSol(),
                    nowMs = HomeFixtures.NOW_MS
                )
            }
        }
        copyLatest(name)
    }

    private fun copyLatest(name: String) {
        val roots = listOf(
            File("src/test/snapshots/images"),
            File("app/src/test/snapshots/images"),
            File("build/reports/paparazzi"),
            File("app/build/reports/paparazzi")
        )
        val destDir = File("/opt/cursor/artifacts").apply { mkdirs() }
        val hits = roots.filter { it.isDirectory }.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "png" && f.name.contains(name) } }
        val src = hits.maxByOrNull { it.lastModified() } ?: return
        src.copyTo(File(destDir, "$name.png"), overwrite = true)
        if (src.name != "$name.png") src.copyTo(File(destDir, src.name), overwrite = true)
    }
}
