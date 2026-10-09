package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.trade.RealMoneyPhrase

/**
 * Arming for limited live Autopilot. 0.3.39: the armed flag is persisted in [store], so it
 * survives restarts, process death and reboots. Arming still needs [tapApprove] and then the
 * typed phrase REAL MONEY. Neither call places an order. Errors never disarm; only [disarm]
 * (Real Money tab, or leaving LIVE mode) does.
 */
class LiveAutopilotSession(
    private val store: LiveArmStore = InMemoryLiveArmStore(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var armed: Boolean = runCatching { store.loadArmed() }.getOrDefault(false)
        private set
    @Volatile
    var approveTapped: Boolean = false
        private set

    fun tapApprove() {
        if (!armed) approveTapped = true
    }

    /** Arms only after Approve and an exact typed REAL MONEY. Returns the armed state. */
    @Synchronized
    fun confirmRealMoney(typed: String?): Boolean {
        if (armed) return true
        if (!approveTapped || !RealMoneyPhrase.matches(typed)) return false
        armed = true
        runCatching { store.saveArmed(true, nowMs()) }
        return true
    }

    @Synchronized
    fun disarm() {
        armed = false
        approveTapped = false
        runCatching { store.saveArmed(false, nowMs()) }
    }
}
