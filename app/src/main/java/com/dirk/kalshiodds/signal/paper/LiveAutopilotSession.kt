package com.dirk.kalshiodds.signal.paper

/**
 * In-memory arming for limited live Autopilot. Process death disarms.
 * [confirmRealMoney] does nothing until [tapApprove] has happened, and
 * neither call places an order.
 */
class LiveAutopilotSession {
    var armed: Boolean = false
        private set
    var approveTapped: Boolean = false
        private set

    fun tapApprove() {
        if (!armed) approveTapped = true
    }

    fun confirmRealMoney(): Boolean {
        if (!approveTapped || armed) return armed
        armed = true
        return true
    }

    fun disarm() {
        armed = false
        approveTapped = false
    }
}
