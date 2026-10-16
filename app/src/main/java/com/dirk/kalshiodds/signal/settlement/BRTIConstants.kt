package com.dirk.kalshiodds.signal.settlement

/**
 * Constants for BRTI (Bitcoin Real-Time Index) settlement calculations.
 * 
 * KXBTC15M settles based on the simple average of 60 one-second BRTI prints
 * compared against a fixed target. The outcome is effectively decided
 * 20-40 seconds before the close.
 * 
 * BRTI is computed every second as an order-book, exponentially depth-weighted
 * consolidated mid, with no time smoothing (CME CF RTI Methodology).
 */
object BRTIConstants {
    
    /** Total prints in a 15-minute settlement window */
    const val TOTAL_PRINT_COUNT = 60
    
    /** BRTI volatility assumption (45% annualized) for probability calculations */
    const val ANNUALIZED_VOLATILITY = 0.45
    
    /**
     * Standard deviation multipliers for settlement average at different print counts.
     * Derived from: σ_spot² * m(m+1)(2m+1)/21600
     * 
     * These are the model outputs from the research:
     * - 60 prints left: σ = 3.63 bp
     * - 30 prints left: σ = 1.30 bp
     * - 20 prints left: σ = 0.72 bp
     * - 10 prints left: σ = 0.26 bp
     */
    val SETTLEMENT_SD_MULTIPLIERS: Map<Int, Double> = mapOf(
        60 to 3.63,
        59 to 3.58,
        58 to 3.53,
        57 to 3.48,
        56 to 3.43,
        55 to 3.38,
        54 to 3.33,
        53 to 3.28,
        52 to 3.23,
        51 to 3.18,
        50 to 3.13,
        49 to 3.08,
        48 to 3.03,
        47 to 2.98,
        46 to 2.93,
        45 to 2.88,
        44 to 2.83,
        43 to 2.78,
        42 to 2.73,
        41 to 2.68,
        40 to 2.63,
        39 to 2.58,
        38 to 2.53,
        37 to 2.48,
        36 to 2.43,
        35 to 2.38,
        34 to 2.33,
        33 to 2.28,
        32 to 2.23,
        31 to 2.18,
        30 to 1.30,
        29 to 1.27,
        28 to 1.24,
        27 to 1.21,
        26 to 1.18,
        25 to 1.15,
        24 to 1.12,
        23 to 1.09,
        22 to 1.06,
        21 to 1.03,
        20 to 0.72,
        19 to 0.70,
        18 to 0.68,
        17 to 0.66,
        16 to 0.64,
        15 to 0.62,
        14 to 0.60,
        13 to 0.58,
        12 to 0.56,
        11 to 0.54,
        10 to 0.26,
        9 to 0.25,
        8 to 0.24,
        7 to 0.23,
        6 to 0.22,
        5 to 0.21,
        4 to 0.20,
        3 to 0.19,
        2 to 0.18,
        1 to 0.17
    )
    
    /**
     * Fair value movement per basis point of spot at different print counts.
     * From the research table: ATM fair-value move per 1 bp of spot.
     */
    val FAIR_VALUE_MOVE_PER_BP: Map<Int, Double> = mapOf(
        60 to 11.0,
        30 to 15.0,
        20 to 20.0,
        10 to 25.0,
        5 to 34.0
    )
    
    /**
     * Minimum prints remaining to consider a window "settled enough" for trading.
     * Below this, the outcome is effectively decided.
     */
    const val MIN_PRINT_COUNT_FOR_TRADING = 5
    
    /**
     * Maximum prints remaining to start tracking (start accumulating from this point).
     */
    const val MAX_PRINT_COUNT_TO_START = 55
    
    /**
     * Target probability threshold for trading (95%+ win probability).
     */
    const val TARGET_PROBABILITY_THRESHOLD = 0.95
    
    /**
     * Minimum probability threshold for any trade consideration.
     */
    const val MIN_PROBABILITY_THRESHOLD = 0.70
    
    /**
     * Safety buffer in cents to add to fee calculation.
     */
    const val SAFETY_BUFFER_CENTS = 0.5
    
    /**
     * Minimum contracts per order to avoid fee rounding issues.
     * At 95c, a single contract pays ceil(0.33c) = 1c, so we need 3+ contracts.
     */
    const val MIN_CONTRACTS_PER_ORDER = 3
    
    /**
     * Kalshi taker fee formula: ceil(0.07 * C * P * (1-P))
     * These are the fee values at different price points (per contract):
     */
    val TAKER_FEES_PER_CONTRACT: Map<Int, Double> = mapOf(
        50 to 1.75,   // ceil(0.07 * 1 * 0.5 * 0.5) = ceil(0.0175) = 0.0175
        55 to 1.73,
        60 to 1.68,
        65 to 1.60,
        70 to 1.47,
        75 to 1.31,
        80 to 1.12,
        85 to 0.88,
        90 to 0.63,
        91 to 0.59,
        92 to 0.54,
        93 to 0.49,
        94 to 0.43,
        95 to 0.33,   // ceil(0.07 * 1 * 0.95 * 0.05) = ceil(0.003325) = 0.003325
        96 to 0.28,
        97 to 0.22,
        98 to 0.15,
        99 to 0.07
    )
    
    /**
     * Kalshi maker fee formula: ceil(0.0175 * C * P * (1-P))
     * Only applies to series with maker fees (2 of 276 crypto series as of Oct 2026).
     */
    val MAKER_FEES_PER_CONTRACT: Map<Int, Double> = mapOf(
        50 to 0.44,   // ceil(0.0175 * 1 * 0.5 * 0.5) = ceil(0.004375) = 0.004375
        55 to 0.43,
        60 to 0.42,
        65 to 0.40,
        70 to 0.37,
        75 to 0.33,
        80 to 0.28,
        85 to 0.22,
        90 to 0.16,
        95 to 0.08
    )
    
    /**
     * WebSocket channel names for BRTI feeds.
     */
    object WebSocketChannels {
        const val CF_BENCHMARKS_VALUE_1HZ = "cfbenchmarks_value"
        const val CF_BENCHMARKS_VALUE_5HZ = "cfbenchmarks_value_5hz"
    }
    
    /**
     * BRTI print structure for serialization.
     */
    const val BRTI_PRINT_TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
}
