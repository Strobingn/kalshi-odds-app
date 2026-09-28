package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestScorecardTest {
    @Test
    fun notEnoughDataUnderFiftyBtc() {
        val rows = (0 until 20).map { i ->
            PredictionLogEntry(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                predictedYes = 0.6,
                predictedNo = 0.4,
                marketMid = 0.55,
                timestampMs = 1_000L * i,
                closeTimeMs = 2_000L * i,
                outcome = "yes",
                score = 1,
                brier = 0.16,
                predictedSide = "YES",
                edgePp = 5.0
            )
        }
        val h = ScorecardMetrics.honest(rows)
        assertEquals(20, h.n)
        assertEquals(20, h.hits)
        assertFalse(h.enoughData)
        assertTrue(h.showBrier)
        assertTrue(h.modelBrier != null)
        assertTrue(h.marketBrier != null)
        assertTrue(h.sideBrier != null)
        assertEquals(listOf("BTC"), h.perAsset.map { ScorecardMetrics.coinOf(it.series) }.distinct())
    }

    @Test
    fun enoughAtFiftyBtcAndEthRowsDoNotCount() {
        val btc = (0 until 50).map { i ->
            PredictionLogEntry(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                predictedYes = 0.7,
                predictedNo = 0.3,
                marketMid = 0.5,
                timestampMs = i.toLong(),
                closeTimeMs = i.toLong(),
                outcome = if (i % 2 == 0) "yes" else "no",
                predictedSide = "YES",
                edgePp = 4.0
            )
        }
        val eth = (0 until 50).map { i ->
            PredictionLogEntry(
                ticker = "KXETH15M-$i",
                series = "KXETH15M",
                predictedYes = 0.7,
                predictedNo = 0.3,
                marketMid = 0.5,
                timestampMs = i.toLong(),
                closeTimeMs = i.toLong(),
                outcome = if (i % 2 == 0) "yes" else "no",
                predictedSide = "YES",
                edgePp = 4.0
            )
        }
        val mixed = ScorecardMetrics.honest(btc + eth)
        val scored = ScorecardMetrics.settledScoredPicks(btc + eth)
        assertEquals(50, scored.size)
        assertEquals(50, mixed.n)
        assertTrue(scored.all { it.ticker.startsWith("KXBTC") })
        assertEquals(50, mixed.perAsset.single().stats.total)
        assertTrue(mixed.enoughData)
        assertEquals(1, mixed.perAsset.size)
        assertEquals("BTC", ScorecardMetrics.coinOf(mixed.perAsset.single().series))
        assertEquals(listOf("BTC"), mixed.perCoin.map { it.key })
        assertFalse(mixed.perCoin.any { it.key == "ETH" || it.key == "SOL" })
    }
}
