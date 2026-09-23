package com.dirk.kalshiodds.signal.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Kalshi ticker + orderbook WebSocket alive
 * while "Live signals" is enabled. Analysis / alerts only — no order channels.
 */
class LiveSignalsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: KalshiWsClient? = null
    private var bindJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SignalNotifier.ensureChannels(this)
        val notifier = KalshiOddsApp.from(this).container.notifier
        startInForeground(notifier)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = KalshiOddsApp.from(this).container
        startInForeground(container.notifier)
        if (bindJob == null) {
            bindJob = scope.launch { runPipeline() }
        }
        return START_STICKY
    }

    private fun startInForeground(notifier: SignalNotifier) {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, SignalNotifier.FG_NOTIFICATION_ID, notifier.foregroundNotification(), type)
    }

    private suspend fun runPipeline() {
        val container = KalshiOddsApp.from(this).container
        val hub = container.hub
        val prefs = container.preferences
        combine(prefs.settings, hub.watchTickers) { settings, tickers -> settings to tickers }
            .collectLatest { (settings, tickers) ->
                hub.settings = settings
                if (!settings.liveSignalsEnabled) {
                    client?.stop()
                    client = null
                    hub.setConnection(WsConnectionState.IDLE, detail = "live signals off")
                    stopSelf()
                    return@collectLatest
                }
                if (!settings.credentialsConfigured) {
                    client?.stop()
                    client = null
                    hub.wsLive = false
                    hub.setConnection(WsConnectionState.NEEDS_API_KEY, detail = "WS needs API key — using REST")
                    return@collectLatest
                }
                val wanted = tickers.filter { settings.isWatchedTicker(it) }
                val channels = buildList {
                    add("ticker")
                    if (wanted.isNotEmpty()) add("orderbook_delta")
                    if (settings.subscribeTrades) add("trade")
                }
                val existing = client
                if (existing == null) {
                    hub.setConnection(WsConnectionState.CONNECTING)
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
                        },
                        onLog = { msg -> Log.d(TAG, msg) }
                    )
                    client = ws
                    val (keyId, pem) = prefs.credentialSnapshot()
                    ws.start(keyId, pem, channels, wanted)
                } else {
                    existing.updateSubscriptions(channels, wanted)
                }
            }
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        KalshiOddsApp.from(this).container.hub.wsLive = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DipHunterWS"

        fun start(context: Context) {
            val intent = Intent(context, LiveSignalsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSignalsService::class.java))
        }
    }
}
