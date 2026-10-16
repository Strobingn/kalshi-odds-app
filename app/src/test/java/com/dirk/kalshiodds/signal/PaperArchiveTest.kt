package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperArchiveTest {
    @Test
    fun resetArchivesInsteadOfDeleting() {
        val book = PaperBook(
            initial = PaperBookState(
                cashUsd = 80.0,
                fills = listOf(
                    PaperFill(
                        id = "p1",
                        ticker = "KXBTC15M-A",
                        side = "YES",
                        stakeUsd = 5.0,
                        contracts = 10,
                        limitPrice = 0.50,
                        source = "AI hunter",
                        createdAtMs = 1L,
                        note = "t"
                    )
                )
            )
        )
        book.reset()
        val snap = book.snapshot()
        assertEquals(com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_START_USD, snap.cashUsd, 1e-9)
        assertTrue(snap.fills.isEmpty())
        assertEquals(1, snap.archived.size)
        assertEquals(1, snap.archived[0].fills.size)
        assertEquals("KXBTC15M-A", snap.archived[0].fills[0].ticker)
    }

    @Test
    fun upsertFromSyncSkipsFillIdsAlreadyArchived() {
        val archived = PaperFill(
            id = "pre-reset",
            ticker = "KXBTC15M-OLD",
            side = "YES",
            stakeUsd = 10.0,
            contracts = 9_346,
            limitPrice = 0.001,
            source = "AI hunter",
            createdAtMs = 1L,
            settled = true,
            won = true,
            pnlUsd = 9_336.0,
            note = "archived"
        )
        val book = PaperBook(
            initial = PaperBookState(
                startingUsd = 1_000.0,
                cashUsd = 1_000.0,
                lifetimeRealizedPnlUsd = 0.0,
                archived = listOf(
                    com.dirk.kalshiodds.signal.paper.PaperArchive(
                        archivedAtMs = 1L,
                        startingUsd = 1_000.0,
                        cashUsd = 10.0,
                        fills = listOf(archived)
                    )
                )
            )
        )
        book.upsertFromSync(archived.copy(updatedAtMs = 50L, pnlUsd = 9_336.0))
        val snap = book.snapshot()
        assertTrue(snap.fills.none { it.id == "pre-reset" })
        assertTrue(snap.syncTail.none { it.id == "pre-reset" })
        assertTrue(snap.scorecardFills().none { it.id == "pre-reset" })
        val fresh = archived.copy(id = "post-reset", pnlUsd = 1.25, updatedAtMs = 60L, limitPrice = 0.40, contracts = 2)
        book.upsertFromSync(fresh)
        assertEquals(listOf("post-reset"), book.snapshot().scorecardFills().map { it.id })
    }
}
