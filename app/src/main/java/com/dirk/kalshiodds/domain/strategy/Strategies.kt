package com.dirk.kalshiodds.domain.strategy

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live settlement-feed context for the final minute (report Edge 1):
 * the RTI one-second samples observed so far in the settlement minute and
 * the window's opening reference price (60-second pre-open RTI average).
 */
data class SettlementContext(
    val openReference: Double,
    val samples: List<Double>
)

/**
 * Context for one evaluation pass over the live 15-minute window.
 *
 * @param marketUpPriceCents live best ask / last for the UP (YES) side in
 *   cents, or null when the market price is unknown.
 * @param settlement running RTI samples for the final minute, when the
 *   settlement pass-through feed is available.
 */
data class WindowContext(
    val nowMs: Long,
    val windowCloseMs: Long,
    val marketUpPriceCents: Int? = null,
    val settlement: SettlementContext? = null
)

/**
 * An advisory strategy. [evaluate] returns null when there is no edge
 * (sit out) or warm-up data is insufficient (< [MIN_CANDLES] candles).
 */
interface Strategy {
    /** Short stable key used in signal history and on the home card. */
    val name: String

    fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal?

    companion object {
        const val MIN_CANDLES = 60
        const val WINDOW_MS = 15 * 60_000L
    }
}

/**
 * Majority vote across six trend gauges: SMA5>SMA20, MACD histogram sign,
 * RSI vs 50, close vs Bollinger mid, OBV slope (K>D substitute), close vs
 * rolling VWAP. Emits only when |bull − bear| >= 4 and ADX > 25 (real trend).
 * Confidence is the winning agreement fraction.
 */
object ConsensusTrend : Strategy {
    override val name = "Trend"

    private const val ADX_MIN = 25.0
    private const val LEAD_MIN = 4

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        val closes = candles.map { it.close }
        val last = candles.size - 1

        val sma5 = Indicators.sma(closes, 5)[last] ?: return null
        val sma20 = Indicators.sma(closes, 20)[last] ?: return null
        val hist = Indicators.macd(closes)[last]?.histogram ?: return null
        val rsi = Indicators.rsi(closes)[last] ?: return null
        val mid = Indicators.bollinger(closes)[last]?.second ?: return null
        val vw = Indicators.vwap(candles)[last] ?: return null
        val adx = Indicators.adx(candles)[last]?.adx ?: return null
        val obv = Indicators.obv(candles)
        if (last < 3) return null
        val obvSlope = obv[last] - obv[last - 3]
        val close = closes[last]

        var bull = 0
        var bear = 0
        fun vote(bullish: Boolean?) {
            if (bullish == true) bull++ else if (bullish == false) bear++
        }
        vote(sma5 > sma20)
        vote(if (hist > 0) true else if (hist < 0) false else null)
        vote(if (rsi > 50.0) true else if (rsi < 50.0) false else null)
        vote(close > mid)
        vote(if (obvSlope > 0) true else if (obvSlope < 0) false else null)
        vote(close > vw)

        val lead = bull - bear
        if (abs(lead) < LEAD_MIN || adx <= ADX_MIN) return null
        val direction = if (lead > 0) Direction.UP else Direction.DOWN
        val votes = bull + bear
        val confidence = maxOf(bull, bear).toDouble() / votes
        val side = if (direction == Direction.UP) "bullish" else "bearish"
        val rationale = "${maxOf(bull, bear)}/$votes $side votes, ADX ${adx.roundToInt()}"
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}

/**
 * Fades stretched moves: RSI14 > 80 with a close above the upper Bollinger
 * Band → DOWN; RSI14 < 20 with a close below the lower band → UP.
 * Confidence = 0.55 + min(0.15, overshoot), overshoot in tenths of a percent
 * beyond the band.
 */
object MeanReversionFade : Strategy {
    override val name = "Fade"

    private const val RSI_HIGH = 80.0
    private const val RSI_LOW = 20.0

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        val closes = candles.map { it.close }
        val last = candles.size - 1
        val rsi = Indicators.rsi(closes)[last] ?: return null
        val band = Indicators.bollinger(closes)[last] ?: return null
        val upper = band.first ?: return null
        val lower = band.third ?: return null
        val close = closes[last]

        val direction: Direction
        val overshootPct: Double
        when {
            rsi > RSI_HIGH && close > upper -> {
                direction = Direction.DOWN
                overshootPct = (close - upper) / upper * 100.0
            }
            rsi < RSI_LOW && close < lower -> {
                direction = Direction.UP
                overshootPct = (lower - close) / lower * 100.0
            }
            else -> return null
        }
        val confidence = 0.55 + min(0.15, overshootPct / 10.0)
        val rationale = if (direction == Direction.DOWN) {
            "RSI ${rsi.roundToInt()} above upper band"
        } else {
            "RSI ${rsi.roundToInt()} below lower band"
        }
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}

/**
 * Bollinger squeeze release: bandwidth sits at its 20-period rolling minimum,
 * the MACD histogram just flipped sign on the last bar, and OBV rose over the
 * last 3 candles. Emits in the direction of the flip. One-bar persistence —
 * the flip must be on the latest candle.
 */
object SqueezeBreakout : Strategy {
    override val name = "Squeeze"

    private const val BW_LOOKBACK = 20

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        val closes = candles.map { it.close }
        val last = candles.size - 1
        val bw = Indicators.bollingerBandwidth(closes)
        val cur = bw[last] ?: return null
        val window = bw.subList(maxOf(0, last - BW_LOOKBACK + 1), last + 1)
            .filterNotNull()
        if (window.isEmpty() || cur > window.min()) return null

        val macd = Indicators.macd(closes)
        val h0 = macd[last]?.histogram ?: return null
        val h1 = macd[last - 1]?.histogram ?: return null
        if (h0 == 0.0 || h1 == 0.0 || (h0 > 0) == (h1 > 0)) return null

        val obv = Indicators.obv(candles)
        if (last < 3 || obv[last] <= obv[last - 3]) return null

        val direction = if (h0 > 0) Direction.UP else Direction.DOWN
        val rationale = "band squeeze released ${direction.name.lowercase()}, MACD flipped, OBV rising"
        return StrategySignal(name, direction, 0.60, rationale, ctx.windowCloseMs)
    }
}

/**
 * Last-3-minutes mispricing: fair P(UP) ≈ sigmoid of the spot-vs-window-open
 * distance in ATR units. When the live market price differs from fair by
 * >= 8 cents, emits the underpriced side. Null when the market price is
 * unknown or more than 3 minutes remain.
 */
object LateWindowDislocation : Strategy {
    override val name = "Dislocation"

    const val LATE_WINDOW_MS = 3 * 60_000L
    const val GAP_CENTS_MIN = 8.0

    override fun evaluate(candles: List<Candle>, ctx: WindowContext): StrategySignal? {
        if (candles.size < Strategy.MIN_CANDLES) return null
        val leftMs = ctx.windowCloseMs - ctx.nowMs
        if (leftMs <= 0 || leftMs > LATE_WINDOW_MS) return null
        val marketCents = ctx.marketUpPriceCents ?: return null

        val atr = Indicators.atr(candles).lastOrNull { it != null } ?: return null
        if (atr != null && atr <= 0.0) return null
        val windowStartMs = ctx.windowCloseMs - Strategy.WINDOW_MS
        val windowOpen = candles.lastOrNull { it.openTimeMs <= windowStartMs }?.open
            ?: candles.last().open
        val spot = candles.last().close
        val distAtr = (spot - windowOpen) / atr
        val fairUp = Indicators.sigmoid(distAtr)
        val gapCents = fairUp * 100.0 - marketCents
        if (abs(gapCents) < GAP_CENTS_MIN) return null

        val direction = if (gapCents > 0) Direction.UP else Direction.DOWN
        val confidence = 0.55 + min(0.20, abs(gapCents) / 100.0)
        val fairCents = (fairUp * 100.0).roundToInt()
        val rationale = "fair UP ${fairCents}¢ vs market $marketCents¢ (${abs(gapCents).roundToInt()}¢ gap)"
        return StrategySignal(name, direction, confidence, rationale, ctx.windowCloseMs)
    }
}
