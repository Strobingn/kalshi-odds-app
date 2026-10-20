package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.llm.AiWatchStore
import com.dirk.kalshiodds.llm.LlmEvents
import com.dirk.kalshiodds.llm.LlmProviders
import com.dirk.kalshiodds.llm.LlmSettings
import com.dirk.kalshiodds.llm.LlmSources
import com.dirk.kalshiodds.llm.ModelSwitchEvent
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** FieldOps render of the usage charts. Recorded by testDebugUnitTest; not a golden diff. */
class AiUsageChartsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false, screenHeight = 5600),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test fun chartsWithRecordedCalls() {
        val now = System.currentTimeMillis()
        val store = AiWatchStore()
        store.record(LlmSources.WATCHER, LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 140, 40, now - 3_600_000, latencyMs = 420, automatic = true)
        store.record(LlmSources.WATCHER, LlmProviders.MISTRAL, LlmProviders.MISTRAL_DEFAULT, 90, 20, now - 1_800_000, latencyMs = 510, automatic = true)
        store.record(LlmSources.CHAT, LlmProviders.GROQ, LlmProviders.GROQ_120, 30, 12, now - 600_000, latencyMs = 180, automatic = false)
        store.record(LlmSources.WINDOW, LlmProviders.GEMINI, LlmProviders.GEMINI_DEFAULT, 2_200, 80, now, latencyMs = 900, automatic = true)
        LlmEvents.push(ModelSwitchEvent(now - 500_000, LlmProviders.MISTRAL, "mistral-large-latest", LlmProviders.MISTRAL_DEFAULT, "model not on plan"))
        paparazzi.snapshot(name = "ai_usage_charts_fieldops") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
                Column(Modifier.fillMaxWidth().background(DipTheme.colors.bg).padding(12.dp)) {
                    AiUsageChartsCard(store)
                }
            }
        }
    }

    @Test fun missingKeySitsUnderProviderStatus() {
        val store = AiWatchStore()
        val settings = LlmSettings(paparazzi.context)
        paparazzi.snapshot(name = "ai_settings_missing_key") {
            KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.FIELDOPS) {
                Column(Modifier.fillMaxWidth().background(DipTheme.colors.bg).padding(12.dp)) {
                    AiUsageSettingsCard(store, settings)
                }
            }
        }
    }
}
