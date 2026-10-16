package com.dirk.kalshiodds.signal.settlement

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents a single BRTI (Bitcoin Real-Time Index) print.
 * 
 * BRTI is computed every second as an order-book, exponentially depth-weighted
 * consolidated mid, with no time smoothing (CME CF RTI Methodology).
 * 
 * @param timestamp The exact timestamp of this print (UTC)
 * @param value The BRTI value in USD at this timestamp
 * @param windowId The 15-minute window identifier this print belongs to
 * @param printIndex The index of this print within its window (0-59)
 * @param isSettled Whether this print has been used in a settled contract
 */
@Serializable
data class BRTIPrint(
    val timestamp: Instant,
    val value: Double,
    val windowId: String,
    val printIndex: Int,
    val isSettled: Boolean = false
) {
    /**
     * Get the timestamp as milliseconds since epoch.
     */
    val timestampMs: Long get() = timestamp.toEpochMilli()
    
    /**
     * Check if this print is valid (non-null, positive value).
     */
    val isValid: Boolean get() = value > 0 && value.isFinite()
    
    /**
     * Get a display string for this print.
     */
    fun display(): String = "${java.time.format.DateTimeFormatter.ISO_INSTANT.format(timestamp)}: $${"%.2f".format(value)}"
    
    companion object {
        /**
         * Create a BRTI print from timestamp and value.
         */
        fun of(timestamp: Instant, value: Double, windowId: String, printIndex: Int): BRTIPrint {
            return BRTIPrint(timestamp, value, windowId, printIndex)
        }
        
        /**
         * Create a BRTI print from milliseconds timestamp.
         */
        fun fromMs(timestampMs: Long, value: Double, windowId: String, printIndex: Int): BRTIPrint {
            return BRTIPrint(Instant.ofEpochMilli(timestampMs), value, windowId, printIndex)
        }
    }
}

/**
 * A window of BRTI prints for a single 15-minute settlement period.
 */
@Serializable
data class BRTIWindow(
    val windowId: String,
    val target: Double,
    val closeTimeEpochMs: Long,
    val prints: MutableList<BRTIPrint> = mutableListOf(),
    val isComplete: Boolean = false,
    val settlementValue: Double? = null,
    val settledOutcome: String? = null
) {
    /**
     * Get the number of prints collected so far.
     */
    val printCount: Int get() = prints.size
    
    /**
     * Get the number of prints remaining.
     */
    val printsRemaining: Int get() = BRTIConstants.TOTAL_PRINT_COUNT - prints.size
    
    /**
     * Get the sum of all collected prints.
     */
    val sumOfPrints: Double get() = prints.sumOf { it.value }
    
    /**
     * Get the current running average of collected prints.
     */
    val runningAverage: Double get() = if (prints.isEmpty()) 0.0 else sumOfPrints / prints.size
    
    /**
     * Calculate the effective price E = (Σₖ + m·S_now)/60
     * where Σₖ is sum of fixed prints and m is remaining prints.
     */
    fun effectivePrice(currentBRTI: Double): Double {
        if (prints.isEmpty()) return currentBRTI
        val m = printsRemaining
        return (sumOfPrints + m * currentBRTI) / BRTIConstants.TOTAL_PRINT_COUNT
    }
    
    /**
     * Calculate the standard deviation of the settlement average.
     * Uses pre-computed multipliers from BRTIConstants.
     */
    fun settlementStandardDeviation(): Double {
        val m = printsRemaining
        return BRTIConstants.SETTLEMENT_SD_MULTIPLIERS[m] ?: 
            BRTIConstants.SETTLEMENT_SD_MULTIPLIERS[BRTIConstants.TOTAL_PRINT_COUNT] ?: 3.63
    }
    
    /**
     * Calculate the probability that the settlement average >= target.
     * Uses the effective price and standard deviation.
     */
    fun probabilityOfYes(currentBRTI: Double): Double {
        val e = effectivePrice(currentBRTI)
        val sd = settlementStandardDeviation() / 100.0  // Convert bp to decimal
        val z = (e - target) / sd
        return cumulativeNormalDistribution(z)
    }
    
    /**
     * Calculate the probability that the settlement average < target.
     */
    fun probabilityOfNo(currentBRTI: Double): Double = 1.0 - probabilityOfYes(currentBRTI)
    
    /**
     * Check if this window is ready for trading (enough prints collected).
     */
    fun isReadyForTrading(): Boolean = 
        prints.size >= BRTIConstants.MIN_PRINT_COUNT_FOR_TRADING &&
        prints.size <= BRTIConstants.TOTAL_PRINT_COUNT
    
    /**
     * Check if we should start tracking this window.
     */
    fun shouldStartTracking(): Boolean = 
        prints.size <= BRTIConstants.MAX_PRINT_COUNT_TO_START
    
    /**
     * Get the fair value for YES side based on current BRTI.
     */
    fun fairValueYes(currentBRTI: Double): Double = probabilityOfYes(currentBRTI)
    
    /**
     * Get the fair value for NO side based on current BRTI.
     */
    fun fairValueNo(currentBRTI: Double): Double = probabilityOfNo(currentBRTI)
    
    /**
     * Add a new print to this window.
     */
    fun addPrint(print: BRTIPrint): Boolean {
        if (prints.size >= BRTIConstants.TOTAL_PRINT_COUNT) return false
        if (prints.any { it.timestampMs == print.timestampMs }) return false
        prints.add(print)
        prints.sortBy { it.timestampMs }
        return true
    }
    
    /**
     * Mark this window as complete with settlement value.
     */
    fun complete(settlementValue: Double, outcome: String) {
        this.settlementValue = settlementValue
        this.settledOutcome = outcome
    }
    
    companion object {
        /**
         * Create a new BRTI window for a given close time.
         */
        fun forCloseTime(closeTimeEpochMs: Long, target: Double): BRTIWindow {
            val windowId = "KXBTC15M-${closeTimeEpochMs}"
            return BRTIWindow(windowId, target, closeTimeEpochMs)
        }
        
        /**
         * Approximation of cumulative normal distribution (Φ function).
         * Uses the error function approximation.
         */
        private fun cumulativeNormalDistribution(z: Double): Double {
            // Abramowitz and Stegun approximation (accuracy ~7.5e-8)
            if (z < -8.0) return 0.0
            if (z > 8.0) return 1.0
            
            val sign = if (z < 0) -1.0 else 1.0
            val x = kotlin.math.abs(z)
            val t = 1.0 / (1.0 + 0.2316419 * x)
            val d = 0.3989422804014327  // 1/sqrt(2π)
            val p = d * kotlin.math.exp(-x * x / 2.0)
            val q = p * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
            return 1.0 - sign * q
        }
    }
}
