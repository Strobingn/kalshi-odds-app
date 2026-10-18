package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home paper cash reset archives the ledger and restores cash. It does not delete history or call Kalshi. */
class HomePaperCashTest {

    @Test fun labelsUseADashWhenTheNumberIsMissing() {
        assertEquals("—", HomePaperCash.cash(Double.NaN))
        assertEquals("—", HomePaperCash.pnl(Double.NaN))
        assertEquals("$19.50", HomePaperCash.cash(19.5))
        assertTrue(HomePaperCash.pnl(-2.5).startsWith("-") || HomePaperCash.pnl(-2.5).startsWith("−") || HomePaperCash.pnl(-2.5).contains("-"))
        assertTrue(HomePaperCash.pnl(2.5).contains("+"))
    }

    @Test fun confirmCopyArchivesAndDoesNotTouchKalshi() {
        assertTrue(HomePaperCash.BUTTON.contains("Reset paper cash"))
        assertTrue(HomePaperCash.BODY.contains("archived", ignoreCase = true))
        assertTrue(HomePaperCash.BODY.contains("not deleted", ignoreCase = true) || HomePaperCash.BODY.contains("Nothing is deleted"))
        assertTrue(HomePaperCash.BODY.contains("Kalshi"))
        assertFalse(HomePaperCash.BODY.contains("createOrder", ignoreCase = true))
    }

    @Test fun resetRestoresCashAndKeepsThePreviousRun() {
        val prior = PaperFill(
            id = "p1", ticker = "KXBTC15M-26OCT122015-15", side = "YES", stakeUsd = 10.0,
            contracts = 20, limitPrice = 0.50, source = "paper", createdAtMs = 1_000L,
            settled = true, won = false, pnlUsd = -10.0, note = "paper fill"
        )
        val book = PaperBook(
            initial = PaperBookState(startingUsd = 20_000.0, cashUsd = 19_990.0, fills = listOf(prior)),
            nowMs = { 5_000L }
        )
        book.reset(20_000.0)
        val next = book.snapshot()
        assertEquals(20_000.0, next.cashUsd, 1e-9)
        assertEquals(20_000.0, next.startingUsd, 1e-9)
        assertTrue(next.fills.isEmpty())
        assertEquals(1, next.archived.size)
        assertEquals("p1", next.archived.single().fills.single().id)
        assertEquals(-10.0, next.archived.single().fills.single().pnlUsd!!, 1e-9)
        assertTrue(next.lastMessage!!.contains("archived"))
    }
}
