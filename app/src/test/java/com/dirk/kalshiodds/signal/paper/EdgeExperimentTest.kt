package com.dirk.kalshiodds.signal.paper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeExperimentTest {

    private fun fill(
        id: String,
        pnl: Double,
        won: Boolean = pnl > 0,
        settled: Boolean = true,
        outcome: String? = if (settled) "yes" else null
    ) = PaperFill(
        id = id,
        ticker = "KXBTC15M-1000-a",
        side = "YES",
        stakeUsd = 5.0,
        contracts = 10,
        limitPrice = 0.50,
        source = "AI hunter",
        createdAtMs = 1L,
        settled = settled,
        outcome = outcome,
        won = if (settled) won else null,
        pnlUsd = if (settled) pnl else null,
        note = "test"
    )

    @Test
    fun emptyLedgerIsCollecting() {
        val v = EdgeExperiment.evaluate(PaperBookState())
        assertEquals(0, v.settledCount)
        assertFalse(v.edgeDetected)
        assertTrue(v.verdictLine.contains("experiment is running"))
    }

    @Test
    fun openFillsDoNotCount() {
        val state = PaperBookState(fills = listOf(fill("o", 0.0, settled = false)))
        val v = EdgeExperiment.evaluate(state)
        assertEquals(0, v.settledCount)
    }

    @Test
    fun voidFillsAreExcluded() {
        val state = PaperBookState(
            fills = listOf(fill("v", 5.0, won = true, outcome = "void"))
        )
        val v = EdgeExperiment.evaluate(state)
        assertEquals(0, v.settledCount)
    }

    @Test
    fun consistentWinnerIsEdge() {
        val fills = (1..40).map { fill("w$it", 2.0, won = true) }
        val v = EdgeExperiment.evaluate(PaperBookState(fills = fills))
        assertEquals(40, v.settledCount)
        assertTrue(v.edgeDetected)
        assertTrue(v.verdictLine.contains("Edge emerging", ignoreCase = true))
    }

    @Test
    fun confidentEdgeAt100Windows() {
        val fills = (1..100).map { fill("w$it", 2.0, won = true) }
        val v = EdgeExperiment.evaluate(PaperBookState(fills = fills))
        assertTrue(v.edgeDetected)
        assertTrue(v.enoughSamples)
        assertTrue(v.verdictLine.contains("EDGE DETECTED"))
    }

    @Test
    fun coinFlipIsNotEdge() {
        val fills = (1..60).map {
            if (it % 2 == 0) fill("w$it", 4.5, won = true) else fill("l$it", -5.0, won = false)
        }
        val v = EdgeExperiment.evaluate(PaperBookState(fills = fills))
        assertFalse(v.edgeDetected)
        assertTrue(v.verdictLine.contains("No edge"))
    }

    @Test
    fun smallSampleNeedsMoreData() {
        val fills = (1..10).map { fill("w$it", 2.0, won = true) }
        val v = EdgeExperiment.evaluate(PaperBookState(fills = fills))
        assertFalse(v.edgeDetected)
        assertTrue(v.verdictLine.contains("Collecting"))
        assertTrue(v.detailLine.contains("more"))
    }
}

class PaperAutoGateTest {

    @Test
    fun nullQuotePasses() {
        assertTrue(PaperAutoGate.passes(null))
        assertNull(PaperAutoGate.reason(null))
    }

    @Test
    fun tightSpreadPasses() {
        val q = PaperAutoGate.Quote(bestBid = 0.49, bestAsk = 0.51, secondsLeft = 300)
        assertTrue(PaperAutoGate.passes(q))
        assertNull(PaperAutoGate.reason(q))
    }

    @Test
    fun wideSpreadSkips() {
        val q = PaperAutoGate.Quote(bestBid = 0.40, bestAsk = 0.55, secondsLeft = 300)
        assertFalse(PaperAutoGate.passes(q))
        assertTrue(PaperAutoGate.reason(q)!!.contains("spread"))
    }

    @Test
    fun lateWindowSkips() {
        val q = PaperAutoGate.Quote(bestBid = 0.49, bestAsk = 0.50, secondsLeft = 30)
        assertFalse(PaperAutoGate.passes(q))
        assertTrue(PaperAutoGate.reason(q)!!.contains("left in the window"))
    }

    @Test
    fun missingBidDoesNotBlock() {
        val q = PaperAutoGate.Quote(bestBid = null, bestAsk = 0.50, secondsLeft = 300)
        assertTrue(PaperAutoGate.passes(q))
    }

    @Test
    fun crossedQuoteIsNullSpread() {
        val q = PaperAutoGate.Quote(bestBid = 0.55, bestAsk = 0.50, secondsLeft = 300)
        assertTrue(PaperAutoGate.passes(q))
    }
}
