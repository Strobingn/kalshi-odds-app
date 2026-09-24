package com.dirk.kalshiodds.signal.engine

/**
 * Latest-wins throttle for Compose overlays.
 *
 * 0.3.1 used a *debounce* (wait for quiet). Book/ticker floods kept resetting
 * the delay so the main screen never painted live scores. This applies the
 * newest value immediately when the interval has elapsed, otherwise schedules
 * exactly one trailing apply of whatever is pending.
 */
class OverlayThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var pending: Boolean = false
        private set

    @Volatile
    private var lastApplyMs: Long = 0L

    /**
     * @return 0 to apply now; a positive delay in ms to schedule a trailing apply.
     */
    @Synchronized
    fun onEvent(): Long {
        pending = true
        val now = clock()
        val elapsed = now - lastApplyMs
        if (elapsed >= intervalMs) return 0L
        return (intervalMs - elapsed).coerceAtLeast(1L)
    }

    @Synchronized
    fun markApplied() {
        pending = false
        lastApplyMs = clock()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 80L
    }
}
