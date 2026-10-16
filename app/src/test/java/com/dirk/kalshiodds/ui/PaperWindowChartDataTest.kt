package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.paper.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaperWindowChartDataTest {
    private val end = 1_000_000L
    private fun fill() = PaperFill("buy", "BTC", "YES", 4.0, 10, .40,
        "AI ticket", end - 600_000, note = "paper", entryFeeUsd = .18, closeTimeMs = end)

    @Test fun partialSalesSurviveRestartAsTwoExactExitsAndOneOriginalBuy() {
        var now = end - 500_000
        var sequence = 0
        val book = PaperBook(PaperBookState(fills = listOf(fill())),
            idFactory = { "slice-${++sequence}" }, nowMs = { now })
        book.sellAtPrice("BTC", "YES", .50, 3, "TEST", "first partial")!!
        now += 5_000
        book.sellAtPrice("BTC", "YES", .46, 2, "TEST", "second partial")!!
        val restored = Json.decodeFromString<PaperBookState>(Json.encodeToString(book.snapshot()))
        val markers = PaperWindowChartData.build("BTC", end, now, emptyList(), restored).markers("YES")
        assertEquals(3, restored.fills.map { it.id }.distinct().size)
        assertEquals(1, markers.count { it.kind == ExecutionWindowData.Kind.BUY })
        assertEquals(10, markers.first { it.kind == ExecutionWindowData.Kind.BUY }.qty)
        assertEquals(listOf(.50, .46), markers.filter { it.kind == ExecutionWindowData.Kind.SELL }.map { it.price })
        assertEquals(listOf(3, 2), markers.filter { it.kind == ExecutionWindowData.Kind.SELL }.map { it.qty })
        assertEquals(listOf(end - 500_000, now), markers.filter { it.kind == ExecutionWindowData.Kind.SELL }.map { it.atMs })
        assertEquals(.18, markers.first { it.kind == ExecutionWindowData.Kind.BUY }.feeUsd!!, 1e-9)
        assertTrue(PaperWindowChartData.build("OTHER", end, now, emptyList(), restored).executions.isEmpty())
    }

    @Test fun lateSettlementPlotsAtCloseAndRetainsActualRecordedTime() {
        val book = PaperBook(PaperBookState(fills = listOf(fill().copy(side = "NO"))), nowMs = { end + 7_000 })
        book.settle("BTC", "no")
        val data = PaperWindowChartData.build("BTC", end, end + 8_000, emptyList(), book.snapshot())
        val settled = data.markers("NO").last()
        assertEquals(ExecutionWindowData.Kind.SETTLEMENT, settled.kind)
        assertEquals(end, settled.atMs)
        assertEquals(end + 7_000, settled.recordedAtMs)
        assertEquals(1.0, settled.price, 1e-9)
        assertTrue(data.markers("YES").isEmpty())
    }

    @Test fun oldLedgerDefaultsLoadWithoutInventingAnExitOrLosingArchivedBuys() {
        val legacy = fill().copy(settled = true, outcome = "sell", pnlUsd = .80)
        val json = Json { encodeDefaults = false }.encodeToString(legacy)
        val restored = Json.decodeFromString<PaperFill>(json)
        assertNull(restored.exitAtMs)
        val archived = PaperBookState(archived = listOf(PaperArchive(end, 1000.0, 1000.8, listOf(restored))))
        val markers = PaperWindowChartData.build("BTC", end, end, emptyList(), archived).markers("YES")
        assertEquals(listOf(ExecutionWindowData.Kind.BUY), markers.map { it.kind })
    }
}
