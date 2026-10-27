package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kashi's late favourites (sf16 / sf19 / sf21 / sf23 / sf31): last minute, fair from the live spot, hold to the close. */
class KashiLateFavTest {

    private val t = "KXBTC15M-26OCT091215-15"

    private val favs = listOf(
        ScalpStrategy.KASHI_FAV_BASE, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
        ScalpStrategy.KASHI_FAV_LAST30, ScalpStrategy.KASHI_FAV_NIGHT
    )

    private fun runner(open: Long = 0L, fair: () -> Double? = { null }) = PaperScalper(
        model = null,
        ledger = ScalperLedger(),
        topOfBook = { null },
        bookLevels = { null },
        closeMs = { open + 900_000L },
        fairYes = { _, _ -> fair() }, strategies = ScalpStrategy.values().toList(),
        // These tests are about the signals, not the arena's band: every strategy on its researched band.
        engine = ScalperEngine(tuning = ScalpTuning().useOwnBands())
    )

    /** Seconds [from]..[to] of two-sided prints: takers sell YES at [bid] and buy YES at [ask]. */
    private fun prints(s: PaperScalper, from: Int, to: Int, bid: Double, ask: Double, open: Long = 0L) {
        for (sec in from..to) {
            s.onTrade(t, "no", bid, 20.0, open + sec * 1000L + 100L)
            s.onTrade(t, "yes", ask, 20.0, open + sec * 1000L + 200L)
        }
    }

    private fun yes(s: PaperScalper, st: ScalpStrategy) = s.engine.workingFor(st, t, "YES")

    @Test
    fun theFiveAreKashisRulesHeldToTheCloseWithAStop() {
        assertEquals(favs, ScalpStrategy.values().filter { it.fav != null && it.name.startsWith("KASHI_") })
        for (st in favs) {
            assertTrue(st.name, st.hold && st.taker && st.stopDollars > 0.0 && st.targetDollars == 0.0 && st.timeoutMs == 0L)
            assertTrue(st.name, st.origin.startsWith("kashi sf"))
        }
        assertEquals(ScalpStrategy.FavSpec(20, 60, 0.93, 0.05), ScalpStrategy.KASHI_FAV_STRICT.fav)
        assertEquals(0.15, ScalpStrategy.KASHI_FAV_STRICT.stopDollars, 0.0)
        assertEquals(ScalpStrategy.FavSpec(10, 45, 0.95, 0.05), ScalpStrategy.KASHI_FAV_LAST30.fav)
        assertEquals(ScalpStrategy.FavSpec(20, 90, 0.90, 0.05), ScalpStrategy.KASHI_FAV_BASE.fav)
        assertEquals(ScalpStrategy.FavSpec(20, 60, 0.97, 0.02), ScalpStrategy.KASHI_FAV_97.fav)
        assertEquals(2, ScalpStrategy.KASHI_FAV_NIGHT.maxPerSide)
        assertEquals(15, ScalpStrategy.KASHI_FAV_NIGHT.cooldownSeconds)
        assertTrue(ScalpStrategy.KASHI_FAV_NIGHT.fav!!.offUsHours)
    }

    @Test
    fun fairFromSpotIsAHeavyFavouriteFarFromTheStrikeLate() {
        val far = ScalpFair.fairYes(spot = 100_300.0, strike = 100_000.0, tteSeconds = 40.0, std1m = 4e-4, coin = "BTC")!!
        assertTrue("0.3% above with 40 s left is near-certain YES: $far", far > 0.99)
        val atStrike = ScalpFair.fairYes(100_000.0, 100_000.0, 600.0, 4e-4, "BTC")!!
        assertEquals(0.5, atStrike, 0.02)
        assertNotNull("BTC falls back to Kashi's σ while the stream warms up", ScalpFair.fairYes(100_300.0, 100_000.0, 40.0, null, "BTC"))
        assertNull("no σ for an unknown coin", ScalpFair.fairYes(100_300.0, 100_000.0, 40.0, null, "DOGE"))
        assertNull(ScalpFair.fairYes(null, 100_000.0, 40.0, 4e-4, "BTC"))
        assertNull(ScalpFair.fairYes(100_300.0, 100_000.0, 0.0, 4e-4, "BTC"))
    }

    @Test
    fun theyBuyTheFavouriteOnlyLateWhileItsAskIsUnderFair() {
        val s = runner { 0.98 }
        // Up to 96 s left: the fair price says YES, but it is not late enough for any of them.
        prints(s, 0, 805, 0.90, 0.92)
        assertTrue(favs.all { yes(s, it) == 0 })
        // 90 s left: only base, which buys from 90 s since 2026-10-20.
        prints(s, 806, 815, 0.90, 0.92)
        assertEquals(1, yes(s, ScalpStrategy.KASHI_FAV_BASE))
        assertTrue(favs.filter { it != ScalpStrategy.KASHI_FAV_BASE }.all { yes(s, it) == 0 })
        // 60 s left: strict, 97% and the off-US-hours copy (1970-01-01 00:14 UTC) buy; last-45 waits.
        prints(s, 816, 845, 0.90, 0.92)
        for (st in listOf(ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97, ScalpStrategy.KASHI_FAV_NIGHT)) {
            assertEquals(st.name, 1, yes(s, st))
        }
        assertEquals(0, yes(s, ScalpStrategy.KASHI_FAV_LAST30))
        prints(s, 846, 875, 0.90, 0.92)
        assertEquals("45 s left", 1, yes(s, ScalpStrategy.KASHI_FAV_LAST30))
        assertEquals("the off-US-hours copy buys a second 15 s later", 2, yes(s, ScalpStrategy.KASHI_FAV_NIGHT))
        assertTrue("one each for the rest: a hold stays open", favs.filter { it != ScalpStrategy.KASHI_FAV_NIGHT }.all { yes(s, it) == 1 })
        assertTrue("never the underdog", favs.all { s.engine.workingFor(it, t, "NO") == 0 })
    }

    @Test
    fun noFairPriceOrTooSmallAGapNoTrade() {
        val none = runner { null }
        prints(none, 0, 880, 0.90, 0.92)
        assertTrue(favs.all { yes(none, it) == 0 })
        // Fair 0.96 against a 0.93 ask: a 3¢ gap is enough only for the 97% rule, whose fair floor it misses.
        val thin = runner { 0.96 }
        prints(thin, 0, 880, 0.91, 0.93)
        assertTrue(favs.all { yes(thin, it) == 0 })
    }

    @Test
    fun theOffUsHoursCopySkipsThirteenToTwentyUtc() {
        val open = 14L * 3_600_000L          // 1970-01-01 14:00 UTC
        val s = runner(open) { 0.98 }
        prints(s, 0, 850, 0.90, 0.92, open)
        assertEquals(0, yes(s, ScalpStrategy.KASHI_FAV_NIGHT))
        assertEquals(1, yes(s, ScalpStrategy.KASHI_FAV_STRICT))
    }

    @Test
    fun aHoldWithAStopSellsAtTheBidButNeverTimesOut() {
        val e = ScalperEngine(tuning = ScalpTuning().useOwnBands())
        e.buyNow(ScalpStrategy.KASHI_FAV_STRICT, t, "YES", 0.92, { _, _ -> null }, 0L)
        assertTrue("no time-out", e.onClock(t, 50_000L, bidYes = 0.80, bidNo = 0.18).isEmpty())
        val c = e.onClock(t, 51_000L, bidYes = 0.77, bidNo = 0.22).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals(ExitKind.STOP, c.kind)
        assertEquals(0.77, c.exitPrice, 1e-9)
        // The originals still have no exit at all.
        e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "YES", 0.85, { _, _ -> null }, 0L)
        assertTrue(e.onClock(t, 600_000L, bidYes = 0.10, bidNo = 0.89).isEmpty())
    }
}
