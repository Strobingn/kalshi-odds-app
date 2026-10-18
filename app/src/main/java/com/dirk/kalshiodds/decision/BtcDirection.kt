package com.dirk.kalshiodds.decision

/**
 * Bitcoin scalp direction. The live model predicts the YES-contract mid move,
 * not a dollar price. A positive forecast is UP (buy YES). A negative forecast
 * is DOWN (buy NO). Zero, missing, or non-finite is no call.
 *
 * Paper only. This is not a claim that the forecast is profitable.
 */
object BtcDirection {
    const val COIN = "BTC"
    const val SERIES = "KXBTC15M"
    /** Ignore forecasts smaller than a tenth of a cent. */
    const val FLAT_USD = 0.001

    enum class Call { UP, DOWN, FLAT }

    fun isBitcoin(ticker: String): Boolean = ticker.trim().uppercase().startsWith(SERIES)

    fun call(forecastUsd: Double?): Call = when {
        forecastUsd == null || !forecastUsd.isFinite() -> Call.FLAT
        forecastUsd > FLAT_USD -> Call.UP
        forecastUsd < -FLAT_USD -> Call.DOWN
        else -> Call.FLAT
    }

    /** Ride side. Null when the model is not calling a direction. */
    fun rideSide(forecastUsd: Double?): String? = when (call(forecastUsd)) {
        Call.UP -> "YES"
        Call.DOWN -> "NO"
        Call.FLAT -> null
    }
}

/**
 * Bitcoin forecast scalp. Paper only, not proven.
 *
 * Buy the side the forecast says is about to rise, and only when that move
 * still has positive expected value after the spread and both taker fees.
 * Sell on a real 2¢ drop. One tick under the bid you could have sold at entry
 * is the spread you already paid, not a stop.
 *
 * Settlement context (KXBTC15M): YES if the 60-second CF Benchmarks BRTI average
 * before the close is at least the average before the open. Do not hold that
 * as a reason to ignore the spread.
 */
object BtcAiScalp {
    const val MAX_ASK = 0.50
    const val STOP = 0.02
    const val MIN_TAU_S = 90.0
    /** Spread at least half a cent means the fill paid through the touch. */
    private const val SPREAD_EPS = 0.005

    /**
     * [refBid] is the sellable bid recorded at entry. When it sits below the fill,
     * the stop is 2¢ under that bid, so a one-tick print (31¢ fill, 30¢ bid, 29¢ noise)
     * does not stop. With no recorded bid, a 2¢ drop from the fill does stop.
     */
    fun stopHit(entry: Double, bid: Double, refBid: Double? = null): Boolean {
        if (!entry.isFinite() || !bid.isFinite() || entry <= 0.0) return false
        val ref = refBid?.takeIf { it.isFinite() && it > 0.0 && it <= entry + 1e-12 }
        val basis = if (ref != null && entry - ref >= SPREAD_EPS) ref else entry
        return bid <= basis - STOP + 1e-9
    }

    /**
     * True only when [move] exceeds the spread plus the entry taker fee plus the
     * fee to sell at the price the move implies. Ask must be under 50¢ and the
     * window must have at least [MIN_TAU_S] left. Not a profitability proof.
     */
    fun wantsEntry(ask: Double, spread: Double, move: Double, tauS: Double, contracts: Int = ScalpRule.CONTRACTS): Boolean {
        if (!ask.isFinite() || ask <= 0.0 || ask >= MAX_ASK - 1e-12) return false
        if (!ScalpRule.buyPriceOk(ask)) return false
        if (!spread.isFinite() || spread < 0.0) return false
        if (!move.isFinite() || move <= BtcDirection.FLAT_USD) return false
        if (!tauS.isFinite() || tauS < MIN_TAU_S) return false
        return expectedValueAfterCosts(ask, spread, move, contracts) > 1e-12
    }

    /** Forecast move minus spread minus both taker fees, dollars per contract. */
    fun expectedValueAfterCosts(ask: Double, spread: Double, move: Double, contracts: Int = ScalpRule.CONTRACTS): Double {
        if (!ask.isFinite() || !spread.isFinite() || !move.isFinite()) return Double.NaN
        val exitPx = (ask - spread + move).coerceIn(0.0, 1.0)
        val cost = spread + ScalpRule.feePerContract(contracts, ask) + ScalpRule.feePerContract(contracts, exitPx)
        return move - cost
    }

    /** Null forecast is not "turning down". The 2¢ stop still applies. */
    fun exitReason(sideForecast: Double, bid: Double, entry: Double, tauS: Double, refBid: Double? = null): String? = when {
        stopHit(entry, bid, refBid) -> "stop −2¢"
        !sideForecast.isNaN() && sideForecast <= 0.0 -> "forecast turning down"
        tauS.isFinite() && tauS < ScalpRule.TIME_STOP_S -> "time left under 60s"
        else -> null
    }
}
