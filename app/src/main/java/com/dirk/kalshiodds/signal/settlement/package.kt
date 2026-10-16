package com.dirk.kalshiodds.signal.settlement

/**
 * Settlement-based trading strategies for Kalshi prediction markets.
 * 
 * This package implements the Rank 1 strategy from the research:
 * "Averaging-aware late favourite: extend your rule to any side, 10–45 prints left,
 * fair from known print sum"
 * 
 * Key insight: KXBTC15M settles based on the simple average of 60 one-second BRTI prints
 * compared against a fixed target. With m prints remaining, the effective price is:
 *   E = (Σₖ + m·S_now)/60
 * 
 * The variance collapses rapidly as m decreases, meaning the outcome is effectively
 * decided 20-40 seconds before the close.
 * 
 * Main Components:
 * - BRTIConstants: Constants for BRTI settlement calculations
 * - BRTIPrint: Data class for BRTI prints
 * - BRTIWindow: Data class for BRTI print windows
 * - BRTISettlementTracker: Tracks BRTI prints and calculates probabilities
 * - CFBenchmarksWebSocketClient: WebSocket client for CF Benchmarks feed
 * - QueuePositionMonitor: Monitors order queue positions
 * - FeeTypeVerifier: Verifies fee types for series
 * - AveragingAwareStrategy: Rank 1 strategy implementation
 * - MakerStrategy: Rank 3 strategy (maker version)
 * - SettlementStrategyManager: Coordinates all settlement strategies
 * - SettlementModule: Factory for creating strategy components
 */
