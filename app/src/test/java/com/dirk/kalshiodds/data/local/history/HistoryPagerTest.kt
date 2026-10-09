package com.dirk.kalshiodds.data.local.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagerTest {
    @Test
    fun pagesWithoutOverlap() {
        val all = (0 until 25).toList()
        val first = HistoryPager.page(all, 0, 10)
        val second = HistoryPager.page(all, 10, 10)
        val last = HistoryPager.page(all, 20, 10)
        assertEquals((0..9).toList(), first.items)
        assertTrue(first.hasMore)
        assertEquals((10..19).toList(), second.items)
        assertEquals((20..24).toList(), last.items)
        assertFalse(last.hasMore)
    }

    @Test
    fun pastEndIsEmpty() {
        val page = HistoryPager.page(listOf(1, 2), 40, 10)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }
}
