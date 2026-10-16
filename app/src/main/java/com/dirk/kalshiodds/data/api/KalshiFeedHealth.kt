package com.dirk.kalshiodds.data.api

/**
 * Last non-crashing status from any Kalshi poller, shown on Home.
 */
class KalshiFeedHealth {
    @Volatile var banner: String? = null
        private set

    fun note(error: Throwable, retryInMs: Long) {
        if (KalshiRequestStatus.isLocalThrottle(error)) return // 0.3.45: local pacing is not a feed problem
        banner = KalshiRequestStatus.message(error, retryInMs)
    }

    fun show(message: String) {
        banner = message
    }

    fun clear() {
        banner = null
    }
}

class KalshiTraffic {
    val limiter = KalshiRateLimiter()
    val health = KalshiFeedHealth()
}
