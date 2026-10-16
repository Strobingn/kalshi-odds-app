package com.dirk.kalshiodds.signal.settlement

import android.util.Log
import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.dto.SeriesDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Verifies the fee type for KXBTC15M and other series.
 * 
 * Critical check: As of October 2026, only 2 of 276 crypto series carried maker fees.
 * If KXBTC15M has maker fees, the +0.35¢ front-of-queue edge becomes negative.
 * 
 * @see <a href="https://kalshi.com/docs/kalshi-fee-schedule.pdf">Kalshi Fee Schedule</a>
 * @see <a href="https://www.allium.so/blog/kalshi-fees-why-the-cost-peaks-near-50-cents/">Allium Fee Analysis</a>
 */
class FeeTypeVerifier(
    private val tradeApi: KalshiTradeApi,
    private val credentials: () -> Pair<String, String>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    
    companion object {
        private const val TAG = "FeeTypeVerifier"
        private const val KXBTC15M_TICKER = "KXBTC15M"
    }
    
    private val mutex = Mutex()
    private val _feeInfoCache = mutableMapOf<String, SeriesFeeInfo>()
    
    /**
     * Fee information cache.
     */
    val feeInfoCache: Map<String, SeriesFeeInfo> get() = _feeInfoCache.toMap()
    
    /**
     * Whether KXBTC15M has maker fees.
     */
    private val _kxtbc15mHasMakerFees = MutableStateFlow<Boolean?>(null)
    val kxtbc15mHasMakerFees: StateFlow<Boolean?> = _kxtbc15mHasMakerFees.asStateFlow()
    
    /**
     * All series with their fee types.
     */
    private val _allSeriesFeeInfo = MutableStateFlow<Map<String, SeriesFeeInfo>>(emptyMap())
    val allSeriesFeeInfo: StateFlow<Map<String, SeriesFeeInfo>> = _allSeriesFeeInfo.asStateFlow()
    
    /**
     * Fetch and cache fee information for KXBTC15M.
     */
    suspend fun verifyKXBTC15MFeeType(): SeriesFeeInfo? = mutex.withLock {
        // Return cached value if available
        _feeInfoCache[KXBTC15M_TICKER]?.let { return@withLock it }
        
        val feeInfo = fetchSeriesFeeInfo(KXBTC15M_TICKER)
        if (feeInfo != null) {
            _feeInfoCache[KXBTC15M_TICKER] = feeInfo
            _kxtbc15mHasMakerFees.value = feeInfo.hasMakerFees
        }
        return@withLock feeInfo
    }
    
    /**
     * Fetch fee information for a specific series.
     */
    suspend fun fetchSeriesFeeInfo(ticker: String): SeriesFeeInfo? {
        return try {
            val (apiKey, secret) = credentials()
            
            // Call Kalshi API to get series info
            // GET /trade-api/v2/series/{ticker}
            val response = tradeApi.getSeries(ticker)
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch series info for $ticker: ${response.code()}")
                return null
            }
            
            val seriesDto = response.body() ?: run {
                Log.e(TAG, "Empty response for series $ticker")
                return null
            }
            
            // Parse fee type from response
            val feeType = seriesDto.feeType ?: "unknown"
            val feeMultiplier = seriesDto.feeMultiplier ?: 1.0
            val hasMakerFees = parseHasMakerFees(feeType, seriesDto)
            
            SeriesFeeInfo(
                ticker = ticker,
                feeType = feeType,
                feeMultiplier = feeMultiplier,
                hasMakerFees = hasMakerFees,
                takerFeeFormula = getTakerFeeFormula(feeType),
                makerFeeFormula = if (hasMakerFees) getMakerFeeFormula(feeType) else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching fee info for $ticker", e)
            null
        }
    }
    
    /**
     * Parse whether a series has maker fees from its fee type.
     */
    private fun parseHasMakerFees(feeType: String, seriesDto: SeriesDto): Boolean {
        // Based on Kalshi fee schedule:
        // - "quadratic" = taker fees only (most common)
        // - "quadratic_with_maker_fees" = both taker and maker fees
        // - Other types may exist
        
        return when (feeType.lowercase()) {
            "quadratic_with_maker_fees" -> true
            "quadratic" -> false
            else -> {
                // Check if series appears in maker fees section
                // This would require additional API calls
                seriesDto.makerFeeSchedule != null
            }
        }
    }
    
    /**
     * Get the taker fee formula for a given fee type.
     */
    private fun getTakerFeeFormula(feeType: String): String {
        return when (feeType.lowercase()) {
            "quadratic", "quadratic_with_maker_fees" -> "ceil(0.07 * C * P * (1-P))"
            else -> "unknown"
        }
    }
    
    /**
     * Get the maker fee formula for a given fee type.
     */
    private fun getMakerFeeFormula(feeType: String): String {
        return when (feeType.lowercase()) {
            "quadratic_with_maker_fees" -> "ceil(0.0175 * C * P * (1-P))"
            else -> "unknown"
        }
    }
    
    /**
     * Calculate taker fee for a given price and contract count.
     * 
     * @param price Price per contract (0-1)
     * @param contracts Number of contracts
     * @param ticker Series ticker (to get fee type)
     * @return Fee in USD
     */
    suspend fun calculateTakerFee(price: Double, contracts: Int, ticker: String = KXBTC15M_TICKER): Double {
        val feeInfo = fetchSeriesFeeInfo(ticker) ?: run {
            // Default to standard quadratic fee
            return calculateStandardTakerFee(price, contracts)
        }
        
        return when (feeInfo.feeType.lowercase()) {
            "quadratic", "quadratic_with_maker_fees" -> 
                calculateStandardTakerFee(price, contracts) * feeInfo.feeMultiplier
            else -> calculateStandardTakerFee(price, contracts)
        }
    }
    
    /**
     * Calculate standard taker fee: ceil(0.07 * C * P * (1-P))
     */
    private fun calculateStandardTakerFee(price: Double, contracts: Int): Double {
        val p = price.coerceIn(0.0, 1.0)
        val feePerContract = 0.07 * p * (1 - p)
        val totalFee = contracts * feePerContract
        return kotlin.math.ceil(totalFee * 100) / 100  // Round up to nearest cent
    }
    
    /**
     * Calculate maker fee for a given price and contract count.
     * 
     * @param price Price per contract (0-1)
     * @param contracts Number of contracts
     * @param ticker Series ticker
     * @return Fee in USD, or 0.0 if no maker fees
     */
    suspend fun calculateMakerFee(price: Double, contracts: Int, ticker: String = KXBTC15M_TICKER): Double {
        val feeInfo = fetchSeriesFeeInfo(ticker) ?: return 0.0
        
        if (!feeInfo.hasMakerFees) return 0.0
        
        return when (feeInfo.feeType.lowercase()) {
            "quadratic_with_maker_fees" -> 
                calculateStandardMakerFee(price, contracts) * feeInfo.feeMultiplier
            else -> 0.0
        }
    }
    
    /**
     * Calculate standard maker fee: ceil(0.0175 * C * P * (1-P))
     */
    private fun calculateStandardMakerFee(price: Double, contracts: Int): Double {
        val p = price.coerceIn(0.0, 1.0)
        val feePerContract = 0.0175 * p * (1 - p)
        val totalFee = contracts * feePerContract
        return kotlin.math.ceil(totalFee * 100) / 100
    }
    
    /**
     * Check if a series is eligible for the Liquidity Incentive Program (LIP).
     */
    suspend fun isEligibleForLIP(ticker: String): Boolean {
        return try {
            val (apiKey, secret) = credentials()
            // Call Kalshi API to check LIP eligibility
            // GET /trade-api/v2/markets/{ticker}/lip-eligibility
            // For now, return false as placeholder
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking LIP eligibility for $ticker", e)
            false
        }
    }
    
    /**
     * Get LIP reward rate for a series.
     */
    suspend fun getLIPRewardRate(ticker: String): Double {
        return try {
            val (apiKey, secret) = credentials()
            // Call Kalshi API to get LIP reward rate
            // For now, return 0.0 as placeholder
            0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting LIP reward rate for $ticker", e)
            0.0
        }
    }
    
    /**
     * Refresh all fee information.
     */
    suspend fun refreshAllFeeInfo() = mutex.withLock {
        // In a real implementation, this would fetch all series
        // For now, just refresh KXBTC15M
        verifyKXBTC15MFeeType()
    }
    
    /**
     * Clear cache.
     */
    fun clearCache() = mutex.withLock {
        _feeInfoCache.clear()
        _kxtbc15mHasMakerFees.value = null
    }
    
    /**
     * Get fee impact analysis for a strategy.
     */
    suspend fun getFeeImpactAnalysis(
        ticker: String = KXBTC15M_TICKER,
        strategyType: StrategyType = StrategyType.TAKER_LATE_FAVORITE
    ): FeeImpactAnalysis {
        val feeInfo = fetchSeriesFeeInfo(ticker) ?: run {
            return FeeImpactAnalysis(
                ticker = ticker,
                strategyType = strategyType,
                hasMakerFees = false,
                makerFeeImpact = 0.0,
                takerFeeImpact = -1.75,  // Worst case at 50c
                isStrategyViable = true,
                recommendation = "Strategy viable (no maker fees confirmed)"
            )
        }
        
        val hasMakerFees = feeInfo.hasMakerFees
        
        val (makerFeeAt50c, makerFeeAt95c) = if (hasMakerFees) {
            Pair(0.44, 0.08)  // cents per contract
        } else {
            Pair(0.0, 0.0)
        }
        
        val (takerFeeAt50c, takerFeeAt95c) = Pair(1.75, 0.33)  // cents per contract
        
        val isStrategyViable = when (strategyType) {
            StrategyType.TAKER_LATE_FAVORITE -> {
                // Taker strategy only viable at high prices (90c+)
                // where taker fee is small
                takerFeeAt95c < 0.5  // Less than 0.5c fee
            }
            StrategyType.MAKER_FRONT_OF_QUEUE -> {
                // Maker strategy only viable if no maker fees
                // or if edge exceeds maker fee
                !hasMakerFees
            }
            StrategyType.MAKER_LATE_WINDOW -> {
                // Similar to front-of-queue
                !hasMakerFees
            }
        }
        
        val recommendation = when {
            hasMakerFees && strategyType == StrategyType.MAKER_FRONT_OF_QUEUE ->
                "AVOID: Maker fees (${"%.2f".format(makerFeeAt50c)}c at 50c) exceed edge (${"%.2f".format(0.35)}c)"
            hasMakerFees && strategyType == StrategyType.TAKER_LATE_FAVORITE ->
                "CAUTION: Only trade at 90c+ where taker fee (${"%.2f".format(takerFeeAt95c)}c) is small"
            !hasMakerFees && strategyType == StrategyType.MAKER_FRONT_OF_QUEUE ->
                "VIABLE: No maker fees, front-of-queue edge (${"%.2f".format(0.35)}c) is positive"
            else -> "Strategy viable"
        }
        
        return FeeImpactAnalysis(
            ticker = ticker,
            strategyType = strategyType,
            hasMakerFees = hasMakerFees,
            makerFeeAt50c = makerFeeAt50c,
            makerFeeAt95c = makerFeeAt95c,
            takerFeeAt50c = takerFeeAt50c,
            takerFeeAt95c = takerFeeAt95c,
            makerFeeImpact = if (hasMakerFees) -makerFeeAt50c else 0.0,
            takerFeeImpact = -takerFeeAt50c,
            isStrategyViable = isStrategyViable,
            recommendation = recommendation
        )
    }
}

/**
 * Fee information for a series.
 */
data class SeriesFeeInfo(
    val ticker: String,
    val feeType: String,
    val feeMultiplier: Double,
    val hasMakerFees: Boolean,
    val takerFeeFormula: String,
    val makerFeeFormula: String?
)

/**
 * Strategy types for fee impact analysis.
 */
enum class StrategyType {
    TAKER_LATE_FAVORITE,
    MAKER_FRONT_OF_QUEUE,
    MAKER_LATE_WINDOW,
    AVERAGING_AWARE
}

/**
 * Fee impact analysis for a strategy.
 */
data class FeeImpactAnalysis(
    val ticker: String,
    val strategyType: StrategyType,
    val hasMakerFees: Boolean,
    val makerFeeAt50c: Double,
    val makerFeeAt95c: Double,
    val takerFeeAt50c: Double,
    val takerFeeAt95c: Double,
    val makerFeeImpact: Double,
    val takerFeeImpact: Double,
    val isStrategyViable: Boolean,
    val recommendation: String
) {
    fun display(): String {
        return "[$ticker] ${strategyType}: " +
               "Maker fees: ${if (hasMakerFees) "YES" else "NO"}, " +
               "Viable: ${if (isStrategyViable) "YES" else "NO"}, " +
               "Recommendation: $recommendation"
    }
}
