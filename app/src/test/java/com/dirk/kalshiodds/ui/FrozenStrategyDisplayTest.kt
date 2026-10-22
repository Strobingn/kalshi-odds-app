package com.dirk.kalshiodds.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpStat
import com.dirk.kalshiodds.decision.insights.BenchDecision
import com.dirk.kalshiodds.decision.insights.BenchRegistry
import com.dirk.kalshiodds.decision.insights.BenchStatus
import com.dirk.kalshiodds.decision.insights.ScalpFreeze
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.0.14: frozen losers stay on the Scalp tab and settings with their history and a FROZEN - losing badge. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrozenStrategyDisplayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @After fun resetRegistry() { BenchRegistry.publish(emptyMap()) }
    private val frozen = BenchDecision("dip", BenchStatus.BENCHED, 0, 0.0, 0.0, ScalpFreeze.BADGE + " · 1.0.14 default (lost money Oct 17–19) · Force trade unfreezes")

    @Test fun statusLineNamesTheFreeze() {
        assertEquals("Bench · trading · no manual override", benchStatusLine(null, false, autoBenchEnabled = false))
        assertEquals("Bench · trading · no manual override", benchStatusLine(null, false))
        assertEquals("Bench · trading · no manual override", benchStatusLine(null, true))
        assertFalse(benchStatusLine(null, true, frozen = true).contains("FROZEN - losing"))
        assertEquals("Bench · forced trading", benchStatusLine("ACTIVE", false, frozen = true))
        assertEquals("Bench · forced benched", benchStatusLine("BENCHED", false, frozen = true))
    }

    @Test fun frozenStrategyCardStillShowsItsHistory() {
        BenchRegistry.publish(mapOf("dip" to frozen))
        val row = ScalpModels.rowFromStat(ScalpStat("dip", n = 12, wins = 3, netUsd = -7.5))
        assertEquals(ScalpModels.Model.DIP_HUNTER, row.model)
        rule.setContent { KalshiOddsTheme { ArenaStrategyRow(row, ScalpTabState(controls = ScalpControls(autoBench = true), algoAllTime = mapOf(row.model to row))) {} } }
        rule.onNodeWithText(ScalpModels.Model.DIP_HUNTER.label, useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("FROZEN_BADGE_dip", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("12 trades", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun settingsScreenShowsFrozenWithForceTrade() {
        BenchRegistry.publish(mapOf("dip" to frozen))
        rule.setContent { KalshiOddsTheme { ScalpStrategySettingsContent(ScalpModels.Model.DIP_HUNTER, ScalpControls(autoBench = true), onBenchOverride = {}) } }
        rule.onNodeWithText("Bench · trading · no manual override").assertExists()
        rule.onNodeWithText("Force trade").assertExists()
        rule.onNodeWithText("Force bench").assertExists()
    }
}
