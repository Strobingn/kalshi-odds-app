package com.dirk.kalshiodds.signal.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.ws.KalshiWsClient
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the Kalshi ticker + orderbook WebSocket and the
 * scoring loop while "Live signals" is on. Survives Activity onStop / process
 * reclaim via START_STICKY, onTaskRemoved restart, and a WorkManager watchdog.
 * Analysis / alerts only — no order channels, no auto-fire.
 *
 * Crash contract: startForeground is the first thing in onCreate and is
 * wrapped; pipeline / WS / notification failures never kill the process.
 */
class LiveSignalsService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            com.dirk.kalshiodds.data.local.results.CrashBreadcrumb.record("pipeline", t)
            Log.e(TAG, "pipeline", t)
        }
    )
    private var client: KalshiWsClient? = null
    private var pipelineJob: Job? = null
    private var metadataJob: Job? = null
    private var rolloverJob: Job? = null
    @Volatile private var rolloverBound = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var explicitStop = false
    @Volatile private var lastTickerCount = 0
    @Volatile private var foregroundFailed = false
    @Volatile private var foregroundTypeInUse = 0
    @Volatile private var wsUsesDemo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must be first: startForegroundService timeout is ~5–10s and a throw
        // here is a process-killing RemoteServiceException. 0.3.41: nothing else runs on the
        // main thread before it; the rest of the setup moves to a background dispatcher.
        promoteToForeground()
        isRunning = true
        scope.launch {
            runCatching { acquireWakeLock() }
            runCatching { bindRollover() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (foregroundFailed) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == LiveSignalsPolicy.ACTION_STOP) {
            return handleExplicitStop()
        }
        if (intent?.action == LiveSignalsPolicy.ACTION_RESUME) {
            LiveSignalsKeepAlive.setTimeoutPaused(this, false)
        }
        explicitStop = false
        LiveSignalsKeepAlive.setTimeoutPaused(this, false)
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(SignalNotifier.PAUSED_NOTIFICATION_ID)
        }
        if (pipelineJob == null || pipelineJob?.isActive != true) {
            pipelineJob = scope.launch { runPipeline() }
        }
        if (metadataJob == null || metadataJob?.isActive != true) {
            metadataJob = scope.launch { runMetadataLoop() }
        }
        // 0.3.39: host the process-scoped Autopilot (OddsViewModel) even with no Activity.
        // 0.3.41: posted after this call returns, so it never delays startForeground.
        android.os.Handler(mainLooper).post { runCatching { KalshiOddsApp.from(this).ensureAutopilotHost() } }
        if (rolloverJob == null || rolloverJob?.isActive != true) {
            rolloverJob = scope.launch {
                runCatching {
                    KalshiOddsApp.from(this@LiveSignalsService).container.rollover.start(this)
                }
            }
        }
        return START_STICKY
    }

    private fun bindRollover() {
        if (rolloverBound) return
        val container = runCatching { KalshiOddsApp.from(this).container }.getOrNull() ?: return
        rolloverBound = true
        container.rollover.addListener { event ->
            container.hub.setWatchTickers(event.activeTickers)
            if (event.droppedTickers.isNotEmpty()) {
                container.tickets.voidTickers(event.droppedTickers)
                scope.launch {
                    runCatching { container.repository.scoreSettlementsNow(container.clock.nowMs()) }
                }
            }
        }
    }

    private fun handleExplicitStop(): Int {
        explicitStop = true
        LiveSignalsKeepAlive.setEnabled(this, false)
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1_500L) {
                    KalshiOddsApp.from(this@LiveSignalsService).container.preferences.updateLiveSignals(false)
                }
            }
        }
        tearDownPipeline()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
        return START_NOT_STICKY
    }

    @Volatile private var promoted = false

    private fun promoteToForeground() {
        // 0.3.41: minimal work — no container access (AppContainer/OddsViewModel stay out of this path).
        // Re-run on every start command: each startForegroundService() must be answered by startForeground().
        val notifier = SignalNotifier(applicationContext)
        val notification = runCatching { notifier.foregroundNotification() }.getOrNull() ?: return
        val types = LiveSignalsPolicy.foregroundServiceTypesToTry(Build.VERSION.SDK_INT)
        if (types.isEmpty()) {
            // API 26–28: untyped startForeground.
            try {
                @Suppress("DEPRECATION")
                startForeground(SignalNotifier.FG_NOTIFICATION_ID, notification)
                promoted = true
                foregroundFailed = false
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground failed: ${t.message}")
                foregroundFailed = true
            }
            return
        }
        var started = false
        for (type in types) {
            try {
                ServiceCompat.startForeground(
                    this,
                    SignalNotifier.FG_NOTIFICATION_ID,
                    notification,
                    type
                )
                foregroundTypeInUse = type
                started = true
                promoted = true
                foregroundFailed = false
                break
            } catch (t: Throwable) {
                Log.w(TAG, "startForeground type=$type failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (!started) {
            foregroundFailed = true
            Log.e(TAG, "could not promote to foreground — refusing to crash-loop")
        }
    }

    private suspend fun runPipeline() {
        try {
            val container = KalshiOddsApp.from(this).container
            val hub = container.hub
            val prefs = container.preferences
            combine(prefs.settings, hub.watchTickers) { settings, tickers -> settings to tickers }
                .collectLatest { (settings, tickers) ->
                    runCatching { applyLiveSettings(settings, tickers) }
                        .onFailure { Log.e(TAG, "pipeline tick: ${it.message}") }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "runPipeline: ${t.message}")
        }
    }

    private fun applyLiveSettings(
        settings: com.dirk.kalshiodds.signal.config.SignalSettings,
        tickers: Set<String>
    ) {
        val container = KalshiOddsApp.from(this).container
        val hub = container.hub
        hub.settings = settings
        // 0.3.39: stay up for Live signals OR always-on Autopilot (paper/scalp only).
        val wanted = com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.serviceWanted(
            liveSignalsEnabled = settings.liveSignalsEnabled,
            paperTradingEnabled = settings.paperTradingEnabled,
            aiPaperAutopilotEnabled = settings.aiPaperAutopilotEnabled
        )
        LiveSignalsKeepAlive.setEnabled(this, wanted)
        lastTickerCount = tickers.size
        if (!wanted) {
            explicitStop = true
            client?.stop()
            client = null
            hub.setConnection(WsConnectionState.IDLE, detail = "live signals off")
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        if (!LiveSignalsPolicy.shouldConnectWs(true, settings.tradingCredentialsConfigured())) {
            client?.stop()
            client = null
            hub.wsLive = false
            hub.setConnection(WsConnectionState.NEEDS_API_KEY, detail = "WS needs API key — using REST")
            updateNotification(needsKey = true)
            return
        }
        val plan = LiveSignalsPolicy.subscriptionPlan(settings.subscribeTrades, tickers.filter {
            settings.isWatchedTicker(it)
        })
        val demo = settings.kalshiDemoEnabled
        if (client != null && wsUsesDemo != demo) {
            client?.stop()
            client = null
        }
        val existing = client
        if (existing == null) {
            hub.setConnection(WsConnectionState.CONNECTING)
            updateNotification(reconnecting = true)
            val ws = KalshiWsClient(
                scope = scope,
                onTick = { tick -> runCatching { hub.ingestTick(tick) } },
                onLifecycle = { ticker, eventType ->
                    scope.launch {
                        runCatching {
                            container.rollover.onLifecycleRefresh(ticker, eventType)
                        }
                    }
                },
                onBookSnapshot = { snap ->
                    runCatching {
                        hub.ingestBookSnapshot(
                            ticker = snap.ticker,
                            yesLevels = snap.yesLevels,
                            noLevels = snap.noLevels,
                            seq = snap.seq,
                            receiveElapsedNanos = snap.receiveElapsedNanos
                        )
                    }
                },
                onBookDelta = { delta ->
                    runCatching {
                        hub.ingestBookDelta(
                            ticker = delta.ticker,
                            price = delta.price,
                            delta = delta.delta,
                            side = delta.side,
                            seq = delta.seq,
                            receiveElapsedNanos = delta.receiveElapsedNanos
                        )
                    }
                },
                onState = { state ->
                    runCatching {
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
                    }
                },
                onCf = { tick -> runCatching { container.cfFeed.acceptAndNotify(tick) } },
                onBookGap = { tickers -> runCatching { hub.scoring.book.invalidateBooks(tickers) } },
                onLog = { msg -> Log.d(TAG, msg) },
                urls = com.dirk.kalshiodds.signal.ws.KalshiWsAuth.wsUrls(demo)
            )
            client = ws
            wsUsesDemo = demo
            val (keyId, pem) = runCatching {
                if (demo) container.extraSecrets.demoSnapshot()
                else container.preferences.credentialSnapshot()
            }.getOrElse { "" to "" }
            ws.start(keyId, pem, plan.channels, plan.marketTickers)
        } else {
            existing.updateSubscriptions(plan.channels, plan.marketTickers)
        }
    }

    /**
     * Keep [com.dirk.kalshiodds.signal.SignalHub.watchTickers] fresh after the
     * Activity/ViewModel is gone so orderbook subscriptions do not go stale.
     * Skipped while the UI is foreground — OddsViewModel already polls, and
     * a second refresh raced the shared TFLite interpreter.
     */
    private suspend fun runMetadataLoop() {
        val container = runCatching { KalshiOddsApp.from(this).container }.getOrNull() ?: return
        while (scope.isActive) {
            refreshWakeLock()
            val uiUp = LiveSignalsKeepAlive.isUiInForeground()
            val headless = com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.headlessDriving.get()
            // 0.3.44: hand the markets loop straight back to the UI loop.
            if (uiUp || headless) com.dirk.kalshiodds.data.api.KalshiPollLoops.release(com.dirk.kalshiodds.data.api.KalshiPollLoops.Type.MARKETS, "service")
            if (LiveSignalsKeepAlive.isEnabled(this) && !uiUp && !headless &&
                com.dirk.kalshiodds.data.api.KalshiPollLoops.tryRun(com.dirk.kalshiodds.data.api.KalshiPollLoops.Type.MARKETS, "service", leaseMs = 2 * LiveSignalsPolicy.METADATA_INTERVAL_MS)) {
                runCatching {
                    val settings = container.hub.settings
                    runCatching { container.external.refreshIfStale() }.getOrNull()?.let {
                        container.hub.applyExternal(it)
                    }
                    val snap = container.repository.refresh(
                        watchBtc = true,
                        watchEth = true,
                        watchSol = true,
                        extraTickers = com.dirk.kalshiodds.domain.CryptoMarkets.liveTickers(settings.extraTickerList()),
                        edgeThresholdPp = settings.effectiveEdgeThresholdPp()
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
        val notification = runCatching { notifier.foregroundNotification(text) }.getOrNull() ?: return
        val type = if (foregroundTypeInUse != 0) {
            foregroundTypeInUse
        } else {
            LiveSignalsPolicy.foregroundServiceTypesToTry(Build.VERSION.SDK_INT).firstOrNull() ?: 0
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && type != 0) {
                ServiceCompat.startForeground(
                    this,
                    SignalNotifier.FG_NOTIFICATION_ID,
                    notification,
                    type
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(SignalNotifier.FG_NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (LiveSignalsPolicy.shouldRestartAfterKill(
                LiveSignalsKeepAlive.isEnabled(this),
                explicitStop,
                foregroundFailed,
                LiveSignalsKeepAlive.isTimeoutPaused(this)
            )
        ) {
            // 0.3.42: the service keeps running (stopWithTask=false); only arm the WorkManager watchdog.
            LiveSignalsKeepAlive.enqueueSoon(this)
        }
    }

    /**
     * API 34 `shortService` timeout. We do not use shortService, but must
     * still [stopSelf] if the system delivers this callback.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout()
    }

    /**
     * Android 15 `dataSync` / `mediaProcessing` 6h-per-24h timeout.
     * Official contract: [stopSelf] within a few seconds. Never re-promote.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout()
    }

    private fun handleForegroundTimeout() {
        if (!LiveSignalsPolicy.timeoutRequiresStopSelf()) {
            stopSelf()
            return
        }
        explicitStop = true
        runCatching { LiveSignalsKeepAlive.setTimeoutPaused(this, true) }
        tearDownPipeline()
        releaseWakeLock()
        val notifier = runCatching { KalshiOddsApp.from(this).container.notifier }
            .getOrElse { SignalNotifier(this) }
        val paused = runCatching { notifier.pausedNotification() }.getOrNull()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        if (paused != null) {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(SignalNotifier.PAUSED_NOTIFICATION_ID, paused)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        val restart = LiveSignalsPolicy.shouldRestartAfterKill(
            LiveSignalsKeepAlive.isEnabled(this),
            explicitStop,
            foregroundFailed,
            LiveSignalsKeepAlive.isTimeoutPaused(this)
        )
        tearDownPipeline()
        releaseWakeLock()
        runCatching { KalshiOddsApp.from(this).container.hub.wsLive = false }
        scope.cancel()
        super.onDestroy()
        isRunning = false
        if (restart) {
            // 0.3.42: never startForegroundService() from inside onDestroy — when the system tears the
            // service down (e.g. after a missed start window) an immediate re-start re-arms the same
            // deadline on a stalled main thread. Restart via WorkManager (delay + exponential backoff).
            LiveSignalsKeepAlive.enqueueSoon(applicationContext)
        }
    }

    private fun tearDownPipeline() {
        runCatching { client?.stop() }
        client = null
        pipelineJob?.cancel()
        pipelineJob = null
        metadataJob?.cancel()
        metadataJob = null
        rolloverJob?.cancel()
        rolloverJob = null
        runCatching { KalshiOddsApp.from(this).container.rollover.stop() }
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
            runCatching { acquireWakeLock() }
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

        /** 0.3.42: true between onCreate and onDestroy of this process's service instance. */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) = LiveSignalsKeepAlive.requestStart(context)

        fun stop(context: Context) = LiveSignalsKeepAlive.stopService(context)
    }
}
