package com.dirk.kalshiodds.prediction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PredictionLogFreezeTest {

    @Test
    fun firstEntrySticksUntilTheSideChanges() {
        val kept = OpenPredictionMerge.freeze(
            prevSide = "YES",
            prevAsk = 0.40,
            prevContracts = 10,
            prevStake = 4.0,
            prevFee = 0.20,
            snapSide = "YES",
            snapAsk = 0.90,
            snapContracts = 1,
            snapStake = 0.90,
            snapFee = 0.01
        )
        assertEquals("YES", kept.predictedSide)
        assertEquals(0.40, kept.entryAsk!!, 1e-9)
        assertEquals(10, kept.contracts)
        assertEquals(4.0, kept.stakeUsd!!, 1e-9)
        assertEquals(0.20, kept.feeUsd!!, 1e-9)

        val flipped = OpenPredictionMerge.freeze(
            prevSide = "YES",
            prevAsk = 0.40,
            prevContracts = 10,
            prevStake = 4.0,
            prevFee = 0.20,
            snapSide = "NO",
            snapAsk = null,
            snapContracts = null,
            snapStake = null,
            snapFee = null
        )
        assertEquals("NO", flipped.predictedSide)
        assertNull(flipped.entryAsk)
        assertNull(flipped.contracts)
        assertNull(flipped.stakeUsd)
        assertNull(flipped.feeUsd)

        val filledLater = OpenPredictionMerge.freeze(
            prevSide = "YES",
            prevAsk = null,
            prevContracts = null,
            prevStake = null,
            prevFee = null,
            snapSide = "YES",
            snapAsk = 0.34,
            snapContracts = 8,
            snapStake = 2.90,
            snapFee = 0.18
        )
        assertEquals(0.34, filledLater.entryAsk!!, 1e-9)
        assertEquals(8, filledLater.contracts)
    }

    @Test
    fun storeKeepsFirstAskAndEvictsOpenRowsPastCloseGrace() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PredictionLogStore(ctx)
        val staleClose = 1_000L
        store.upsertOpenPrediction(
            ticker = "KXBTC15M-STALE-FREEZE",
            series = "KXBTC15M",
            predictedYes = 0.70,
            predictedNo = 0.30,
            marketMid = 0.40,
            timestampMs = staleClose - 10L,
            closeTimeMs = staleClose,
            throttleMs = 0L,
            snapshot = SignalSnapshot(
                predictedSide = "YES",
                entryAsk = 0.40,
                contracts = 10,
                stakeUsd = 4.0,
                feeUsd = 0.20
            )
        )
        store.upsertOpenPrediction(
            ticker = "KXBTC15M-STALE-FREEZE",
            series = "KXBTC15M",
            predictedYes = 0.20,
            predictedNo = 0.80,
            marketMid = 0.55,
            timestampMs = staleClose,
            closeTimeMs = staleClose,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "NO", entryAsk = null)
        )
        val flipped = store.readAll().last { it.ticker == "KXBTC15M-STALE-FREEZE" && it.outcome == null }
        assertEquals("YES", flipped.predictedSide)
        assertEquals(0.40, flipped.entryAsk!!, 1e-9)
        assertEquals(0.70, flipped.predictedYes, 1e-9)
        assertEquals(0.40, flipped.marketMid, 1e-9)

        val now = staleClose + OpenPredictionMerge.STALE_OPEN_GRACE_MS + 1_000L
        store.upsertOpenPrediction(
            ticker = "KXBTC15M-FRESH-FREEZE",
            series = "KXBTC15M",
            predictedYes = 0.60,
            predictedNo = 0.40,
            marketMid = 0.50,
            timestampMs = now,
            closeTimeMs = now + 60_000L,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "YES", entryAsk = 0.45, contracts = 4, stakeUsd = 2.0, feeUsd = 0.10)
        )
        val left = store.readAll()
        assertTrue(left.none { it.ticker == "KXBTC15M-STALE-FREEZE" && it.outcome == null })
        assertTrue(left.any { it.ticker == "KXBTC15M-FRESH-FREEZE" && it.outcome == null })
    }

    @Test
    fun settlementQuoteAndPostCloseCreateAreNotPicks() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PredictionLogStore(ctx)
        val close = 5_000_000L
        store.upsertOpenPrediction(
            ticker = "KXBTC15M-PICK",
            series = "KXBTC15M",
            predictedYes = 0.62,
            predictedNo = 0.38,
            marketMid = 0.41,
            timestampMs = close - 60_000L,
            closeTimeMs = close,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "YES", entryAsk = 0.41)
        )
        val captured = store.readAll().single { it.ticker == "KXBTC15M-PICK" }
        assertEquals(0.41, captured.entryAsk!!, 1e-9)
        assertEquals(0.41, captured.marketMid, 1e-9)
        assertTrue((captured.stakeUsd ?: 0.0) > 0.0)
        assertTrue((captured.contracts ?: 0) > 0)

        store.upsertOpenPrediction(
            ticker = "KXBTC15M-PICK",
            series = "KXBTC15M",
            predictedYes = 0.99,
            predictedNo = 0.01,
            marketMid = 1.0,
            timestampMs = close - 1_000L,
            closeTimeMs = close,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "YES", entryAsk = null)
        )
        store.upsertOpenPrediction(
            ticker = "KXBTC15M-AFTER",
            series = "KXBTC15M",
            predictedYes = 0.99,
            predictedNo = 0.01,
            marketMid = 1.0,
            timestampMs = close + 5_000L,
            closeTimeMs = close,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "YES")
        )
        val kept = store.readAll().single { it.ticker == "KXBTC15M-PICK" && it.outcome == null }
        assertEquals(0.62, kept.predictedYes, 1e-9)
        assertEquals(0.41, kept.marketMid, 1e-9)
        assertEquals(0.41, kept.entryAsk!!, 1e-9)
        assertTrue(store.readAll().none { it.ticker == "KXBTC15M-AFTER" })

        store.applySettlement("KXBTC15M-PICK", "yes", close + 10_000L)
        val settled = store.readAll().single { it.ticker == "KXBTC15M-PICK" }
        assertEquals("yes", settled.outcome)
        assertEquals(1, settled.score)

        store.upsertOpenPrediction(
            ticker = "KXBTC15M-EMPTY",
            series = "KXBTC15M",
            predictedYes = 0.97,
            predictedNo = 0.03,
            marketMid = 0.55,
            timestampMs = close - 1_000L,
            closeTimeMs = close,
            throttleMs = 0L,
            snapshot = SignalSnapshot(predictedSide = "YES")
        )
        store.applySettlement("KXBTC15M-EMPTY", "yes", close + 20_000L)
        assertTrue(store.readAll().none { it.ticker == "KXBTC15M-EMPTY" })
        val removed = store.purgeFakePicks()
        assertTrue(removed >= 0)
    }
}
