package com.dirk.kalshiodds.signal.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** prev_window_return parity: the window BEFORE the market's open, not the last 15 minutes before now. */
class PrevWindowReturnTest {

    private val open = 1_791_550_800_000L   // a quarter hour

    /** Prints every 5 s: 100 up to 15 min before the open, 101 from the open on, 100.5 in between. */
    private fun book(untilMs: Long): SpotStreamBook {
        val b = SpotStreamBook()
        var t = open - 960_000L
        while (t <= untilMs) {
            val px = when {
                t <= open - 900_000L -> 100.0
                t >= open -> 101.0
                else -> 100.5
            }
            b.onPrint("BTC", px, t)
            t += 5_000L
        }
        return b
    }

    @Test
    fun returnRunsFromFifteenMinutesBeforeTheOpenToTheOpen() {
        val b = book(open + 30_000L)
        assertEquals(0.01, b.prevWindowReturn("BTC", open, open + 30_000L)!!, 1e-12)
        assertNull("not known before the window opens", b.prevWindowReturn("BTC", open + 900_000L, open + 30_000L))
        assertNull("nothing on the tape that far back", b.prevWindowReturn("BTC", open - 3_600_000L, open + 30_000L))
        assertNull(b.prevWindowReturn("ETH", open, open + 30_000L))
    }

    @Test
    fun theValueIsKeptForTheRestOfTheWindow() {
        val b = book(open + 30_000L)
        val first = b.prevWindowReturn("BTC", open, open + 30_000L)!!
        // Ten minutes later the tape no longer reaches 15 minutes before the open, and the price has moved.
        var t = open + 35_000L
        while (t <= open + 600_000L) {
            b.onPrint("BTC", 102.0, t)
            t += 5_000L
        }
        assertEquals(first, b.prevWindowReturn("BTC", open, open + 600_000L)!!, 0.0)
        // The old trailing-15-minute return would have been ~1% here instead of the previous window's move.
        val trailing = b.latest("BTC", open + 600_000L)!!.return15m
        assertEquals(true, trailing == null || kotlin.math.abs(trailing - first) > 1e-6)
    }
}
