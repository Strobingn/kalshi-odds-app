package com.dirk.kalshiodds.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Automatic checks on process start. The Settings button is not limited. */
object UpdateCheckSchedule {
    const val INTERVAL_MS = 6L * 60L * 60L * 1000L

    fun due(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs - lastCheckMs >= INTERVAL_MS
}

/** Latest newer Kashi debug release, if the startup check or Settings found one. */
object UpdateAvailability {
    private val _offer = MutableStateFlow<KashiReleasePolicy.Release?>(null)
    val offer: StateFlow<KashiReleasePolicy.Release?> = _offer.asStateFlow()

    fun publish(release: KashiReleasePolicy.Release?) {
        _offer.value = release
    }
}

object InstallUnknownApps {
    const val GUIDE =
        "Allow Install unknown apps for Kalshi Trader, then tap Check for updates again."

    fun settingsAction(): String = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"

    fun needsPermission(sdkInt: Int, canRequestPackageInstalls: Boolean): Boolean =
        sdkInt >= 26 && !canRequestPackageInstalls
}
