package com.dirk.kalshiodds.decision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Paper-only signals from the 15-minute binary study.
 * Student-t fair value, pin risk, order-flow imbalance, Avellaneda–Stoikov quotes, and fee cents.
 * No network and no allocation on the quote walk beyond the rings updated in [noteSpot].
 */
object HftSignals {
    const val NU = 4.2
    const val PIN_BPS = 8.0
    const val PIN_HORIZON_S = 120.0
    const val STRESS_CORR = 0.85
    const val VWAP_SIGMA = 2.0
    const val GAMMA = 40.0
    const val KAPPA = 80.0
    const val CONTRACT_SIGMA = 0.015
    private const val YEAR_S = 365.25 * 24.0 * 3600.0

    private val lock = Any()
    private val spots = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    fun reset() = synchronized(lock) { spots.clear() }

    fun noteSpot(coin: String, atMs: Long, spot: Double) = synchronized(lock) {
        if (!spot.isFinite() || spot <= 0.0) return@synchronized
        val row = spots.getOrPut(coin.uppercase()) { ArrayDeque() }
        row.addLast(atMs to spot)
        while (row.isNotEmpty() && atMs - row.first().first > 20 * 60_000L) row.removeFirst()
    }

    /** Cash-or-nothing probability under a Student-t shock, ν = 4.2. Side YES is P(S>K). */
    fun studentTFair(spot: Double?, strike: Double?, sigmaPerSec: Double?, tauS: Double, side: String): Double? {
        if (spot == null || strike == null || sigmaPerSec == null) return null
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!sigmaPerSec.isFinite() || sigmaPerSec <= 0.0) return null
        if (tauS <= 0.0) {
            val yes = if (spot > strike) 1.0 else 0.0
            return if (side == "NO") 1.0 - yes else yes
        }
        val scale = sigmaPerSec * sqrt(tauS)
        if (scale <= 1e-12) return null
        val d = ln(spot / strike) / scale
        val yes = studentTCdf(d, NU)
        return if (side == "NO") 1.0 - yes else yes
    }

    /** True when spot is within a few bps of the reference and time is short enough for pin gamma. */
    fun pinRisk(spot: Double?, strike: Double?, tauS: Double): Boolean {
        if (spot == null || strike == null || spot <= 0.0 || strike <= 0.0 || tauS > PIN_HORIZON_S) return false
        val bps = abs(ln(spot / strike)) * 10_000.0
        return bps <= PIN_BPS
    }

    /**
     * Cont–Kukanov–Stoikov best-level imbalance.
     * e = 1{Pb≥Pb'} qb − 1{Pb≤Pb'} qb' − 1{Pa≤Pa'} qa + 1{Pa≥Pa'} qa'
     */
    fun ofi(
        prevBid: Double, prevBidSz: Double, prevAsk: Double, prevAskSz: Double,
        bid: Double, bidSz: Double, ask: Double, askSz: Double
    ): Double {
        val bidUp = if (bid >= prevBid) bidSz else 0.0
        val bidDn = if (bid <= prevBid) prevBidSz else 0.0
        val askDn = if (ask <= prevAsk) askSz else 0.0
        val askUp = if (ask >= prevAsk) prevAskSz else 0.0
        return bidUp - bidDn - askDn + askUp
    }

    fun ofiOverDepth(ofi: Double, bidSz: Double, askSz: Double): Double {
        val d = bidSz + askSz
        if (d <= 1e-9 || !ofi.isFinite()) return 0.0
        return (ofi / d).coerceIn(-5.0, 5.0)
    }

    /** Trailing VWAP change, in standard deviations of the same window. Above 2 is the momentum flag. */
    fun vwapZ(prices: List<Double>, sizes: List<Double>): Double {
        if (prices.size < 4 || prices.size != sizes.size) return 0.0
        val mid = prices.size / 2
        val a = vwap(prices.subList(0, mid), sizes.subList(0, mid))
        val b = vwap(prices.subList(mid, prices.size), sizes.subList(mid, sizes.size))
        if (a <= 0.0 || b <= 0.0) return 0.0
        val slope = ln(b / a)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (i in 1 until prices.size) {
            if (prices[i - 1] <= 0.0 || prices[i] <= 0.0) continue
            val r = ln(prices[i] / prices[i - 1])
            sum += r
            sumSq += r * r
            n++
        }
        if (n < 2) return 0.0
        val mean = sum / n
        val sd = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        if (sd <= 1e-12) return 0.0
        return (slope / sd).coerceIn(-8.0, 8.0)
    }

    fun momentum(z: Double): Boolean = abs(z) > VWAP_SIGMA

    /** Spot versus the settlement average, in bps. Only meaningful inside the final 90 seconds. */
    fun settleDivergenceBps(spot: Double?, settleAvg: Double?, tauS: Double): Double {
        if (tauS > 90.0 || spot == null || settleAvg == null || spot <= 0.0 || settleAvg <= 0.0) return 0.0
        return (ln(spot / settleAvg) * 10_000.0).coerceIn(-500.0, 500.0)
    }

    /** Pearson correlation of the overlapping 15-minute log returns. 0 when a coin is missing. */
    fun crossCorr(): Double = synchronized(lock) {
        val series = listOf("BTC", "ETH", "SOL").mapNotNull { coin ->
            val row = spots[coin] ?: return@mapNotNull null
            if (row.size < 3) return@mapNotNull null
            returns(row)
        }
        if (series.size < 2) return@synchronized 0.0
        var acc = 0.0
        var n = 0
        for (i in series.indices) for (j in i + 1 until series.size) {
            val c = pearson(series[i], series[j])
            if (c.isFinite()) { acc += c; n++ }
        }
        if (n == 0) 0.0 else acc / n
    }

    fun stress(corr: Double): Boolean = corr > STRESS_CORR

    /** r = s − q γ σ² (T−t). Time is in years so a 15-minute window does not explode the reservation. */
    fun reservation(mid: Double, inventory: Double, gamma: Double = GAMMA, sigma: Double = CONTRACT_SIGMA, tauS: Double): Double {
        val sig = dampedSigma(sigma, mid)
        val t = (tauS.coerceAtLeast(0.0) / YEAR_S)
        return mid - inventory * gamma * sig * sig * t
    }

    /** δa + δb = γ σ² (T−t) + (2/γ) ln(1 + γ/κ). */
    fun optimalSpread(gamma: Double = GAMMA, sigma: Double = CONTRACT_SIGMA, tauS: Double, kappa: Double = KAPPA, mid: Double = 0.5): Double {
        val sig = dampedSigma(sigma, mid)
        val t = (tauS.coerceAtLeast(0.0) / YEAR_S)
        val width = gamma * sig * sig * t + (2.0 / gamma) * ln(1.0 + gamma / kappa)
        return width.coerceIn(0.01, 0.20)
    }

    /** σ_bin ∝ p(1−p), normalized so a 50¢ contract keeps the input sigma. */
    fun dampedSigma(sigma: Double, price: Double): Double {
        val p = price.coerceIn(0.01, 0.99)
        return sigma * (p * (1.0 - p) / 0.25)
    }

    data class MakerQuote(val reservation: Double, val bid: Double, val ask: Double, val spread: Double)

    /** Inventory-skewed bid/ask. Positive OFI (buy pressure) lifts both quotes. */
    fun makerQuote(mid: Double, inventory: Double, tauS: Double, ofiNorm: Double, gamma: Double = GAMMA, kappa: Double = KAPPA): MakerQuote {
        val r = reservation(mid, inventory, gamma, CONTRACT_SIGMA, tauS)
        val spr = optimalSpread(gamma, CONTRACT_SIGMA, tauS, kappa, mid)
        val skew = ofiNorm.coerceIn(-1.0, 1.0) * spr * 0.25
        return MakerQuote(r, r - spr / 2.0 + skew, r + spr / 2.0 + skew, spr)
    }

    /**
     * Resting bid: never through the ask, and never behind the touch.
     * A tight book stays on the displayed bid. A wide book may step inside.
     */
    fun restingBid(touchBid: Double, touchAsk: Double?, tauS: Double, ofiNorm: Double, inventory: Double = 0.0): Double {
        val ask = touchAsk ?: (touchBid + 0.02)
        val mid = ((touchBid + ask) / 2.0).coerceIn(0.01, 0.99)
        val raw = makerQuote(mid, inventory, tauS, ofiNorm).bid
        val cent = kotlin.math.round(raw * 100.0) / 100.0
        val improved = maxOf(touchBid, cent)
        val cap = ask - 0.01
        return improved.coerceIn(0.01, cap.coerceAtLeast(0.01))
    }

    /** Per-order cent round-up. Ten contracts at 50¢ cost less than ten one-contract orders. */
    fun orderFee(contracts: Int, price: Double): Double {
        if (contracts <= 0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        return ceil(0.07 * contracts * price * (1.0 - price) * 100.0 - 1e-12) / 100.0
    }

    /**
     * Half-Kelly, or quarter-Kelly inside the pin zone or the final 90 seconds.
     * f* = (q · gain − (1−q) · loss) / gain, then scaled. Never above the clip.
     */
    fun kellyFraction(q: Double, gain: Double, loss: Double, pin: Boolean, tauS: Double): Double {
        if (gain <= 1e-9) return 0.0
        val star = (q.coerceIn(0.0, 1.0) * gain - (1.0 - q.coerceIn(0.0, 1.0)) * loss.coerceAtLeast(0.0)) / gain
        val scale = if (pin || tauS <= 90.0) 0.25 else 0.50
        return (star.coerceAtLeast(0.0) * scale).coerceIn(0.0, 1.0)
    }

    /** Student-t density. Same ν as [studentTCdf]. */
    fun studentTPdf(z: Double, nu: Double = NU): Double {
        if (!z.isFinite()) return 0.0
        val logC = lnGamma((nu + 1.0) / 2.0) - lnGamma(nu / 2.0) - 0.5 * ln(nu * Math.PI)
        return exp(logC - ((nu + 1.0) / 2.0) * ln(1.0 + z * z / nu))
    }

    fun studentTCdf(t: Double, nu: Double = NU): Double {
        if (!t.isFinite()) return if (t > 0.0) 1.0 else 0.0
        if (t == 0.0) return 0.5
        val x = nu / (nu + t * t)
        val tail = 0.5 * regularizedBeta(x, nu / 2.0, 0.5)
        return (if (t > 0.0) 1.0 - tail else tail).coerceIn(0.0, 1.0)
    }

    private fun vwap(px: List<Double>, sz: List<Double>): Double {
        var num = 0.0
        var den = 0.0
        for (i in px.indices) {
            val w = sz[i].coerceAtLeast(0.0)
            num += px[i] * w
            den += w
        }
        return if (den <= 0.0) px.average() else num / den
    }

    private fun returns(row: ArrayDeque<Pair<Long, Double>>): DoubleArray {
        val out = ArrayList<Double>()
        var prev = row.first().second
        for (p in row) {
            if (prev > 0.0 && p.second > 0.0) out += ln(p.second / prev)
            prev = p.second
        }
        return out.toDoubleArray()
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 3) return 0.0
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until n) { sa += a[a.size - n + i]; sb += b[b.size - n + i] }
        val ma = sa / n
        val mb = sb / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[a.size - n + i] - ma
            val xb = b[b.size - n + i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        if (da <= 0.0 || db <= 0.0) return 0.0
        return num / sqrt(da * db)
    }

    private fun regularizedBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) bt * betaCf(a, b, x) / a
        else 1.0 - bt * betaCf(b, a, 1.0 - x) / b
    }

    private fun betaCf(a: Double, b: Double, x: Double): Double {
        val max = 200
        val eps = 1e-10
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < 1e-30) d = 1e-30
        d = 1.0 / d
        var h = d
        for (m in 1..max) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < 1e-30) d = 1e-30
            c = 1.0 + aa / c
            if (abs(c) < 1e-30) c = 1e-30
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < 1e-30) d = 1e-30
            c = 1.0 + aa / c
            if (abs(c) < 1e-30) c = 1e-30
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < eps) break
        }
        return h
    }

    private fun lnGamma(z: Double): Double {
        val p = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.3234287776531,
            -176.6150291621406, 12.507343278686905, -0.13857109526572012,
            9.984369578019572e-6, 1.5056327351493116e-7
        )
        var x = z
        var y = z
        var tmp = x + 5.5
        tmp = (x + 0.5) * ln(tmp) - tmp
        var ser = 1.000000000190015
        for (c in p) {
            y += 1.0
            ser += c / y
        }
        return tmp + ln(2.5066282746310005 * ser / x)
    }
}
