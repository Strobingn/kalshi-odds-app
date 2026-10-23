package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.llm.ChatMessage
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.0.35: Quant is its own tab, and the decision log is a picker rather than the last row only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuantHybridUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun resetBefore() { ModelFilter.resetForTest() }
    @After fun resetAfter() { ModelFilter.resetForTest() }

    @Test fun quantSitsAfterStrategiesAndTheOverviewBannerOpensIt() {
        val order = KashiArenaTab.values().map { it.name }
        assertEquals(
            listOf("OVERVIEW", "STRATEGIES", "QUANT", "ANALYSIS", "TRADES", "CHAT"),
            order
        )
        compose.setContent { KalshiOddsTheme(darkTheme = true) { KashiArenaContent(ScalpTabState()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("ARENA_TAB_QUANT").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("MODEL_FILTER").assertExists()
        compose.onNodeWithTag("MODEL_RUNTIME").assertExists()
        compose.onNodeWithTag("MODEL_LATENCY").assertExists()
        compose.onNodeWithTag("MODEL_AUTO").assertExists()
        compose.onNodeWithTag("BENCHMARK_EPS").assertExists()
        compose.onNodeWithTag("SCORECARD_CONTROL").assertExists()
        compose.onNodeWithTag("SCORECARD_LOGISTIC").assertExists()
        compose.onNodeWithTag("SCORECARD_LGBM").assertExists()
        compose.onNodeWithTag("RESEARCH_ASK").performScrollTo().assertExists()
        compose.onNodeWithTag("RESEARCH_JOURNAL_ASK").assertExists()
        compose.onNodeWithTag("RESEARCH_RETRAIN").assertExists()
        compose.onNodeWithTag("SESSION_JOURNAL").assertExists()
        compose.onNodeWithText("LightGBM 120t·d4", substring = true).assertExists()
        compose.onNodeWithText("Groq · llama-3.1-8b-instant", substring = true).assertExists()
        compose.onNodeWithText("10–15m", substring = true).assertExists()
        compose.onNodeWithText("<2m", substring = true).assertExists()
        compose.onNodeWithText("Ask Groq to Explain This Trade").assertExists()
    }

    @Test fun overviewKeepsAShortBannerThatOpensQuant() {
        compose.setContent { KalshiOddsTheme(darkTheme = true) { KashiArenaContent(ScalpTabState()) } }
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("MODEL_FILTER"))
        compose.onNodeWithTag("MODEL_FILTER").assertExists()
        compose.onNodeWithText("Open Quant & Hybrid LLM →").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Three paper arms").assertExists()
        compose.onNodeWithTag("BENCHMARK_EPS").assertExists()
    }

    @Test fun decisionPickerSelectsARowAndAsksGroq() {
        val older = log("older", 0.42, true, 0.20, 0.80)
        val newer = log("newer", 0.71, false, -0.10, 0.15)
        var asked = ""
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuantHybridScreen(decisions = listOf(older, newer), onAskAi = { prompt, done ->
                        asked = prompt
                        done("The skip was the spread. The residual was not enough to pay the fee.")
                    })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("sf41-logistic · skipped · score 0.71", substring = true).assertExists()
        compose.onNodeWithTag("DECISION_older").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("sf41-logistic · entered · score 0.42", substring = true).assertExists()
        compose.onNodeWithTag("RESEARCH_ASK").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(asked.contains("entered"))
        assertTrue(asked.contains("0.42"))
        compose.onNodeWithTag("RESEARCH_NOTE").assertExists()
        compose.onNodeWithText("The skip was the spread.", substring = true).assertExists()
    }

    @Test fun journalAskSendsTheSessionSummary() {
        var asked = ""
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuantHybridScreen(onAskAi = { prompt, done ->
                        asked = prompt
                        done("Quiet time left is the only green regime. Retrain after more closes.")
                    })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("RESEARCH_JOURNAL_ASK").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(asked.contains("Paper session journal"))
        compose.onNodeWithText("Quiet time left is the only green regime.", substring = true).assertExists()
    }

    @Test fun aiTabSwitchesBetweenChatAndHybridQuant() {
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                AiChatContent(messages = emptyList(), busy = false, strategyStatus = "needs key", hasKey = false)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("AI_MODE_CHAT").assertExists()
        compose.onNodeWithTag("AI_MODE_HYBRID").assertExists()
        compose.onNodeWithText("Paper strategy: needs key", substring = true).assertExists()
        compose.onNodeWithTag("AI_MODE_HYBRID").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Three paper arms").assertExists()
        compose.onNodeWithTag("RESEARCH_ASK").assertExists()
        compose.onNodeWithTag("AI_MODE_CHAT").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Paper strategy: needs key", substring = true).assertExists()
    }

    private fun log(id: String, score: Double, entered: Boolean, net: Double, timeLeft: Double) = ModelFilter.LogRow(
        id = id,
        twinId = "sf41-logistic",
        atMs = 1L,
        score = score,
        scalar = 1.0,
        entered = entered,
        reason = if (entered) "enter" else "skip",
        features = DoubleArray(ModelFilter.FEATURES) { i -> if (i == 0) timeLeft else if (i == 22) 0.4 else 0.1 },
        outcomeUsd = net,
        side = "YES",
        expectedNet = 0.012
    )
}
