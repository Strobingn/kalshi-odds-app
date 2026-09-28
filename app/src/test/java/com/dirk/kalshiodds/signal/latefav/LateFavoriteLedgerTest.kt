package com.dirk.kalshiodds.signal.latefav

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LateFavoriteLedgerTest {

    private fun decision(
        ticker: String = "KXBTC15M-26SEP281215-15",
        side: String = "YES",
        ask: Double = 0.95
    ): LateFavoriteRule.Decision = LateFavoriteRule.Decision(
        ticker = ticker,
        nowMs = 1_000L,
        tteSeconds = 200L,
        z = if (side == "YES") 2.1 else -2.1,
        side = side,
        ask = ask,
        sized = LateFavoriteRule.sizeAllIn(ask)!!,
        worseAsk = ask + 0.01,
        worse = LateFavoriteRule.sizeAllIn(ask + 0.01)
    )

    @Test
    fun recordsOneEntryPerMarket() {
        val ledger = LateFavoriteLedger()
        val row = ledger.record(decision())
        assertNotNull(row)
        assertEquals(5, row!!.contracts)
        assertEquals(4.77, row.costUsd, 1e-9)
        assertEquals(4.82, row.worseCostUsd, 1e-9)
        assertTrue(ledger.hasEntry("kxbtc15m-26sep281215-15"))
        assertNull(ledger.record(decision()))
        assertEquals(1, ledger.snapshot().entries.size)
        assertEquals(1, ledger.snapshot().openCount)
        assertEquals(setOf("KXBTC15M-26SEP281215-15"), ledger.openTickers())
    }

    @Test
    fun winSettlesWithFeeAwarePnlAndWorseFill() {
        val ledger = LateFavoriteLedger(nowMs = { 9_000L })
        ledger.record(decision())
        val done = ledger.settle("KXBTC15M-26SEP281215-15", "yes")
        assertEquals(1, done.size)
        val e = done.single()
        assertEquals(true, e.won)
        assertEquals(0.23, e.pnlUsd!!, 1e-9)
        assertEquals(0.18, e.pnlWorseUsd!!, 1e-9)
        assertEquals(9_000L, e.settledAtMs)
        val t = ledger.snapshot().totals
        assertEquals(1, t.wins)
        assertEquals(0, t.losses)
        assertEquals(0.23, t.pnlUsd, 1e-9)
        assertEquals(0.18, t.pnlWorseUsd, 1e-9)
        assertEquals(0, ledger.snapshot().openCount)
        assertTrue(ledger.openTickers().isEmpty())
        // Settling twice is a no-op.
        assertTrue(ledger.settle("KXBTC15M-26SEP281215-15", "yes").isEmpty())
        assertEquals(1, ledger.snapshot().totals.wins)
    }

    @Test
    fun lossAndNoSideAndVoid() {
        val ledger = LateFavoriteLedger()
        ledger.record(decision(ticker = "KXBTC15M-A", side = "YES"))
        ledger.record(decision(ticker = "KXBTC15M-B", side = "NO", ask = 0.90))
        ledger.record(decision(ticker = "KXBTC15M-C"))
        ledger.settle("KXBTC15M-A", "no")
        ledger.settle("KXBTC15M-B", "no")
        ledger.settle("KXBTC15M-C", "void")
        val byTicker = ledger.snapshot().entries.associateBy { it.ticker }
        assertEquals(false, byTicker.getValue("KXBTC15M-A").won)
        assertEquals(-4.77, byTicker.getValue("KXBTC15M-A").pnlUsd!!, 1e-9)
        assertEquals(-4.82, byTicker.getValue("KXBTC15M-A").pnlWorseUsd!!, 1e-9)
        assertEquals(true, byTicker.getValue("KXBTC15M-B").won)
        assertEquals(5 - 4.54, byTicker.getValue("KXBTC15M-B").pnlUsd!!, 1e-9)
        assertNull(byTicker.getValue("KXBTC15M-C").won)
        assertEquals(0.0, byTicker.getValue("KXBTC15M-C").pnlUsd!!, 1e-9)
        val t = ledger.snapshot().totals
        assertEquals(1, t.wins)
        assertEquals(1, t.losses)
        assertEquals(1, t.voids)
        assertEquals(2, t.settledBets)
        assertEquals(-4.77 + 0.46, t.pnlUsd, 1e-9)
        assertEquals((-4.77 + 0.46) / 2, t.perBetUsd!!, 1e-9)
    }

    @Test
    fun ignoresUnknownResultsAndTickers() {
        val ledger = LateFavoriteLedger()
        ledger.record(decision())
        assertTrue(ledger.settle("KXBTC15M-26SEP281215-15", "pending").isEmpty())
        assertTrue(ledger.settle("KXBTC15M-OTHER", "yes").isEmpty())
        assertEquals(1, ledger.snapshot().openCount)
    }

    @Test
    fun settlesFromPredictionLogOutcomes() {
        val ledger = LateFavoriteLedger()
        ledger.record(decision())
        ledger.settleFromLog(
            listOf(
                PredictionLogEntry(
                    ticker = "KXBTC15M-26SEP281215-15",
                    series = "KXBTC15M",
                    predictedYes = 0.9,
                    predictedNo = 0.1,
                    marketMid = 0.9,
                    timestampMs = 0L,
                    closeTimeMs = 0L,
                    outcome = "yes"
                )
            )
        )
        assertEquals(1, ledger.snapshot().totals.wins)
    }

    @Test
    fun persistsEveryChangeAndRoundTripsThroughJson() {
        var saved: LateFavoriteState? = null
        val ledger = LateFavoriteLedger(persist = { saved = it })
        ledger.record(decision())
        ledger.settle("KXBTC15M-26SEP281215-15", "yes")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val raw = json.encodeToString(LateFavoriteState.serializer(), saved!!)
        val restored = LateFavoriteLedger(initial = json.decodeFromString(LateFavoriteState.serializer(), raw))
        assertEquals(saved, restored.snapshot())
        // One-per-market survives a restart.
        assertTrue(restored.hasEntry("KXBTC15M-26SEP281215-15"))
        assertNull(restored.record(decision()))
    }

    @Test
    fun capsEntriesButKeepsAllTimeTotals() {
        val ledger = LateFavoriteLedger()
        val n = LateFavoriteLedger.MAX_ENTRIES + 5
        for (i in 0 until n) {
            ledger.record(decision(ticker = "KXBTC15M-T$i"))
            ledger.settle("KXBTC15M-T$i", "yes")
        }
        assertEquals(LateFavoriteLedger.MAX_ENTRIES, ledger.snapshot().entries.size)
        assertEquals(n, ledger.snapshot().totals.wins)
        assertEquals("KXBTC15M-T${n - 1}", ledger.snapshot().entries.first().ticker)
    }

    @Test
    fun resetClearsLedger() {
        val ledger = LateFavoriteLedger()
        ledger.record(decision())
        ledger.reset()
        assertTrue(ledger.snapshot().entries.isEmpty())
        assertEquals(0, ledger.snapshot().totals.settledBets)
        assertFalse(ledger.hasEntry("KXBTC15M-26SEP281215-15"))
        assertNotNull(ledger.record(decision()))
    }

    @Test
    fun summaryShowsRecordPnlWorseFillOpenAndNote() {
        val ledger = LateFavoriteLedger()
        ledger.record(decision(ticker = "KXBTC15M-A"))
        ledger.record(decision(ticker = "KXBTC15M-B"))
        ledger.settle("KXBTC15M-A", "yes")
        val s = LateFavoriteSummary.of(ledger.snapshot())
        assertEquals("Late favorite · PAPER", s.title)
        assertTrue(s.recordLine, s.recordLine.startsWith("1-0"))
        assertTrue(s.recordLine, s.recordLine.contains("1 / 2000 settled"))
        assertTrue(s.pnlLine, s.pnlLine.contains("+$0.23"))
        assertTrue(s.pnlLine, s.pnlLine.contains("/bet"))
        assertTrue(s.worseLine, s.worseLine.contains("+$0.18"))
        assertEquals("Open: B UP @ 95¢", s.openLine)
        assertEquals(
            "Paper only. Unproven: needs ~2,000 settled windows. Loses ~19 wins per loss at 95¢.",
            s.note
        )
        val empty = LateFavoriteSummary.of(LateFavoriteState())
        assertTrue(empty.recordLine.startsWith("0-0"))
        assertEquals("Open: none", empty.openLine)
    }
}
