package com.dirk.kalshiodds.decision

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Paper-only 15-minute binary engines. No orders leave the phone.
 * Student-t ν = 4.2, HJB quotes, five-level OFI, settlement variance, and the five risk gates.
 */
object HftMathEngines {
    const val NU = 4.2
    const val GAMMA = 0.40
    const val KAPPA = 1.50
    const val LAMBDA_OFI = 0.005
    const val WINDOW_S = 900.0
    const val MAX_INVENTORY = 25.0
    const val TOXICITY = 2.5
    const val PIN_TAU_S = 30.0
    const val OFI_ALPHA = 0.5
    const val RHO = 0.15
    const val KAPPA_TRANS = 0.02
    const val OFI_SURGE = 1.2
    const val MID_SURGE = 0.02
    const val FLAT_K = 0.25
    const val DEFAULT_VOL15M = 0.0075
    const val TAKER = 0.07
    const val MAKER_IN = 0.0175
    const val ETA = 0.25
    const val TAU_CRIT = 60.0
    const val EV_MIN = 0.05
    const val ASK_CAP = 0.88
    const val DRAWDOWN_CEILING = 1000.0
    const val MAX_SPREAD = 0.04
    const val MAX_BOOK_AGE_MS = 1_500L
    const val STRESS_CORR = 0.85
    const val SMILE_K = 0.12
    const val SMILE_EDGE = 0.04
    const val REF_DOS_PNL = 6888.03
    const val REF_DOS_WIN = 0.99
    const val REF_DOS_SHARPE = 75.86
    const val REF_NAIVE_PNL = -77223.17
    const val GUARD_PIN = "CANCEL_ALL_PIN_RISK_GUARD"
    const val GUARD_TOX = "TOXICITY_GUARD"
    val IDS = setOf("dos-mm", "ts-arb", "mrla-cs", "iv-smile")

    fun isHft(id: String?): Boolean = id != null && id in IDS

    fun perSecVol(vol15m: Double): Double = vol15m / sqrt(WINDOW_S)

    fun vol15mOf(sigmaPerSec: Double): Double = sigmaPerSec * sqrt(WINDOW_S)

    /** z_t with the variance correction √((ν−2)/ν). */
    fun zMoneyness(spot: Double, strike: Double, sigmaPerSec: Double, tau: Double, nu: Double = NU): Double {
        if (spot <= 0.0 || strike <= 0.0 || sigmaPerSec <= 0.0) return 0.0
        val scale = sigmaPerSec * sqrt(tau.coerceAtLeast(1e-9))
        if (scale <= 1e-15) return 0.0
        return ln(spot / strike) / scale * sqrt((nu - 2.0) / nu)
    }

    /** Final-window z. Volatility is σ/√3 from the averaging isometry. */
    fun zTerm(spot: Double, kEff: Double, sigmaPerSec: Double, tauRem: Double, nu: Double = NU): Double =
        zMoneyness(spot, kEff, sigmaPerSec / sqrt(3.0), tauRem, nu)

    fun fairProbability(spot: Double, strike: Double, sigmaPerSec: Double, tau: Double, nu: Double = NU): Double {
        if (spot <= 0.0 || strike <= 0.0) return 0.5
        if (tau <= 1.0) return if (spot > strike) 0.99 else 0.01
        return HftSignals.studentTCdf(zMoneyness(spot, strike, sigmaPerSec, tau, nu), nu).coerceIn(0.01, 0.99)
    }

    data class Greeks(
        val z: Double,
        val fair: Double,
        val delta: Double,
        val gamma: Double,
        val theta: Double,
        val sigmaP: Double,
        val pdf: Double
    )

    fun greeks(spot: Double, strike: Double, sigmaPerSec: Double, tau: Double, nu: Double = NU): Greeks {
        val tauUse = if (tau <= 1.0) 1.0 else tau
        val z = zMoneyness(spot, strike, sigmaPerSec, tauUse, nu)
        val pdf = HftSignals.studentTPdf(z, nu)
        val fair = if (tau <= 1.0) {
            if (spot > strike) 0.99 else 0.01
        } else HftSignals.studentTCdf(z, nu).coerceIn(0.01, 0.99)
        val sqrtTau = sqrt(tauUse)
        val adj = sqrt((nu - 2.0) / nu)
        val sig = sigmaPerSec.coerceAtLeast(1e-12)
        val s = spot.coerceAtLeast(1e-9)
        val delta = pdf * (1.0 / (s * sig * sqrtTau)) * adj
        val bracket = 1.0 + ((nu + 1.0) * z) / (nu + z * z) * (1.0 / (sig * sqrtTau)) * adj
        val gamma = -pdf / (s * s * sig * sqrtTau) * adj * bracket
        val theta = pdf * z / (2.0 * tauUse)
        val sigmaP = delta * sig * s
        return Greeks(z, fair, delta, gamma, theta, sigmaP, pdf)
    }

    /** Discrete ceiling fee per contract. m = 0, 0.0175, or 0.07. */
    fun fee(contracts: Int, price: Double, m: Double): Double {
        if (contracts <= 0 || m <= 0.0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        return ceil(100.0 * m * contracts * price * (1.0 - price)) / (100.0 * contracts)
    }

    fun vamp(bid: Double, ask: Double, bidSz: Double, askSz: Double): Double {
        val den = max(1.0, bidSz + askSz)
        return (bid * askSz + ask * bidSz) / den
    }

    data class DosQuote(
        val vamp: Double,
        val blended: Double,
        val variance: Double,
        val reservation: Double,
        val halfSpread: Double,
        val bid: Double,
        val ask: Double,
        val guard: String?
    )

    fun dosQuote(
        bidPx: Double,
        askPx: Double,
        bidSz: Double,
        askSz: Double,
        fair: Double,
        inventory: Double,
        ofiHat: Double,
        tau: Double
    ): DosQuote {
        val midV = vamp(bidPx, askPx, bidSz, askSz)
        val blended = 0.6 * midV + 0.4 * fair
        val variance = blended * (1.0 - blended)
        val tauNorm = tau.coerceAtLeast(0.0) / WINDOW_S
        val reservation = (blended - inventory * GAMMA * variance * tauNorm + LAMBDA_OFI * ofiHat).coerceIn(0.02, 0.98)
        val rawSpread = 0.5 * GAMMA * variance * tauNorm + (1.0 / GAMMA) * ln(1.0 + GAMMA / KAPPA)
        val half = rawSpread.coerceIn(0.01, 0.04)
        val myBid = min(round2(askPx - 0.01), max(0.01, floorCent(reservation - half))).coerceIn(0.01, 0.99)
        val myAsk = max(round2(bidPx + 0.01), min(0.99, ceilCent(reservation + half))).coerceIn(0.01, 0.99)
        val guard = when {
            tau <= PIN_TAU_S -> GUARD_PIN
            kotlin.math.abs(ofiHat) > TOXICITY -> GUARD_TOX
            else -> null
        }
        return DosQuote(midV, blended, variance, reservation, half, myBid, myAsk, guard)
    }

    fun round2(x: Double): Double = round(x * 100.0) / 100.0
    fun floorCent(x: Double): Double = floor(x * 100.0 + 1e-9) / 100.0
    fun ceilCent(x: Double): Double = ceil(x * 100.0 - 1e-9) / 100.0

    data class OfLevel(val eBid: Double, val eAsk: Double, val depth: Double)

    /** Top-5 exponentially weighted OFI. Depth is D_m so the divisor is 2 D_m. */
    fun ofiWeighted(levels: List<OfLevel>, alpha: Double = OFI_ALPHA): Double {
        if (levels.isEmpty()) return 0.0
        var sum = 0.0
        val n = min(5, levels.size)
        for (i in 0 until n) {
            val w = exp(-alpha * i)
            val denom = 2.0 * levels[i].depth
            if (denom <= 1e-12) continue
            sum += w * (levels[i].eBid - levels[i].eAsk) / denom
        }
        return sum
    }

    class OfiBuffer {
        private val ring = ArrayDeque<Double>()
        private var prevBid = Double.NaN
        private var prevBidSz = 0.0
        private var prevAsk = Double.NaN
        private var prevAskSz = 0.0
        private var hasPrev = false

        fun update(bid: Double, bidSz: Double, ask: Double, askSz: Double): Double {
            if (!hasPrev) {
                prevBid = bid
                prevBidSz = bidSz
                prevAsk = ask
                prevAskSz = askSz
                hasPrev = true
                return 0.0
            }
            val e = HftSignals.ofi(prevBid, prevBidSz, prevAsk, prevAskSz, bid, bidSz, ask, askSz)
            prevBid = bid
            prevBidSz = bidSz
            prevAsk = ask
            prevAskSz = askSz
            val norm = (e / max(1e-9, bidSz + askSz)).coerceIn(-5.0, 5.0)
            ring.addLast(norm)
            while (ring.size > 20) ring.removeFirst()
            return ring.sum()
        }
    }

    fun transientBounce(kappaTrans: Double, v0: Double, dt: Double, rho: Double = RHO): Double =
        kappaTrans * v0 * (1.0 - exp(-rho * dt.coerceAtLeast(0.0)))

    data class Propagator(
        val lambdaPerm: Double,
        val v0: Double,
        val bounce: Double,
        val cmdr: Double,
        val triggered: Boolean
    )

    fun cmdr(dMid: Double, dLogSpot: Double, sigma10: Double): Double {
        val spotUnit = if (sigma10 > 1e-12) kotlin.math.abs(dLogSpot) / sigma10 else 0.0
        val bookUnit = kotlin.math.abs(dMid) / MID_SURGE
        return if (spotUnit < 1e-9) bookUnit else bookUnit / spotUnit
    }

    fun propagator(
        ofi: Double,
        dMid: Double,
        dLogSpot: Double,
        sigma10: Double,
        dt: Double,
        kappaTrans: Double = KAPPA_TRANS
    ): Propagator {
        val flat = sigma10 > 0.0 && kotlin.math.abs(dLogSpot) < FLAT_K * sigma10
        val surge = kotlin.math.abs(ofi) >= OFI_SURGE && kotlin.math.abs(dMid) >= MID_SURGE
        val triggered = flat && surge
        val direction = if (dMid == 0.0) -sign(ofi) else -sign(dMid)
        val v0 = if (triggered) direction * kotlin.math.abs(ofi) else 0.0
        val bounce = if (triggered) transientBounce(kappaTrans, v0, dt) else 0.0
        val lambdaPerm = if (triggered) 0.0 else kotlin.math.abs(dMid)
        return Propagator(lambdaPerm, v0, bounce, cmdr(dMid, dLogSpot, sigma10), triggered)
    }

    fun sigma10s(logReturns: List<Double>, vol15m: Double): Double {
        if (logReturns.size < 3) return vol15m * sqrt(10.0 / WINDOW_S)
        var mean = 0.0
        for (r in logReturns) mean += r
        mean /= logReturns.size
        var v = 0.0
        for (r in logReturns) {
            val d = r - mean
            v += d * d
        }
        return sqrt(v / (logReturns.size - 1)).coerceAtLeast(1e-8)
    }

    fun settlementAlpha(tauRem: Double): Double = ((60.0 - tauRem) / 60.0).coerceIn(0.0, 0.98)

    fun kEff(strike: Double, locked: Double, alpha: Double): Double {
        if (alpha >= 0.999) return strike
        return (strike - alpha * locked) / (1.0 - alpha)
    }

    fun sHat(spot: Double, locked: Double, alpha: Double): Double = alpha * locked + (1.0 - alpha) * spot

    fun vwapSlopeZ(timesSec: DoubleArray, vwap: DoubleArray): Double {
        val n = timesSec.size
        if (n < 4 || vwap.size != n) return 0.0
        var mt = 0.0
        var mp = 0.0
        for (i in 0 until n) {
            mt += timesSec[i]
            mp += vwap[i]
        }
        mt /= n
        mp /= n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dt = timesSec[i] - mt
            sxx += dt * dt
            sxy += dt * (vwap[i] - mp)
        }
        if (sxx <= 1e-12) return 0.0
        val beta = sxy / sxx
        var sse = 0.0
        for (i in 0 until n) {
            val err = vwap[i] - (mp + beta * (timesSec[i] - mt))
            sse += err * err
        }
        val sigma = sqrt(sse / (n - 2).coerceAtLeast(1))
        val se = sigma / sqrt(sxx)
        if (se <= 1e-12) {
            return if (kotlin.math.abs(beta) <= 1e-12) 0.0 else (if (beta > 0.0) 8.0 else -8.0)
        }
        return (beta / se).coerceIn(-8.0, 8.0)
    }

    fun evYes(pTerm: Double, ask: Double, contracts: Int = 20): Double =
        pTerm - ask - fee(contracts, ask, TAKER)

    fun evNo(pTerm: Double, noAsk: Double, contracts: Int = 20): Double =
        (1.0 - pTerm) - noAsk - fee(contracts, noAsk, TAKER)

    fun arbSide(pTerm: Double, pSpot: Double, yesAsk: Double, noAsk: Double, spread: Double, zVwap: Double, tau: Double): String? {
        if (tau <= 15.0 || tau > 90.0) return null
        val yes = evYes(pTerm, yesAsk)
        val no = evNo(pTerm, noAsk)
        if (yes > EV_MIN && yesAsk <= ASK_CAP) return "TAKER_ARB_BUY_YES"
        if (no > EV_MIN && noAsk <= ASK_CAP) return "TAKER_ARB_BUY_NO"
        val gap = kotlin.math.abs(pTerm - pSpot)
        val cost = fee(20, yesAsk, TAKER) + spread
        if (gap > cost && kotlin.math.abs(zVwap) > 2.0) {
            return when {
                pTerm >= pSpot && yesAsk <= ASK_CAP -> "TAKER_ARB_BUY_YES"
                pTerm < pSpot && noAsk <= ASK_CAP -> "TAKER_ARB_BUY_NO"
                else -> null
            }
        }
        return null
    }

    data class RoundTrip(val winNet: Double, val lossNet: Double, val pMin: Double, val collateral: Double, val label: String)

    fun pMin(winNet: Double, lossNet: Double): Double {
        val den = winNet + lossNet
        if (den <= 1e-12) return 1.0
        return (lossNet / den).coerceIn(0.0, 1.0)
    }

    fun percentLabel(p: Double): String {
        val tenths = floor(p.coerceIn(0.0, 1.0) * 1000.0 + 1e-9) / 10.0
        return String.format(Locale.US, "%.1f%%", tenths)
    }

    fun makerInTakerStop(): RoundTrip = roundTrip(1, 0.50, 0.08, 0.039, MAKER_IN, TAKER, TAKER, "Maker-In/Taker-Stop")

    fun makerMaker(): RoundTrip = roundTrip(1, 0.50, 0.08, 0.08, 0.0, 0.0, 0.0, "Maker/Maker")

    fun takerTaker(): RoundTrip = roundTrip(1, 0.50, 0.08, 0.039, TAKER, TAKER, TAKER, "Taker/Taker")

    private fun roundTrip(c: Int, pIn: Double, dWin: Double, dLoss: Double, mIn: Double, mWin: Double, mLoss: Double, label: String): RoundTrip {
        val feeIn = fee(c, pIn, mIn)
        val feeWin = fee(c, (pIn + dWin).coerceIn(0.01, 0.99), mWin)
        val feeLoss = fee(c, (pIn - dLoss).coerceIn(0.01, 0.99), mLoss)
        val w = dWin - feeIn - feeWin
        val l = dLoss + feeIn + feeLoss
        return RoundTrip(w, l, pMin(w, l), pIn + feeIn, label)
    }

    data class Kelly(val fStar: Double, val collateral: Double, val damp: Double, val rawContracts: Double, val contracts: Int)

    fun kelly(p: Double, winNet: Double, lossNet: Double, collateral: Double, bankroll: Double, tau: Double): Kelly {
        val pp = p.coerceIn(0.0, 1.0)
        val edge = pp * winNet - (1.0 - pp) * lossNet
        val fStar = if (winNet <= 1e-12 || lossNet <= 1e-12 || collateral <= 1e-12) 0.0
        else max(0.0, edge / (winNet * lossNet) * collateral)
        val damp = min(1.0, sqrt(tau.coerceAtLeast(0.0) / TAU_CRIT))
        val raw = if (collateral <= 1e-12) 0.0 else bankroll * ETA * fStar / collateral * damp
        return Kelly(fStar, collateral, damp, raw, floor(raw).toInt().coerceAtLeast(0))
    }

    fun smileSigma(z: Double, sigmaAtm: Double, ofi: Double, spotZ: Double): Double {
        val trend = sigmaAtm * (0.05 * ofi.coerceIn(-2.0, 2.0) + 0.05 * spotZ.coerceIn(-2.0, 2.0))
        return (sigmaAtm * (1.0 + SMILE_K * z * z) + trend).coerceAtLeast(1e-8)
    }

    fun smileFair(spot: Double, strike: Double, sigmaPerSec: Double, tau: Double, ofi: Double, spotZ: Double): Double {
        val zAtm = zMoneyness(spot, strike, sigmaPerSec, tau)
        val sig = smileSigma(zAtm, sigmaPerSec, ofi, spotZ)
        val z = zMoneyness(spot, strike, sig, tau) + 0.10 * ofi.coerceIn(-3.0, 3.0) + 0.10 * spotZ.coerceIn(-3.0, 3.0)
        return HftSignals.studentTCdf(z).coerceIn(0.01, 0.99)
    }

    fun smileEdge(fair: Double, ask: Double, contracts: Int): Double = fair - ask - fee(contracts, ask, TAKER)

    fun smileExit(fair: Double): Double = (fair - 0.02).coerceIn(0.01, 0.99)

    data class RiskGate(val name: String, val pass: Boolean, val detail: String)

    fun riskGates(
        inventory: Double,
        dailyPnl: Double,
        drawdownCeiling: Double,
        contractsOpt: Int,
        spread: Double,
        bookAgeMs: Long,
        corr: Double,
        maxSpread: Double = MAX_SPREAD,
        maxAgeMs: Long = MAX_BOOK_AGE_MS
    ): List<RiskGate> = listOf(
        RiskGate("MaxPositionLimit", kotlin.math.abs(inventory) <= MAX_INVENTORY + 1e-9, "q=$inventory"),
        RiskGate("DailyDrawdownCeiling", dailyPnl >= -drawdownCeiling, "pnl=$dailyPnl"),
        RiskGate("QuarterKellyPinDamper", contractsOpt >= 1, "Copt=$contractsOpt"),
        RiskGate("SpreadWidthGuard", spread <= maxSpread + 1e-9, "spread=$spread"),
        RiskGate("SnapshotLatencyAndCorrRegime", bookAgeMs <= maxAgeMs && corr <= STRESS_CORR, "age=$bookAgeMs corr=$corr")
    )

    /** Average fill minus the touch. Positive means walking up the ask ladder. */
    fun bookWalkSlippage(ladder: List<Pair<Double, Double>>, qty: Double): Double {
        if (qty <= 0.0 || ladder.isEmpty()) return 0.0
        var left = qty
        var cost = 0.0
        var filled = 0.0
        for ((px, sz) in ladder) {
            if (left <= 1e-12) break
            val take = min(left, sz.coerceAtLeast(0.0))
            cost += take * px
            filled += take
            left -= take
        }
        if (filled <= 0.0) return 0.0
        return cost / filled - ladder.first().first
    }

    fun syntheticArb(yesKalshi: Double, noExternal: Double, fees: Double): Boolean =
        yesKalshi + noExternal + fees < 1.0 - 1e-12

    fun referenceDos(): String = String.format(Locale.US, "DOS-MM +$%,.2f / %.1f%% win / Sharpe %.2f", REF_DOS_PNL, REF_DOS_WIN * 100.0, REF_DOS_SHARPE)

    fun referenceNaive(): String = String.format(Locale.US, "Naive OFI Taker -$%,.2f", -REF_NAIVE_PNL)
}
