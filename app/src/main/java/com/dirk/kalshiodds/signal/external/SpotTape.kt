package com.dirk.kalshiodds.signal.external

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Recent spot prints for one product plus an EWMA of 10-second log returns.
 * Pure math: no clock, no I/O, not thread-safe ([SpotStreamBook] guards it).
 *
 * ## Ring buffer
 * At most one sample per [slotMs] (the latest print in a slot wins), kept
 * for [windowMs]. Defaults (1 s slots × 20 min) cap it at ~1,200 samples
 * (~19 KB of primitive arrays) whatever the print rate.
 *
 * ## Returns
 * Simple returns `last / price(t − h) − 1` for any horizon h (1 m and 5 m are
 * used), where price(t) is the last print at or before t. Null until the
 * buffer reaches back that far, or when the print found is more than
 * [lookbackSlackMs] older than the target (e.g. across a stream outage).
 *
 * ## Volatility
 * Prints are resampled to [binMs] bins (close = last print in the bin; an
 * empty bin repeats the previous close, i.e. a 0 return — the ticker channel
 * only prints on trades). Each completed bin adds r = ln(close_k / close_k−1)
 * to a zero-mean, bias-corrected EWMA of r²:
 *
 *     S ← λ·S + r²,   W ← λ·W + 1,   σ²_bin = S / W,   λ = 0.5^(binMs / halfLifeMs)
 *
 * Half-life **30 minutes** (180 bins of 10 s): ~520 effective samples and
 * ~75% of the weight on the last hour, so σ is steady but still follows an
 * intraday vol regime change within the hour. The REST path it replaces is a
 * plain std of 15 one-minute returns (±18% sampling error on σ). σ is
 * exposed only after [minReturns] returns (5 minutes of bins).
 *
 * [barStd1m] rescales to the unit the rest of the app expects in
 * `AssetSpotFeatures.realizedVol15m` — the std of **1-minute** log returns —
 * via √(60 s / binMs) (√6 for 10 s bins). ScoringEngine annualizes that with
 * √(SECONDS_PER_YEAR / 60), so annual σ = σ_10s · √(SECONDS_PER_YEAR / 10).
 *
 * Gaps: prints more than [maxGapMs] apart restart the return chain (no
 * return spans an outage); more than [resetAfterMs] apart clear everything
 * (an hours-old σ is not carried into a new session).
 */
class SpotTape(
    val windowMs: Long = WINDOW_MS,
    val slotMs: Long = SLOT_MS,
    val binMs: Long = BIN_MS,
    val halfLifeMs: Long = HALF_LIFE_MS,
    val minReturns: Int = MIN_RETURNS,
    val maxGapMs: Long = MAX_GAP_MS,
    val resetAfterMs: Long = RESET_AFTER_MS,
    val lookbackSlackMs: Long = LOOKBACK_SLACK_MS
) {
    init {
        require(windowMs > 0 && slotMs > 0 && binMs > 0 && halfLifeMs > 0) { "positive spans" }
    }

    /** Capacity is fixed: memory never grows with the print rate. */
    val capacity: Int = (windowMs / slotMs).toInt() + 2
    private val times = LongArray(capacity)
    private val prices = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    private val lambda = 0.5.pow(binMs.toDouble() / halfLifeMs.toDouble())
    private var ewmaS = 0.0
    private var ewmaW = 0.0
    private var returns = 0

    private var hasBin = false
    private var curBin = 0L
    private var curClose = 0.0
    /** Close of the last completed bin in the current chain; NaN = chain start. */
    private var prevClose = Double.NaN

    val sampleCount: Int get() = size
    val returnCount: Int get() = returns
    val lastPrice: Double? get() = if (size == 0) null else prices[idx(size - 1)]
    val lastTimeMs: Long? get() = if (size == 0) null else times[idx(size - 1)]
    val oldestTimeMs: Long? get() = if (size == 0) null else times[idx(0)]

    /**
     * Adds a print at [tMs]. Returns false (ignored) for a non-finite or
     * non-positive price, or a > [MAX_PRINT_JUMP] log jump from a print less
     * than [maxGapMs] old (a bad tick, not a market move).
     */
    fun add(tMs: Long, price: Double): Boolean {
        if (!price.isFinite() || price <= 0.0) return false
        var lastT = lastTimeMs
        val lastP = lastPrice
        if (lastT != null && lastP != null && tMs - lastT <= maxGapMs &&
            abs(ln(price / lastP)) > MAX_PRINT_JUMP
        ) {
            return false
        }
        if (lastT != null && tMs - lastT > resetAfterMs) {
            reset()
            lastT = null
        } else if (lastT != null && tMs - lastT > maxGapMs) {
            breakChain()
        }
        // A clock that steps backwards must not un-sort the buffer.
        val t = if (lastT != null && tMs < lastT) lastT else tMs
        pushSample(t, price)
        updateBins(t, price)
        evict(t)
        return true
    }

    /** Next print starts a new return chain (call on reconnect). */
    fun breakChain() {
        hasBin = false
        prevClose = Double.NaN
    }

    fun reset() {
        head = 0
        size = 0
        ewmaS = 0.0
        ewmaW = 0.0
        returns = 0
        breakChain()
    }

    /** `last / price(end − horizon) − 1`, end = max(nowMs, last print). */
    fun returnOver(horizonMs: Long, nowMs: Long): Double? {
        if (size == 0 || horizonMs <= 0) return null
        val lastI = idx(size - 1)
        val end = maxOf(nowMs, times[lastI])
        val target = end - horizonMs
        val i = indexAtOrBefore(target)
        if (i < 0) return null
        val at = idx(i)
        if (target - times[at] > lookbackSlackMs) return null
        val base = prices[at]
        if (base <= 0.0) return null
        return prices[lastI] / base - 1.0
    }

    /** EWMA std of one [binMs] log return; null while warming up. */
    fun binStd(): Double? {
        if (returns < minReturns || ewmaW <= 0.0) return null
        val v = ewmaS / ewmaW
        if (!v.isFinite() || v < 0.0) return null
        return sqrt(v)
    }

    /** [binStd] as a 1-minute-bar std (the `realizedVol15m` unit). */
    fun barStd1m(): Double? = binStd()?.let { it * sqrt(60_000.0 / binMs.toDouble()) }

    private fun idx(i: Int): Int = (head + i) % capacity

    private fun pushSample(t: Long, price: Double) {
        if (size > 0) {
            val li = idx(size - 1)
            if (Math.floorDiv(times[li], slotMs) == Math.floorDiv(t, slotMs)) {
                times[li] = t
                prices[li] = price
                return
            }
        }
        if (size == capacity) {
            head = (head + 1) % capacity
            size -= 1
        }
        val i = idx(size)
        times[i] = t
        prices[i] = price
        size += 1
    }

    private fun evict(nowMs: Long) {
        val cutoff = nowMs - windowMs
        // Keep the newest sample even if it is somehow older than the window.
        while (size > 1 && times[head] < cutoff) {
            head = (head + 1) % capacity
            size -= 1
        }
    }

    /** Logical index (0 = oldest) of the last sample with time ≤ [t], or −1. */
    private fun indexAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[idx(mid)] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun updateBins(t: Long, price: Double) {
        val bin = Math.floorDiv(t, binMs)
        if (!hasBin) {
            hasBin = true
            curBin = bin
            curClose = price
            return
        }
        if (bin <= curBin) {
            curClose = price
            return
        }
        if (!prevClose.isNaN()) addReturn(ln(curClose / prevClose))
        // Bins with no print: price unchanged → one 0 return each. Bounded
        // by maxGapMs / binMs because a longer gap already broke the chain.
        val empty = bin - curBin - 1
        if (empty > 0) addZeroReturns(empty)
        prevClose = curClose
        curBin = bin
        curClose = price
    }

    private fun addReturn(r: Double) {
        val x = r.coerceIn(-MAX_BIN_RETURN, MAX_BIN_RETURN)
        ewmaS = lambda * ewmaS + x * x
        ewmaW = lambda * ewmaW + 1.0
        returns = saturatingAdd(returns, 1L)
    }

    private fun addZeroReturns(k: Long) {
        val decay = lambda.pow(k.toDouble())
        ewmaS *= decay
        ewmaW = decay * ewmaW + (1.0 - decay) / (1.0 - lambda)
        returns = saturatingAdd(returns, k)
    }

    private fun saturatingAdd(a: Int, b: Long): Int =
        (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** Ring buffer span (review: "last ~20 minutes"). */
        const val WINDOW_MS = 20L * 60_000L
        const val SLOT_MS = 1_000L
        const val BIN_MS = 10_000L
        const val HALF_LIFE_MS = 30L * 60_000L

        /** 5 minutes of 10 s bins before σ replaces the REST estimate. */
        const val MIN_RETURNS = 30

        /** Prints further apart than this restart the return chain. */
        const val MAX_GAP_MS = 120_000L

        /** Prints further apart than this clear the tape. */
        const val RESET_AFTER_MS = 30L * 60_000L

        /** A 1m/5m base print may be at most this much older than its target. */
        const val LOOKBACK_SLACK_MS = 15_000L

        /** |ln(p / last)| above this between close prints is a bad tick. */
        const val MAX_PRINT_JUMP = 0.10

        /** Winsorize one 10 s return so a glitch cannot own σ for hours. */
        const val MAX_BIN_RETURN = 0.05
    }
}
