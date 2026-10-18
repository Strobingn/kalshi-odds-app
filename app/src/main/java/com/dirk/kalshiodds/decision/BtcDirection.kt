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
 * Settled losing Bitcoin scalps. They keep their code and label, and their old
 * signal still chooses a side, but a new Bitcoin trade opens only when that
 * side is the side [BtcDirection.rideSide] would buy and [BtcAiScalp.wantsEntry]
 * passes. No finite forecast means no new trade.
 *
 * The numbers are [G14Copies.SOURCES]. Forecast ride already uses [BtcAiScalp]
 * and stays on that path. v2 copies and g14 copies of the losers use the same
 * entry and the same [BtcAiScalp.exitReason] exit: 2¢ under the fill, the
 * forecast for the held side turning down, or under 60 seconds left. They do
 * not hold to the 15-minute settlement.
 *
 * Fade (sf28) used to buy the dumped side. On Bitcoin it buys the side the
 * spot move went to, and only when the forecast agrees with that side.
 */
object BtcRedo {
    const val KEPT_RIDE = "fcride"
    private val EXTRA = Regex("(t1|t2|x5|x10)$")

    fun settled(code: String): G14Copies.Snapshot? {
        val row = G14Copies.SOURCES[code] ?: return null
        return row.takeIf { it.netUsd < 0.0 && it.trades >= BtcScalpTuner.MIN_TRADES }
    }

    /** True when this code's old Bitcoin rules lost money and the redo applies. */
    fun applies(code: String): Boolean {
        if (code == KEPT_RIDE || code.isEmpty()) return false
        if (G14Copies.isCopyCode(code) || G14Copies.isCopyVariant(code)) return true
        if (code == ForecastScalp.FADE_PREFIX || code.removePrefix(ScalpV2.PREFIX).startsWith(ForecastScalp.FADE_PREFIX)) return true
        if (code == "sf28" || code == "v2-sf28") return true
        if (settled(code) != null) return true
        val root = rootOf(code)
        if (root == code || root == KEPT_RIDE) return false
        return root == "sf28" || settled(root) != null
    }

    fun appliesShock(p: ShockFav.Params): Boolean =
        p.kind == ShockFav.Kind.FADE_MAKER || applies(p.code)

    /** Under 50¢, with enough time left to get out before the window ends. */
    fun entryOk(ask: Double, tauS: Double): Boolean =
        ask.isFinite() && ask > 0.0 && ask < BtcAiScalp.MAX_ASK - 1e-12 &&
            ScalpRule.buyPriceOk(ask) &&
            tauS.isFinite() && tauS >= BtcAiScalp.MIN_TAU_S

    /**
     * New Bitcoin entry for a losing scalp: the strategy's side is the forecast
     * ride side, the ask is under 50¢, the move clears the flat threshold, and
     * at least 90 seconds remain. A missing or flat forecast does not enter.
     */
    fun agrees(side: String, yesForecast: Double, ask: Double, tauS: Double): Boolean {
        if (BtcDirection.rideSide(yesForecast) != side) return false
        return BtcAiScalp.wantsEntry(ask, kotlin.math.abs(yesForecast), tauS)
    }

    /** Forecast for the held side. NaN when the YES forecast is missing. */
    fun sideForecast(side: String, yesForecast: Double): Double =
        if (!yesForecast.isFinite()) Double.NaN else if (side == "YES") yesForecast else -yesForecast

    fun exitFor(side: String, yesForecast: Double, bid: Double, entry: Double, tauS: Double): String? =
        BtcAiScalp.exitReason(sideForecast(side, yesForecast), bid, entry, tauS)

    /** Positive move buys YES. Negative move buys NO. Flat is no trade. */
    fun rideMoveSide(moveBp: Double): String? = when {
        moveBp > 0.0 -> "YES"
        moveBp < 0.0 -> "NO"
        else -> null
    }

    /** v2-sf41t2 / v2-sf42x10 / g14x-sf30 → the original loser code, when there is one. */
    private fun rootOf(code: String): String {
        var c = code
        if (c.startsWith(G14Copies.VARIANT_PREFIX)) c = c.removePrefix(G14Copies.VARIANT_PREFIX)
        if (c.endsWith(G14Copies.SUFFIX)) c = c.removeSuffix(G14Copies.SUFFIX)
        if (c.startsWith(ScalpV2.PREFIX)) c = c.removePrefix(ScalpV2.PREFIX)
        return c.replace(EXTRA, "")
    }
}
