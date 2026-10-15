package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.*
import org.junit.Test

class PaperScalpSignalsTest {
    private fun q(at: Long, bid: Double, ask: Double = bid + .01, depth: Double = 1000.0) =
        PaperDepth("KXBTC15M-SIGNAL", at, at, 900000, listOf(bid to depth), listOf((1 - ask) to 500.0))
    @Test fun signedForecastFollowsInsteadOfFadesAndTargetsBidAfterHaircut() {
        assertNotNull(PaperScalpSignals.forecastTarget(.3, .01, .20, "YES"))
        assertNull(PaperScalpSignals.forecastTarget(.3, .01, .20, "NO"))
        assertNull(PaperScalpSignals.forecastTarget(.3, .01, -.20, "YES"))
        assertEquals(.445, PaperScalpSignals.forecastTarget(.3, .01, -.20, "NO")!!, 1e-9)
        assertNull(PaperScalpSignals.forecastTarget(.3, .01, Double.NaN, "YES"))
    }
    @Test fun extraUnchangedPacketsCannotTurnFlatBookIntoMomentum() {
        for (interval in listOf(250L, 1000L)) {
            val rows = (1000L..60000L step interval).map { q(it, .35) }
            assertTrue(PaperScalpSignals.scan(q(61000, .35), rows).signals.none { it.setup == "momentum" })
        }
    }
    @Test fun signalTargetsAndRiskAreInvariantToDuplicatedQuoteRate() {
        val slow = (1000L..31000L step 1000).map { q(it, .62) } + q(32000, .35)
        val fast = slow.flatMap { listOf(it.copy(atMs = it.atMs - 500, revision = it.revision - 500), it) }
        val a = PaperScalpSignals.scan(q(33000, .39), slow).signals.associateBy { it.setup }
        val b = PaperScalpSignals.scan(q(33000, .39), fast).signals.associateBy { it.setup }
        assertTrue(a.containsKey("cheap")); assertEquals(a.keys, b.keys)
        a.forEach { (id, signal) ->
            assertEquals(signal.targetBid, b.getValue(id).targetBid, 1e-9)
            assertEquals(signal.risk, b.getValue(id).risk, 1e-9)
        }
    }
    @Test fun costAndRiskRejectSmallBouncesEvenWhenThereIsASignal() {
        val history = (1000L..31000L step 1000).map { q(it, .44) } + q(32000, .36)
        val scan = PaperScalpSignals.scan(q(33000, .39), history)
        assertTrue(scan.signals.none { it.setup == "cheap" || it.setup == "dip" })
        assertTrue(scan.note.contains("fees"))
    }
    @Test fun continuousHistoryCannotBridgeFeedOutage() {
        val before = (1000L..31000L step 1000).map { q(it, .62) }
        val scan = PaperScalpSignals.scan(q(60000, .39), before)
        assertTrue(scan.signals.isEmpty()); assertTrue(scan.note.contains("continuous"))
    }
    @Test fun trendUsesThirtySecondsAndNeedsSupportingDepth() {
        val rows = (1000L..31000L step 1000).map { t -> q(t, .08 + t / 1000 * .01) }
        val supported = PaperScalpSignals.scan(q(32000, .40), rows).signals
        assertTrue(supported.any { it.setup == "momentum" })
        assertTrue(PaperScalpSignals.scan(q(32000, .40, depth = 10.0), rows).signals.none { it.setup == "momentum" })
    }
    @Test fun everyReturnedSignalStaysUnder50AndClearsRewardRisk() {
        val rows = (1000L..31000L step 1000).map { q(it, .62) } + q(32000, .35)
        val signals = PaperScalpSignals.scan(q(33000, .39), rows).signals
        assertTrue(signals.isNotEmpty())
        assertTrue(signals.all { it.ask < .50 && it.rewardRisk >= 1.25 && it.targetBid > it.ask })
    }
}
