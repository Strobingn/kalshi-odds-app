package com.dirk.kalshiodds.signal.service

/** Immediate process-local flags; one coalesced writer performs disk commits without holding the reader lock. */
internal class AsyncBooleanFlags(
    initial: Map<String, Boolean>,
    private val persist: (Map<String, Boolean>) -> Boolean,
    private val execute: (() -> Unit) -> Unit
) {
    private val lock = Any()
    private val values = initial.toMutableMap()
    private val pending = linkedMapOf<String, Boolean>()
    private var scheduled = false
    private val writerLock = Any()
    fun get(key: String): Boolean = synchronized(lock) { values[key] ?: false }
    fun set(key: String, value: Boolean) {
        val launch = synchronized(lock) {
            if (values[key] != value) { values[key] = value; pending[key] = value }
            if (scheduled || pending.isEmpty()) false else { scheduled = true; true }
        }
        if (launch) execute { flushBlocking() }
    }
    /** IO callers can await persistence before ending the last foreground component. */
    fun flushBlocking(): Boolean = synchronized(writerLock) {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) { scheduled = false; null }
                else pending.toMap().also { pending.clear() }
            } ?: return@synchronized true
            if (!runCatching { persist(batch) }.getOrDefault(false)) {
                val retryNewer = synchronized(lock) {
                    val newer = pending.isNotEmpty()
                    // Do not overwrite a newer toggle that arrived while a commit was blocked.
                    batch.forEach { (key, value) -> pending.putIfAbsent(key, value) }
                    scheduled = newer
                    newer
                }
                if (retryNewer) execute { flushBlocking() }
                return@synchronized false // next set retries; never spin on a failed disk
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
}
