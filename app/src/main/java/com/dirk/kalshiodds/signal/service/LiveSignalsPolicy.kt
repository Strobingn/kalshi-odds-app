package com.dirk.kalshiodds.signal.service

/**
 * Pure keep-alive / subscription rules for the live-signals foreground service.
 * No Android types so unit tests can lock the restart contract.
 */
object LiveSignalsPolicy {
    const val ACTION_STOP = "com.dirk.kalshiodds.signal.service.STOP_LIVE_SIGNALS"
    const val PREFS_NAME = "diphunter_keepalive"
    const val PREFS_ENABLED_KEY = "live_signals_enabled"

    const val METADATA_INTERVAL_MS = 45_000L
    const val WATCHDOG_PERIOD_MINUTES = 15L
    const val WATCHDOG_SOON_SECONDS = 25L
    const val WAKELOCK_TAG = "diphunter:live-signals"
    const val WAKELOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L

    const val NOTIFICATION_TITLE = "DipHunter live signals"
    /**
     * IMPORTANCE_MIN channel: silent, collapsed, no status-bar icon. A new id
     * because Android never lowers an existing channel's importance.
     */
    const val CHANNEL_ONGOING = "diphunter_live_signals_quiet"
    const val CHANNEL_LEGACY = "diphunter_live_signals"

    /** Earlier ongoing channels, deleted on start so only the quiet one remains. */
    val CHANNELS_RETIRED = listOf(CHANNEL_LEGACY, "diphunter_live_signals_ongoing")

    const val BATTERY_REQUEST_ACTION = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val BATTERY_SETTINGS_ACTION = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val APP_DETAILS_ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** Android [android.app.Service.START_STICKY] — restart after the OS reclaims the process. */
    const val START_STICKY = 1

    /** Android [android.app.Service.START_NOT_STICKY] — user asked us to stay down. */
    const val START_NOT_STICKY = 2

    /**
     * [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC].
     * specialUse is intentionally not used — it crashed startForeground on some
     * API 34/35 devices when combined with dataSync / missing Play property.
     */
    const val FGS_TYPE_DATA_SYNC = 1

    const val TICKET_REBUILD_DEBOUNCE_MS = 300L
    const val LIFECYCLE_CHANNEL = "market_lifecycle_v2"

    fun startCommand(explicitStop: Boolean): Int =
        if (explicitStop) START_NOT_STICKY else START_STICKY

    /**
     * Application.onCreate is too early / often still "background" for FGS.
     * Promote from the Activity / ProcessLifecycleOwner foreground instead.
     */
    fun shouldPromoteFromApplicationOnCreate(): Boolean = false

    fun shouldPromoteFromUiForeground(liveEnabled: Boolean): Boolean = liveEnabled

    /**
     * Types to try in [androidx.core.app.ServiceCompat.startForeground], in order.
     * Empty on API < 29 (legacy startForeground has no type).
     */
    fun foregroundServiceTypesToTry(sdkInt: Int): List<Int> = buildList {
        if (sdkInt >= 29) add(FGS_TYPE_DATA_SYNC)
    }

    fun shouldRestartAfterKill(
        liveEnabled: Boolean,
        explicitStop: Boolean,
        foregroundFailed: Boolean = false
    ): Boolean = liveEnabled && !explicitStop && !foregroundFailed

    fun shouldConnectWs(liveEnabled: Boolean, credentialsConfigured: Boolean): Boolean =
        liveEnabled && credentialsConfigured

    data class SubscriptionPlan(
        val channels: List<String>,
        val marketTickers: List<String>
    )

    fun subscriptionPlan(
        subscribeTrades: Boolean,
        watchedTickers: Collection<String>
    ): SubscriptionPlan {
        val wanted = watchedTickers.filter { it.isNotBlank() }.distinct()
        val channels = buildList {
            add("ticker")
            if (wanted.isNotEmpty()) add("orderbook_delta")
            if (subscribeTrades) add("trade")
            add(LIFECYCLE_CHANNEL)
        }
        return SubscriptionPlan(channels, wanted)
    }

    fun foregroundText(
        connected: Boolean,
        reconnecting: Boolean,
        needsKey: Boolean,
        tickerCount: Int
    ): String = when {
        needsKey -> "WS needs API key — REST fallback. Alerts never auto-place."
        connected -> {
            val n = if (tickerCount > 0) " · $tickerCount markets" else ""
            "Watching Kalshi WebSocket$n. Approve still required to trade."
        }
        reconnecting -> "Reconnecting Kalshi WebSocket…"
        else -> "Starting live odds. Leave this notification on."
    }

    fun batteryPackageUri(packageName: String): String = "package:$packageName"
}
