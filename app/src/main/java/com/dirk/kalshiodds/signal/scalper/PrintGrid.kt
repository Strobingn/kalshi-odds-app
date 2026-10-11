package com.dirk.kalshiodds.signal.scalper

import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One 15-minute window's public trade prints on a 1-second grid, and the
 * scalper model's features rebuilt from them.
 *
 * This mirrors `tools/research/scalp/scalp_b.py` (`grid`) and `build_ml.py`
 * exactly, because the model was trained on these numbers and nothing else:
 *
 *  - bid = price of the last print where the taker sold YES (taker side
 *    "no"), ask = last print where the taker bought YES, each at most
 *    [MAX_QUOTE_AGE_S] seconds old;
 *  - a second belongs to the grid once it has ended, so features at second
 *    `s` use every print up to the end of second `s`;
 *  - the DOWN (NO) frame is the mirror image: NO bid = 1 − YES ask.
 *
 * `ScalperParityTest` replays a recorded window and checks every feature and
 * every model output against the Python values.
 *
 * Not thread-safe: [com.dirk.kalshiodds.signal.scalper.PaperScalper] holds
 * the lock.
 */
class PrintGrid(val openMs: Long) {

    private val bidPrint = DoubleArray(SECONDS) { Double.NaN }   // last taker-NO print of the second
    private val askPrint = DoubleArray(SECONDS) { Double.NaN }   // last taker-YES print of the second
    private val volYes = DoubleArray(SECONDS)
    private val volNo = DoubleArray(SECONDS)
    private val maxYes = DoubleArray(SECONDS)
    private val maxNo = DoubleArray(SECONDS)
    private val count = DoubleArray(SECONDS)

    /** First second that had a print, or -1. Features need 60 s of grid before the decision. */
    var firstSecond: Int = -1
        private set

    /** Whole seconds since the window opened, or -1 / 900+ outside it. */
    fun secondOf(nowMs: Long): Int {
        val d = nowMs - openMs
        return if (d < 0L) -1 else (d / 1000L).toInt()
    }

    /** A public trade: [takerYes] = the taker bought YES at [yesPrice]. */
    fun add(nowMs: Long, takerYes: Boolean, yesPrice: Double, contracts: Double) {
        val s = secondOf(nowMs)
        if (s < 0 || s >= SECONDS) return
        if (!yesPrice.isFinite() || yesPrice <= 0.0 || yesPrice >= 1.0) return
        if (!contracts.isFinite() || contracts <= 0.0) return
        val p = Math.round(yesPrice * 10_000.0) / 10_000.0
        if (firstSecond < 0) firstSecond = s
        count[s] += 1.0
        if (takerYes) {
            askPrint[s] = p
            volYes[s] += contracts
            if (contracts > maxYes[s]) maxYes[s] = contracts
        } else {
            bidPrint[s] = p
            volNo[s] += contracts
            if (contracts > maxNo[s]) maxNo[s] = contracts
        }
    }

    /** YES bid proxy at the end of second [s]: the last taker-NO print, at most 10 s old. */
    fun yesBid(s: Int): Double = lastWithin(bidPrint, s)

    /** YES ask proxy at the end of second [s]. */
    fun yesAsk(s: Int): Double = lastWithin(askPrint, s)

    /** Bid of [side] ("YES" / "NO") from the prints, NaN when stale. */
    fun bid(side: String, s: Int): Double = if (isYes(side)) yesBid(s) else 1.0 - yesAsk(s)

    fun ask(side: String, s: Int): Double = if (isYes(side)) yesAsk(s) else 1.0 - yesBid(s)

    private fun mid(yes: Boolean, s: Int): Double {
        if (s < 0) return Double.NaN
        val b = yesBid(s)
        val a = yesAsk(s)
        return if (yes) (b + a) / 2.0 else ((1.0 - a) + (1.0 - b)) / 2.0
    }

    /**
     * How far [side]'s mid moved over the last [lagSeconds] up to the end of
     * second [s], in dollars (+ = the side got dearer). NaN when either end
     * has no fresh quote.
     */
    fun move(side: String, s: Int, lagSeconds: Int): Double {
        val yes = isYes(side)
        return mid(yes, s) - mid(yes, s - lagSeconds)
    }

    private fun lastWithin(arr: DoubleArray, s: Int): Double {
        if (s < 0) return Double.NaN
        val top = if (s >= SECONDS) SECONDS - 1 else s
        var j = top
        val floor = max(s - MAX_QUOTE_AGE_S, 0)
        while (j >= floor) {
            val v = arr[j]
            if (!v.isNaN()) return v
            j--
        }
        return Double.NaN
    }

    /** True when [side] can be quoted at second [s]: both proxies fresh, bid below ask, bid 10–90¢. */
    fun quotable(side: String, s: Int): Boolean {
        val b = bid(side, s)
        val a = ask(side, s)
        if (b.isNaN() || a.isNaN()) return false
        return b < a && b >= MIN_PRICE - EPS && b <= MAX_PRICE + EPS
    }

    /** True once the grid has prints from at least [WARM_SECONDS] before [s]. */
    fun warm(s: Int): Boolean = firstSecond in 0..(s - WARM_SECONDS)

    /**
     * The model's feature row for [side] at the end of second [s], in
     * [FEATURES] order, or null when the grid is not warm or the side is not
     * quotable. Values are rounded to float like the training data; missing
     * history is 0, as in training.
     */
    fun features(side: String, s: Int): FloatArray? {
        if (s < MIN_DECISION_SECOND || s > MAX_DECISION_SECOND) return null
        if (!warm(s) || !quotable(side, s)) return null
        val yes = isYes(side)
        val out = FloatArray(FEATURES.size)
        val b = bid(side, s)
        val a = ask(side, s)
        val m = (b + a) / 2.0
        var k = 0
        out[k++] = m.toFloat()
        out[k++] = (a - b).toFloat()
        out[k++] = (SECONDS - s).toFloat()
        for (lag in LAGS) {
            out[k++] = clean(m - mid(yes, s - lag))
        }
        // 60 s of one-second mid changes (a change next to a missing quote counts as 0).
        var s1 = 0.0
        var s2 = 0.0
        var changes = 0.0
        val from = max(s - 59, 0)
        var prev = mid(yes, from - 1)
        for (i in from..s) {
            val cur = mid(yes, i)
            var d = if (i == 0) 0.0 else cur - prev
            if (d.isNaN()) d = 0.0
            s1 += d
            s2 += d * d
            if (d != 0.0) changes += 1.0
            prev = cur
        }
        out[k++] = sqrt(max(s2 / 60.0 - (s1 / 60.0) * (s1 / 60.0), 0.0)).toFloat()
        out[k++] = changes.toFloat()
        val buy = if (yes) volYes else volNo
        val sell = if (yes) volNo else volYes
        for (w in WINDOWS) {
            val by = sum(buy, s, w)
            val bn = sum(sell, s, w)
            val tot = by + bn
            out[k++] = (if (tot > 0.0) (by - bn) / max(tot, 1e-9) else 0.0).toFloat()
            out[k++] = ln1p(tot).toFloat()
        }
        out[k++] = ln1p(sum(count, s, 30)).toFloat()
        out[k++] = ln1p(maxOver(if (yes) maxYes else maxNo, s, 30)).toFloat()
        out[k++] = ln1p(maxOver(if (yes) maxNo else maxYes, s, 30)).toFloat()
        // The side's bid comes from prints of takers selling it: taker-NO prints for YES, taker-YES prints for NO.
        out[k++] = since(if (yes) bidPrint else askPrint, s).toFloat()
        out[k] = since(if (yes) askPrint else bidPrint, s).toFloat()
        return out
    }

    private fun clean(v: Double): Float = if (v.isNaN()) 0f else v.toFloat()

    private fun sum(arr: DoubleArray, s: Int, w: Int): Double {
        var t = 0.0
        for (i in max(s - w + 1, 0)..s) t += arr[i]
        return t
    }

    private fun maxOver(arr: DoubleArray, s: Int, w: Int): Double {
        var t = 0.0
        for (i in max(s - w + 1, 0)..s) if (arr[i] > t) t = arr[i]
        return t
    }

    private fun since(arr: DoubleArray, s: Int): Double {
        var j = s
        while (j >= 0) {
            if (!arr[j].isNaN()) return (s - j).toDouble()
            j--
        }
        return SECONDS.toDouble()
    }

    companion object {
        const val SECONDS = 900
        const val MAX_QUOTE_AGE_S = 10
        const val WARM_SECONDS = 60
        const val MIN_DECISION_SECOND = 60
        const val MAX_DECISION_SECOND = 780
        const val MIN_PRICE = 0.10
        const val MAX_PRICE = 0.90
        private const val EPS = 1e-9
        private val LAGS = intArrayOf(5, 10, 20, 30, 60)
        private val WINDOWS = intArrayOf(5, 10, 30, 60)

        /** Same names and order as `train_compact.py`; the model file repeats them and is checked on load. */
        val FEATURES: List<String> = listOf(
            "mid", "spread", "tte", "dm5", "dm10", "dm20", "dm30", "dm60", "vol60", "nchg60",
            "imb5", "lvol5", "imb10", "lvol10", "imb30", "lvol30", "imb60", "lvol60",
            "lcnt30", "lmaxbuy30", "lmaxsell30", "stale_bid", "stale_ask"
        )

        fun isYes(side: String): Boolean = !side.equals("NO", ignoreCase = true)
    }
}
