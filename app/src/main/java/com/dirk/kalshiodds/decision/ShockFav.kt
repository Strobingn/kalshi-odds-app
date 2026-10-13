package com.dirk.kalshiodds.decision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 0.3.57 sf57: twenty deterministic, versioned paper scalpers built on the two scalp57 foundations —
 * SPOT-SHOCK LAG (spot jumps ≥ X bp in Y s while the book still lags fair by ≥ lag → buy the move side at the ask,
 * exit when the bid catches up to fair − conv, after max-hold, or at τ < 30 s) and LATE FAVOURITE (20 s ≤ τ ≤ tmax,
 * fair ≥ fmin, ask ≤ fair − gap → buy and hold to settlement, one per market).
 * Params are the TRAIN-chosen values of /workspace/scalp57/bt58.py (train 2026-09-25..10-04, test 10-05..10-10).
 * PAPER ONLY. No LLM. Taker fills at the displayed touch, capped at displayed size, Kalshi taker fee both legs.
 */
object ShockFav {
    const val VERSION = "sf58-v1"
    /** 0.3.58: paper entries fill at the first quote ≥ 2 s after the signal (bt59 delay test), at that quote's ask. */
    const val ENTRY_DELAY_MS = 2_000L
    const val ENTRY_GIVE_UP_MS = 6_000L
    const val PREFIX = "sf"
    const val CLIP = 10

    /** Per-coin median 1 s σ on the train days (regime filters sf11 / sf12). */
    val MEDIAN_SIGMA: Map<String, Double> = mapOf("BTC" to 2.654e-5, "ETH" to 3.990e-5, "SOL" to 7.569e-5)

    data class Params(
        val code: String,
        val label: String,
        val shock: Boolean = true,
        val fav: Boolean = false,
        val xBp: Double = 5.0,
        val yS: Int = 5,
        val lag: Double = 0.05,
        val maxHoldS: Int = 90,
        val conv: Double = 0.01,
        val tauLo: Double = 60.0,
        val tauHi: Double = 870.0,
        val coins: Set<String>? = null,
        val volLo: Double? = null,
        val volHi: Double? = null,
        val momoBp: Double? = null,
        val multiS: Int? = null,
        val settle: Boolean = false,
        val favHold: Boolean = false,
        val favTmin: Double = 20.0,
        val favTmax: Double = 60.0,
        val fmin: Double = 0.90,
        val gap: Double = 0.05,
        val amax: Double = 0.97,
        val brti: Boolean = false,
        /** Walk-forward test-day result of bt58 (per trade, $, 10-ct clips, after fees) and its 95 % CI, for the card. */
        val oos: String = ""
    ) {
        val shockId: String get() = code
        val favId: String get() = code + "f"
        fun summary(): String = buildString {
            if (shock) append(String.format(java.util.Locale.US, "shock ≥%.0fbp/%ds, lag ≥%.0f¢, hold ≤%ds", xBp, yS, lag * 100, maxHoldS))
            if (shock && fav) append(" + ")
            if (fav) append(String.format(java.util.Locale.US, "late fav τ≤%.0fs, fair ≥%.0f%%, ≥%.0f¢ under%s", favTmax, fmin * 100, gap * 100, if (brti) " (60 s avg fair)" else ""))
            coins?.let { append(" · ").append(it.sorted().joinToString("+")) }
        }
    }

    val VARIANTS: List<Params> = listOf(
        Params("sf01", "Shock base", oos = "2s delay: -0.31/trade (-0.44…-0.18) n=473"),
        Params("sf02", "Shock fast 3s", xBp = 6.0, yS = 3, lag = 0.06, maxHoldS = 60, oos = "2s delay: -0.44/trade (-0.60…-0.29) n=181"),
        Params("sf03", "Shock big move", xBp = 15.0, yS = 10, maxHoldS = 120, oos = "2s delay: -0.06/trade (-0.54…+0.43) n=44"),
        Params("sf04", "Shock wide lag", lag = 0.10, oos = "2s delay: -0.30/trade (-0.54…-0.07) n=193"),
        Params("sf05", "Shock quick exit", maxHoldS = 45, oos = "2s delay: -0.30/trade (-0.39…-0.21) n=521"),
        Params("sf06", "Shock long hold", maxHoldS = 300, oos = "2s delay: -0.27/trade (-0.48…-0.06) n=408"),
        Params("sf07", "Shock hold to close", maxHoldS = 900, settle = true, oos = "2s delay: -0.26/trade (-0.72…+0.17) n=330"),
        Params("sf08", "Shock tight catch-up", conv = 0.02, oos = "2s delay: -0.31/trade (-0.43…-0.18) n=477"),
        Params("sf09", "Shock BTC only", coins = setOf("BTC"), oos = "2s delay: -0.25/trade (-0.48…-0.02) n=92"),
        Params("sf10", "Shock ETH+SOL", coins = setOf("ETH", "SOL"), xBp = 8.0, oos = "2s delay: -0.34/trade (-0.60…-0.09) n=137"),
        Params("sf11", "Shock calm regime", volLo = 0.8, oos = "2s delay: -0.48/trade (-1.44…+0.31) n=15"),
        Params("sf12", "Shock hot regime", volHi = 1.25, oos = "2s delay: -0.25/trade (-0.42…-0.08) n=314"),
        Params("sf13", "Shock + 30s momentum", momoBp = 5.0, oos = "2s delay: -0.31/trade (-0.58…-0.07) n=112"),
        Params("sf14", "Multi-shock", multiS = 30, oos = "2s delay: -0.45/trade (-0.75…-0.17) n=68"),
        Params("sf15", "Shock mid-window", tauLo = 180.0, tauHi = 720.0, oos = "2s delay: -0.29/trade (-0.45…-0.14) n=316"),
        Params("sf16", "Late fav base", shock = false, fav = true, oos = "2s delay: +0.95/trade (-0.13…+2.10) n=31"),
        Params("sf17", "Late fav 60s-avg fair", shock = false, fav = true, brti = true, gap = 0.03, oos = "2s delay: -0.04/trade (-0.53…+0.42) n=158"),
        Params("sf18", "Late fav wide window", shock = false, fav = true, favTmax = 120.0, fmin = 0.90, gap = 0.08, oos = "2s delay: +0.21/trade (-0.61…+1.04) n=79"),
        Params("sf19", "Late fav strict", shock = false, fav = true, fmin = 0.95, amax = 0.95, oos = "2s delay: +1.31/trade (+0.29…+2.47) n=26"),
        Params("sf20", "Combo shock→fav", shock = true, fav = true, favHold = true, oos = "2s delay: -0.22/trade (-0.37…-0.07) n=504")
    )
    private val BY_CODE = VARIANTS.associateBy { it.code }

    fun isShockFav(variantId: String): Boolean = variantId.length >= 4 && variantId.startsWith(PREFIX) && variantId[2].isDigit()
    fun byVariantId(variantId: String): Params? = if (isShockFav(variantId)) BY_CODE[variantId.take(4)] else null
    fun isFavLeg(variantId: String): Boolean = isShockFav(variantId) && variantId.endsWith("f")

    /** Per-coin 1 s spot ring (last 128 s). Single writer under ScalpBook's lock. */
    class SpotRing {
        private val secs = LongArray(N) { Long.MIN_VALUE }
        private val px = DoubleArray(N)
        fun put(sec: Long, p: Double) { val i = (sec and MASK).toInt(); secs[i] = sec; px[i] = p }
        /** Latest price at or before [sec] within 3 s, else null. */
        fun at(sec: Long): Double? {
            for (d in 0..3) { val s = sec - d; val i = (s and MASK).toInt(); if (secs[i] == s) return px[i] }
            return null
        }
        /** bt58 σ: RMS of consecutive 1 s log returns over the trailing 900 s (null under 120 returns). Cached per second. */
        private var sigSec = Long.MIN_VALUE
        private var sigVal: Double? = null
        fun sigma900(sec: Long): Double? {
            if (sec == sigSec) return sigVal
            var ss = 0.0; var n = 0
            for (s in sec - 899..sec) {
                val i = (s and MASK).toInt(); val j = ((s - 1) and MASK).toInt()
                if (secs[i] == s && secs[j] == s - 1) { val r = ln(px[i] / px[j]); ss += r * r; n++ }
            }
            sigSec = sec; sigVal = if (n < 120) null else maxOf(sqrt(ss / n), 1e-6)
            return sigVal
        }
        companion object { const val N = 1024; const val MASK = 1023L }
    }

    /** bt58 fair: Φ(ln(S·e^(−basis)/K) / (σ·√τ)), basis = ln(spot at window open / strike) when seen, else 0. */
    fun fairYes(spot: Double, strike: Double, sigma: Double, tauS: Double, basis: Double): Double {
        val z = ln(spot * exp(-basis) / strike) / (sigma * sqrt(maxOf(tauS, 1.0)))
        return ScalpMath.phi(z.coerceIn(-8.0, 8.0))
    }

    /** Signed Y-second log return in bp, or null without data. */
    fun moveBp(r: SpotRing, sec: Long, y: Int): Double? {
        val a = r.at(sec) ?: return null; val b = r.at(sec - y) ?: return null
        return ln(a / b) * 1e4
    }

    /** Shock entry side ("YES" up / "NO" down) or null. Pure. */
    fun shockSide(p: Params, coin: String, r: SpotRing, sec: Long, tauS: Double, sigma: Double?): String? {
        if (!p.shock || tauS < p.tauLo || tauS > p.tauHi) return null
        if (p.coins != null && coin !in p.coins) return null
        val m = moveBp(r, sec, p.yS) ?: return null
        if (abs(m) < p.xBp) return null
        val med = MEDIAN_SIGMA[coin] ?: return null
        if (p.volLo != null && (sigma == null || sigma / med > p.volLo)) return null
        if (p.volHi != null && (sigma == null || sigma / med < p.volHi)) return null
        if (p.momoBp != null) {
            val a = r.at(sec - p.yS) ?: return null; val b = r.at(sec - 30) ?: return null
            val m30 = ln(a / b) * 1e4
            if (m30 * m <= 0 || abs(m30) < p.momoBp) return null
        }
        if (p.multiS != null) {
            var cnt = 0
            for (s in sec - p.multiS until sec) { val x = moveBp(r, s, p.yS) ?: continue; if (abs(x) >= p.xBp && x * m > 0) cnt++ }
            if (cnt < p.yS + 1) return null
        }
        return if (m > 0) "YES" else "NO"
    }

    fun shockEntryOk(p: Params, sideFair: Double, ask: Double): Boolean = sideFair - ask >= p.lag - 1e-9 && ask in 0.05..0.95

    fun favEntryOk(p: Params, sideFair: Double, ask: Double, tauS: Double, coin: String): Boolean =
        p.fav && (p.coins == null || coin in p.coins) && tauS >= p.favTmin && tauS <= p.favTmax &&
            sideFair >= p.fmin - 1e-9 && sideFair - ask >= p.gap - 1e-9 && ask <= p.amax + 1e-9

    /**
     * Shock exit reason, or null to keep holding. Hold-to-close / combo legs ride to settlement once in the
     * favourite zone (τ ≤ 75 s and side fair ≥ 90 %).
     */
    fun shockExit(p: Params, heldS: Double, tauS: Double, bid: Double, sideFair: Double): String? {
        val holdOk = p.settle || p.favHold
        val favZone = holdOk && tauS <= 75.0 && sideFair >= 0.90
        if (favZone) return null
        val caught = !p.settle && bid >= sideFair - p.conv - 1e-9
        return when {
            caught -> "bid caught fair"
            tauS < 30.0 -> "τ < 30 s"
            heldS >= p.maxHoldS -> "max hold ${p.maxHoldS}s"
            else -> null
        }
    }

    /**
     * P(YES) when settlement is the mean of the final 60 s of the index: observed part + Gaussian remainder
     * (bt58 brti_fair). [obs] = per-second prices already inside the final minute, oldest first.
     */
    fun brtiFairYes(spot: Double, strike: Double, sigma: Double, tauS: Double, obs: List<Double>): Double {
        val mean: Double
        val sd: Double
        if (tauS >= 60.0) { mean = spot; sd = spot * sigma * sqrt((tauS - 60.0) + 20.0) }
        else {
            val r = (60 - obs.size).coerceAtLeast(0)
            mean = (obs.sum() + r * spot) / 60.0
            sd = spot * sigma * sqrt(maxOf(r.toDouble(), 1e-9) / 3.0) * r / 60.0
        }
        if (sd <= 1e-12) return if (mean > strike) 1.0 else 0.0
        return ScalpMath.phi((mean - strike) / sd)
    }

}

internal object ScalpMath {
    fun phi(x: Double): Double {
        // Abramowitz–Stegun 7.1.26 erf, |err| < 1.5e-7
        val z = x / sqrt(2.0); val t = 1.0 / (1.0 + 0.3275911 * abs(z))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return 0.5 * (1.0 + if (z >= 0) y else -y)
    }
}
