package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalsKeepAliveTest {

    @Test
    fun stickyRestartWhenLiveAndNotExplicitStop() {
        assertEquals(LiveSignalsPolicy.START_STICKY, LiveSignalsPolicy.startCommand(explicitStop = false))
        assertTrue(LiveSignalsPolicy.shouldRestartAfterKill(liveEnabled = true, explicitStop = false))
    }

    @Test
    fun noRestartOnUserStopOrToggleOff() {
        assertEquals(LiveSignalsPolicy.START_NOT_STICKY, LiveSignalsPolicy.startCommand(explicitStop = true))
        assertFalse(LiveSignalsPolicy.shouldRestartAfterKill(liveEnabled = true, explicitStop = true))
        assertFalse(LiveSignalsPolicy.shouldRestartAfterKill(liveEnabled = false, explicitStop = false))
        assertFalse(LiveSignalsPolicy.shouldRestartAfterKill(liveEnabled = false, explicitStop = true))
    }

    @Test
    fun wsOnlyWithLiveSignalsAndKey() {
        assertTrue(LiveSignalsPolicy.shouldConnectWs(liveEnabled = true, credentialsConfigured = true))
        assertFalse(LiveSignalsPolicy.shouldConnectWs(liveEnabled = true, credentialsConfigured = false))
        assertFalse(LiveSignalsPolicy.shouldConnectWs(liveEnabled = false, credentialsConfigured = true))
    }

    @Test
    fun subscriptionPlanAddsBookAndTradesForWatchedTickers() {
        val plan = LiveSignalsPolicy.subscriptionPlan(
            subscribeTrades = true,
            watchedTickers = listOf("KXBTC15M-A", "KXETH15M-B", "KXBTC15M-A", "")
        )
        assertEquals(listOf("ticker", "orderbook_delta", "trade", "market_lifecycle_v2"), plan.channels)
        assertEquals(listOf("KXBTC15M-A", "KXETH15M-B"), plan.marketTickers)
    }

    @Test
    fun subscriptionPlanTickerOnlyWhenWatchlistEmpty() {
        val plan = LiveSignalsPolicy.subscriptionPlan(
            subscribeTrades = false,
            watchedTickers = emptyList()
        )
        assertEquals(listOf("ticker", "market_lifecycle_v2"), plan.channels)
        assertTrue(plan.marketTickers.isEmpty())
    }

    @Test
    fun foregroundCopyStatesConnectionAndNeverClaimsAutoTrade() {
        val live = LiveSignalsPolicy.foregroundText(
            connected = true,
            reconnecting = false,
            needsKey = false,
            tickerCount = 6
        )
        assertTrue(live.contains("6 markets"))
        assertTrue(live.contains("Approve"))
        assertEquals(
            "Reconnecting Kalshi WebSocket…",
            LiveSignalsPolicy.foregroundText(
                connected = false,
                reconnecting = true,
                needsKey = false,
                tickerCount = 0
            )
        )
        val needsKey = LiveSignalsPolicy.foregroundText(
            connected = false,
            reconnecting = false,
            needsKey = true,
            tickerCount = 0
        )
        assertTrue(needsKey.contains("API key"))
    }

    @Test
    fun batteryDeepLinkUsesPackageUriAndSettingsActions() {
        assertEquals("package:com.dirk.kalshiodds", LiveSignalsPolicy.batteryPackageUri("com.dirk.kalshiodds"))
        assertEquals(
            "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            LiveSignalsPolicy.BATTERY_REQUEST_ACTION
        )
        assertEquals(
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            LiveSignalsPolicy.BATTERY_SETTINGS_ACTION
        )
        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            LiveSignalsPolicy.APP_DETAILS_ACTION
        )
    }

    @Test
    fun applicationMustNotPromoteFgsOnCreate() {
        assertFalse(LiveSignalsPolicy.shouldPromoteFromApplicationOnCreate())
        assertTrue(LiveSignalsPolicy.shouldPromoteFromUiForeground(liveEnabled = true))
        assertFalse(LiveSignalsPolicy.shouldPromoteFromUiForeground(liveEnabled = false))
    }

    @Test
    fun fgsTypesPreferSpecialUseOnApi34Plus() {
        assertTrue(LiveSignalsPolicy.foregroundServiceTypesToTry(28).isEmpty())
        assertEquals(listOf(LiveSignalsPolicy.FGS_TYPE_DATA_SYNC), LiveSignalsPolicy.foregroundServiceTypesToTry(29))
        assertEquals(listOf(LiveSignalsPolicy.FGS_TYPE_DATA_SYNC), LiveSignalsPolicy.foregroundServiceTypesToTry(33))
        assertEquals(
            listOf(LiveSignalsPolicy.FGS_TYPE_SPECIAL_USE, LiveSignalsPolicy.FGS_TYPE_DATA_SYNC),
            LiveSignalsPolicy.foregroundServiceTypesToTry(34)
        )
        assertEquals(
            listOf(LiveSignalsPolicy.FGS_TYPE_SPECIAL_USE, LiveSignalsPolicy.FGS_TYPE_DATA_SYNC),
            LiveSignalsPolicy.foregroundServiceTypesToTry(35)
        )
        assertEquals(1, LiveSignalsPolicy.FGS_TYPE_DATA_SYNC)
        assertEquals(0x40000000, LiveSignalsPolicy.FGS_TYPE_SPECIAL_USE)
        assertEquals(
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            LiveSignalsPolicy.PROPERTY_SPECIAL_USE_FGS_SUBTYPE
        )
        assertTrue(LiveSignalsPolicy.SPECIAL_USE_SUBTYPE.contains("Kalshi"))
        assertTrue(LiveSignalsPolicy.SPECIAL_USE_SUBTYPE.contains("orderbook"))
    }

    @Test
    fun android15TimeoutStopsSelfAndDoesNotRestartFromBackground() {
        assertTrue(LiveSignalsPolicy.timeoutRequiresStopSelf())
        assertEquals("Live alerts paused, tap to resume", LiveSignalsPolicy.PAUSED_NOTIFICATION_TEXT)
        assertFalse(
            LiveSignalsPolicy.shouldRestartAfterKill(
                liveEnabled = true,
                explicitStop = false,
                timeoutPaused = true
            )
        )
        assertFalse(LiveSignalsPolicy.shouldStartFromBackground(liveEnabled = true, timeoutPaused = true))
        assertTrue(LiveSignalsPolicy.shouldStartFromBackground(liveEnabled = true, timeoutPaused = false))
        assertTrue(LiveSignalsPolicy.shouldPromoteFromUiForeground(liveEnabled = true))
    }

    @Test
    fun manifestDeclaresSpecialUsePropertyAndTimeoutStopSelf() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?: java.io.File("app/src/main/AndroidManifest.xml")
        val xml = manifest.readText()
        assertTrue(xml.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(xml.contains("specialUse|dataSync") || xml.contains("specialUse"))
        assertTrue(xml.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))

        val service = java.io.File("src/main/java/com/dirk/kalshiodds/signal/service/LiveSignalsService.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/service/LiveSignalsService.kt")
        val src = service.readText()
        assertTrue(src.contains("override fun onTimeout(startId: Int)"))
        assertTrue(src.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        assertTrue(src.contains("handleForegroundTimeout"))
        assertTrue(src.contains("stopSelf()"))
        assertTrue(src.contains("pausedNotification"))
        assertFalse(src.contains("handleDataSyncTimeout"))
        val timeoutFn = src.substring(src.indexOf("private fun handleForegroundTimeout"))
        val body = timeoutFn.substring(0, timeoutFn.indexOf("override fun onDestroy"))
        assertTrue(body.contains("stopSelf()"))
        assertFalse(body.contains("promoteToForeground()"))
    }

    @Test
    fun failedForegroundDoesNotRestart() {
        assertFalse(
            LiveSignalsPolicy.shouldRestartAfterKill(
                liveEnabled = true,
                explicitStop = false,
                foregroundFailed = true
            )
        )
        assertTrue(
            LiveSignalsPolicy.shouldRestartAfterKill(
                liveEnabled = true,
                explicitStop = false,
                foregroundFailed = false
            )
        )
    }

    @Test
    fun ticketRebuildDebounceIsShort() {
        assertTrue(LiveSignalsPolicy.TICKET_REBUILD_DEBOUNCE_MS in 100L..1_000L)
    }

    @Test
    fun stopActionAndWatchdogConstantsAreStable() {
        assertEquals(
            "com.dirk.kalshiodds.signal.service.STOP_LIVE_SIGNALS",
            LiveSignalsPolicy.ACTION_STOP
        )
        assertEquals("diphunter_keepalive", LiveSignalsPolicy.PREFS_NAME)
        assertEquals("live_signals_enabled", LiveSignalsPolicy.PREFS_ENABLED_KEY)
        assertEquals("live_signals_timeout_paused", LiveSignalsPolicy.PREFS_TIMEOUT_PAUSED_KEY)
        assertEquals("Kalshi Trader", LiveSignalsPolicy.NOTIFICATION_TITLE)
        assertEquals(
            "com.dirk.kalshiodds.signal.service.RESUME_LIVE_SIGNALS",
            LiveSignalsPolicy.ACTION_RESUME
        )
        assertEquals("diphunter_live_signals_ongoing", LiveSignalsPolicy.CHANNEL_ONGOING)
        assertEquals(45_000L, LiveSignalsPolicy.METADATA_INTERVAL_MS)
        assertEquals(15L, LiveSignalsPolicy.WATCHDOG_PERIOD_MINUTES)
        assertTrue(LiveSignalsPolicy.WATCHDOG_SOON_SECONDS in 5L..60L)
    }
}
