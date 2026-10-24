package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index history saved across app starts comes back without overwriting live prints. */
class CfIndexRestoreTest {
    private fun print(book: CfIndexBook, value: Double, nowMs: Long) =
        book.onUpdate(KalshiWsMessages.Parsed.IndexValue("BRTI", value, nowMs, nowMs, null, null, null, null), nowMs)

    @Test
    fun restoredSecondsFillTheSeriesAndLivePrintsWin() {
        val now = 1_800_000_000_000L
        val saved = CfIndexBook()
        for (i in 0 until 400) print(saved, 100_000.0 + i, now - (400 - i) * 1000L)
        val pts = saved.export("BTC")
        assertEquals(400, pts.size)

        // A fresh start: one live print, then the saved history.
        val book = CfIndexBook()
        print(book, 123_456.0, now)
        val n = book.restore("BTC", pts + (now / 1000L - 10_000L to 1.0), now)
        assertEquals("the one older than the kept history is dropped", 400, n)
        val s = book.series("BTC", now, 512)
        assertEquals(401, s.count { it.isFinite() })
        assertEquals(123_456.0, s.last(), 0.0)
        assertTrue(book.std1m("BTC", now) != null)
        // Restoring the same file again adds nothing: none of it is older than what is already there.
        assertEquals(0, book.restore("BTC", pts, now))
    }
}
