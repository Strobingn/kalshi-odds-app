package com.dirk.kalshiodds.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Composition walks this list reversed. It must be a snapshot, not a live view of the store's ArrayList. */
class AiAdjustmentSnapshotTest {
    @Test fun publishedAdjustmentsSurviveMutationDuringReverseIteration() {
        val store = AiWatchStore()
        repeat(20) { i ->
            store.addAdjustment(AiAdjustment(i.toLong(), "v2-fair", "stopCents", "6", "${7 + i}", "r$i"))
        }
        val seen = store.adjustments.value
        assertEquals("r19", seen.first().reason)
        assertEquals("r0", seen.last().reason)
        assertFalse(seen.javaClass.name.contains("Reversed"))

        val errors = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            try {
                start.await()
                repeat(400) {
                    val snap = store.adjustments.value
                    walkReverse(snap)
                    walkReverse(seen)
                }
            } catch (t: Throwable) {
                errors.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        start.countDown()
        repeat(40) { i ->
            store.addAdjustment(AiAdjustment(1_000L + i, "v2-dip", "maxEntryCents", "40", "34", "n$i"))
            store.record("chat", "groq", "llama", 12, 8, 1_700_000_000_000L + i)
        }
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertNull(errors.get()?.toString(), errors.get())
        assertEquals(20, seen.size)
        assertEquals("r19", seen.first().reason)
        assertEquals("n39", store.adjustments.value.first().reason)
    }

    private fun walkReverse(rows: List<AiAdjustment>) {
        val it = rows.listIterator(rows.size)
        var n = 0
        while (it.hasPrevious()) n += it.previous().reason.length
        check(n >= 0)
    }
}
