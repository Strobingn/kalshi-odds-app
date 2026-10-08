package com.dirk.kalshiodds.domain.strategy

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure-Kotlin technical indicators for the strategy engine. No Android deps.
 * Every series is index-aligned with the input; warm-up indices are null.
 * Formulas match the stock-signal-analyzer skill (Wilder smoothing where noted).
 */

/** One OHLCV bar. For KXBTC15M these are 15-minute Coinbase spot candles. */
data class Candle(
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/** MACD line / signal / histogram at one index. */
data class MacdPoint(val line: Double, val signal: Double, val histogram: Double)

/** ADX with directional indices at one index (Wilder). */
data class AdxPoint(val adx: Double, val plusDI: Double, val minusDI: Double)

object Indicators {

    /** Simple moving average; null until `period` values are available. */
    fun sma(values: List<Double>, period: Int): List<Double?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<Double>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out.toList()
    }

    /** Exponential moving average seeded with the SMA of the first `period` values. */
    fun ema(values: List<Double>, period: Int): List<Double?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<Double>(values.size)
        if (values.size < period) return out.toList()
        val k = 2.0 / (period + 1)
        var prev = values.subList(0, period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1.0 - k)
            out[i] = prev
        }
        return out.toList()
    }

    /** Wilder RSI; null until `period` deltas exist. Monotonic rises read 100. */
    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<Double>(closes.size)
        if (closes.size <= period) return out.toList()
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) avgGain += d else avgLoss -= d
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val gain = if (d > 0) d else 0.0
            val loss = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out.toList()
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) {
            if (avgGain == 0.0) 50.0 else 100.0
        } else {
            val rs = avgGain / avgLoss
            100.0 - 100.0 / (1.0 + rs)
        }

    /**
     * Bollinger Bands (population stddev). Null until `period` closes exist;
     * then Triple(upper, mid, lower).
     */
    fun bollinger(
        closes: List<Double>,
        period: Int = 20,
        mult: Double = 2.0
    ): List<Triple<Double?, Double?, Double?>?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<Triple<Double?, Double?, Double?>>(closes.size)
        var sum = 0.0
        var sumSq = 0.0
        for (i in closes.indices) {
            sum += closes[i]
            sumSq += closes[i] * closes[i]
            if (i >= period) {
                sum -= closes[i - period]
                sumSq -= closes[i - period] * closes[i - period]
            }
            if (i >= period - 1) {
                val mid = sum / period
                val variance = (sumSq / period - mid * mid).coerceAtLeast(0.0)
                val sd = sqrt(variance)
                out[i] = Triple(mid + mult * sd, mid, mid - mult * sd)
            }
        }
        return out.toList()
    }

    /** (upper − lower) / mid per index; null during warm-up or when mid is 0. */
    fun bollingerBandwidth(
        closes: List<Double>,
        period: Int = 20,
        mult: Double = 2.0
    ): List<Double?> =
        bollinger(closes, period, mult).map { band ->
            val upper = band?.first
            val mid = band?.second
            val lower = band?.third
            if (upper == null || mid == null || lower == null || mid == 0.0) null
            else (upper - lower) / mid
        }

    /** MACD: line = EMA(fast) − EMA(slow), signal = EMA(line, signal). Null warm-up. */
    fun macd(
        closes: List<Double>,
        fast: Int = 12,
        slow: Int = 26,
        signal: Int = 9
    ): List<MacdPoint?> {
        require(fast > 0 && slow > fast && signal > 0) { "require 0 < fast < slow and signal > 0" }
        val out = arrayOfNulls<MacdPoint>(closes.size)
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val line = arrayOfNulls<Double>(closes.size)
        for (i in closes.indices) {
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f != null && s != null) line[i] = f - s
        }
        // Signal EMA seeded with the SMA of the first `signal` line values.
        val firstLine = line.indexOfFirst { it != null }
        if (firstLine < 0 || closes.size < firstLine + signal) return out.toList()
        val k = 2.0 / (signal + 1)
        var sig = (firstLine until firstLine + signal)
            .map { line[it]!! }
            .average()
        val sigStart = firstLine + signal - 1
        out[sigStart] = MacdPoint(line[sigStart]!!, sig, line[sigStart]!! - sig)
        for (i in sigStart + 1 until closes.size) {
            val l = line[i] ?: continue
            sig = l * k + sig * (1.0 - k)
            out[i] = MacdPoint(l, sig, l - sig)
        }
        return out.toList()
    }

    /**
     * Wilder ADX with +DI / −DI. Null until the first smoothed ADX exists
     * (index `2 * period - 1`). Flat series yield DX denominator 0 → ADX 0.
     */
    fun adx(candles: List<Candle>, period: Int = 14): List<AdxPoint?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<AdxPoint>(candles.size)
        if (candles.size < 2 * period) return out.toList()
        val n = candles.size
        val tr = DoubleArray(n)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val c = candles[i]
            val p = candles[i - 1]
            tr[i] = maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
            val up = c.high - p.high
            val down = p.low - c.low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        var trS = 0.0
        var pS = 0.0
        var mS = 0.0
        for (i in 1..period) {
            trS += tr[i]
            pS += plusDm[i]
            mS += minusDm[i]
        }
        val dx = DoubleArray(n) { Double.NaN }
        fun diAt(i: Int): Pair<Double, Double> {
            if (trS == 0.0) return 0.0 to 0.0
            return 100.0 * pS / trS to 100.0 * mS / trS
        }
        run {
            val (pdi, mdi) = diAt(period)
            dx[period] = dxValue(pdi, mdi)
        }
        for (i in period + 1 until n) {
            trS = trS - trS / period + tr[i]
            pS = pS - pS / period + plusDm[i]
            mS = mS - mS / period + minusDm[i]
            val (pdi, mdi) = diAt(i)
            dx[i] = dxValue(pdi, mdi)
            if (i == 2 * period - 1) {
                val firstAdx = (period..i).map { dx[it] }.average()
                out[i] = AdxPoint(firstAdx, pdi, mdi)
            } else if (i > 2 * period - 1) {
                val prev = out[i - 1]?.adx ?: continue
                val next = (prev * (period - 1) + dx[i]) / period
                out[i] = AdxPoint(next, pdi, mdi)
            }
        }
        return out.toList()
    }

    private fun dxValue(plusDI: Double, minusDI: Double): Double {
        val denom = plusDI + minusDI
        if (denom == 0.0) return 0.0
        return 100.0 * abs(plusDI - minusDI) / denom
    }

    /** Wilder Average True Range; null until index `period`. */
    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        require(period > 0) { "period must be > 0" }
        val out = arrayOfNulls<Double>(candles.size)
        if (candles.size <= period) return out.toList()
        fun trAt(i: Int): Double {
            if (i == 0) return candles[0].high - candles[0].low
            val c = candles[i]
            val p = candles[i - 1]
            return maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
        }
        var a = 0.0
        for (i in 1..period) a += trAt(i)
        a /= period
        out[period] = a
        for (i in period + 1 until candles.size) {
            a = (a * (period - 1) + trAt(i)) / period
            out[i] = a
        }
        return out.toList()
    }

    /** On-balance volume, cumulative from 0 at the first candle. */
    fun obv(candles: List<Candle>): List<Double> {
        val out = ArrayList<Double>(candles.size)
        var acc = 0.0
        for (i in candles.indices) {
            if (i > 0) {
                val d = candles[i].close - candles[i - 1].close
                if (d > 0) acc += candles[i].volume else if (d < 0) acc -= candles[i].volume
            }
            out.add(acc)
        }
        return out
    }

    /**
     * Rolling VWAP over a trailing `window` of candles using the typical price
     * (H+L+C)/3. Null until `window` candles exist.
     */
    fun vwap(candles: List<Candle>, window: Int = 20): List<Double?> {
        require(window > 0) { "window must be > 0" }
        val out = arrayOfNulls<Double>(candles.size)
        var pv = 0.0
        var vv = 0.0
        for (i in candles.indices) {
            val tp = (candles[i].high + candles[i].low + candles[i].close) / 3.0
            pv += tp * candles[i].volume
            vv += candles[i].volume
            if (i >= window) {
                val old = candles[i - window]
                val otp = (old.high + old.low + old.close) / 3.0
                pv -= otp * old.volume
                vv -= old.volume
            }
            if (i >= window - 1) out[i] = if (vv == 0.0) null else pv / vv
        }
        return out.toList()
    }

    /** Logistic squashing helper shared by strategies. */
    fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
