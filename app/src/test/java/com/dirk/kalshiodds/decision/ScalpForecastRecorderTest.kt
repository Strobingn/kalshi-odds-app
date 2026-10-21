package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpForecastRecorderTest {
    private val close = 1_800_000_000_000L

    @Test fun recordsOneUpForecastPerCoinAndRespectsCadence() {
        val rows = ArrayList<LedgerRow>()
        val recorder = ScalpForecastRecorder { rows += it; true }
        val now = close - 600_000L
        recorder.observe(
            listOf(
                quote("KXBTC15M-26OCT181530-30", now, strike = 100.0, spot = 101.0),
                quote("KXBTC15M-26OCT181530-31", now, strike = 110.0, spot = 101.0),
                quote("KXETH15M-26OCT181530-30", now, strike = 200.0, spot = 200.0)
            )
        ) { 0.64 }
        assertEquals(listOf("BTC", "ETH"), rows.map { ScalpParams.coinOf(it.ticker) }.sorted())
        val btc = rows.first { it.ticker.startsWith("KXBTC") }
        assertEquals("KXBTC15M-26OCT181530-30", btc.ticker)
        assertEquals(0.64, btc.finalProb!!, 1e-12)
        assertEquals(0.64, btc.rawModelProb!!, 1e-12)
        assertEquals("YES", btc.side)
        assertEquals("FORECAST", btc.decision)
        assertEquals(ScalpForecastRecorder.MODEL, btc.modelVersion)
        assertEquals(null, btc.sizeContracts)
        assertEquals(null, btc.orderPrice)
        assertTrue(btc.marketMid!! in 0.0..1.0)
        recorder.observe(listOf(quote("KXBTC15M-26OCT181530-30", now + 10_000, strike = 100.0, spot = 101.0))) { 0.9 }
        assertEquals(2, rows.size)
        recorder.observe(listOf(quote("KXBTC15M-26OCT181530-30", now + 61_000, strike = 100.0, spot = 101.0))) { 0.7 }
        assertEquals(0.7, rows.last().finalProb!!, 1e-12)
    }

    @Test fun skipsStaleBooksAndMissingProbabilities() {
        val rows = ArrayList<LedgerRow>()
        val recorder = ScalpForecastRecorder { rows += it; true }
        val now = close - 120_000L
        val stale = quote("KXBTC15M-26OCT181530-30", now, strike = 100.0, spot = 101.0).copy(bookAtMs = now - 60_000)
        recorder.observe(listOf(stale)) { 0.5 }
        recorder.observe(listOf(quote("KXBTC15M-26OCT181530-30", now, strike = 100.0, spot = 101.0))) { null }
        recorder.observe(listOf(quote("KXBTCD-26OCT1817", now, strike = 100.0, spot = 101.0))) { 0.5 }
        assertTrue(rows.isEmpty())
    }

    @Test fun closedWindowsDropOutOfTheCadenceMap() {
        val recorder = ScalpForecastRecorder { true }
        val openAt = close - 120_000L
        recorder.observe(listOf(quote("KXBTC15M-26OCT181530-30", openAt, strike = 100.0, spot = 101.0))) { 0.5 }
        assertEquals(setOf(close), recorder.trackedCloses())
        val nextClose = close + 900_000L
        recorder.observe(
            listOf(quote("KXBTC15M-26OCT181545-45", close + 60_000L, strike = 100.0, spot = 101.0).copy(closeMs = nextClose))
        ) { 0.5 }
        assertEquals(setOf(nextClose), recorder.trackedCloses())
    }

    @Test fun batchesDueCoinsIntoOneInsert() {
        var calls = 0
        val batches = ArrayList<List<LedgerRow>>()
        val recorder = ScalpForecastRecorder(
            insert = { error("single insert") },
            insertBatch = { rows ->
                calls++
                batches += rows
                rows.size
            }
        )
        val now = close - 200_000L
        recorder.observe(
            listOf(
                quote("KXBTC15M-26OCT181530-30", now, strike = 100.0, spot = 101.0),
                quote("KXETH15M-26OCT181530-30", now, strike = 200.0, spot = 200.0),
                quote("KXSOL15M-26OCT181530-30", now, strike = 150.0, spot = 150.0)
            )
        ) { 0.55 }
        assertEquals(1, calls)
        assertEquals(listOf("BTC", "ETH", "SOL"), batches.single().map { ScalpParams.coinOf(it.ticker) }.sorted())
        recorder.observe(listOf(quote("KXBTC15M-26OCT181530-30", now + 5_000, strike = 100.0, spot = 101.0))) { 0.2 }
        assertEquals(1, calls)
    }

    @Test fun failedBatchDoesNotBurnTheCadence() {
        var ok = false
        val batches = ArrayList<List<LedgerRow>>()
        val recorder = ScalpForecastRecorder(
            insert = { false },
            insertBatch = { rows ->
                if (!ok) 0 else {
                    batches += rows
                    rows.size
                }
            }
        )
        val now = close - 90_000L
        val q = quote("KXSOL15M-26OCT181530-30", now, strike = 150.0, spot = 150.0)
        recorder.observe(listOf(q)) { 0.4 }
        assertTrue(batches.isEmpty())
        ok = true
        recorder.observe(listOf(q.copy(nowMs = now + 1_000))) { 0.4 }
        assertEquals(1, batches.single().size)
    }

    @Test fun failedInsertDoesNotBurnTheCadence() {
        var ok = false
        val rows = ArrayList<LedgerRow>()
        val recorder = ScalpForecastRecorder {
            if (!ok) false else {
                rows += it
                true
            }
        }
        val now = close - 90_000L
        val q = quote("KXSOL15M-26OCT181530-30", now, strike = 150.0, spot = 150.0)
        recorder.observe(listOf(q)) { 0.4 }
        assertTrue(rows.isEmpty())
        ok = true
        recorder.observe(listOf(q.copy(nowMs = now + 1_000))) { 0.4 }
        assertEquals(1, rows.size)
        assertEquals("KXSOL15M", rows.single().series)
    }

    private fun quote(ticker: String, now: Long, strike: Double, spot: Double) = ScalpRule.Quote(
        ticker = ticker,
        nowMs = now,
        closeMs = close,
        bookAtMs = now,
        yesBid = 0.48,
        yesBidSize = 10.0,
        yesAsk = 0.52,
        yesAskSize = 8.0,
        spot = spot,
        strike = strike,
        sigmaPerSec = 1e-4
    )
}
