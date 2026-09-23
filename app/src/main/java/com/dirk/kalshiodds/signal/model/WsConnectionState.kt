package com.dirk.kalshiodds.signal.model

enum class WsConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    REST_FALLBACK,
    NEEDS_API_KEY,
    ERROR
}

data class SignalStatus(
    val state: WsConnectionState = WsConnectionState.IDLE,
    val lastTickLatencyMs: Double? = null,
    val lastTickAgeMs: Long? = null,
    val host: String? = null,
    val detail: String? = null
) {
    /** Compact chip label shown on OddsScreen. */
    fun chipLabel(): String = when (state) {
        WsConnectionState.CONNECTED -> {
            val lat = lastTickLatencyMs?.let { String.format(java.util.Locale.US, " · %.0fms", it) }.orEmpty()
            "WS connected$lat"
        }
        WsConnectionState.RECONNECTING -> "WS reconnecting"
        WsConnectionState.CONNECTING -> "WS connecting"
        WsConnectionState.NEEDS_API_KEY -> "WS needs API key — using REST"
        WsConnectionState.REST_FALLBACK -> {
            val lat = lastTickLatencyMs?.let { String.format(java.util.Locale.US, " · %.0fms", it) }.orEmpty()
            "REST fallback$lat"
        }
        WsConnectionState.ERROR -> detail?.let { "WS error — $it" } ?: "WS error — using REST"
        WsConnectionState.IDLE -> "REST poll"
    }
}
