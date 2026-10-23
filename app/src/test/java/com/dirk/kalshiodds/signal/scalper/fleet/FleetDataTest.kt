package com.dirk.kalshiodds.signal.scalper.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.random.Random

class FleetDataTest {

    @Test
    fun calendar() {
        val fomc = ZonedDateTime.of(2026, 10, 28, 14, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals("FOMC", MacroCalendar.last(fomc + 60_000L)!!.second)
        assertNull(MacroCalendar.last(fomc - 1))
        assertNull(MacroCalendar.last(fomc + 16 * 60_000L))
        val jobs = ZonedDateTime.of(2026, 11, 6, 8, 30, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(jobs to "Jobs report", MacroCalendar.next(fomc + 1))
        val cpi = ZonedDateTime.of(2026, 11, 10, 8, 30, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(cpi to "CPI", MacroCalendar.next(jobs + 1))
        assertTrue(MacroCalendar.EVENTS.size >= 38)
    }

    @Test
    fun behaviourTableStartsFromTheRecordedDaysAndLearns() {
        val t = BehaviourTable()
        assertEquals(0.1232 - 35.0 / 443, t.epsilon(0.12)!!, 1e-9)
        assertTrue(t.epsilon(0.17)!! < 0)
        assertNull(t.epsilon(0.30))
        repeat(1000) { t.add(0.12, true) }
        assertTrue("wins pull ε down", t.epsilon(0.12)!! < 0)
    }

    @Test
    fun hourVolFindsTheQuietHours() {
        val rnd = Random(3)
        var p = 100_000.0
        val bars = ArrayList<Pair<Long, Double>>()
        var t = 1_800_000_000L / 300 * 300
        repeat(12 * 24 * 4) {
            val h = ((t / 3600) % 24).toInt()
            val sd = if (h in 1..6) 0.0005 else 0.0015
            p *= exp(sd * (rnd.nextDouble() * 2 - 1) * 1.7320508)
            bars += t to p
            t += 300
        }
        val (omega, s24) = FleetSeries.hourVol(bars)!!
        assertTrue(omega[3] < 0.65)
        assertTrue(omega[15] > 1.0)
        assertTrue(s24 > 0)
    }

    @Test
    fun windowReturnsAlign() {
        val btc = listOf(0L to 100.0, 900L to 101.0, 1800L to 100.0, 3600L to 102.0)
        val r = FleetSeries.windowReturns(btc)
        assertEquals(2, r.size) // the gap 1800 → 3600 is not a window
        val eth = FleetSeries.windowReturns(listOf(900L to 10.0, 1800L to 10.5))
        val (b, e) = FleetSeries.aligned(r, eth)
        assertEquals(1, b.size)
        assertEquals(-0.0099, b[0], 1e-4)
        assertEquals(0.05, e[0], 1e-12)
        assertNotNull(FleetSeries.aligned(r, emptyList()))
    }
}
