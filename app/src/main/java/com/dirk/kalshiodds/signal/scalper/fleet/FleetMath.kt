package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The math behind the 20-scalper fleet (the "20 profitable scalpers" plan, 2026-10-23). Pure, unit tested.
 * Prices are dollars of a contract (0–1); Bitcoin prices are dollars.
 */
object FleetMath {

    // ------------------------------------------------------------ distributions

    fun normPdf(z: Double): Double = exp(-0.5 * z * z) / sqrt(2.0 * Math.PI)

    /** Standard normal CDF (Abramowitz–Stegun 7.1.26 on erf, |error| < 1.5e-7). */
    fun normCdf(z: Double): Double {
        if (z.isNaN()) return Double.NaN
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) 0.5 * (1.0 + y) else 0.5 * (1.0 - y)
    }

    /** Inverse standard normal CDF (Acklam), |relative error| < 1.2e-9. */
    fun normInv(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p must be in (0,1)" }
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val lo = 0.02425
        return when {
            p < lo -> {
                val q = sqrt(-2 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p > 1 - lo -> -normInv(1 - p)
            else -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
        }
    }

    private fun lnGamma(x: Double): Double {
        val g = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x
        val tmp = x + 5.5 - (x + 0.5) * ln(x + 5.5)
        var ser = 1.000000000190015
        for (c in g) { y += 1.0; ser += c / y }
        return -tmp + ln(2.5066282746310005 * ser / x)
    }

    /** Regularized incomplete beta I_x(a, b) (continued fraction, Numerical Recipes). */
    fun betaInc(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) bt * betaCf(a, b, x) / a else 1.0 - bt * betaCf(b, a, 1.0 - x) / b
    }

    private fun betaCf(a: Double, b: Double, x: Double): Double {
        val fpmin = 1e-300
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1.0)
        if (abs(d) < fpmin) d = fpmin
        d = 1.0 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1.0 + aa * d; if (abs(d) < fpmin) d = fpmin
            c = 1.0 + aa / c; if (abs(c) < fpmin) c = fpmin
            d = 1.0 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1.0 + aa * d; if (abs(d) < fpmin) d = fpmin
            c = 1.0 + aa / c; if (abs(c) < fpmin) c = fpmin
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < 3e-12) break
        }
        return h
    }

    /** Student-t CDF with [nu] degrees of freedom. */
    fun studentTCdf(t: Double, nu: Double): Double {
        val x = nu / (nu + t * t)
        val tail = 0.5 * betaInc(nu / 2.0, 0.5, x)
        return if (t >= 0) 1.0 - tail else tail
    }

    /** Student-t density. */
    fun studentTPdf(t: Double, nu: Double): Double =
        exp(lnGamma((nu + 1) / 2) - lnGamma(nu / 2)) / sqrt(nu * Math.PI) * Math.pow(1 + t * t / nu, -(nu + 1) / 2)

    /**
     * Student-t scaled to unit variance (so a "z" in standard deviations means the same as in the normal): the
     * chance of finishing above a point [z] std-devs below the current price, i.e. F(z·√(ν/(ν−2))).
     */
    fun studentTUnitCdf(z: Double, nu: Double): Double = studentTCdf(z * sqrt(nu / (nu - 2.0)), nu)

    // ------------------------------------------------------------ the universal profitability gate

    /** Kalshi's taker fee per contract on an order of [contracts] at [price] (rounded up per order). */
    fun takerFeePer(contracts: Int, price: Double, rate: Double = KalshiFee.TAKER_COEFFICIENT): Double =
        if (contracts <= 0) 0.0 else KalshiFee.total(contracts, price, rate) / contracts

    /** Implied YES ask from the best NO bid: P_ask,YES = 1 − B_best,NO. */
    fun impliedYesAsk(bestNoBid: Double): Double = 1.0 - bestNoBid

    /** Volume-weighted price of buying [contracts] through [levels] (price, size), best first. Null when too thin. */
    fun walkBook(levels: List<Pair<Double, Double>>, contracts: Double): Double? {
        if (contracts <= 0.0) return null
        var left = contracts
        var cost = 0.0
        for ((p, v) in levels) {
            if (v <= 0.0) continue
            val take = min(left, v)
            cost += take * p
            left -= take
            if (left <= 1e-9) return cost / contracts
        }
        return null
    }

    /**
     * The gate every fleet order passes: P_model − (P_exec + fee per contract) ≥ θ_min.
     * [feePer] is 0 for a resting (maker) order on KXBTC15M.
     */
    fun passesGate(pModel: Double, pExec: Double, feePer: Double, thetaMin: Double = THETA_MIN): Boolean =
        pModel.isFinite() && pExec.isFinite() && pModel - pExec - feePer >= thetaMin - 1e-12

    /**
     * Quarter-Kelly contracts: f* = (p(b+1) − 1)/b with net odds b = (1 − P − f)/(P + f); stake k·f*·W, divided by
     * the cost of one contract. 0 when there is no edge; capped at [maxContracts].
     */
    fun kellyContracts(pModel: Double, pExec: Double, feePer: Double, bankroll: Double, k: Double = KELLY_FRACTION, maxContracts: Int = MAX_CONTRACTS): Int {
        val cost = pExec + feePer
        if (!(cost > 0.0 && cost < 1.0) || !pModel.isFinite()) return 0
        val b = (1.0 - cost) / cost
        val f = (pModel * (b + 1.0) - 1.0) / b
        if (f <= 0.0) return 0
        return floor(k * f * bankroll / cost).toInt().coerceIn(0, maxContracts)
    }

    // ------------------------------------------------------------ 1–4: the final minute

    /**
     * Scalper 1: the settlement is the mean of 60 one-second prints. With [prints] (the k already in the final
     * minute), the rest must average K_req = (60K − ΣI)/(60 − k). From the latest print [last], a driftless walk with
     * 1-second std [sigma1s] (dollars) gives the remaining m = 60 − k prints a mean with variance σ²(m+1)(2m+1)/(6m).
     */
    fun lockedInProbYes(strike: Double, prints: DoubleArray, last: Double, sigma1s: Double): Double? {
        val k = prints.size
        if (k >= 60) return if (prints.average() >= strike) 1.0 else 0.0
        if (!(sigma1s > 0.0) || !last.isFinite()) return null
        val m = 60 - k
        val req = requiredAverage(strike, prints)
        val sd = sigma1s * sqrt((m + 1.0) * (2.0 * m + 1.0) / (6.0 * m))
        return normCdf((last - req) / sd)
    }

    fun requiredAverage(strike: Double, prints: DoubleArray): Double = (60.0 * strike - prints.sum()) / (60 - prints.size)

    /** Scalper 2: a jump [jump] at print k moves the settlement only by (60 − k)/60 of it. */
    fun spikeImpact(jump: Double, k: Int): Double = (60 - k).coerceAtLeast(0) / 60.0 * jump

    /** Scalper 3: OLS slope of [y] on 0..n−1, its standard error, as (β, se). Null under 3 points. */
    fun olsSlope(y: DoubleArray): Pair<Double, Double>? {
        val pts = y.indices.filter { y[it].isFinite() }
        val n = pts.size
        if (n < 3) return null
        val mx = pts.average()
        val my = pts.sumOf { y[it] } / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in pts) { sxx += (i - mx) * (i - mx); sxy += (i - mx) * (y[i] - my) }
        if (sxx <= 0.0) return null
        val beta = sxy / sxx
        var sse = 0.0
        for (i in pts) { val e = y[i] - my - beta * (i - mx); sse += e * e }
        val se = sqrt(sse / (n - 2) / sxx)
        return beta to se
    }

    /**
     * Scalper 4: the side is decided when the remaining average would need a move of more than [sigmas] standard
     * deviations of the remaining prints: |K_req − I_k| > n·σ√(60 − k).
     */
    fun tailLocked(strike: Double, prints: DoubleArray, last: Double, sigma1s: Double, sigmas: Double = 6.0): Boolean {
        val k = prints.size
        if (k < 45 || k >= 60 || !(sigma1s > 0.0)) return false
        return abs(requiredAverage(strike, prints) - last) > sigmas * sigma1s * sqrt(60.0 - k)
    }

    // ------------------------------------------------------------ 5–8: probability and volatility

    /** Scalper 5: z = ln(S/K)/(σ15m √τ), τ = time left as a fraction of 15 minutes. */
    fun displacement(spot: Double, strike: Double, sigma15m: Double, tau: Double): Double =
        ln(spot / strike) / (sigma15m * sqrt(tau.coerceAtLeast(1e-6)))

    /**
     * Scalper 7: implied 15-minute vol from a YES mid: P = Φ(ln(S/K)/(σ√τ)) ⇒ σ = ln(S/K)/(Φ⁻¹(P)√τ).
     * Null when the mid sits at 50¢ or on the wrong side of the strike (no unique answer).
     */
    fun impliedVol15m(mid: Double, spot: Double, strike: Double, tau: Double): Double? {
        if (!(mid > 0.02 && mid < 0.98) || tau <= 0.0) return null
        val z = normInv(mid)
        val x = ln(spot / strike)
        if (abs(z) < 0.05 || x * z <= 0.0) return null
        return x / (z * sqrt(tau))
    }

    /** Exponentially weighted realized vol of [returns] (newest last) with decay λ, per return. */
    fun ewVol(returns: DoubleArray, lambda: Double = 0.97): Double? {
        if (returns.size < 10) return null
        var w = 1.0 - lambda
        var s = 0.0
        var tot = 0.0
        for (i in returns.indices.reversed()) {
            val r = returns[i]
            if (r.isFinite()) { s += w * r * r; tot += w }
            w *= lambda
        }
        return if (tot > 0) sqrt(s / tot) else null
    }

    /** Pearson correlation; null under 5 pairs or no variance. */
    fun correlation(a: DoubleArray, b: DoubleArray): Double? {
        val n = min(a.size, b.size)
        val pts = (0 until n).filter { a[it].isFinite() && b[it].isFinite() }
        if (pts.size < 5) return null
        val ma = pts.sumOf { a[it] } / pts.size
        val mb = pts.sumOf { b[it] } / pts.size
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in pts) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb) }
        if (saa <= 0 || sbb <= 0) return null
        return sab / sqrt(saa * sbb)
    }

    /** Scalper 8: the altcoin's conditional return given BTC's: E[r_alt | r_btc] = ρ·(σ_alt/σ_btc)·r_btc. */
    fun conditionalReturn(rho: Double, sigmaAlt: Double, sigmaBtc: Double, rBtc: Double): Double =
        if (sigmaBtc <= 0.0) 0.0 else rho * sigmaAlt / sigmaBtc * rBtc

    // ------------------------------------------------------------ 9–13: the order book

    /**
     * Scalper 9, Avellaneda–Stoikov, worked in cents (the γ = 0.2, κ = 1.5 of the plan are cent-scaled):
     * reservation r = s − q·γ·σ²·(T−t), spread δ = γσ²(T−t) + (2/γ)·ln(1 + γ/κ), at least [minSpread].
     * [mid] dollars, [sigma] dollars per √second, [tLeft] seconds, [inventory] in quote lots. Returns (r, δ) in dollars.
     */
    fun avellanedaStoikov(mid: Double, inventory: Double, gamma: Double, sigma: Double, tLeft: Double, kappa: Double, minSpread: Double = 0.02, maxSpread: Double = 0.10): Pair<Double, Double> {
        val sc = sigma * 100.0
        val v = sc * sc * tLeft
        val rC = mid * 100.0 - inventory * gamma * v
        val dC = gamma * v + (2.0 / gamma) * ln(1.0 + gamma / kappa)
        return rC / 100.0 to (dC / 100.0).coerceIn(minSpread, maxSpread)
    }

    /** Scalper 10: rung m bids B − 0.01m; its exit is P_m + 0.02 + 0.005m. */
    fun ladderRung(bestBid: Double, m: Int): Pair<Double, Double> {
        val p = bestBid - 0.01 * m
        return p to p + 0.02 + 0.005 * m
    }

    /** Scalper 11: order-book imbalance over the top levels with weights e^{−λ(i−1)}, in [−1, 1]. */
    fun obi(yesBids: List<Pair<Double, Double>>, noBids: List<Pair<Double, Double>>, levels: Int = 3, lambda: Double = 0.5): Double? {
        var y = 0.0
        var n = 0.0
        for (i in 0 until levels) {
            val w = exp(-lambda * i)
            y += w * (yesBids.getOrNull(i)?.second ?: 0.0)
            n += w * (noBids.getOrNull(i)?.second ?: 0.0)
        }
        return if (y + n <= 0.0) null else (y - n) / (y + n)
    }

    /** Micro-price: the mid leaned toward the thinner side, (B·V_ask + A·V_bid)/(V_bid + V_ask). */
    fun microPrice(bid: Double, bidQty: Double, ask: Double, askQty: Double): Double =
        if (bidQty + askQty <= 0) (bid + ask) / 2 else (bid * askQty + ask * bidQty) / (bidQty + askQty)

    /** Scalper 12: VPIN = mean |V_yes − V_no| / V over the last [n] equal-volume buckets. */
    fun vpin(buckets: List<Pair<Double, Double>>, n: Int = 20): Double? {
        val last = buckets.takeLast(n)
        if (last.size < n) return null
        return last.sumOf { (y, no) -> abs(y - no) / (y + no).coerceAtLeast(1e-9) } / last.size
    }

    // ------------------------------------------------------------ 14–17: venues

    /**
     * Scalper 14: a CF-style index from venue books. Each venue's bids and asks (price, BTC) are merged; the mid
     * curve is taken at depths v up to where the spread passes [maxDev] of the mid, and weighted by an exponential
     * density λe^{−λv} with λ = 1/(0.3·v_max), as in the CF Benchmarks methodology.
     */
    fun cfNowcast(books: List<Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>>, maxDev: Double = 0.005, steps: Int = 50): Double? {
        val bids = books.flatMap { it.first }.filter { it.first > 0 && it.second > 0 }.sortedByDescending { it.first }
        val asks = books.flatMap { it.second }.filter { it.first > 0 && it.second > 0 }.sortedBy { it.first }
        if (bids.isEmpty() || asks.isEmpty()) return null
        val mid0 = (bids[0].first + asks[0].first) / 2
        fun priceAt(levels: List<Pair<Double, Double>>, v: Double): Double? {
            var cum = 0.0
            for ((p, q) in levels) { cum += q; if (cum >= v) return p }
            return null
        }
        // Utilized depth: the largest v where both sides exist and each stays within maxDev of the mid.
        var vMax = 0.0
        val total = min(bids.sumOf { it.second }, asks.sumOf { it.second })
        val dv = total / 400.0
        var v = dv
        while (v <= total) {
            val b = priceAt(bids, v) ?: break
            val a = priceAt(asks, v) ?: break
            if ((a - b) / 2 / mid0 > maxDev) break
            vMax = v
            v += dv
        }
        if (vMax <= 0.0) return mid0
        val lambda = 1.0 / (0.3 * vMax)
        var num = 0.0
        var den = 0.0
        for (i in 1..steps) {
            val x = vMax * i / steps
            val b = priceAt(bids, x) ?: continue
            val a = priceAt(asks, x) ?: continue
            val w = lambda * exp(-lambda * x)
            num += w * (a + b) / 2
            den += w
        }
        return if (den > 0) num / den else mid0
    }

    /** Polymarket's taker fee per share (docs.polymarket.com/trading/fees): feeRate·p·(1 − p), crypto rate 0.07. */
    fun polymarketFeePer(price: Double, rate: Double, exponent: Double = 1.0): Double =
        rate * Math.pow(price * (1.0 - price), exponent)

    /** Scalper 16: the binary's delta to Bitcoin under the Student-t: dP/dS = f_t(z)/(S·σ15m·√τ). */
    fun binaryDelta(spot: Double, strike: Double, sigma15m: Double, tau: Double, nu: Double): Double {
        val z = displacement(spot, strike, sigma15m, tau)
        return studentTPdf(z, nu) / (spot * sigma15m * sqrt(tau.coerceAtLeast(1e-6)))
    }

    // ------------------------------------------------------------ 18–20

    /** Scalper 19: δ_pin = max(0.02, 1.6 · |dP/dS| · S · σ1s · √Δt), contract dollars. */
    fun pinSpread(dPdS: Double, spot: Double, sigma1sLog: Double, dtTrade: Double): Double =
        max(0.02, 1.6 * abs(dPdS) * spot * sigma1sLog * sqrt(dtTrade.coerceAtLeast(0.1)))

    /**
     * Scalper 20: chance the price ends at or above [strike] from forecast quantiles (q10..q90 at 0.1..0.9),
     * linear between them and normal tails fitted to the q10/q90 spread, with extra variance [extraVar] (dollars²)
     * for time past the forecast horizon.
     */
    fun probAboveFromQuantiles(q: DoubleArray, strike: Double, extraVar: Double = 0.0): Double? {
        if (q.size != 9 || q.any { !it.isFinite() }) return null
        val med = q[4]
        val sd0 = (q[8] - q[0]) / (2 * 1.2815515655446004)
        if (extraVar > 0.0 || sd0 <= 0.0) {
            val sd = sqrt(max(sd0, 1e-9) * max(sd0, 1e-9) + extraVar)
            return 1.0 - normCdf((strike - med) / sd)
        }
        val levels = DoubleArray(9) { 0.1 * (it + 1) }
        if (strike <= q[0]) return 1.0 - 0.1 * normCdf((strike - q[0]) / sd0) / 0.5
        if (strike >= q[8]) return 0.1 * (1.0 - normCdf((strike - q[8]) / sd0)) / 0.5
        for (i in 0 until 8) {
            if (strike <= q[i + 1]) {
                val span = q[i + 1] - q[i]
                val f = if (span <= 0) 0.5 else (strike - q[i]) / span
                return 1.0 - (levels[i] + f * (levels[i + 1] - levels[i]))
            }
        }
        return 0.0
    }

    /** θ_min: the least profit a contract must clear after fees (the plan's 0.5–1.5¢ range, middle). */
    const val THETA_MIN = 0.01
    const val KELLY_FRACTION = 0.25
    /** Paper bankroll each fleet scalper sizes against. */
    const val BANKROLL_USD = 250.0
    /** No upper limit on contracts an order (you asked 2026-10-23): Kelly alone sizes it. */
    const val MAX_CONTRACTS = Int.MAX_VALUE
}
