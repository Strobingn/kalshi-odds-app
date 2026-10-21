package com.dirk.kalshiodds.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSeriesCacheTest {
    @Test fun freshEntrySkipsAnotherReadUntilTtl() {
        var now = 1_000L
        val cache = CardSeriesCache(ttlMs = 2_000L) { now }
        assertFalse(cache.fresh("KXBTC15M", 5_000L))
        cache.put("KXBTC15M", 5_000L, listOf(BidPoint(tMs = 1L, upBidCents = 40f, downBidCents = 60f, spotUsd = 1.0)), listOf(true))
        assertTrue(cache.fresh("KXBTC15M", 5_000L))
        assertEquals(1.0, cache.get("KXBTC15M", 5_000L)!!.bids.single().spotUsd!!, 0.0)
        now = 2_500L
        assertTrue(cache.fresh("KXBTC15M", 5_000L))
        now = 3_000L
        assertFalse(cache.fresh("KXBTC15M", 5_000L))
    }

    @Test fun aNewWindowIsNotTheCachedSeries() {
        val cache = CardSeriesCache(nowMs = { 10L })
        cache.put("KXBTC15M", 5_000L, emptyList(), emptyList())
        assertNull(cache.get("KXBTC15M", 9_000L))
        cache.invalidate("KXBTC15M")
        assertNull(cache.get("KXBTC15M", 5_000L))
    }
}
