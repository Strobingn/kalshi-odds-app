package com.dirk.kalshiodds.signal.paper

/**
 * 0.3.38 one-tap Stop. Engaging it is the only real-money action without a typed REAL MONEY confirm,
 * because it can only reduce risk: Autopilot off, live arming dropped, every resting order cancelled.
 * While engaged, [AutopilotDispatch] refuses to place anything. Turning Autopilot back on releases it.
 */
class StopLatch {
    @Volatile
    var engagedAtMs: Long? = null
        private set

    val engaged: Boolean get() = engagedAtMs != null

    fun engage(nowMs: Long) { engagedAtMs = nowMs }

    fun release() { engagedAtMs = null }
}

/** What one Stop tap did. */
data class StopOutcome(val cancelled: Int, val failed: Int, val skippedNoKey: Boolean) {
    fun message(): String = buildString {
        append("STOPPED: Autopilot off")
        when {
            skippedNoKey -> append(" · no Kalshi key, so no resting orders to cancel")
            failed > 0 -> append(" · cancelled $cancelled resting order(s), $failed failed. Check Real Money")
            else -> append(" · cancelled $cancelled resting order(s)")
        }
    }
}
