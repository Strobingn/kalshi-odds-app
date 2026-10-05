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
        assertEquals("NO", flipped.predictedSide)
        assertNull(flipped.entryAsk)

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
}
