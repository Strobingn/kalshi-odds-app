package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.ui.components.DipBottomBar
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 0.3.27 Classic home with the deeper DOWN reds. */
class Release0327RedsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 3200),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun homeDarkReds() = home("home_dark_reds", dark = true)

    @Test
    fun homeLightReds() = home("home_light_reds", dark = false)

    private fun home(name: String, dark: Boolean) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark, colorStyle = ColorStyles.CLASSIC) {
                CompositionLocalProvider(LocalDipTabBar provides true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            HomeScreen(
                                state = HomeFixtures.state(
                                    HomeFixtures.actionableDownBtc(),
                                    HomeFixtures.noBetEth(),
                                    HomeFixtures.noBetSol(),
                                    hasKey = true
                                ),
                                onOpenSettings = {},
                                onOpenScorecard = {},
                                onOpenHistory = {},
                                onOpenSignalHistory = {},
                                onOpenChart = {},
                                onRefresh = {},
                                onBuyMarket = { _, _ -> },
                                onPaperSide = { _, _ -> },
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
                                nowMs = HomeFixtures.NOW_MS
                            )
                        }
                        DipBottomBar(current = AppRoutes.HOME, onSelect = {})
                    }
                }
            }
        }
        copyLatest(name)
    }

    private fun copyLatest(name: String) {
        val destDir = File("/opt/cursor/artifacts").apply { mkdirs() }
        val runDirs = listOf(
            File("build/reports/paparazzi/debug/runs"),
            File("app/build/reports/paparazzi/debug/runs")
        ).filter { it.isDirectory }
        val latest = runDirs.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile }
        }.sortedByDescending { it.name }
        for (js in latest) {
            val text = js.readText()
            if (!text.contains("\"name\": \"$name\"")) continue
            val rel = Regex("\"file\": \"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
            val parent = js.parentFile?.parentFile ?: continue
            val src = File(parent, rel)
            if (!src.isFile) continue
            val dest = File(destDir, "$name.png")
            src.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
    }
}
