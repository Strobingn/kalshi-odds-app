package com.dirk.kalshiodds.signal.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.ws.KalshiWsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that owns the Kalshi ticker + orderbook WebSocket and the
 * scoring loop while "Live signals" is on. Survives Activity onStop / process
 * reclaim via START_STICKY, onTaskRemoved restart, and a WorkManager watchdog.
 * Analysis / alerts only — no order channels, no auto-fire.
 */
class LiveSignalsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: KalshiWsClient? = null
    private var pipelineJob: Job? = null
    private var metadataJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var explicitStop = false
    @Volatile private var lastTickerCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SignalNotifier.ensureChannels(this)
        promoteToForeground()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent?.action == LiveSignalsPolicy.ACTION_STOP) {
            return handleExplicitStop()
        }
        explicitStop = false
        if (pipelineJob == null) {
            pipelineJob = scope.launch { runPipeline() }
        }
        if (metadataJob == null) {
            metadataJob = scope.launch { runMetadataLoop() }
        }
        return START_STICKY
    }

    private fun handleExplicitStop(): Int {
        explicitStop = true
        LiveSignalsKeepAlive.setEnabled(this, false)
        // Persist off before onDestroy cancels [scope], otherwise a later
        // DataStore emission of the stale "on" would restart the service.
        runCatching {
            runBlocking(Dispatchers.IO) {
                KalshiOddsApp.from(this@LiveSignalsService).container.preferences.updateLiveSignals(false)
            }
        }
        tearDownPipeline()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun promoteToForeground(specialUseOnly: Boolean = false) {
        val notifier = runCatching { KalshiOddsApp.from(this).container.notifier }
            .getOrElse { SignalNotifier(this) }
        val type = foregroundType(specialUseOnly)
        ServiceCompat.startForeground(
            this,
            SignalNotifier.FG_NOTIFICATION_ID,
            notifier.foregroundNotification(),
            type
        )
    }

    private fun foregroundType(specialUseOnly: Boolean): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29 && !specialUseOnly) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= 34) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private suspend fun runPipeline() {
        val container = KalshiOddsApp.from(this).container
        val hub = container.hub
        val prefs = container.preferences
        combine(prefs.settings, hub.watchTickers) { settings, tickers -> settings to tickers }
            .collectLatest { (settings, tickers) ->
                hub.settings = settings
                LiveSignalsKeepAlive.setEnabled(this, settings.liveSignalsEnabled)
                lastTickerCount = tickers.size
                if (!settings.liveSignalsEnabled) {
                    explicitStop = true
                    client?.stop()
                    client = null
                    hub.setConnection(WsConnectionState.IDLE, detail = "live signals off")
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                if (!LiveSignalsPolicy.shouldConnectWs(true, settings.credentialsConfigured)) {
                    client?.stop()
                    client = null
                    hub.wsLive = false
                    hub.setConnection(WsConnectionState.NEEDS_API_KEY, detail = "WS needs API key — using REST")
                    updateNotification(needsKey = true)
                    return@collectLatest
                }
                val plan = LiveSignalsPolicy.subscriptionPlan(settings.subscribeTrades, tickers.filter {
                    settings.isWatchedTicker(it)
                })
                val existing = client
                if (existing == null) {
                    hub.setConnection(WsConnectionState.CONNECTING)
                    updateNotification(reconnecting = true)
                    val ws = KalshiWsClient(
                        scope = scope,
                        onTick = { tick -> hub.ingestTick(tick) },
                        onBookSnapshot = { snap ->
                            hub.ingestBookSnapshot(
                                ticker = snap.ticker,
                                yesLevels = snap.yesLevels,
                                noLevels = snap.noLevels,
                                seq = snap.seq,
                                receiveElapsedNanos = snap.receiveElapsedNanos
                            )
                        },
                        onBookDelta = { delta ->
                            hub.ingestBookDelta(
                                ticker = delta.ticker,
                                price = delta.price,
                                delta = delta.delta,
                                side = delta.side,
                                seq = delta.seq,
                                receiveElapsedNanos = delta.receiveElapsedNanos
                            )
                        },
                        onState = { state ->
                            hub.wsLive = state.connected
                            val mapped = when {
                                state.connected -> WsConnectionState.CONNECTED
                                state.reconnecting -> WsConnectionState.RECONNECTING
                                state.detail?.contains("auth", ignoreCase = true) == true ->
                                    WsConnectionState.NEEDS_API_KEY
                                else -> WsConnectionState.REST_FALLBACK
                            }
                            hub.setConnection(mapped, host = state.host, detail = state.detail)
                            updateNotification(
                                connected = state.connected,
                                reconnecting = state.reconnecting,
                                needsKey = mapped == WsConnectionState.NEEDS_API_KEY
                            )
                        },
                        onLog = { msg -> Log.d(TAG, msg) }
                    )
                    client = ws
                    val (keyId, pem) = prefs.credentialSnapshot()
                    ws.start(keyId, pem, plan.channels, plan.marketTickers)
                } else {
                    existing.updateSubscriptions(plan.channels, plan.marketTickers)
                }
            }
    }

    /**
     * Keep [com.dirk.kalshiodds.signal.SignalHub.watchTickers] fresh after the
     * Activity/ViewModel is gone so orderbook subscriptions do not go stale.
     */
    private suspend fun runMetadataLoop() {
        val container = KalshiOddsApp.from(this).container
        while (scope.isActive) {
            refreshWakeLock()
            if (LiveSignalsKeepAlive.isEnabled(this)) {
                runCatching {
                    val settings = container.hub.settings
                    runCatching { container.external.refreshIfStale() }.getOrNull()?.let {
                        container.hub.applyExternal(it)
                    }
                    val snap = container.repository.refresh(
                        watchBtc = settings.watchBtc,
                        watchEth = settings.watchEth,
                        watchSol = settings.watchSol,
                        extraTickers = settings.extraTickerList(),
                        edgeThresholdPp = settings.edgeThresholdPp
                    )
                    container.hub.ingestRestSnapshot(snap)
                }.onFailure { Log.w(TAG, "metadata refresh: ${it.message}") }
            }
            delay(LiveSignalsPolicy.METADATA_INTERVAL_MS)
        }
    }

    private fun updateNotification(
        connected: Boolean = false,
        reconnecting: Boolean = false,
        needsKey: Boolean = false
    ) {
        val notifier = runCatching { KalshiOddsApp.from(this).container.notifier }
            .getOrElse { SignalNotifier(this) }
        val text = LiveSignalsPolicy.foregroundText(connected, reconnecting, needsKey, lastTickerCount)
        runCatching {
            ServiceCompat.startForeground(
                this,
                SignalNotifier.FG_NOTIFICATION_ID,
                notifier.foregroundNotification(text),
                foregroundType(false)
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (LiveSignalsPolicy.shouldRestartAfterKill(LiveSignalsKeepAlive.isEnabled(this), explicitStop)) {
            LiveSignalsKeepAlive.startService(this)
            LiveSignalsKeepAlive.enqueueSoon(this)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onTimeout(startId: Int) {
        handleDataSyncTimeout()
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleDataSyncTimeout()
    }

    private fun handleDataSyncTimeout() {
        // API 35 dataSync time-box. Stay up under specialUse if the user still wants live odds.
        if (LiveSignalsPolicy.shouldRestartAfterKill(LiveSignalsKeepAlive.isEnabled(this), explicitStop)) {
            promoteToForeground(specialUseOnly = true)
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val restart = LiveSignalsPolicy.shouldRestartAfterKill(
            LiveSignalsKeepAlive.isEnabled(this),
            explicitStop
        )
        tearDownPipeline()
        releaseWakeLock()
        runCatching { KalshiOddsApp.from(this).container.hub.wsLive = false }
        scope.cancel()
        super.onDestroy()
        if (restart) {
            LiveSignalsKeepAlive.startService(applicationContext)
            LiveSignalsKeepAlive.enqueueSoon(applicationContext)
        }
    }

    private fun tearDownPipeline() {
        client?.stop()
        client = null
        pipelineJob?.cancel()
        pipelineJob = null
        metadataJob?.cancel()
        metadataJob = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LiveSignalsPolicy.WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(LiveSignalsPolicy.WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun refreshWakeLock() {
        val held = wakeLock
        if (held == null || !held.isHeld) {
            acquireWakeLock()
            return
        }
        runCatching {
            held.acquire(LiveSignalsPolicy.WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DipHunterWS"

        fun start(context: Context) = LiveSignalsKeepAlive.startService(context)

        fun stop(context: Context) = LiveSignalsKeepAlive.stopService(context)
    }
}
