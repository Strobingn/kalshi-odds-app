package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PredictionLogLockTest {

    @Test
    fun laterUpdateKeepsTheFirstSideAndEntryPrice() {
        val first = openRow(side = "NO", ask = 0.001, contracts = 9_346, stake = 10.0)
        val flipped = lockOpenPrediction(
            prev = first,
            predictedYes = 0.80,
            predictedNo = 0.20,
            marketMid = 0.70,
            timestampMs = 60_000L,
            closeTimeMs = 90_000L,
            snapshot = SignalSnapshot(
                predictedSide = "YES",
                entryAsk = 0.62,
                contracts = 16,
                stakeUsd = 9.92,
                feeUsd = 0.22,
                fairValuePp = 80.0
            )
        )
        assertEquals("NO", flipped.predictedSide)
        assertEquals(0.001, flipped.entryAsk!!, 1e-12)
        assertEquals(9_346, flipped.contracts)
        assertEquals(10.0, flipped.stakeUsd!!, 1e-9)
        assertEquals(0.80, flipped.predictedYes, 1e-9)
        assertEquals(80.0, flipped.fairValuePp!!, 1e-9)
        assertEquals(0.70, flipped.marketMid, 1e-9)
    }

    @Test
    fun blankFirstRowCanRecordSideAndPriceOnce() {
        val blank = openRow(side = null, ask = null, contracts = null, stake = null)
        val recorded = lockOpenPrediction(
            prev = blank,
            predictedYes = 0.60,
            predictedNo = 0.40,
            marketMid = 0.50,
            timestampMs = 2_000L,
            closeTimeMs = 9_000L,
            snapshot = SignalSnapshot(
                predictedSide = "YES",
                entryAsk = 0.40,
                contracts = 24,
                stakeUsd = 9.60,
                feeUsd = 0.16
            )
        )
        assertEquals("YES", recorded.predictedSide)
        assertEquals(0.40, recorded.entryAsk!!, 1e-9)
        assertEquals(24, recorded.contracts)
        val later = lockOpenPrediction(
            prev = recorded,
            predictedYes = 0.20,
            predictedNo = 0.80,
            marketMid = 0.80,
            timestampMs = 8_000L,
            closeTimeMs = 9_000L,
            snapshot = SignalSnapshot(
                predictedSide = "NO",
                entryAsk = 0.01,
                contracts = 900,
                stakeUsd = 9.0,
                feeUsd = 0.06
            )
        )
        assertEquals("YES", later.predictedSide)
        assertEquals(0.40, later.entryAsk!!, 1e-9)
        assertEquals(24, later.contracts)
        assertNull(openRow(side = " ", ask = null, contracts = null, stake = null).predictedSide?.takeIf { it.isNotBlank() })
    }

    private fun openRow(side: String?, ask: Double?, contracts: Int?, stake: Double?) = PredictionLogEntry(
        ticker = "KXBTC15M-26OCT041915-15",
        series = "KXBTC15M",
        predictedYes = 0.20,
        predictedNo = 0.80,
        marketMid = 0.50,
        timestampMs = 1_000L,
        closeTimeMs = 90_000L,
        predictedSide = side,
        entryAsk = ask,
        contracts = contracts,
        stakeUsd = stake,
        feeUsd = if (ask != null) 0.07 else null
    )
}
