package com.dirk.kalshiodds.release

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.crash.CrashLog
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.ScalpData
import com.dirk.kalshiodds.ui.ScalpDataContent
import com.dirk.kalshiodds.ui.ScalpDataView
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseGate0347Test {
    @get:Rule val compose = createComposeRule()

    private fun trip(id: String, net: Double?, closed: Long, variant: String = ScalpParams.CLASSIC.id, shadow: Boolean = false, ticker: String = "KXBTC15M-26OCT091015-15") = ScalpTrade(
        id = id, ticker = ticker, side = "YES", state = ScalpState.CLOSED, signalAtMs = closed - 60_000,
        signalAsk = 0.4, fairAtSignal = 0.5, contracts = 10, entryPrice = 0.4, entryFeeUsd = 0.15, entryAtMs = closed - 57_000,
        soldContracts = 10, proceedsUsd = 4.3, exitFeeUsd = 0.15, closedAtMs = closed, netUsd = net,
        exitReason = "target", ruleVersion = "${ScalpRule.VERSION}|$variant|${if (shadow) "S" else "P"}"
    )

    private fun render(trades: List<ScalpTrade>, all: List<ScalpTrade> = trades) {
        compose.setContent {
            KalshiOddsTheme {
                ScalpDataContent(trades, all, emptyList(), {}, {}, {}, { _, _, _ -> }, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading scalp data…").fetchSemanticsNodes().isEmpty() }
        // Scroll through the whole list so every round-trip row (and its key) is composed.
        val list = compose.onNode(hasScrollAction())
        var i = 0
        while (i < 400) {
            val ok = runCatching { list.performScrollToIndex(i) }.exceptionOrNull()
                ?.let { if (it.message?.contains("out of bounds") == true) false else throw it } ?: true
            if (!ok) break
            compose.waitForIdle(); i += 5
        }
    }

    /** Reproduces the 0.3.46 crash mechanism: the old key ("t" + trade id) repeats when ids repeat → LazyColumn throws. */
    @Test fun oldKeysDuplicateAndLazyColumnThrows() {
        val dup = listOf(trip("same", 1.0, 2_000L), trip("same", -1.0, 1_000L))
        val old = ScalpData.tripLines(dup).map { "t" + it.key }
        assertEquals(1, old.toSet().size)
        val err = runCatching {
            compose.setContent { LazyColumn { items(old, key = { it }) { Text(it) } } }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("expected duplicate-key crash, got $err", err is IllegalArgumentException)
    }

    @Test fun duplicateIdsArchivedPlusCurrentRender() {
        val rows = listOf(trip("a", 1.0, 3_000L), trip("a", -0.5, 2_000L), trip("a", 0.2, 1_000L), trip("b", 0.1, 500L))
        render(rows)
        val keys = ScalpDataView.of(rows, rows).rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun emptyAndOneTradeRender() {
        render(emptyList())
    }

    @Test fun oneTradeRenders() { render(listOf(trip("only", 0.4, 1_000L))) }

    @Test fun nanInfinityAndUnknownStrategyAreSafe() {
        val rows = listOf(trip("n", Double.NaN, 1_000L), trip("i", Double.POSITIVE_INFINITY, 2_000L), trip("u", 1.0, 3_000L, variant = "unknown-xyz"),
            trip("z", 0.5, 4_000L, ticker = ""))
        val v = ScalpDataView.of(rows, rows)
        assertTrue(v.curve.all { it.isFinite() })
        assertTrue(v.makerLines.none { it.contains("NaN") || it.contains("Infinity") })
        render(rows)
    }

    @Test fun thousandsOfTradesWithMakerAndShadowRowsArePagedAndFast() {
        val strategies = ScalpStrategy.values()
        val all = (0 until 3_000).map { i ->
            val s = strategies[i % strategies.size]
            val p = ScalpParams.STRATEGY_SEEDS[s] ?: ScalpParams.CLASSIC
            val id = if (i % 7 == 0) "dup${i % 50}" else "id$i"
            trip(id, (i % 9 - 4) / 10.0, 1_000L + i * 1_000L, variant = if (i % 2 == 0) p.id else p.copy(maker = true).id, shadow = i % 3 == 0)
        }
        val primary = all.filter { it.isPrimary }
        val t0 = System.nanoTime()
        val v = ScalpDataView.of(primary, all)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("derive took $ms ms", ms < 3_000)
        assertEquals(v.rows.size, v.rows.map { it.key }.toSet().size)
        render(primary, all)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("more of", substring = true))
    }

    @Test fun crashHandlerPersistsAndAnnouncesOnce() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        CrashLog.attach(ctx); CrashLog.clear(); CrashLog.attach(ctx)
        val f = CrashLog.write("main", IllegalArgumentException("Key \"tX\" was already used"), "0.3.49", nowMs = 1_000L)
        assertNotNull(f)
        assertEquals(1, CrashLog.files().size)
        assertTrue(CrashLog.headline(f!!).contains("IllegalArgumentException"))
        assertTrue(CrashLog.read(f).contains("at com.dirk.kalshiodds"))
        assertNotNull(CrashLog.consumePending())
        assertNull(CrashLog.consumePending()) // shown once
        assertTrue(CrashLog.exportAll().contains("already used"))
        repeat(25) { CrashLog.write("t", RuntimeException("x$it"), nowMs = 2_000L + it) }
        assertEquals(CrashLog.MAX_FILES, CrashLog.files().size)
        val nav = com.dirk.kalshiodds.ui.DipNav.moreDestinations.first { it.route == com.dirk.kalshiodds.ui.AppRoutes.CRASH_LOG }
        assertEquals("Crash log", nav.label)
    }
}
