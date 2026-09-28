package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardTargetsTest {

    @Test
    fun bitcoinOnlyFloorsAreFiftyAndFive() {
        assertEquals(50, ScorecardTargets.MIN_SETTLED_BTC_SIGNALS)
        assertEquals(5, ScorecardTargets.MIN_PER_SLOT_SAMPLES)
        assertEquals(50, ScorecardTargets.settledTarget())
        assertEquals(5, ScorecardTargets.perSlotMinimum())
        assertEquals(ScorecardTargets.MIN_SETTLED_BTC_SIGNALS, ScorecardMetrics.MIN_HONEST_SAMPLES)
        assertEquals(ScorecardTargets.MIN_PER_SLOT_SAMPLES, ScorecardMetrics.MIN_BUCKET_SAMPLES)
        assertEquals(listOf("BTC"), ScorecardMetrics.COIN_ORDER)
        assertFalse(ScorecardMetrics.COIN_ORDER.contains("SOL"))
        assertFalse(ScorecardMetrics.COIN_ORDER.contains("ETH"))
        assertTrue(ScorecardLedger.isScorecardTicker("KXBTC15M-26SEP251200-00"))
        assertFalse(ScorecardLedger.isScorecardTicker("KXETH15M-26SEP251200-00"))
        assertFalse(ScorecardLedger.isScorecardTicker("KXSOL15M-26SEP251200-00"))
    }

    @Test
    fun scorecardAggregatesAndPolicyEvalAreBitcoinOnly() {
        val btcSettled = entry("KXBTC15M-S", "KXBTC15M", outcome = "yes")
        val ethSettled = entry("KXETH15M-S", "KXETH15M", outcome = "yes")
        val solSettled = entry("KXSOL15M-S", "KXSOL15M", outcome = "yes")
        val btcOpen = entry("KXBTC15M-O", "KXBTC15M", outcome = null)
        val ethOpen = entry("KXETH15M-O", "KXETH15M", outcome = null)
        val btcVoid = entry("KXBTC15M-V", "KXBTC15M", outcome = "void")
        val ethVoid = entry("KXETH15M-V", "KXETH15M", outcome = "void")
        val snap = ScorecardMetrics.compute(
            listOf(btcSettled, ethSettled, solSettled, btcOpen, ethOpen, btcVoid, ethVoid)
        )
        assertEquals(1, snap.sampleCount)
        assertEquals(1, snap.openCount)
        assertEquals(1, snap.voidCount)
        assertEquals(1, snap.policy!!.allAlerts.n)
        assertTrue(snap.perSeries.all { it.series.startsWith("KXBTC") || it.series == "KXBTC15M" })
    }

    private fun entry(ticker: String, series: String, outcome: String?) = PredictionLogEntry(
        ticker = ticker,
        series = series,
        predictedYes = 0.70,
        predictedNo = 0.30,
        marketMid = 0.50,
        timestampMs = 1L,
        closeTimeMs = 2L,
        outcome = outcome,
        predictedSide = "YES",
        edgePp = 8.0,
        confidence = 0.60,
        wouldAlert = true,
        settledAtMs = if (outcome == null) null else 2L
    )
}
