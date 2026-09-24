package com.dirk.kalshiodds.signal.engine

/**
 * Latest-wins throttle for the live score overlay.
 *
 * 0.3.1 used a debounce that **reset on every book delta**. Under a live
 * WebSocket flood the overlay never fired and the main screen stayed quiet.
 *
 * This applies immediately when [intervalMs] has elapsed since the last
 * apply; otherwise it keeps the newest value and asks for **one** trailing
 * apply. Later events only replace the pending value (they do not reset
 * the trailing timer).
 */
class OverlayThrottle<T>(
    val intervalMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    enum class Decision { APPLY_NOW, SCHEDULE_TRAILING, HOLD }

    @Volatile
    var lastApplyMs: Long = 0L
        private set

    @Volatile
    var pending: T? = null
        private set

    @Volatile
    var trailingScheduled: Boolean = false
        private set

    @Synchronized
    fun onEvent(value: T): Decision {
        val now = nowMs()
        if (now - lastApplyMs >= intervalMs) {
            pending = null
            trailingScheduled = false
            lastApplyMs = now
            return Decision.APPLY_NOW
        }
        pending = value
        if (trailingScheduled) return Decision.HOLD
        trailingScheduled = true
        return Decision.SCHEDULE_TRAILING
    }

    fun remainingMs(): Long = (intervalMs - (nowMs() - lastApplyMs)).coerceAtLeast(0L)

    @Synchronized
    fun takeTrailing(): T? {
        trailingScheduled = false
        val value = pending
        pending = null
        if (value != null) lastApplyMs = nowMs()
        return value
    }
}
