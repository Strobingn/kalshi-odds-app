package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.ui.components.DipBottomBar
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** 0.3.26 Classic default, FieldOps option, and the update control. */
class Release0326ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 2400),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun homeDarkClassic() = home("home_dark_classic", dark = true, style = ColorStyles.CLASSIC)

    @Test
    fun homeLightClassic() = home("home_light_classic", dark = false, style = ColorStyles.CLASSIC)

    @Test
    fun homeDarkFieldOps() = home("home_dark_fieldops", dark = true, style = ColorStyles.FIELDOPS)

    @Test
    fun settingsUpdateDark() {
        paparazzi.snapshot(name = "settings_update_dark") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) {
                SettingsContent(
                    state = SettingsUiState(
                        settings = SignalSettings(apiKeyId = "key-id", hasPrivateKey = true),
                        updateMessage = "Checks v0.3.*-debug releases for DipHunter-debug.apk. Other tags are ignored."
                    ),
                    onBack = {},
                    onOpenData = {},
                    onCheckUpdate = {}
                )
            }
        }
    }

    private fun home(name: String, dark: Boolean, style: String) {
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark, colorStyle = style) {
                CompositionLocalProvider(LocalDipTabBar provides true) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            HomeScreen(
                                state = HomeFixtures.state(
                                    HomeFixtures.actionableBtc(),
                                    HomeFixtures.noBetEth(),
                                    HomeFixtures.noBetSol(),
                                    hasKey = true
                                ).copy(updateBanner = if (dark && style == ColorStyles.CLASSIC) "Update v0.3.27-debug available" else null),
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
    }
}
