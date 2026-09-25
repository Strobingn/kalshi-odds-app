package com.dirk.kalshiodds.signal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteSanityTest {
    @Test
    fun placeholdersDropped() {
        assertTrue(QuoteSanity.isPlaceholder(0.0))
        assertTrue(QuoteSanity.isPlaceholder(1.0))
        assertTrue(QuoteSanity.isPlaceholder(null))
        assertFalse(QuoteSanity.isPlaceholder(0.64))
    }

    @Test
    fun crossedBookRejected() {
        assertTrue(QuoteSanity.isCrossed(0.70, 0.60))
        assertFalse(QuoteSanity.isCrossed(0.60, 0.64))
        val (b, a) = QuoteSanity.usablePair(0.70, 0.60)
        assertNull(b)
        assertNull(a)
    }

    @Test
    fun robustMidIgnoresZeroTick() {
        assertEquals(0.64, QuoteSanity.robustMid(0.63, 0.65)!!, 1e-9)
        assertNull(QuoteSanity.robustMid(0.0, 1.0))
        assertEquals(0.64, QuoteSanity.robustMid(null, null, last = 0.64)!!, 1e-9)
        assertNull(QuoteSanity.robustMid(0.0, 0.0, last = 0.0))
    }

    @Test
    fun medianFilterIgnoresOneBadTick() {
        val mid = QuoteSanity.medianFilter(listOf(0.64, 0.65, 0.01, 0.64, 0.63))
        assertTrue(mid != null && mid!! > 0.50)
    }

    @Test
    fun usableCentsDropsZeroAndHundred() {
        assertNull(QuoteSanity.usableCents(0f))
        assertNull(QuoteSanity.usableCents(100f))
        assertEquals(64f, QuoteSanity.usableCents(64f)!!, 0.01f)
    }
}
