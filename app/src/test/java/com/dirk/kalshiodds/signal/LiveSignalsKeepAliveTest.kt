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
    fun fgsTypesAreDataSyncOnlyOnApi29Plus() {
        assertTrue(LiveSignalsPolicy.foregroundServiceTypesToTry(28).isEmpty())
        assertEquals(listOf(LiveSignalsPolicy.FGS_TYPE_DATA_SYNC), LiveSignalsPolicy.foregroundServiceTypesToTry(29))
        assertEquals(listOf(LiveSignalsPolicy.FGS_TYPE_DATA_SYNC), LiveSignalsPolicy.foregroundServiceTypesToTry(34))
        assertEquals(listOf(LiveSignalsPolicy.FGS_TYPE_DATA_SYNC), LiveSignalsPolicy.foregroundServiceTypesToTry(35))
        assertEquals(1, LiveSignalsPolicy.FGS_TYPE_DATA_SYNC)
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
        assertEquals("DipHunter live signals", LiveSignalsPolicy.NOTIFICATION_TITLE)
        assertEquals("diphunter_live_signals_ongoing", LiveSignalsPolicy.CHANNEL_ONGOING)
        assertEquals(45_000L, LiveSignalsPolicy.METADATA_INTERVAL_MS)
        assertEquals(15L, LiveSignalsPolicy.WATCHDOG_PERIOD_MINUTES)
        assertTrue(LiveSignalsPolicy.WATCHDOG_SOON_SECONDS in 5L..60L)
    }
}
