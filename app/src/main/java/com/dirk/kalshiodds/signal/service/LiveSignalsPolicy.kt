package com.dirk.kalshiodds.signal.service

/**
 * Pure keep-alive / subscription rules for the live-signals foreground service.
 * No Android types so unit tests can lock the restart contract.
 *
 * FGS type (sideloaded live-odds monitor):
 *
 * Official timeout docs
 * (https://developer.android.com/develop/background-work/services/fgs/timeout):
 * only `dataSync` and `mediaProcessing` have a 6-hour-per-24-hour limit.
 * On timeout the system calls [android.app.Service.onTimeout] `(int, int)`;
 * the service must [android.app.Service.stopSelf] within a few seconds or
 * the process dies with
 * `RemoteServiceException$ForegroundServiceDidNotStopInTimeException`.
 * After timeout the service is no longer FGS; starting another `dataSync`
 * from the background throws `ForegroundServiceStartNotAllowedException`
 * until the user brings the app to the foreground (which resets the 6h
 * timer). `shortService` (API 34) has a ~3 minute limit and
 * `onTimeout(int)` — we do not use `shortService`.
 *
 * Official types
 * (https://developer.android.com/develop/background-work/services/fgs/service-types#special-use):
 * `specialUse` covers valid FGS use cases that are not covered by the
 * other types. It is **not** in the time-limited set. Requires
 * `FOREGROUND_SERVICE_SPECIAL_USE` and the manifest property
 * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Play Console reviews
 * that property for Play-distributed apps; this build is sideloaded, but
 * the property is still required for the type to be valid.
 *
 * Evaluation:
 * - `dataSync`: wrong. Meant for short transfers (upload/download/backup).
 *   Android 15 also forbids starting `dataSync` from `BOOT_COMPLETED`.
 *   Dirk's S24 (SM-S928U, API 35) crashed after the 6h cap on v0.3.15.
 * - `mediaPlayback` / `location` / `camera` / `connectedDevice` /
 *   `remoteMessaging` / `health` / `phoneCall` / `mediaProcessing`: none
 *   describe a Kalshi ticker/orderbook WebSocket + last-minute alerts.
 * - `specialUse`: correct for a sideloaded live-odds monitor that must
 *   stay up while the user is away from the app.
 *
 * Try `specialUse` first on API 34+. Keep `dataSync` as a fallback if
 * `startForeground(specialUse)` fails on an OEM. [onTimeout] must still
 * `stopSelf()` — never re-promote as `dataSync` from the timeout callback.
 */
object LiveSignalsPolicy {
    const val ACTION_STOP = "com.dirk.kalshiodds.signal.service.STOP_LIVE_SIGNALS"
    const val ACTION_RESUME = "com.dirk.kalshiodds.signal.service.RESUME_LIVE_SIGNALS"
    const val PREFS_NAME = "diphunter_keepalive"
    const val PREFS_ENABLED_KEY = "live_signals_enabled"
    const val PREFS_TIMEOUT_PAUSED_KEY = "live_signals_timeout_paused"

    const val METADATA_INTERVAL_MS = 45_000L
    const val WATCHDOG_PERIOD_MINUTES = 15L
    const val WATCHDOG_SOON_SECONDS = 25L
    const val WAKELOCK_TAG = "diphunter:live-signals"
    const val WAKELOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L

    const val NOTIFICATION_TITLE = "DipHunter live signals"
    const val PAUSED_NOTIFICATION_TEXT = "Live alerts paused, tap to resume"
    const val CHANNEL_ONGOING = "diphunter_live_signals_ongoing"
    const val CHANNEL_LEGACY = "diphunter_live_signals"

    const val BATTERY_REQUEST_ACTION = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val BATTERY_SETTINGS_ACTION = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val APP_DETAILS_ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** Android [android.app.Service.START_STICKY] — restart after the OS reclaims the process. */
    const val START_STICKY = 1

    /** Android [android.app.Service.START_NOT_STICKY] — user asked us to stay down. */
    const val START_NOT_STICKY = 2

    /** [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]. */
    const val FGS_TYPE_DATA_SYNC = 1

    /**
     * [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]
     * = `0x40000000`. Added in API 34.
     */
    const val FGS_TYPE_SPECIAL_USE = 0x40000000

    const val PROPERTY_SPECIAL_USE_FGS_SUBTYPE =
        "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"

    const val SPECIAL_USE_SUBTYPE =
        "Sideloaded live Kalshi odds monitor: persist WebSocket ticker and " +
            "orderbook plus last-minute signal alerts while the user is away " +
            "from the app. Not a time-limited data transfer."

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
     * API 34+: `specialUse` first (no 6h cap), then `dataSync` fallback.
     */
    fun foregroundServiceTypesToTry(sdkInt: Int): List<Int> = buildList {
        if (sdkInt >= 34) add(FGS_TYPE_SPECIAL_USE)
        if (sdkInt >= 29) add(FGS_TYPE_DATA_SYNC)
    }

    /** Android 15 `onTimeout` — the process dies if we do not `stopSelf()`. */
    fun timeoutRequiresStopSelf(): Boolean = true

    fun shouldStartFromBackground(liveEnabled: Boolean, timeoutPaused: Boolean): Boolean =
        liveEnabled && !timeoutPaused

    fun shouldRestartAfterKill(
        liveEnabled: Boolean,
        explicitStop: Boolean,
        foregroundFailed: Boolean = false,
        timeoutPaused: Boolean = false
    ): Boolean = liveEnabled && !explicitStop && !foregroundFailed && !timeoutPaused

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
