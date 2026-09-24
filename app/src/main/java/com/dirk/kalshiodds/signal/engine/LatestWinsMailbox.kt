package com.dirk.kalshiodds.signal.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Latest-wins mailbox so a Kalshi book-delta flood cannot enqueue one
 * coroutine per message (that is the 256MB OOM: CancellableContinuationImpl
 * backlog + captured snapshots). One drain coroutine services all keys.
 */
class LatestWinsMailbox<T> {
    private val pending = ConcurrentHashMap<String, T>()
    private val scheduled = AtomicBoolean(false)

    fun offer(key: String, value: T): Boolean {
        pending[key] = value
        return scheduled.compareAndSet(false, true)
    }

    fun drain(): List<Pair<String, T>> {
        if (pending.isEmpty()) return emptyList()
        val keys = pending.keys.toList()
        val out = ArrayList<Pair<String, T>>(keys.size)
        for (k in keys) {
            val v = pending.remove(k) ?: continue
            out.add(k to v)
        }
        return out
    }

    fun markIdleAndNeedsRerun(): Boolean {
        scheduled.set(false)
        return pending.isNotEmpty() && scheduled.compareAndSet(false, true)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun size(): Int = pending.size
}
