package com.dirk.kalshiodds.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.llm.AiWatchStore
import com.dirk.kalshiodds.llm.LlmProviders
import com.dirk.kalshiodds.llm.LlmSettings
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Built-in on-device hides the API key prompt. A cloud provider with no key still shows it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BuiltinKeyPromptTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun builtinSelectedShowsNoKeyPrompt() {
        show(settings(LlmProviders.BUILTIN))
        rule.onNodeWithText("Active provider: Built-in LLM (unlimited on-device) · free").assertExists()
        rule.onNodeWithText("Auto pace: unlimited on-device").assertExists()
        assertKeyPromptHidden()
    }

    @Test fun choosingBuiltinHidesThePromptAndCloudMissingKeyShowsIt() {
        val saved = settings(LlmProviders.GROQ)
        show(saved)
        rule.onNodeWithText("Active provider: Groq (free) · free").assertExists()
        rule.onNodeWithText("No Groq API key saved yet.").assertExists()
        rule.onNodeWithText("Enter your Groq API key", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("AI_PROVIDER_KEY_WARNING").assertExists()
        rule.onNodeWithTag("AI_PROVIDER_KEY_PROMPT").assertExists()
        rule.onNodeWithTag("AI_PROVIDER_KEY_FIELD").performScrollTo().assertExists()

        rule.onNodeWithText("Built-in LLM (unlimited on-device)").performScrollTo().performClick()
        rule.waitForIdle()
        assertKeyPromptHidden()
        assertEquals(LlmProviders.GROQ, saved.providerId)

        rule.onNodeWithText("Mistral (free)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No Mistral API key saved yet.").assertExists()
        rule.onNodeWithText("Enter your Mistral API key", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("AI_PROVIDER_KEY_FIELD").assertExists()

        rule.onNodeWithText("Google Gemini (free)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No Gemini API key saved yet.").assertExists()
        rule.onNodeWithText("Enter your Gemini API key", useUnmergedTree = true).assertExists()
    }

    @Test fun savedCloudKeyKeepsTheFieldAndDropsTheMissingWarning() {
        show(settings(LlmProviders.GROQ, key = "gsk-test"))
        rule.onNodeWithTag("AI_PROVIDER_KEY_WARNING").assertDoesNotExist()
        rule.onNodeWithTag("AI_PROVIDER_KEY_PROMPT").assertDoesNotExist()
        rule.onNodeWithText("No Groq API key saved yet.").assertDoesNotExist()
        rule.onNodeWithTag("AI_PROVIDER_KEY_FIELD").assertExists()
        rule.onNodeWithText("Groq (free) API key (encrypted on this phone)", useUnmergedTree = true).assertExists()
    }

    @Test fun chatHidesTheKeyFieldWhenTheProviderIsBuiltin() {
        rule.setContent {
            KalshiOddsTheme(darkTheme = true) {
                AiChatContent(
                    messages = emptyList(),
                    busy = false,
                    strategyStatus = "on device",
                    hasKey = true,
                    showApiKey = false,
                    providerLabel = "Built-in LLM (unlimited on-device)"
                )
            }
        }
        rule.onNodeWithContentDescription("LLM key settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("AI_CHAT_KEY_FIELD").assertDoesNotExist()
        rule.onNodeWithText("LLM API key", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Model", useUnmergedTree = true).assertExists()
    }

    @Test fun chatStillShowsTheKeyFieldForACloudProvider() {
        rule.setContent {
            KalshiOddsTheme(darkTheme = true) {
                AiChatContent(
                    messages = emptyList(),
                    busy = false,
                    strategyStatus = "needs key",
                    hasKey = false,
                    showApiKey = true,
                    providerLabel = "Groq (free)"
                )
            }
        }
        rule.onNodeWithTag("AI_CHAT_KEY_FIELD").assertExists()
        rule.onNodeWithText("LLM API key (stored encrypted on this phone)", useUnmergedTree = true).assertExists()
    }

    private fun assertKeyPromptHidden() {
        rule.onNodeWithTag("AI_PROVIDER_KEY_WARNING").assertDoesNotExist()
        rule.onNodeWithTag("AI_PROVIDER_KEY_PROMPT").assertDoesNotExist()
        rule.onNodeWithTag("AI_PROVIDER_KEY_FIELD").assertDoesNotExist()
        rule.onNodeWithText("API key saved yet", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Enter your", substring = true).assertDoesNotExist()
        rule.onNodeWithText("API key (encrypted", substring = true).assertDoesNotExist()
    }

    private fun show(settings: LlmSettings) {
        rule.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AiUsageSettingsCard(AiWatchStore(), settings)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun settings(provider: String, key: String = ""): LlmSettings {
        val prefs = rule.activity.getSharedPreferences("llm-$provider", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("provider", provider)
            .putString("key.$provider", key)
            .putBoolean(LlmSettings.KEY_MIGRATED_GROQ_1035, true)
            .commit()
        return LlmSettings(prefs)
    }
}
