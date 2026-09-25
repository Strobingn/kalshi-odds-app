package com.dirk.kalshiodds.domain

/**
 * Injectable time source so 15m window rollover can be tested with a fake clock.
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

class FakeClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs

    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }

    fun set(ms: Long) {
        nowMs = ms
    }
}
