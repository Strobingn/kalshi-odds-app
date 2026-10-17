package com.dirk.kalshiodds.decision

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ScalpFlushConcurrencyTest {
    @Test fun slowWriterCannotOverwriteANewerTradeStateAndFailureRetries() {
        val backing = InMemoryScalpPersistence()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val secondWrite = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val store = object : ScalpPersistence by backing {
            override fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) {
                if (writes.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, SECONDS))
                } else secondWrite.countDown()
                backing.upsertScalpAndStats(trade, prev)
            }
        }
        val tk = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(tk)!!
        val at = close - 300_000
        val t = ScalpTrade("source", tk, "YES", ScalpState.PENDING_ENTRY, at, .4, .5,
            ruleVersion = "${ScalpRule.VERSION}|sf41|P")
        val controls = MemoryScalpControls(ScalpControls(enabled = false))
        val book = ScalpBook(store, variants = emptyList(), liveExtras = true, controls = controls)
        book.importRestored(listOf(t))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Int> { book.flushWrites() }
            assertTrue(entered.await(5, SECONDS))
            book.settle(tk, "YES", close)
            val b = pool.submit<Int> { secondStarted.countDown(); book.flushWrites() }
            assertTrue(secondStarted.await(5, SECONDS))
            assertFalse(secondWrite.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            a.get(5, SECONDS); b.get(5, SECONDS)
            book.flushWrites()
            assertEquals(ScalpState.NO_FILL, backing.loadScalps().single().state)
        } finally { release.countDown(); pool.shutdownNow() }

        val attempts = AtomicInteger()
        val retryBacking = InMemoryScalpPersistence()
        val failing = object : ScalpPersistence by retryBacking {
            override fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) {
                if (attempts.incrementAndGet() == 1) throw IllegalStateException("temporary IO failure")
                retryBacking.upsertScalpAndStats(trade, prev)
            }
        }
        val retryBook = ScalpBook(failing, variants = emptyList())
        retryBook.importRestored(listOf(t))
        assertEquals(0, retryBook.flushWrites())
        assertEquals(1, retryBook.flushWrites())
        assertEquals(t.id, retryBacking.loadScalps().single().id)
    }
}

class ScalpQuoteWriterIsolationTest {
    @Test fun blockedDatabaseWriterDoesNotHoldQuoteLockOrWaitForFlushOnQuotes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val backing = InMemoryScalpPersistence()
        val store = object : ScalpPersistence by backing {
            override fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) {
                entered.countDown(); check(release.await(5, SECONDS))
                backing.upsertScalpAndStats(trade, prev)
            }
        }
        val tk = "KXBTC15M-26OCT122015-15"; val close = ScalpTicker.closeMs(tk)!!; val at = close - 300_000
        val book = ScalpBook(store, variants = emptyList(), liveExtras = true,
            controls = MemoryScalpControls(ScalpControls(enabled = false)))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val imported = pool.submit<Int> { book.importRestored((1..90).map { i ->
                ScalpTrade("s$i", tk, "YES", ScalpState.PENDING_ENTRY, at, .4, .5, ruleVersion = "v|sf41|P") }) }
            assertTrue(entered.await(5, SECONDS))
            assertEquals(90, imported.get(1, SECONDS))
            val quote = ScalpRule.Quote(tk, at + 1000, close, at + 1000, .39, 10.0, .40, 10.0, null, null, null)
            pool.submit { book.onQuote(quote, false); book.requestFlush() }.get(1, SECONDS)
            assertEquals(90, book.allTrades().size)
        } finally { release.countDown(); pool.shutdownNow() }
        book.flushWrites()
        assertEquals(90, backing.loadScalps().size)
    }
}
