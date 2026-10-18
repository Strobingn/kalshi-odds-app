package com.dirk.kalshiodds.decision

/**
 * Bitcoin scalp direction. The live model predicts the YES-contract mid move,
 * not a dollar price. A positive forecast is UP (buy YES). A negative forecast
 * is DOWN (buy NO). Zero, missing, or non-finite is no call.
 *
 * The published fc54 weights stay as trained. [LeadForecaster.predict] removes
 * any built-in side bias by antisymmetrising the feature mirror, so a flipped
 * book gets the flipped call.
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

    fun label(forecastUsd: Double?): String = when (call(forecastUsd)) {
        Call.UP -> "UP"
        Call.DOWN -> "DOWN"
        Call.FLAT -> "NO CALL"
    }

    /**
     * BTC's published horizon and the cents the forecast must clear after
     * spread and both taker fees. This is the live config, not a new fit.
     */
    fun config(): LeadForecaster.Config = LeadForecaster.configFor(COIN)
}

/**
 * Bitcoin AI scalp. Buy the side the forecast says is about to rise, and only
 * when that side is offered under 50¢. Sell when the forecast turns down, when
 * the bid is 2¢ under the fill, or when under a minute is left. No fade and
 * no hold to the 15-minute close.
 */
object BtcAiScalp {
    const val MAX_ASK = 0.50
    const val STOP = 0.02
    const val MIN_TAU_S = 90.0
    const val EXIT_TAU_S = 60.0

    fun wantsEntry(ask: Double, move: Double, tauS: Double): Boolean =
        ask.isFinite() && ask > 0.0 && ask < MAX_ASK - 1e-12 &&
            ScalpRule.buyPriceOk(ask) &&
            move.isFinite() && move > BtcDirection.FLAT_USD &&
            tauS.isFinite() && tauS >= MIN_TAU_S

    fun stopHit(entry: Double, bid: Double): Boolean =
        entry.isFinite() && bid.isFinite() && bid <= entry - STOP + 1e-9

    /** Null forecast does not count as "turning down". The 2¢ stop still does. */
    fun exitReason(sideForecast: Double, bid: Double, entry: Double, tauS: Double): String? = when {
        stopHit(entry, bid) -> "stop −2¢"
        !sideForecast.isNaN() && sideForecast <= 0.0 -> "forecast turning down"
        tauS.isFinite() && tauS < EXIT_TAU_S -> "time left under 60s"
        else -> null
    }
}

/**
 * Bitcoin scalps whose settled paper total was already negative.
 *
 * The numbers are [G14Copies.SOURCES]: the Supabase snapshot stored when the
 * g14 copies were added. Every row has at least [BtcScalpTuner.MIN_TRADES]
 * trades and a negative net. That is the same bar the chat tuner uses.
 *
 * Forecast ride (`fcride`) is the one code that still opens a new KXBTC15M
 * trade. Its Bitcoin entries already follow [BtcAiScalp] instead of the
 * 40–60¢ band, the fade, and the hold toward the 15-minute close that lost
 * $85.13 over 185 trades. Every other settled loser is blocked, including the
 * g14 copies that hold to settlement. Shock fade-maker (sf28) is blocked too:
 * its v2 copy lost $25.56 over 53 trades, and a new Bitcoin entry does not fade.
 */
object BtcRedo {
    const val KEPT_RIDE = "fcride"

    fun settled(code: String): G14Copies.Snapshot? {
        val row = G14Copies.SOURCES[code] ?: return null
        return row.takeIf { it.netUsd < 0.0 && it.trades >= BtcScalpTuner.MIN_TRADES }
    }

    fun blocksNewBitcoinEntry(code: String): Boolean {
        if (code == KEPT_RIDE) return false
        if (G14Copies.isCopyCode(code) || G14Copies.isCopyVariant(code)) return true
        if (code == ForecastScalp.FADE_PREFIX || code.startsWith(ScalpV2.PREFIX + ForecastScalp.FADE_PREFIX)) return true
        if (code == "sf28" || code == "v2-sf28") return true
        return settled(code) != null
    }

    /** Shock / open-50 variant. Fade-maker and a settled-negative code cannot open. */
    fun blocksShock(p: ShockFav.Params): Boolean =
        p.kind == ShockFav.Kind.FADE_MAKER || blocksNewBitcoinEntry(p.code)
}
