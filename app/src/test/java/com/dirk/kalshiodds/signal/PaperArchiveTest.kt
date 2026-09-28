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
}
