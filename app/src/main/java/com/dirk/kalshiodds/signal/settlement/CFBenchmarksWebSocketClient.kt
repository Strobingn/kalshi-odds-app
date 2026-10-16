package com.dirk.kalshiodds.signal.settlement

import android.util.Log
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.ws.KalshiWsClient
import com.dirk.kalshiodds.signal.ws.WsConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/**
 * WebSocket client for CF Benchmarks (BRTI) value feed.
 * 
 * Subscribes to the `cfbenchmarks_value` channel which provides:
 * - 1Hz updates: "trailing 60-second and quarter-hour averages"
 * - 5Hz channel also available for intra-second precision
 * 
 * This is the primary data source for tracking BRTI prints in real-time.
 * 
 * @see <a href="https://docs.kalshi.com/websockets/cfbenchmarks-value">Kalshi CF Benchmarks Value Feed</a>
 * @see <a href="https://docs.kalshi.com/changelog">Kalshi Changelog</a>
 */
class CFBenchmarksWebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val onBRTIPrint: (BRTIPrint) -> Unit,
    private val onConnectionStateChange: (WsConnectionState) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : WebSocketListener() {
    
    companion object {
        private const val TAG = "CFBenchmarksWS"
        private const val CF_BENCHMARKS_WS_URL = "wss://ws.kalshi.com/v1/ws"
        
        // Channel names
        private const val CHANNEL_1HZ = "cfbenchmarks_value"
        private const val CHANNEL_5HZ = "cfbenchmarks_value_5hz"
    }
    
    private var webSocket: WebSocket? = null
    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()
    
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    
    /**
     * Connect to the CF Benchmarks WebSocket feed.
     */
    fun connect(use5Hz: Boolean = false) {
        if (_connectionState.value == WsConnectionState.CONNECTED) {
            Log.i(TAG, "Already connected")
            return
        }
        
        _connectionState.value = WsConnectionState.CONNECTING
        onConnectionStateChange(WsConnectionState.CONNECTING)
        
        val channel = if (use5Hz) CHANNEL_5HZ else CHANNEL_1HZ
        val request = Request.Builder()
            .url("$CF_BENCHMARKS_WS_URL?channel=$channel")
            .build()
        
        try {
            webSocket = okHttpClient.newWebSocket(request, this)
            _connectionState.value = WsConnectionState.CONNECTED
            onConnectionStateChange(WsConnectionState.CONNECTED)
            Log.i(TAG, "Connected to CF Benchmarks WebSocket (channel: $channel)")
            
            // Start processing messages
            scope.launch {
                for (message in messageChannel.receiveAsFlow()) {
                    processMessage(message)
                }
            }
        } catch (e: Exception) {
            _connectionState.value = WsConnectionState.ERROR(e.message ?: "Unknown error")
            onConnectionStateChange(WsConnectionState.ERROR(e.message ?: "Unknown error"))
            Log.e(TAG, "WebSocket connection failed", e)
        }
    }
    
    /**
     * Disconnect from the WebSocket.
     */
    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
        onConnectionStateChange(WsConnectionState.DISCONNECTED)
        scope.cancel()
        messageChannel.close()
        Log.i(TAG, "Disconnected from CF Benchmarks WebSocket")
    }
    
    /**
     * Subscribe to BRTI updates.
     */
    fun subscribe() {
        if (_connectionState.value != WsConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot subscribe: not connected")
            return
        }
        
        val subscribeMessage = JSONObject().apply {
            put("action", "subscribe")
            put("channel", CHANNEL_1HZ)
        }.toString()
        
        webSocket?.send(subscribeMessage)
        Log.i(TAG, "Subscribed to $CHANNEL_1HZ")
    }
    
    /**
     * Unsubscribe from BRTI updates.
     */
    fun unsubscribe() {
        val unsubscribeMessage = JSONObject().apply {
            put("action", "unsubscribe")
            put("channel", CHANNEL_1HZ)
        }.toString()
        
        webSocket?.send(unsubscribeMessage)
        Log.i(TAG, "Unsubscribed from $CHANNEL_1HZ")
    }
    
    // WebSocketListener callbacks
    
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.i(TAG, "WebSocket opened: ${response.message}")
        _connectionState.value = WsConnectionState.CONNECTED
        onConnectionStateChange(WsConnectionState.CONNECTED)
    }
    
    override fun onMessage(webSocket: WebSocket, text: String) {
        scope.launch {
            messageChannel.send(text)
        }
    }
    
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "WebSocket closed: $code - $reason")
        _connectionState.value = WsConnectionState.DISCONNECTED
        onConnectionStateChange(WsConnectionState.DISCONNECTED)
        this.webSocket = null
    }
    
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        Log.e(TAG, "WebSocket failure", t)
        _connectionState.value = WsConnectionState.ERROR(t.message ?: "Unknown error")
        onConnectionStateChange(WsConnectionState.ERROR(t.message ?: "Unknown error"))
    }
    
    /**
     * Process an incoming WebSocket message.
     */
    private fun processMessage(message: String) {
        try {
            val json = JSONObject(message)
            
            // Check message type
            when (json.optString("type", "")) {
                "heartbeat" -> {
                    // Heartbeat message, ignore
                    Log.d(TAG, "Heartbeat received")
                }
                "data" -> {
                    processDataMessage(json)
                }
                "error" -> {
                    val error = json.optString("error", "Unknown error")
                    Log.e(TAG, "WebSocket error: $error")
                    _connectionState.value = WsConnectionState.ERROR(error)
                    onConnectionStateChange(WsConnectionState.ERROR(error))
                }
                else -> {
                    // Try to parse as BRTI data directly
                    processBRTIData(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }
    
    /**
     * Process a data message from the WebSocket.
     */
    private fun processDataMessage(json: JSONObject) {
        val channel = json.optString("channel", "")
        val data = json.optJSONObject("data") ?: return
        
        when (channel) {
            CHANNEL_1HZ, CHANNEL_5HZ -> {
                processBRTIData(data)
            }
            else -> {
                Log.d(TAG, "Unknown channel: $channel")
            }
        }
    }
    
    /**
     * Process BRTI data from the WebSocket.
     * 
     * Expected format (from Kalshi docs):
     * {
     *   "timestamp": 1700000000000,
     *   "value": 42000.50,
     *   "window_id": "KXBTC15M-1700000000000",
     *   "print_index": 30
     * }
     */
    private fun processBRTIData(json: JSONObject) {
        try {
            val timestampMs = json.optLong("timestamp", 0L)
            val value = json.optDouble("value", 0.0)
            val windowId = json.optString("window_id", "")
            val printIndex = json.optInt("print_index", -1)
            
            if (timestampMs == 0L || value <= 0 || windowId.isBlank()) {
                Log.w(TAG, "Invalid BRTI data: timestamp=$timestampMs, value=$value, windowId=$windowId")
                return
            }
            
            val timestamp = Instant.ofEpochMilli(timestampMs)
            val print = BRTIPrint(timestamp, value, windowId, printIndex)
            
            // Dispatch to callback
            onBRTIPrint(print)
            
            Log.d(TAG, "BRTI print: ${print.display()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing BRTI data", e)
        }
    }
    
    /**
     * Reconnect to the WebSocket.
     */
    fun reconnect() {
        disconnect()
        connect()
    }
    
    /**
     * Check if connected.
     */
    fun isConnected(): Boolean = _connectionState.value == WsConnectionState.CONNECTED
    
    /**
     * Get the current connection state.
     */
    fun getConnectionState(): WsConnectionState = _connectionState.value
}

/**
 * Factory function to create a CF Benchmarks WebSocket client.
 */
fun createCFBenchmarksClient(
    okHttpClient: OkHttpClient,
    onBRTIPrint: (BRTIPrint) -> Unit,
    onConnectionStateChange: (WsConnectionState) -> Unit = {}
): CFBenchmarksWebSocketClient {
    return CFBenchmarksWebSocketClient(okHttpClient, onBRTIPrint, onConnectionStateChange)
}

/**
 * Extension function for BRTISettlementTracker to integrate with WebSocket.
 */
fun BRTISettlementTracker.connectToCFBenchmarks(
    okHttpClient: OkHttpClient,
    use5Hz: Boolean = false
): CFBenchmarksWebSocketClient {
    val client = createCFBenchmarksClient(okHttpClient, onBRTIPrint = { print ->
        scope.launch {
            addPrint(print)
        }
    })
    
    return client
}
