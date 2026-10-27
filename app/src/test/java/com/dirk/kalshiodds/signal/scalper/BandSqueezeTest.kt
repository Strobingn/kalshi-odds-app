package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tuner may move one end of a strategy's entry band, not squeeze it shut. Before 1.24.0 the only guard was that
 * the band must not be empty, so the Scalping AI ratcheted Kashi's last-45s favourite down to buying 53–54¢: a 1¢
 * window it almost never hits, which is switching a strategy off by the back door.
 */
class BandSqueezeTest {
    private val st = ScalpStrategy.KASHI_FAV_LAST30

    private fun bandOf(tuning: ScalpTuning, s: ScalpStrategy = st) =
        tuning.tuned(s).let { Math.round(it.entryMin * 100.0) to Math.round(it.entryMax * 100.0) }

    @Test
    fun loweringTheTopPushesTheBottomDownInsteadOfLeavingASliver() {
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(entryMinCents = 53.0, entryMaxCents = 70.0))
        val r = SupervisorBounds.apply(tuning, st.name, "entryMaxCents", 54.0)
        assertTrue("$r", r is SupervisorBounds.Change.Applied)
        val (lo, hi) = bandOf(tuning)
        assertEquals("the top moved where the tuner asked", 54L, hi)
        assertEquals("and the bottom gave way to keep 10¢ of room", 44L, lo)
    }

    @Test
    fun raisingTheBottomPushesTheTopUp() {
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(entryMinCents = 40.0, entryMaxCents = 46.0))
        assertTrue(SupervisorBounds.apply(tuning, st.name, "entryMinCents", 45.0) is SupervisorBounds.Change.Applied)
        val (lo, hi) = bandOf(tuning)
        assertEquals(45L, lo)
        assertEquals(55L, hi)
    }

    @Test
    fun aTunerCannotRatchetTheBandShutOneCentAtATime() {
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(entryMinCents = 50.0, entryMaxCents = 95.0))
        // Walk the top down and the bottom up, over and over, as the AI did.
        repeat(60) {
            SupervisorBounds.apply(tuning, st.name, "entryMaxCents", bandOf(tuning).second - 1.0)
            SupervisorBounds.apply(tuning, st.name, "entryMinCents", bandOf(tuning).first + 1.0)
        }
        val (lo, hi) = bandOf(tuning)
        assertTrue("never narrower than 10¢, was $lo-$hi", hi - lo >= ScalpBand.MIN_TUNED_WIDTH_CENTS.toLong())
    }

    @Test
    fun aTunerStaysInsideTheArenasOwnBand() {
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(entryMinCents = 20.0, entryMaxCents = 40.0))
        // It may move the band about inside 15-58c...
        assertTrue(SupervisorBounds.apply(tuning, st.name, "entryMinCents", 30.0) is SupervisorBounds.Change.Applied)
        assertEquals(30L, bandOf(tuning).first)
        // ...but not take a strategy back out to the favourites the arena no longer buys.
        SupervisorBounds.apply(tuning, st.name, "entryMaxCents", 95.0)
        assertTrue("clamped to the arena's top", bandOf(tuning).second <= 58L)
    }

    // ---- the repair of bands already saved ----

    @Test
    fun aStrangledBandIsRecognised() {
        assertTrue(ScalpBand.strangled(ScalpKnobs(entryMinCents = 53.0, entryMaxCents = 54.0)))
        assertTrue(ScalpBand.strangled(ScalpKnobs(entryMinCents = 53.0, entryMaxCents = 53.0)))
        assertFalse("10¢ is the floor, not under it", ScalpBand.strangled(ScalpKnobs(entryMinCents = 45.0, entryMaxCents = 55.0)))
        assertFalse(ScalpBand.strangled(ScalpKnobs(entryMinCents = 15.0, entryMaxCents = 57.0)))
        assertFalse("only one end set is not a squeezed band", ScalpBand.strangled(ScalpKnobs(entryMinCents = 53.0)))
        assertFalse(ScalpBand.strangled(ScalpKnobs()))
    }

    @Test
    fun clearingAStrangledBandGivesTheArenaBandBack() {
        val tuning = ScalpTuning()
        tuning.set(st, ScalpKnobs(entryMinCents = 53.0, entryMaxCents = 54.0, contracts = 7))
        assertEquals(53L to 54L, bandOf(tuning))

        val k = tuning.knobs(st)
        tuning.set(st, k.copy(entryMinCents = null, entryMaxCents = null))
        assertEquals("back to the arena's 15-58c", 15L to 58L, bandOf(tuning))
        assertEquals("the rest of its settings are untouched", 7, tuning.tuned(st).contracts)
        assertNull(tuning.knobs(st).entryMinCents)
    }
}
