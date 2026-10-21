package com.dirk.kalshiodds.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperMarketFeedTest {
    private fun sample(ticker: String, time: Long, age: Long? = null) = PaperMarketFeed.Sample(ticker, time, 2_000_000L, 0.4, 0.5, 0.5, 0.6, 80_000.0, 79_999.0, 51.0, age, 3, -2.0)

    @Test fun recordsOneWindowAndMarksUnknownFreshness() {
        val feed = PaperMarketFeed()
        feed.record(sample("KXBTC15M-A", 1_000))
        feed.record(sample("KXBTC15M-A", 2_000))
        assertTrue(feed.render().contains("2 one-second observations"))
        assertTrue(feed.render().contains("age is milliseconds"))
        assertTrue(feed.render().contains(",?,3,-2.000"))
        feed.record(sample("KXBTC15M-B", 3_000, 1200))
        assertFalse(feed.render().contains("KXBTC15M-A"))
        assertTrue(feed.render().contains("KXBTC15M-B"))
    }

    @Test fun limitsMemoryAndRejectsOutOfOrderSamples() {
        val feed = PaperMarketFeed()
        repeat(1000) { feed.record(sample("KXBTC15M-A", 1_000L + it * 1_000L)) }
        feed.record(sample("KXBTC15M-A", 1_000L))
        assertTrue(feed.render().contains("900 one-second observations"))
        assertTrue(feed.chartSamples().size <= 61)
        feed.clear()
        assertTrue(feed.render().contains("unavailable"))
    }
}
