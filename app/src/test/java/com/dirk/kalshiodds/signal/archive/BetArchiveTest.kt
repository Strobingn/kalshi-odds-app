package com.dirk.kalshiodds.signal.archive

import com.dirk.kalshiodds.signal.latefav.LateFavoriteEntry
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.signal.paper.PaperArchive
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetArchiveTest {

    private fun entry(ticker: String, at: Long, pnl: Double? = null) = LateFavoriteEntry(
        ticker = ticker, entryAtMs = at, tteSeconds = 400, z = 84.0, side = "YES", ask = 0.84, contracts = 5,
        costUsd = 4.32, feeUsd = 0.12, worseAsk = 0.85,
        result = pnl?.let { if (it > 0) "yes" else "no" }, won = pnl?.let { it > 0 }, pnlUsd = pnl, settledAtMs = pnl?.let { at + 500_000L }
    )

    private fun fill(id: String, at: Long) = PaperFill(
        id = id, ticker = "KXBTC15M-26OCT201215-15", side = "no", stakeUsd = 4.0, contracts = 10, limitPrice = 0.40,
        source = "AI signal", createdAtMs = at, feeUsd = 0.17, note = "n"
    )

    @Test
    fun aSaveWritesOnlyNewAndChangedBets() {
        val written = ArrayList<List<BetRow>>()
        val diff = BetArchiveDiff { written += it }
        val a = entry("KXBTC15M-A", 1_000L)
        diff.offer(BetRows.ofTracker(BetRows.CLEAR_LEAD, LateFavoriteState(entries = listOf(a))))
        diff.offer(BetRows.ofTracker(BetRows.CLEAR_LEAD, LateFavoriteState(entries = listOf(a))))
        assertEquals("nothing changed, nothing written", 1, written.size)
        val settled = a.copy(result = "yes", won = true, pnlUsd = 0.68, settledAtMs = 2_000L)
        val b = entry("KXBTC15M-B", 3_000L)
        diff.offer(BetRows.ofTracker(BetRows.CLEAR_LEAD, LateFavoriteState(entries = listOf(b, settled))))
        assertEquals(2, written.size)
        assertEquals(setOf("Clear lead|KXBTC15M-A|1000", "Clear lead|KXBTC15M-B|3000"), written[1].map { it.id }.toSet())
        assertEquals(0.68, written[1].first { it.ticker == "KXBTC15M-A" }.pnlUsd!!, 0.0)
        // A reset empties the tracker; the archive is never told to delete.
        diff.offer(emptyList())
        assertEquals(2, written.size)
    }

    @Test
    fun thePaperBookIncludesItsArchivedRuns() {
        val state = PaperBookState(
            fills = listOf(fill("f2", 2_000L)),
            archived = listOf(PaperArchive(archivedAtMs = 1_500L, startingUsd = 100.0, cashUsd = 90.0, fills = listOf(fill("f1", 1_000L))))
        )
        val rows = BetRows.ofPaper(state)
        assertEquals(listOf("paper|f2", "paper|f1"), rows.map { it.id })
        assertEquals("NO", rows[0].side)
        assertEquals(4.17, rows[0].costUsd, 1e-9)
    }

    @Test
    fun csvAndSummary() {
        val rows = listOf(
            BetRows.ofTracker(BetRows.CLEAR_LEAD, entry("KXBTC15M-A", 86_400_000L, pnl = 0.68)),
            BetRows.ofTracker(BetRows.CLEAR_LEAD, entry("KXBTC15M-B", 86_400_000L + 900_000L, pnl = -4.32)),
            BetRows.ofTracker(BetRows.LATE_FAVORITE, entry("KXBTC15M-C", 2 * 86_400_000L)),
            BetRows.ofPaper(fill("f1", 2 * 86_400_000L + 1L)).copy(note = "has, a comma")
        )
        val csv = BetArchiveCsv.csv(rows)
        assertTrue(csv.startsWith(BetArchiveCsv.HEADER + "\n"))
        assertEquals(5, csv.trimEnd().lines().size)
        assertTrue(csv.contains("\"has, a comma\""))
        val by = BetArchiveSummary.bySource(rows).associateBy { it.name }
        assertEquals(2, by.getValue(BetRows.CLEAR_LEAD).settled)
        assertEquals(1, by.getValue(BetRows.CLEAR_LEAD).wins)
        assertEquals(-3.64, by.getValue(BetRows.CLEAR_LEAD).pnlUsd, 1e-9)
        assertEquals(0, by.getValue(BetRows.LATE_FAVORITE).settled)
        assertEquals(listOf(0.68, -3.64), BetArchiveSummary.cumulative(rows).map { Math.round(it.second * 100) / 100.0 })
        assertEquals(listOf("1970-01-02" to 2, "1970-01-03" to 2), BetArchiveSummary.perDay(rows))
    }
}
