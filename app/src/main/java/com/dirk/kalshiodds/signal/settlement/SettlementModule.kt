package com.dirk.kalshiodds.signal.settlement

import com.dirk.kalshiodds.data.api.KalshiTradeApi
import okhttp3.OkHttpClient

/**
 * Module for creating settlement strategy components.
 * 
 * This provides a centralized way to create and configure all settlement-based
 * trading components with proper dependency injection.
 */
object SettlementModule {
    
    /**
     * Create a complete settlement strategy stack.
     * 
     * @param okHttpClient HTTP client for WebSocket connections
     * @param tradeApi Kalshi trade API for fee verification
     * @param credentials Provider for API credentials
     * @return SettlementStrategyComponents with all initialized components
     */
    fun createSettlementStack(
        okHttpClient: OkHttpClient,
        tradeApi: KalshiTradeApi,
        credentials: () -> Pair<String, String>
    ): SettlementStrategyComponents {
        // Create core components
        val settlementTracker = BRTISettlementTracker()
        val queueMonitor = QueuePositionMonitor(tradeApi, credentials)
        val feeVerifier = FeeTypeVerifier(tradeApi, credentials)
        
        // Create CF Benchmarks WebSocket client
        val cfBenchmarksClient = CFBenchmarksWebSocketClient(
            okHttpClient = okHttpClient,
            onBRTIPrint = { print ->
                // Forward prints to settlement tracker
                // In practice, this would be handled by the tracker's scope
            }
        )
        
        // Create strategies
        val averagingAwareStrategy = AveragingAwareStrategy(settlementTracker, feeVerifier)
        val makerStrategy = MakerStrategy(settlementTracker, queueMonitor, feeVerifier)
        
        // Create manager
        val strategyManager = SettlementStrategyManager(settlementTracker, queueMonitor, feeVerifier)
        
        return SettlementStrategyComponents(
            settlementTracker = settlementTracker,
            queueMonitor = queueMonitor,
            feeVerifier = feeVerifier,
            cfBenchmarksClient = cfBenchmarksClient,
            averagingAwareStrategy = averagingAwareStrategy,
            makerStrategy = makerStrategy,
            strategyManager = strategyManager
        )
    }
    
    /**
     * Create a minimal settlement stack for testing.
     */
    fun createTestStack(): SettlementStrategyComponents {
        val okHttpClient = OkHttpClient()
        val tradeApi = KalshiTradeApi(
            okHttpClient,
            "https://trade-api.kalshi.com/"
        )
        
        return createSettlementStack(okHttpClient, tradeApi, { Pair("", "") })
    }
}

/**
 * Container for all settlement strategy components.
 */
data class SettlementStrategyComponents(
    val settlementTracker: BRTISettlementTracker,
    val queueMonitor: QueuePositionMonitor,
    val feeVerifier: FeeTypeVerifier,
    val cfBenchmarksClient: CFBenchmarksWebSocketClient,
    val averagingAwareStrategy: AveragingAwareStrategy,
    val makerStrategy: MakerStrategy,
    val strategyManager: SettlementStrategyManager
) {
    /**
     * Start all components.
     */
    fun start() {
        settlementTracker.start()
        queueMonitor.start()
        cfBenchmarksClient.connect()
        cfBenchmarksClient.subscribe()
    }
    
    /**
     * Stop all components.
     */
    fun stop() {
        cfBenchmarksClient.disconnect()
        queueMonitor.stop()
        settlementTracker.stop()
    }
    
    /**
     * Connect CF Benchmarks WebSocket.
     */
    fun connectCFBenchmarks(use5Hz: Boolean = false) {
        cfBenchmarksClient.connect(use5Hz)
        cfBenchmarksClient.subscribe()
    }
    
    /**
     * Disconnect CF Benchmarks WebSocket.
     */
    fun disconnectCFBenchmarks() {
        cfBenchmarksClient.disconnect()
    }
}

/**
 * Extension functions for easy access.
 */

/**
 * Create a settlement strategy stack and connect to CF Benchmarks.
 */
suspend fun createAndConnectSettlementStack(
    okHttpClient: OkHttpClient,
    tradeApi: KalshiTradeApi,
    credentials: () -> Pair<String, String>,
    use5Hz: Boolean = false
): SettlementStrategyComponents {
    return SettlementModule.createSettlementStack(okHttpClient, tradeApi, credentials).apply {
        connectCFBenchmarks(use5Hz)
    }
}
