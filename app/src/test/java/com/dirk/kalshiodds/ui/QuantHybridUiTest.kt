package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.HybridQuantGates
import com.dirk.kalshiodds.decision.LocalAnalyst
import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.decision.QuantSession
import com.dirk.kalshiodds.llm.ChatMessage
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.0.35: Quant is its own tab, and the decision log is a picker rather than the last row only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuantHybridUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun resetBefore() {
        ModelFilter.resetForTest()
        LocalAnalyst.resetForTest()
        QuantSession.resetForTest()
    }
    @After fun resetAfter() {
        ModelFilter.resetForTest()
        LocalAnalyst.resetForTest()
        QuantSession.resetForTest()
    }

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

    @Test fun hybridGateTogglesSitOnTheQuantScreen() {
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuantHybridScreen()
                }
            }
        }
        compose.onNodeWithTag("GATE_REASON_STATS").performScrollTo().assertTextContains("mtf_disagree", substring = true)
        compose.onNodeWithTag("GATE_REASON_STATS").assertTextContains("strong_move_low", substring = true)
        compose.onNodeWithTag("GATE_REASON_STATS").assertTextContains("fib_barrier", substring = true)
        compose.onNodeWithTag("MTF_AGREE").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(ModelFilter.hybridPrefs().mtfAgree)
        compose.onNodeWithTag("STRONG_MOVE").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(ModelFilter.hybridPrefs().strongMove)
        compose.onNodeWithTag("FIB_BLOCK").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(HybridQuantGates.FibMode.BLOCK, ModelFilter.hybridPrefs().fibMode)
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

    @Test fun llmAndOnnxPillsUpdateImmediately() {
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuantHybridScreen()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("LLM_GEMMA_3N").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("LLM_STATUS").assertExists()
        compose.onNodeWithText("Selected: Gemma 3n E2B", substring = true).assertExists()
        compose.onNodeWithTag("LLM_GPU").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Gemma 3n E2B · GPU", substring = true).assertExists()
        compose.onNodeWithTag("LLM_Gemma_4_E4B").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Selected: Gemma 4 E4B · GPU", substring = true).assertExists()
        compose.onNodeWithText("Stronger / larger · Gemma 4 E4B · ~3.65 GB", substring = true).assertExists()
        compose.onNodeWithTag("LLM_OUTPUT_256").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("maxOutputTokens 256", substring = true).assertExists()
        compose.onNodeWithTag("LLM_PAUSE").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Local model may run during an entry").assertExists()
        compose.onNodeWithTag("MODEL_ONNX").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(QuantSession.ModelMode.ONNX, QuantSession.mode())
        compose.onNodeWithTag("MODEL_RUNTIME").assertExists()
        compose.onNodeWithText("ONNX ·", substring = true).assertExists()
        compose.onNodeWithTag("KELLY_MODE").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kelly sizing on").assertExists()
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
        compose.onNodeWithTag("GATE_PREFS").assertTextContains("ceiling 0.65", substring = true)
        compose.onNodeWithTag("RESEARCH_JOURNAL_NOTE").assertExists()
        assertRepliesSitInSelectionContainer()
    }

    @Test fun applyAdviceWritesThePaperGateAndRepliesAreSelectable() {
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    QuantHybridScreen(onAskAi = { _, done ->
                        done("Drop scores above 0.65. The late window under five minutes is the green pocket.")
                    })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("GATE_PREFS").performScrollTo().assertExists()
        compose.onNodeWithText("ceiling 0.65", substring = true).assertExists()
        compose.onNodeWithTag("RESEARCH_JOURNAL_ASK").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("APPLY_ADVICE").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(ModelFilter.gatePrefs().lateWindowOnly)
        assertEquals(0.65, ModelFilter.gatePrefs().scoreCeiling, 0.0)
        assertEquals(0.55, ModelFilter.gatePrefs().scoreFloor, 0.0)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        assertTrue(ModelFilter.hybridGateOn())
        compose.onNodeWithTag("GATE_APPLIED").assertExists()
        compose.onNodeWithText("nudge to 0.57 not applied", substring = true).assertExists()
        compose.onNodeWithText("Not applied (protected):", substring = true).assertExists()
        compose.onNodeWithTag("UNDO_ADVICE").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Undid the last Apply advice.", substring = true).assertExists()
        compose.onNodeWithTag("RESEARCH_ASK").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("RESEARCH_NOTE").assertExists()
        assertRepliesSitInSelectionContainer()
    }

    @Test fun aiChatReplyTextIsSelectable() {
        val reply = ChatMessage(id = 7L, role = ChatMessage.AI, text = "Drop scores above 0.65 and keep the floor at 0.55.", atMs = 1_700_000_000_000L)
        compose.setContent {
            KalshiOddsTheme(darkTheme = true) {
                AiChatContent(messages = listOf(reply), busy = false, strategyStatus = "paper", hasKey = true)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("AI_REPLY_TEXT").assertExists()
        compose.onNodeWithText("Drop scores above 0.65 and keep the floor at 0.55.").assertExists()
        assertRepliesSitInSelectionContainer()
    }

    private fun assertRepliesSitInSelectionContainer() {
        fun source(path: String) = listOf(
            java.io.File(path),
            java.io.File("app/$path")
        ).first { it.isFile }.readText()
        val hybrid = source("src/main/java/com/dirk/kalshiodds/ui/QuantHybridScreen.kt")
        val journal = hybrid.substringAfter("if (journalNote.isNotBlank())").substringBefore("APPLY_ADVICE")
        assertTrue(journal, journal.contains("SelectionContainer") && journal.contains("RESEARCH_JOURNAL_NOTE"))
        val explain = hybrid.substringAfter("if (aiNote.isNotBlank())").substringBefore("Session journal")
        assertTrue(explain, explain.contains("SelectionContainer") && explain.contains("RESEARCH_NOTE"))
        val chat = source("src/main/java/com/dirk/kalshiodds/ui/AiChatScreen.kt")
        val bubble = chat.substringAfter("private fun MessageBubble").substringBefore("EtTime.clock")
        assertTrue(bubble, bubble.contains("SelectionContainer") && bubble.contains("AI_REPLY_TEXT"))
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

    @Test fun retrainClickFitsOffTheMainThread() {
        val ranOn = AtomicReference<Thread>()
        ModelFilter.retrainComputeHook = { ranOn.compareAndSet(null, Thread.currentThread()) }
        try {
            compose.setContent {
                KalshiOddsTheme(darkTheme = true) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        QuantHybridScreen()
                    }
                }
            }
            compose.onNodeWithTag("RESEARCH_RETRAIN").performScrollTo().performClick()
            compose.waitUntil(5_000) { ranOn.get() != null }
            assertNotEquals(Looper.getMainLooper().thread, ranOn.get())
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("closed samples", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("RESEARCH_RETRAIN_NOTE").assertExists()
            compose.onNodeWithText("Retrain offline").assertExists()
        } finally {
            ModelFilter.retrainComputeHook = null
        }
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
