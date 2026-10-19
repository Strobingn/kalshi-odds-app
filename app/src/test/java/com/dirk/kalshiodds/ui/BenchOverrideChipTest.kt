package com.dirk.kalshiodds.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.insights.BenchDecision
import com.dirk.kalshiodds.decision.insights.BenchRegistry
import com.dirk.kalshiodds.decision.insights.BenchStatus
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.0.11: Auto / Force trade / Force bench on a strategy's settings screen (Fair-gap). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BenchOverrideChipTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun resetRegistry() { BenchRegistry.publish(emptyMap()) }

    @Test fun tappingChipsSelectsThemAndUpdatesTheStatusLine() {
        BenchRegistry.publish(mapOf("fair" to BenchDecision("fair", BenchStatus.BENCHED, 30, -12.0, 0.0, "net negative")))
        var controls by mutableStateOf(ScalpControls())
        val taps = ArrayList<String?>()
        rule.setContent {
            KalshiOddsTheme {
                ScalpStrategySettingsContent(ScalpModels.Model.FAIR_GAP, controls, onBenchOverride = { v ->
                    taps += v
                    controls = controls.copy(benchOverrides = if (v == null) controls.benchOverrides - "fair" else controls.benchOverrides + ("fair" to v))
                })
            }
        }
        rule.onNodeWithText("Bench · auto · BENCHED (shadow only)").assertExists()
        rule.onNodeWithTag("BENCH_AUTO").assertIsSelected()

        rule.onNodeWithText("Force trade").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("ACTIVE"), taps)
        rule.onNodeWithTag("BENCH_ACTIVE").assertIsSelected()
        rule.onNodeWithText("Bench · forced trading").assertExists()
        assertEquals("ACTIVE", controls.benchOverrides["fair"])

        rule.onNodeWithText("Force bench").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("BENCH_BENCHED").assertIsSelected()
        rule.onNodeWithText("Bench · forced benched").assertExists()
        assertEquals("BENCHED", controls.benchOverrides["fair"])

        rule.onNodeWithText("Auto").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("BENCH_AUTO").assertIsSelected()
        rule.onNodeWithText("Bench · auto · BENCHED (shadow only)").assertExists()
        assertFalse(controls.benchOverrides.containsKey("fair"))
        assertEquals(listOf<String?>("ACTIVE", "BENCHED", null), taps)
    }

    @Test fun algoSettingsScreenPassesTheOverrideToTheViewModel() {
        val src = listOf(
            java.io.File("src/main/java/com/dirk/kalshiodds/ui/ScalpAlgoScreen.kt"),
            java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/ScalpAlgoScreen.kt")
        ).first { it.isFile }.readText()
        org.junit.Assert.assertTrue(src.contains("onBenchOverride = { viewModel.setScalpBenchOverride(model.code, it) }"))
    }
}
