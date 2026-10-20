package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.latefav.LateFavoriteTotals
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.components.ThisWindowCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** JVM screenshots of the "AI SAYS UP / DOWN / WAIT" banner (made-up windows). */
class AiBannerScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 1250, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    private val now = HomeFixtures.NOW_MS

    private fun snap(name: String, dark: Boolean) {
        val up = HomeFixtures.market("KXBTC15M-26OCT201230-30", "Bitcoin", yesAsk = 0.32, aiYes = 50.0, predicted = "YES", closeMs = now + 220_000L)
            .copy(comebackSide = "YES", comebackAsk = 0.32)
        val down = HomeFixtures.market("KXBTC15M-26OCT201245-45", "Bitcoin", yesAsk = 0.13, aiYes = 50.0, predicted = "NO", spotUsd = 66_820.0)
            .copy(clearLeadSide = "NO", clearLeadAsk = 0.88)
        val wait = HomeFixtures.market("KXBTC15M-26OCT201300-00", "Bitcoin", yesAsk = 0.63, aiYes = 50.0, predicted = "YES")
        val s = SignalSettings()
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                Column(
                    Modifier.background(DipTheme.colors.bg).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThisWindowCard(
                        up, BetCall.decide(up, s, now), now,
                        comebackRecord = LateFavoriteTotals(wins = 12, losses = 20, pnlUsd = 8.4), onReview = { _, _ -> }
                    )
                    ThisWindowCard(
                        down, BetCall.decide(down, s, now), now,
                        clearLeadRecord = LateFavoriteTotals(wins = 40, losses = 9, pnlUsd = -3.1), onReview = { _, _ -> }
                    )
                    ThisWindowCard(wait, BetCall.decide(wait, s, now), now, comebackRecord = LateFavoriteTotals())
                }
            }
        }
    }

    @Test
    fun darkBanner() = snap("ai_banner_dark", true)

    @Test
    fun lightBanner() = snap("ai_banner_light", false)
}
