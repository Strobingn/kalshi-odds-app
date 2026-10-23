package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Features by name: every candidate is computable, long lookbacks wait for history, time features read the open. */
class PrintGridCandidatesTest {

    /** A grid with a fresh two-sided quote every second from [fromS] to [toS], the mid drifting up 0.1¢ a second. */
    private fun grid(openMs: Long, fromS: Int, toS: Int): PrintGrid {
        val g = PrintGrid(openMs)
        for (s in fromS..toS) {
            val mid = 0.40 + 0.001 * s
            g.add(openMs + s * 1000L + 100, takerYes = true, yesPrice = mid + 0.01, contracts = 5.0 + s % 3)
            g.add(openMs + s * 1000L + 200, takerYes = false, yesPrice = mid - 0.01, contracts = 4.0)
        }
        return g
    }

    private val open = ZonedDateTime.of(2026, 10, 24, 13, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli() // a Saturday

    @Test
    fun everyCandidateHasAValueOnAWarmGrid() {
        val g = grid(open, 0, 400)
        val names = PrintGrid.CANDIDATES.toList()
        val x = g.features("YES", 360, names)
        assertNotNull(x)
        assertEquals(names.size, x!!.size)
        assertTrue(x.all { it.isFinite() })
        fun v(n: String) = x[names.indexOf(n)]
        assertEquals(0.76f, v("mid"), 1e-6f)
        assertEquals(0.02f, v("spread"), 1e-6f)
        assertEquals(540f, v("tte"), 0f)
        assertEquals(0.26f, v("dist50"), 1e-6f)
        assertEquals(0.30f, v("dm300"), 1e-5f)
        assertEquals(0.36f, v("from_first"), 1e-5f)
        assertEquals(0f, v("from_hi"), 1e-6f)
        assertEquals(0.059f, v("lo60"), 1e-5f)
        assertEquals(13f, v("hour"), 0f)
        assertEquals(1f, v("weekend"), 0f)
        assertEquals(30f, v("nchg30"), 0f)
        // The DOWN frame is the mirror image.
        val no = g.features("NO", 360, names)!!
        assertEquals(1f - 0.76f, no[names.indexOf("mid")], 1e-6f)
        assertEquals(-0.30f, no[names.indexOf("dm300")], 1e-5f)
        assertEquals(0f, g.features("YES", 360, listOf("weekend"))!![0] - 1f, 0f)
    }

    @Test
    fun theLegacyRowIsTheSameAsAskingForItsNames() {
        val g = grid(open, 0, 200)
        assertTrue(g.features("YES", 150)!!.contentEquals(g.features("YES", 150, PrintGrid.FEATURES)!!))
    }

    @Test
    fun longLookbacksWaitForTheHistoryTheyNeed() {
        // The app joined this window 100 s in: it has no prints before that.
        val late = grid(open, 100, 600)
        assertNotNull("60 s of history is there at 200 s", late.features("YES", 200, PrintGrid.FEATURES))
        assertNull("a 300 s change needs the window from the open", late.features("YES", 200, listOf("mid", "dm300")))
        assertNull("a since-the-open feature needs the open", late.features("YES", 500, listOf("imb_all")))
        assertNotNull("by 400 s, 300 s of history is there", late.features("YES", 400, listOf("mid", "dm300")))
        // Watched from the open (first print 3 s in): everything is available.
        val early = grid(open, 3, 600)
        assertNotNull(early.features("YES", 200, listOf("dm300", "imb_all", "from_first")))
        assertEquals(PrintGrid.ALL_HISTORY, PrintGrid.lookback(listOf("mid", "imb_all")))
        assertEquals(PrintGrid.MIN_GRID_SECONDS, PrintGrid.lookback(listOf("mid", "dm5")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownNameIsAnError() {
        PrintGrid.lookback(listOf("spot_z"))
    }
}
