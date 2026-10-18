package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.llm.ChatMessage
import com.dirk.kalshiodds.ui.theme.ColorStyles
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 1.0.4: AI tab layout — messages, confirm card, error, loading, and the pinned input bar render on a phone. */
class AiChatLayoutTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, softButtons = false), theme = "android:Theme.Material3.DayNight.NoActionBar")

    private val msgs = listOf(
        ChatMessage(1, ChatMessage.USER, "Why is Fair-gap hold winning?", 1),
        ChatMessage(2, ChatMessage.AI, "[model grok-4] It only buys 24–42¢ when BRTI fair beats the ask by 10–21¢ after fees.", 2),
        ChatMessage(3, ChatMessage.USER, "Tighten the global stop to 4¢", 3),
        ChatMessage(4, ChatMessage.PROPOSAL, "Change stopCents?", 4, "stopCents", "6", "4", ChatMessage.PENDING),
        ChatMessage(5, ChatMessage.CHANGE, "BLOCKED set_setting liveTradingArmed = true: the AI can never touch real money", 5, status = "rejected"),
        ChatMessage(6, ChatMessage.ERROR, "Error (grok-4, no fallback model): grok-4 failed: HTTP 401", 6))

    @Test fun conversationDark() = paparazzi.snapshot("ai_tab_dark") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) { AiChatContent(msgs, busy = true, strategyStatus = "idle until next window start", hasKey = true) }
    }
    @Test fun emptyNeedsKeyLight() = paparazzi.snapshot("ai_tab_empty_light") {
        KalshiOddsTheme(darkTheme = false, colorStyle = ColorStyles.CLASSIC) { AiChatContent(emptyList(), busy = false, strategyStatus = "needs key", hasKey = false) }
    }

    @Test fun predictionBanners() = paparazzi.snapshot("ai_prediction_banners") {
        KalshiOddsTheme(darkTheme = true, colorStyle = ColorStyles.CLASSIC) { androidx.compose.foundation.layout.Column {
            AiPredictionBanner(com.dirk.kalshiodds.decision.AiDirection.Forecast(com.dirk.kalshiodds.decision.AiDirection.Call.UP, .68, 512, "KXBTC15M"))
            AiPredictionBanner(com.dirk.kalshiodds.decision.AiDirection.Forecast(com.dirk.kalshiodds.decision.AiDirection.Call.DOWN, .74, 95, "KXBTC15M"))
            AiPredictionBanner(com.dirk.kalshiodds.decision.AiDirection.Forecast(com.dirk.kalshiodds.decision.AiDirection.Call.NONE, .52, 300, "KXBTC15M"))
        } }
    }

    @Test fun aiIsABottomTab() {
        assertTrue(AppRoutes.AI in AppRoutes.TABS)
        assertEquals("AI", DipNav.tabLabel[AppRoutes.AI])
        assertEquals("AI tab", DipNav.howToReach(AppRoutes.AI))
    }
}
