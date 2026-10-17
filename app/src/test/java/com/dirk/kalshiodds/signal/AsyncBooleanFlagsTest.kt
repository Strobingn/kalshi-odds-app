package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.service.AsyncBooleanFlags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.*
import org.junit.Test

class AsyncBooleanFlagsTest {
    @Test fun blockedDiskDoesNotBlockReadsOrMainThreadTogglesAndNewestValueWins() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val disk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val flags = AsyncBooleanFlags(mapOf("enabled" to false), persist = { batch ->
            entered.countDown(); check(release.await(5, SECONDS)); disk.putAll(batch)
            if (batch["enabled"] == false) finished.countDown()
            true
        }, execute = { task -> pool.execute(task) })
        try {
            flags.set("enabled", true)
            assertTrue(entered.await(5, SECONDS))
            repeat(20_000) { flags.set("enabled", true) }
            flags.set("enabled", false)
            assertFalse(flags.get("enabled")) // no wait for the blocked disk writer
            val barrierStarted = CountDownLatch(1)
            val durable = pool.submit<Boolean> { barrierStarted.countDown(); flags.flushBlocking() }
            assertTrue(barrierStarted.await(5, SECONDS))
            assertFalse(durable.isDone) // the service must not terminate before persistence finishes
            release.countDown()
            assertTrue(durable.get(5, SECONDS))
            assertTrue(finished.await(5, SECONDS))
            assertEquals(false, disk["enabled"])
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun failedWriteRetriesWithoutOverwritingANewerToggle() {
        val tasks = arrayListOf<() -> Unit>(); val writes = arrayListOf<Map<String, Boolean>>()
        var fail = true
        val flags = AsyncBooleanFlags(emptyMap(), persist = { batch -> writes.add(batch); !fail }, execute = { tasks.add(it) })
        flags.set("paused", true); tasks.removeAt(0)()
        fail = false; flags.set("paused", false); tasks.removeAt(0)()
        assertEquals(listOf(mapOf("paused" to true), mapOf("paused" to false)), writes)
        assertFalse(flags.get("paused"))
    }
    @Test fun newerToggleDuringFailedCommitGetsItsOwnRetryWithoutAnotherSet() {
        val tasks = arrayListOf<() -> Unit>()
        val disk = mutableMapOf<String, Boolean>()
        var first = true
        lateinit var flags: AsyncBooleanFlags
        flags = AsyncBooleanFlags(mapOf("enabled" to false), persist = { batch ->
            if (first) {
                first = false
                flags.set("enabled", false) // a newer request while the old writer owns its batch
                false
            } else { disk.putAll(batch); true }
        }, execute = { tasks.add(it) })
        flags.set("enabled", true)
        tasks.removeAt(0)()
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        assertEquals(false, disk["enabled"])
        assertTrue(tasks.isEmpty())
    }

}
