package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardTestTest {
    @Test fun quotedDepthMustCoverTheWholeAllInCapClip() {
        val thin = ForwardTest.capture(
            "KXBTC15M-A", "KXBTC15M", 1L, 0.75, 0.54, "YES",
            BookLevelSnapshot(yes = listOf(0.50 to 40.0), no = listOf(0.44 to 2.0)), 0.07
        )!!
        assertEquals(0.56, thin.bookAsk!!, 1e-9)
        assertFalse(thin.quoteQualified)
        assertTrue(thin.contracts!! > 2)
        val deep = ForwardTest.capture(
            "KXBTC15M-A", "KXBTC15M", 1L, 0.75, 0.54, "YES",
            BookLevelSnapshot(yes = listOf(0.50 to 40.0), no = listOf(0.44 to 20.0)), 0.07
        )!!
        assertTrue(deep.quoteQualified)
        assertTrue(deep.allInUsd!! <= SignalConstants.LIVE_ALL_IN_CAP_USD + 1e-9)
        val crossed = ForwardTest.capture(
            "KXBTC15M-A", "KXBTC15M", 1L, 0.75, 0.54, "YES",
            BookLevelSnapshot(yes = listOf(0.70 to 40.0), no = listOf(0.44 to 20.0)), 0.07
        )!!
        assertFalse(crossed.quoteQualified)
        assertEquals(null, crossed.bookAsk)
    }

    @Test fun settledScoresUseFrozenForecastAndExcludeVoidAndUnfillableQuotes() {
        val quoted = ForwardTest.capture(
            "KXBTC15M-win", "KXBTC15M", 1L, 0.8, 0.6, "YES",
            BookLevelSnapshot(yes = listOf(0.5 to 20.0), no = listOf(0.4 to 20.0)), 0.07
        )!!
        val unquoted = quoted.copy(ticker = "KXBTC15M-loss", quoteQualified = false)
        val stats = ForwardTest.summarize(listOf(
            quoted.copy(outcome = "yes"),
            unquoted.copy(outcome = "no"),
            quoted.copy(ticker = "KXBTC15M-void", outcome = "void")
        ))
        assertEquals(3, stats.captured)
        assertEquals(2, stats.settled)
        assertEquals(1, stats.quoted)
        assertEquals(0.34, stats.modelBrier!!, 1e-9)
        assertEquals(0.26, stats.marketBrier!!, 1e-9)
        assertNotNull(stats.quotedProxyPnlUsd)
        assertEquals(quoted.contracts!! - quoted.allInUsd!!, stats.quotedProxyPnlUsd!!, 1e-9)
    }

    @Test fun firstSignalSurvivesLaterPricesAndSettlement() {
        val store = InMemoryResultsStore()
        val first = ForwardTest.capture("KXBTC15M-A", "KXBTC15M", 1L, 0.8, 0.6, "YES",
            BookLevelSnapshot(no = listOf(0.4 to 20.0)), 0.07)!!
        store.insertForwardTests(listOf(first, first.copy(capturedAtMs = 2L, modelYes = 0.1)))
        store.upsertSettled(listOf(SettledWindowRow("KXBTC15M-A", "KXBTC15M", "yes")))
        val actual = store.forwardTests().single()
        assertEquals(1L, actual.capturedAtMs)
        assertEquals(0.8, actual.modelYes, 1e-9)
        assertEquals("yes", actual.outcome)
        val csv = ForwardTest.csv(listOf(actual))
        assertTrue(csv.contains("quoted_proxy_pnl_usd"))
        assertTrue(csv.contains("KXBTC15M-A"))
    }
}
