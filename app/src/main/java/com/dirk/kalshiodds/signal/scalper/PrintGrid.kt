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

    /** YES mid at the end of second [s] from the prints (both proxies fresh), NaN otherwise. */
    fun yesMid(s: Int): Double = mid(true, s)

    /** Mid of [side] at the end of second [s] from the prints (NaN when either proxy is stale). */
    fun mid(side: String, s: Int): Double = mid(isYes(side), s)

    /** Highest / lowest mid of [side] over the [w] seconds ending at [s]; NaN when no second had a quote. */
    fun highMid(side: String, s: Int, w: Int): Double = extremeMid(isYes(side), s, w, high = true)

    fun lowMid(side: String, s: Int, w: Int): Double = extremeMid(isYes(side), s, w, high = false)

    private fun extremeMid(yes: Boolean, s: Int, w: Int, high: Boolean): Double {
        var out = Double.NaN
        for (i in max(s - w + 1, 0)..minOf(s, SECONDS - 1)) {
            val m = mid(yes, i)
            if (m.isNaN()) continue
            if (out.isNaN() || (high && m > out) || (!high && m < out)) out = m
        }
        return out
    }

    /**
     * The lowest mid of [side] in the [lowW] seconds ending at [s] (its latest second if tied), and the
     * highest mid in the [w] seconds ending at [s] that came no later than that low: a drop measured
     * high first, then low. Null when either has no quote.
     */
    fun highThenLow(side: String, s: Int, w: Int, lowW: Int): Pair<Double, Double>? {
        val yes = isYes(side)
        val top = minOf(s, SECONDS - 1)
        var low = Double.NaN
        var lowAt = -1
        for (i in max(s - lowW + 1, 0)..top) {
            val m = mid(yes, i)
            if (m.isNaN()) continue
            if (low.isNaN() || m <= low) { low = m; lowAt = i }
        }
        if (lowAt < 0) return null
        var high = Double.NaN
        for (i in max(s - w + 1, 0)..lowAt) {
            val m = mid(yes, i)
            if (m.isNaN()) continue
            if (high.isNaN() || m > high) high = m
        }
        return if (high.isNaN()) null else high to low
    }

    /**
     * Contracts takers bought on [side] over the [w] seconds ending at [s]
     * (a taker buying NO is a print with taker side "no").
     */
    fun takerBought(side: String, s: Int, w: Int): Double {
        if (s < 0) return 0.0
        return sum(if (isYes(side)) volYes else volNo, minOf(s, SECONDS - 1), w)
    }

    /**
     * Average mid of [side] over the [w] seconds ending at [s], over the seconds that had a fresh quote.
     * NaN when fewer than half of them did.
     */
    fun avgMid(side: String, s: Int, w: Int): Double {
        val yes = isYes(side)
        var sum = 0.0
        var n = 0
        for (i in max(s - w + 1, 0)..minOf(s, SECONDS - 1)) {
            val m = mid(yes, i)
            if (m.isNaN()) continue
            sum += m
            n++
        }
        return if (n * 2 < w || n == 0) Double.NaN else sum / n
    }

    /**
     * True when each half of the [w] seconds ending at [s] had a fresh quote in at least half its seconds:
     * a range read off that window then spans the whole window, not a few prints at one end of it.
     */
    fun coveredBothHalves(side: String, s: Int, w: Int): Boolean {
        val half = w / 2
        return !avgMid(side, s - half, half).isNaN() && !avgMid(side, s, half).isNaN()
    }

    /** The biggest single taker buy of [side] (contracts) in the [w] seconds ending at [s]; 0 when none. */
    fun biggestTakerBuy(side: String, s: Int, w: Int): Double {
        if (s < 0) return 0.0
        return maxOver(if (isYes(side)) maxYes else maxNo, minOf(s, SECONDS - 1), w)
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

    /** The model's feature row for [side] at the end of second [s] in the 23-feature [FEATURES] order (older files). */
    fun features(side: String, s: Int): FloatArray? = features(side, s, FEATURES)

    /**
     * The model's feature row for [side] at the end of second [s], one value per name in [names] (any of
     * [CANDIDATES], as `build_ml.py` computes them), or null when the side is not quotable, [s] is outside the
     * decision seconds, or the grid has not watched the market long enough for the longest lookback in [names]
     * ([warmFor]). Values are rounded to float like the training data; missing history is 0, as in training.
     */
    fun features(side: String, s: Int, names: List<String>): FloatArray? {
        if (s < MIN_DECISION_SECOND || s > MAX_DECISION_SECOND) return null
        if (!warmFor(s, lookback(names)) || !quotable(side, s)) return null
        val yes = isYes(side)
        val b = bid(side, s)
        val a = ask(side, s)
        val m = (b + a) / 2.0
        val buy = if (yes) volYes else volNo
        val sell = if (yes) volNo else volYes
        val out = FloatArray(names.size)
        for ((k, name) in names.withIndex()) {
            out[k] = when {
                name == "mid" -> m.toFloat()
                name == "ask" -> a.toFloat()
                name == "bid" -> b.toFloat()
                name == "spread" -> (a - b).toFloat()
                name == "tte" -> (SECONDS - s).toFloat()
                name == "dist50" -> kotlin.math.abs(m - 0.5).toFloat()
                name == "from_hi" -> clean(m - extremeMid(yes, s, s + 1, high = true))
                name == "from_lo" -> clean(m - extremeMid(yes, s, s + 1, high = false))
                name == "from_first" -> clean(m - firstMid(yes, s))
                name == "hi60" -> clean(m - extremeMid(yes, s, 60, high = true))
                name == "lo60" -> clean(m - extremeMid(yes, s, 60, high = false))
                name == "stale_bid" -> since(if (yes) bidPrint else askPrint, s).toFloat()
                name == "stale_ask" -> since(if (yes) askPrint else bidPrint, s).toFloat()
                name == "imb_all" -> imbalance(sum(buy, s, s + 1), sum(sell, s, s + 1))
                name == "lvol_all" -> ln1p(sum(buy, s, s + 1) + sum(sell, s, s + 1)).toFloat()
                name == "hour" -> openHourUtc.toFloat()
                name == "weekend" -> (if (openWeekend) 1f else 0f)
                name.startsWith("dm") -> clean(m - mid(yes, s - suffix(name, "dm")))
                name.startsWith("vol") -> changes(yes, s, suffix(name, "vol")).first.toFloat()
                name.startsWith("nchg") -> changes(yes, s, suffix(name, "nchg")).second.toFloat()
                name.startsWith("imb") -> suffix(name, "imb").let { w -> imbalance(sum(buy, s, w), sum(sell, s, w)) }
                name.startsWith("lvol") -> suffix(name, "lvol").let { w -> ln1p(sum(buy, s, w) + sum(sell, s, w)).toFloat() }
                name.startsWith("lcnt") -> ln1p(sum(count, s, suffix(name, "lcnt"))).toFloat()
                name.startsWith("lmaxbuy") -> ln1p(maxOver(if (yes) maxYes else maxNo, s, suffix(name, "lmaxbuy"))).toFloat()
                name.startsWith("lmaxsell") -> ln1p(maxOver(if (yes) maxNo else maxYes, s, suffix(name, "lmaxsell"))).toFloat()
                else -> throw IllegalArgumentException("unknown feature $name")
            }
        }
        return out
    }

    /**
     * True when the grid has watched the window for [lookbackS] seconds before [s] (from the open when that reaches
     * before it, give or take [WARM_FLOOR_S]): the app builds the grid from the live trade stream, so a window it
     * joined late lacks the history a long lookback needs. [ALL_HISTORY] means from the open.
     */
    fun warmFor(s: Int, lookbackS: Int): Boolean {
        if (firstSecond < 0) return false
        val need = if (lookbackS >= ALL_HISTORY) WARM_FLOOR_S else max(s - max(lookbackS, MIN_GRID_SECONDS), WARM_FLOOR_S)
        return firstSecond <= need
    }

    private val openHourUtc: Int = java.time.Instant.ofEpochMilli(openMs).atZone(java.time.ZoneOffset.UTC).hour
    private val openWeekend: Boolean =
        java.time.Instant.ofEpochMilli(openMs).atZone(java.time.ZoneOffset.UTC).dayOfWeek.value >= 6

    private fun suffix(name: String, prefix: String): Int = name.substring(prefix.length).toInt()

    private fun imbalance(by: Double, bn: Double): Float {
        val tot = by + bn
        return (if (tot > 0.0) (by - bn) / max(tot, 1e-9) else 0.0).toFloat()
    }

    private fun firstMid(yes: Boolean, s: Int): Double {
        for (i in 0..minOf(s, SECONDS - 1)) {
            val v = mid(yes, i)
            if (!v.isNaN()) return v
        }
        return Double.NaN
    }

    /**
     * Over the [w] seconds ending at [s]: the std of one-second mid changes (a change next to a missing quote counts
     * as 0, the first second of the window as 0) and how many of them were not 0, as `build_ml.py`'s `vol` / `nchg`.
     */
    private fun changes(yes: Boolean, s: Int, w: Int): Pair<Double, Double> {
        var s1 = 0.0
        var s2 = 0.0
        var n = 0.0
        val from = max(s - w + 1, 0)
        var prev = mid(yes, from - 1)
        for (i in from..s) {
            val cur = mid(yes, i)
            var d = if (i == 0) 0.0 else cur - prev
            if (d.isNaN()) d = 0.0
            s1 += d
            s2 += d * d
            if (d != 0.0) n += 1.0
            prev = cur
        }
        val ww = w.toDouble()
        return sqrt(max(s2 / ww - (s1 / ww) * (s1 / ww), 0.0)) to n
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
        /** Seconds the grid must hold before any decision (the shortest model needs 60 s of prints). */
        const val MIN_GRID_SECONDS = 60

        /**
         * Slack, in seconds after the open, for "watched from the open": the first print of a window can come a few
         * seconds in even when the app was already listening.
         */
        const val WARM_FLOOR_S = 15

        /** A lookback that needs the window from its open (cumulative features). */
        const val ALL_HISTORY = SECONDS

        /**
         * Every feature the app can rebuild from the prints (`build_ml.py` names), with the seconds of history it
         * needs before the decision. The spot features of the study (Coinbase closes) are left out on purpose: the
         * app prices from Kalshi only.
         */
        val CANDIDATE_LOOKBACK: Map<String, Int> = linkedMapOf<String, Int>().apply {
            for (n in listOf("ask", "bid", "mid", "spread", "tte", "dist50", "hour", "weekend")) put(n, 0)
            for (k in listOf(5, 10, 20, 30, 60, 120, 180, 300)) put("dm$k", k)
            for (n in listOf("from_hi", "from_lo", "from_first", "imb_all", "lvol_all")) put(n, ALL_HISTORY)
            put("hi60", 60); put("lo60", 60)
            for (w in listOf(30, 60, 120, 300)) put("vol$w", w)
            for (w in listOf(30, 60, 120)) put("nchg$w", w)
            for (w in listOf(5, 10, 30, 60, 120, 300)) { put("imb$w", w); put("lvol$w", w) }
            for (w in listOf(30, 60, 120)) put("lcnt$w", w)
            for (w in listOf(30, 120)) { put("lmaxbuy$w", w); put("lmaxsell$w", w) }
            // Seconds since a print: as the older model, held to the minute of history every decision has.
            put("stale_bid", 60); put("stale_ask", 60)
        }

        val CANDIDATES: Set<String> get() = CANDIDATE_LOOKBACK.keys

        /** The longest lookback among [names], at least [MIN_GRID_SECONDS]. */
        fun lookback(names: List<String>): Int =
            max(MIN_GRID_SECONDS, names.maxOfOrNull { CANDIDATE_LOOKBACK[it] ?: throw IllegalArgumentException("unknown feature $it") } ?: 0)

        /** The 23 features of the older regression file (`train_compact.py`), in its order. */
        val FEATURES: List<String> = listOf(
            "mid", "spread", "tte", "dm5", "dm10", "dm20", "dm30", "dm60", "vol60", "nchg60",
            "imb5", "lvol5", "imb10", "lvol10", "imb30", "lvol30", "imb60", "lvol60",
            "lcnt30", "lmaxbuy30", "lmaxsell30", "stale_bid", "stale_ask"
        )

        fun isYes(side: String): Boolean = !side.equals("NO", ignoreCase = true)
    }
}
