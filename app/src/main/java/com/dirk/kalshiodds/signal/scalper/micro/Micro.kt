package com.dirk.kalshiodds.signal.scalper.micro

import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.fleet.Fleet
import com.dirk.kalshiodds.signal.scalper.fleet.FleetMath
import com.dirk.kalshiodds.signal.scalper.fleet.FleetOrder
import com.dirk.kalshiodds.signal.scalper.fleet.FleetView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Order-book and index micro-structure for the four micro scalpers (the ideas from the Gemini review of 2026-10-23,
 * rebuilt on this app's own book, index and paper engine). Pure functions; [MicroTape] keeps the little state they need.
 */
object MicroMath {
    /** Student-t degrees of freedom for Bitcoin's 1-second returns (the fleet's ν). */
    const val NU = 4.2

    /**
     * Five-level book imbalance in [−1, 1], weights e^{−λ(i−1)}: YES bids against NO bids (a NO bid is a YES offer).
     * +1 = all the size is bidding for YES.
     */
    fun obi5(yesBids: List<Pair<Double, Double>>, noBids: List<Pair<Double, Double>>, lambda: Double = 0.5): Double? =
        FleetMath.obi(yesBids, noBids, levels = 5, lambda = lambda)

    /**
     * Multi-level order-flow imbalance (Cont, Kukanov and Stoikov) over the top five levels between two snapshots,
     * YES bids as the bid side and NO bids as the offer side, each level weighted e^{−λ(i−1)} and the sum divided by
     * the average depth shown, so it reads in "books": +1 = a whole book's worth of size came in on the YES side.
     * Null without both snapshots.
     */
    fun ofi5(
        prevYes: List<Pair<Double, Double>>, prevNo: List<Pair<Double, Double>>,
        yes: List<Pair<Double, Double>>, no: List<Pair<Double, Double>>,
        lambda: Double = 0.5
    ): Double? {
        if (prevYes.isEmpty() || prevNo.isEmpty() || yes.isEmpty() || no.isEmpty()) return null
        var sum = 0.0
        var depth = 0.0
        for (i in 0 until 5) {
            val w = exp(-lambda * i)
            sum += w * (levelFlow(prevYes.getOrNull(i), yes.getOrNull(i)) - levelFlow(prevNo.getOrNull(i), no.getOrNull(i)))
            depth += w * ((yes.getOrNull(i)?.second ?: 0.0) + (no.getOrNull(i)?.second ?: 0.0)) / 2.0
        }
        return if (depth <= 0.0) null else sum / depth
    }

    /**
     * Size added to one bid level: a higher price counts its whole size, the same price the change in size, a lower
     * price minus what was there.
     */
    private fun levelFlow(prev: Pair<Double, Double>?, now: Pair<Double, Double>?): Double {
        if (prev == null && now == null) return 0.0
        if (prev == null) return now!!.second
        if (now == null) return -prev.second
        return when {
            now.first > prev.first + 1e-9 -> now.second
            now.first < prev.first - 1e-9 -> -prev.second
            else -> now.second - prev.second
        }
    }

    /** Volume-weighted YES price of the prints in [prints] (time ms, YES price, contracts). Null under [minContracts]. */
    fun vwap(prints: List<Triple<Long, Double, Double>>, minContracts: Double = 1.0): Double? {
        var v = 0.0
        var pv = 0.0
        for ((_, p, c) in prints) { v += c; pv += p * c }
        return if (v < minContracts) null else pv / v
    }

    /**
     * Fair YES from the index under a Student-t (unit variance, ν [NU]): the settlement is the average of the last 60
     * prints, so with [tteSeconds] left the remaining variance is about σ²·(τ − 40 s) (τ − 60 + 60/3), floored at 20 s.
     */
    fun fairT(spot: Double, strike: Double, sigma1sLog: Double, tteSeconds: Double): Double? {
        if (!(spot > 0 && strike > 0 && sigma1sLog > 0)) return null
        val eff = max(20.0, tteSeconds - 40.0)
        val z = ln(spot / strike) / (sigma1sLog * sqrt(eff))
        return FleetMath.studentTUnitCdf(z, NU).coerceIn(0.001, 0.999)
    }

    /** The index's log move over the last [seconds] in 1-second standard deviations. Null with gaps at either end. */
    fun indexZ(index1s: DoubleArray, seconds: Int, sigma1sLog: Double?): Double? {
        val sd = sigma1sLog?.takeIf { it > 0 } ?: return null
        if (index1s.size <= seconds) return null
        val now = index1s.last()
        val then = index1s[index1s.size - 1 - seconds]
        if (!now.isFinite() || !then.isFinite() || now <= 0 || then <= 0) return null
        return ln(now / then) / (sd * sqrt(seconds.toDouble()))
    }
}

/** What the micro scalpers read in one second. */
data class MicroSignals(
    val obi5: Double? = null,
    val ofi5: Double? = null,
    /** 30-second volume-weighted YES price of the Kalshi prints, and the contracts behind it. */
    val vwap30: Double? = null,
    val vwapContracts: Double = 0.0,
    /** Micro-price (the mid leaned to the thinner side). */
    val micro: Double? = null,
    /** Student-t fair YES from the index, now and 10 s ago. */
    val fairT: Double? = null,
    val fairT10: Double? = null,
    /** Index move over 10 s / 30 s in 1-second standard deviations. */
    val idxZ10: Double? = null,
    val idxZ30: Double? = null,
    /** YES mid 10 s ago. */
    val mid10: Double? = null
)

/** One second of the live window for the charts. Prices in YES dollars; null = not known that second. */
data class MicroPoint(
    val s: Int,
    val yesBid: Double?,
    val yesAsk: Double?,
    val fair: Double?,
    val micro: Double?,
    val vwap: Double?,
    val obi5: Double?,
    val ofi5: Double?,
    val spot: Double?,
    val strike: Double?,
    val idxZ10: Double?
) {
    val noBid: Double? get() = yesAsk?.let { 1.0 - it }
    val yesMid: Double? get() = if (yesBid != null && yesAsk != null && yesBid < yesAsk) (yesBid + yesAsk) / 2 else null
}

/** The live window's micro-structure, second by second, for the arena's charts. */
data class LiveMicro(val ticker: String, val points: List<MicroPoint>)

/**
 * Per-ticker state for the micro scalpers: the Kalshi prints of the last minute (for the micro-VWAP), the previous
 * five book levels (for the OFI) and the window's per-second series (for the charts). Not thread-safe; the paper
 * scalper holds its lock.
 */
class MicroTape {
    private class Tape {
        val prints = ArrayDeque<Triple<Long, Double, Double>>()
        var prevYes: List<Pair<Double, Double>> = emptyList()
        var prevNo: List<Pair<Double, Double>> = emptyList()
        var lastSecond = -1
        var last: MicroSignals = MicroSignals()
        val series = ArrayList<MicroPoint>()
    }

    private val tapes = HashMap<String, Tape>()
    private var lastTicker: String? = null

    /** A Kalshi trade print (YES price, contracts). */
    fun onTrade(ticker: String, yesPrice: Double, contracts: Double, nowMs: Long) {
        val t = tapes.getOrPut(ticker) { Tape() }
        t.prints.addLast(Triple(nowMs, yesPrice, contracts))
        while (t.prints.isNotEmpty() && t.prints.first().first < nowMs - KEEP_MS) t.prints.removeFirst()
    }

    /**
     * This second's signals for [v] (computed once a second; a second call in the same second returns the same
     * answer). Also appends the second to the chart series.
     */
    fun signals(v: FleetView): MicroSignals {
        val t = tapes.getOrPut(v.ticker) { Tape() }
        lastTicker = v.ticker
        if (t.lastSecond == v.s) return t.last
        val yes = v.yesLevels.take(5)
        val no = v.noLevels.take(5)
        val ofi = MicroMath.ofi5(t.prevYes, t.prevNo, yes, no)
        if (yes.isNotEmpty() && no.isNotEmpty()) { t.prevYes = yes; t.prevNo = no }
        val recent = t.prints.filter { it.first >= v.nowMs - 30_000L }
        val vol = recent.sumOf { it.third }
        val fair = run {
            val spot = v.spot ?: return@run null
            val k = v.strike ?: return@run null
            val sd = v.sigma1sLog ?: return@run null
            MicroMath.fairT(spot, k, sd, v.tte)
        }
        val fair10 = run {
            val k = v.strike ?: return@run null
            val sd = v.sigma1sLog ?: return@run null
            val idx = v.index1s
            if (idx.size <= 10) return@run null
            val then = idx[idx.size - 11].takeIf { it.isFinite() && it > 0 } ?: return@run null
            MicroMath.fairT(then, k, sd, v.tte + 10.0)
        }
        val micro = if (v.yesBid != null && v.yesAsk != null && v.yesBid < v.yesAsk) {
            FleetMath.microPrice(v.yesBid, v.yesBidQty ?: 0.0, v.yesAsk, v.yesAskQty ?: 0.0)
        } else null
        val mids = v.yesMids
        val sig = MicroSignals(
            obi5 = MicroMath.obi5(yes, no),
            ofi5 = ofi,
            vwap30 = MicroMath.vwap(recent),
            vwapContracts = vol,
            micro = micro,
            fairT = fair,
            fairT10 = fair10,
            idxZ10 = MicroMath.indexZ(v.index1s, 10, v.sigma1sLog),
            idxZ30 = MicroMath.indexZ(v.index1s, 30, v.sigma1sLog),
            mid10 = mids.getOrNull(mids.size - 11)?.takeIf { it.isFinite() }
        )
        t.last = sig
        t.lastSecond = v.s
        if (t.series.lastOrNull()?.s != v.s) {
            t.series += MicroPoint(v.s, v.yesBid, v.yesAsk, sig.fairT, sig.micro, sig.vwap30, sig.obi5, sig.ofi5, v.spot, v.strike, sig.idxZ10)
            while (t.series.size > MAX_POINTS) t.series.removeAt(0)
        }
        return sig
    }

    /** The newest window with a chart series. */
    fun live(): LiveMicro? {
        val key = lastTicker ?: return null
        val t = tapes[key]?.takeIf { it.series.isNotEmpty() } ?: return null
        return LiveMicro(key, t.series.toList())
    }

    fun forget(ticker: String) {
        tapes.remove(ticker)
        if (lastTicker == ticker) lastTicker = null
    }

    /** Keep only [tickers] (the windows still watched). */
    fun retain(tickers: Set<String>) {
        tapes.keys.retainAll(tickers)
        if (lastTicker !in tickers) lastTicker = null
    }

    companion object {
        const val KEEP_MS = 60_000L
        const val MAX_POINTS = 900
    }
}

/**
 * The four micro scalpers. Each answers for one second with at most one paper order per side, priced on the live
 * book, fee-aware (the taker fee is in every buy-now edge; resting bids pay no fee on KXBTC15M) and sized by the
 * strategy's own contracts setting. [trigger] is the strategy's main threshold (your setting, the AI's, or the default).
 */
object MicroScalpers {
    /** OBI maker: least the resting side's Student-t fair must beat its bid by. */
    const val OBI_FAIR_EDGE = 0.025
    /** OBI maker / VWAP reversion: least room between the entry and the sell-back. */
    const val MIN_SCALP = 0.02
    /** BRTI pulse: least the fair must have run ahead of the market in 10 s, and the least edge over fee. */
    const val PULSE_LAG = 0.02
    const val PULSE_EDGE = 0.02
    /** VWAP reversion: least contracts behind the 30-second VWAP, and the widest index move that still counts as calm. */
    const val VWAP_MIN_CONTRACTS = 200.0
    const val VWAP_CALM_Z = 0.5
    /** Theta favourite: least edge over the fee to pay the ask, least edge under the fair to rest a bid, dearest entry. */
    const val THETA_TAKE_EDGE = 0.02
    const val THETA_REST_EDGE = 0.03
    const val THETA_MAX_PRICE = 0.95

    fun decide(st: ScalpStrategy, v: FleetView, m: MicroSignals, trigger: Double, contracts: Int): List<FleetOrder> = runCatching {
        when (st) {
            ScalpStrategy.MICRO_OBI_MAKER -> obiMaker(v, m, trigger, contracts)
            ScalpStrategy.MICRO_BRTI_PULSE -> brtiPulse(v, m, trigger, contracts)
            ScalpStrategy.MICRO_VWAP_REV -> vwapRev(v, m, trigger, contracts)
            ScalpStrategy.MICRO_THETA_FAV -> thetaFav(v, m, trigger, contracts)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun bidOf(v: FleetView, side: String) = (if (side == "YES") v.yesBid else v.noBid)?.takeIf { it > 0.0 && it < 1.0 }
    private fun askOf(v: FleetView, side: String) = (if (side == "YES") v.yesAsk else v.noAsk)?.takeIf { it > 0.0 && it < 1.0 }
    private fun sideP(yesP: Double, side: String) = if (side == "YES") yesP else 1.0 - yesP
    private fun pct(p: Double) = String.format(java.util.Locale.US, "%.1f", p * 100)

    /** 1 · Five-level book imbalance with the Student-t fair behind it: rest on the bid, sell 3¢ up. */
    private fun obiMaker(v: FleetView, m: MicroSignals, trigger: Double, contracts: Int): List<FleetOrder> {
        val obi = m.obi5 ?: return emptyList()
        val fair = m.fairT ?: return emptyList()
        val side = when {
            obi >= trigger -> "YES"
            obi <= -trigger -> "NO"
            else -> return emptyList()
        }
        val bid = bidOf(v, side) ?: return emptyList()
        val ask = askOf(v, side) ?: return emptyList()
        // Step inside a 3¢+ spread, else join the bid.
        val px = if (ask - bid >= 0.03 - 1e-9) Fleet.floorTick(bid + 0.01) else bid
        val pf = sideP(fair, side)
        if (pf - px < OBI_FAIR_EDGE - 1e-9) return emptyList()
        val exit = Fleet.ceilTick(min(px + 0.03, max(pf, px + MIN_SCALP)))
        return listOf(
            FleetOrder(side, false, px, contracts, exit, pf, "OBI5 ${"%+.2f".format(obi)} · t-fair ${pct(pf)}¢ vs ${pct(px)}¢")
        )
    }

    /** 2 · The settlement index moved hard in 10 s and the Kalshi book has not caught up: pay the ask, sell into the catch-up. */
    private fun brtiPulse(v: FleetView, m: MicroSignals, trigger: Double, contracts: Int): List<FleetOrder> {
        val z = m.idxZ10 ?: return emptyList()
        if (abs(z) < trigger) return emptyList()
        val fair = m.fairT ?: return emptyList()
        val fair10 = m.fairT10 ?: return emptyList()
        val mid = v.yesMid ?: return emptyList()
        val mid10 = m.mid10 ?: return emptyList()
        val side = if (z > 0) "YES" else "NO"
        val fairMove = sideP(fair, side) - sideP(fair10, side)
        val marketMove = sideP(mid, side) - sideP(mid10, side)
        if (fairMove - marketMove < PULSE_LAG - 1e-9) return emptyList()
        val ask = askOf(v, side) ?: return emptyList()
        val pf = sideP(fair, side)
        val fee = FleetMath.takerFeePer(contracts, ask)
        if (pf - ask - fee < PULSE_EDGE - 1e-9) return emptyList()
        // Sell back where the book should catch up to: the fair price, at most 6¢ up, at least 2¢ up.
        val exit = Fleet.floorTick(min(pf, ask + 0.06)).coerceAtLeast(Fleet.ceilTick(ask + MIN_SCALP))
        return listOf(
            FleetOrder(side, true, ask, contracts, exit, pf, "index ${"%+.1f".format(z)}σ in 10 s · fair ${pct(pf)}¢ ran ${"%+.1f".format(fairMove * 100)}¢, book ${"%+.1f".format(marketMove * 100)}¢")
        )
    }

    /** 3 · The book swept away from the 30-second VWAP while the index stayed calm: rest on the cheap side, sell at the VWAP. */
    private fun vwapRev(v: FleetView, m: MicroSignals, trigger: Double, contracts: Int): List<FleetOrder> {
        val vw = m.vwap30 ?: return emptyList()
        if (m.vwapContracts < VWAP_MIN_CONTRACTS) return emptyList()
        val calm = m.idxZ30 ?: return emptyList()
        if (abs(calm) > VWAP_CALM_Z) return emptyList()
        val mid = v.yesMid ?: return emptyList()
        val gap = mid - vw
        val side = when {
            gap <= -trigger -> "YES"
            gap >= trigger -> "NO"
            else -> return emptyList()
        }
        val bid = bidOf(v, side) ?: return emptyList()
        val target = Fleet.floorTick(sideP(vw, side))
        if (target - bid < MIN_SCALP - 1e-9) return emptyList()
        // The model: halfway between the VWAP and the index's own fair (the VWAP alone when the index is not in).
        val pm = m.fairT?.let { (sideP(vw, side) + sideP(it, side)) / 2.0 } ?: sideP(vw, side)
        if (pm - bid < FleetMath.THETA_MIN - 1e-9) return emptyList()
        return listOf(
            FleetOrder(side, false, bid, contracts, target, pm, "mid ${pct(mid)}¢ vs VWAP ${pct(vw)}¢ on ${m.vwapContracts.toInt()} ct · index ${"%+.1f".format(calm)}σ")
        )
    }

    /** 4 · 90–240 s left, the leader's Student-t fair at [trigger] or more: pay the ask on a 2¢ edge over the fee, else rest a bid. Hold. */
    private fun thetaFav(v: FleetView, m: MicroSignals, trigger: Double, contracts: Int): List<FleetOrder> {
        if (v.tte < 90.0 || v.tte > 240.0) return emptyList()
        val fair = m.fairT ?: return emptyList()
        val side = if (fair >= 0.5) "YES" else "NO"
        val pf = sideP(fair, side)
        if (pf < trigger - 1e-9) return emptyList()
        val ask = askOf(v, side)
        if (ask != null && ask <= THETA_MAX_PRICE + 1e-9) {
            val fee = FleetMath.takerFeePer(contracts, ask)
            if (pf - ask - fee >= THETA_TAKE_EDGE - 1e-9) {
                return listOf(FleetOrder(side, true, ask, contracts, null, pf, "τ ${v.tte.toInt()} s · t-fair ${pct(pf)}¢ vs ask ${pct(ask)}¢"))
            }
        }
        val bid = bidOf(v, side) ?: return emptyList()
        val px = Fleet.floorTick(bid + 0.01).let { if (ask != null && it >= ask - 1e-9) bid else it }
        if (px > THETA_MAX_PRICE + 1e-9 || pf - px < THETA_REST_EDGE - 1e-9) return emptyList()
        return listOf(FleetOrder(side, false, px, contracts, null, pf, "τ ${v.tte.toInt()} s · t-fair ${pct(pf)}¢ · resting ${pct(px)}¢"))
    }
}
