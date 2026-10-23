package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** One market on another venue or series, as the fleet sees it (ask / bid in dollars of a contract or share). */
data class VenueQuote(
    val id: String,
    val yesAsk: Double?,
    val yesBid: Double?,
    val noAsk: Double?,
    val noBid: Double?,
    val strike: Double? = null,
    val closeMs: Long? = null,
    val askSizeYes: Double? = null,
    val askSizeNo: Double? = null
)

/** The Polymarket 15-minute Bitcoin up/down market for the same quarter hour. */
data class PolyQuote(val slug: String, val upAsk: Double?, val downAsk: Double?, val upAskSize: Double?, val downAskSize: Double?, val feeRate: Double, val updatedMs: Long)

/** Kalshi's Bitcoin perpetual (KXBTCPERP), dollars per contract of [btcPerContract] BTC. */
data class PerpQuote(val bid: Double, val ask: Double, val btcPerContract: Double, val takerFeeRate: Double, val updatedMs: Long) {
    val mid: Double get() = (bid + ask) / 2
}

/** Everything outside the Bitcoin 15-minute book: other venues, other series, the calendar, the forecaster. */
data class FleetExternal(
    val perp: PerpQuote? = null,
    val poly: PolyQuote? = null,
    /** Hourly KXBTCD markets settling at this window's close. */
    val hourly: List<VenueQuote> = emptyList(),
    /** The other coins' 15-minute markets closing with this window, by coin. */
    val alts: Map<String, VenueQuote> = emptyMap(),
    /** Per coin: (Bitcoin's, the coin's) returns over the same past 15-minute windows, oldest first. */
    val pairReturns: Map<String, Pair<DoubleArray, DoubleArray>> = emptyMap(),
    /** Per coin: the latest index value and its value at this window's open. */
    val altSpot: Map<String, Pair<Double, Double>> = emptyMap(),
    /** The most recent macro release at or before now (ms), and its name. */
    val lastMacro: Pair<Long, String>? = null,
    /** CF-style index from the venue books (scalper 14), and when it was made. */
    val nowcast: Pair<Double, Long>? = null,
    /** Venue trades' volume-weighted price per second, newest last (NaN for a second with no trade). */
    val vwap1s: DoubleArray = DoubleArray(0),
    /** TimesFM: quantiles q10..q90 of the index per second ahead (index 0 = 1 s ahead), and when they were made. */
    val forecast: Pair<Array<DoubleArray>, Long>? = null,
    /** Slow loop: market-quality weight 0..1 and who scored it. */
    val regime: Pair<Double, String>? = null,
    /** Hour-of-day volatility multiplier ω_h (σ this hour / σ over 24 h), and σ over 24 h (per √s, log). */
    val hourVol: Pair<Double, Double>? = null
)

/** One second of the Bitcoin 15-minute window as the fleet sees it. */
class FleetView(
    val ticker: String,
    val nowMs: Long,
    /** Seconds since the window opened, and to its close. */
    val s: Int,
    val tte: Double,
    val strike: Double?,
    /** Latest settlement index (BRTI) value. */
    val spot: Double?,
    /** Index std per √second, in log units. */
    val sigma1sLog: Double?,
    /** The index once a second over the last 10 minutes, newest last (NaN gaps). */
    val index1s: DoubleArray,
    /** The index prints already inside the final minute, one a second. */
    val finalPrints: DoubleArray,
    val yesBid: Double?, val yesAsk: Double?, val noBid: Double?, val noAsk: Double?,
    val yesBidQty: Double?, val yesAskQty: Double?, val noBidQty: Double?, val noAskQty: Double?,
    /** Bids, best (highest) first. */
    val yesLevels: List<Pair<Double, Double>>,
    val noLevels: List<Pair<Double, Double>>,
    /** YES mid per second over the last 120 s, newest last (NaN gaps). */
    val yesMids: DoubleArray,
    /** Mean seconds between trade prints over the last minute. */
    val tradeGap: Double?,
    /** YES fair price (the settlement model on the index). */
    val fairYes: Double?,
    /** VPIN over the last 20 equal-volume buckets, and the taker YES share of the last 3 buckets. */
    val vpin: Double?,
    val recentYesShare: Double?,
    /** Behavioural residual ε(P): quoted YES minus how often such YES won, by price (scalper 13). */
    val behavioural: (Double) -> Double?,
    /** Net YES contracts the strategy holds on this window (YES minus NO). */
    val inventory: (ScalpStrategy) -> Double,
    /** Prices the strategy already rests at on a side. */
    val restingAt: (ScalpStrategy, String) -> Set<Double>,
    val ext: FleetExternal
) {
    val yesMid: Double? get() = if (yesBid != null && yesAsk != null && yesBid < yesAsk) (yesBid + yesAsk) / 2 else null
}

/** A paper order a fleet scalper wants. Off-book legs (other venues) are carried in [offBook]. */
data class FleetOrder(
    val side: String,
    val taker: Boolean,
    val price: Double,
    val contracts: Int,
    /** Sell-back price for a scalp; null = the strategy's own target, or a hold to settlement. */
    val exitPrice: Double?,
    val pModel: Double,
    val note: String,
    val offBook: List<OffBookLeg> = emptyList(),
    /** False when the whole trade is on another market (scalper 8): nothing goes on the Bitcoin book. */
    val onBook: Boolean = true
)

/** A paper position on another market: held to its settlement (or, for the perp hedge, to the window's close). */
data class OffBookLeg(
    val venue: Venue,
    /** Market id: a Kalshi ticker, a Polymarket slug, or KXBTCPERP. */
    val market: String,
    /** "YES"/"NO" (Polymarket: YES = Up), or for the perp "YES" = long, "NO" = short. */
    val side: String,
    val price: Double,
    val contracts: Int,
    val feeUsd: Double,
    /** When to close a perp hedge (the window's close); settlements are looked up for the others. */
    val closeMs: Long
) {
    enum class Venue { KALSHI, POLYMARKET, PERP }
}

/**
 * The 20 scalpers' rules (the "20 profitable scalpers" plan). [decide] answers for one strategy and one second;
 * every order has passed the profitability gate ([FleetMath.passesGate]) and is sized at quarter Kelly
 * ([FleetMath.kellyContracts]) unless it is an arbitrage or a market-making quote (fixed size).
 */
object Fleet {
    const val TICK = 0.01
    const val FINE_TICK = 0.001
    /** Contracts per market-making quote; an arbitrage pair's size when a book shows no size (else the books' depth). */
    const val QUOTE_CONTRACTS = 10
    const val ARB_CONTRACTS = 25
    /** Venue data older than this is not traded on. */
    const val STALE_MS = 6_000L

    /** Halted until (ms): the VPIN breaker stops the passive makers (scalper 12). */
    @Volatile
    var makersHaltedUntilMs: Long = 0L

    val MAKERS: Set<ScalpStrategy> by lazy {
        setOf(ScalpStrategy.FLEET_AS_MAKER, ScalpStrategy.FLEET_LADDER, ScalpStrategy.FLEET_OBI, ScalpStrategy.FLEET_PIN_GAMMA)
    }

    /** Kalshi's tick at [p]: 0.1¢ under 10¢ and over 90¢, else 1¢. */
    fun tickAt(p: Double): Double = if (p < 0.10 - 1e-9 || p > 0.90 + 1e-9) FINE_TICK else TICK

    fun floorTick(p: Double): Double { val t = tickAt(p); return round(floor(p / t + 1e-9) * t * 10_000) / 10_000 }
    fun ceilTick(p: Double): Double { val t = tickAt(p); return round(kotlin.math.ceil(p / t - 1e-9) * t * 10_000) / 10_000 }

    private fun pSettle(v: FleetView, spot: Double, sigma1sLog: Double? = v.sigma1sLog): Double? {
        val k = v.strike ?: return null
        val sd = sigma1sLog?.takeIf { it > 0 } ?: return null
        val observed = if (v.tte < 60 && v.finalPrints.isNotEmpty()) v.finalPrints.map { ln(it) }.average() else null
        return DigitalOptionFairValue.pSettleAtLeast(
            spot = spot, strike = k, tteSeconds = v.tte, sigmaAnnual = sd * sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR),
            observedMeanLog = observed, indexNoise = 0.1e-4
        )
    }

    private fun bidOf(v: FleetView, side: String) = if (side == "YES") v.yesBid else v.noBid
    private fun askOf(v: FleetView, side: String) = if (side == "YES") v.yesAsk else v.noAsk
    private fun other(side: String) = if (side == "YES") "NO" else "YES"

    /** Taker order at the ask if it clears the gate; Kelly-sized. */
    private fun takeIfEdge(v: FleetView, side: String, pModel: Double, note: String, theta: Double = FleetMath.THETA_MIN, exit: Double? = null): FleetOrder? {
        val ask = askOf(v, side)?.takeIf { it > 0.0 && it < 1.0 } ?: return null
        val fee10 = FleetMath.takerFeePer(10, ask)
        if (!FleetMath.passesGate(pModel, ask, fee10, theta)) return null
        val c = FleetMath.kellyContracts(pModel, ask, fee10, FleetMath.BANKROLL_USD)
        if (c <= 0) return null
        val fee = FleetMath.takerFeePer(c, ask)
        if (!FleetMath.passesGate(pModel, ask, fee, theta)) return null
        return FleetOrder(side, true, ask, c, exit, pModel, note)
    }

    /** Resting bid at [price] (post-only: below the ask) if it clears the gate (no maker fee); Kelly-sized. */
    private fun restIfEdge(v: FleetView, side: String, price: Double, pModel: Double, note: String, theta: Double = FleetMath.THETA_MIN, exit: Double? = null, fixed: Int? = null): FleetOrder? {
        if (!(price > 0.0 && price < 1.0)) return null
        val ask = askOf(v, side)
        if (ask != null && price >= ask - 1e-9) return null
        if (!FleetMath.passesGate(pModel, price, 0.0, theta)) return null
        val c = fixed ?: FleetMath.kellyContracts(pModel, price, 0.0, FleetMath.BANKROLL_USD)
        if (c <= 0) return null
        return FleetOrder(side, false, price, c, exit, pModel, note)
    }

    fun decide(st: ScalpStrategy, v: FleetView): List<FleetOrder> = runCatching { decideOrThrow(st, v) }.getOrDefault(emptyList())

    private fun decideOrThrow(st: ScalpStrategy, v: FleetView): List<FleetOrder> = when (st) {
        ScalpStrategy.FLEET_LOCKED_IN -> lockedIn(v)
        ScalpStrategy.FLEET_SPIKE_FADE -> spikeFade(v)
        ScalpStrategy.FLEET_VWAP_SLOPE -> vwapSlope(v)
        ScalpStrategy.FLEET_TAIL_HARVEST -> tailHarvest(v)
        ScalpStrategy.FLEET_FAT_TAIL -> fatTail(v)
        ScalpStrategy.FLEET_NIGHT_THETA -> nightTheta(v)
        ScalpStrategy.FLEET_RV_IV -> rvIv(v)
        ScalpStrategy.FLEET_CROSS_ASSET -> crossAsset(v)
        ScalpStrategy.FLEET_AS_MAKER -> asMaker(v)
        ScalpStrategy.FLEET_LADDER -> ladder(v)
        ScalpStrategy.FLEET_OBI -> obi(v)
        ScalpStrategy.FLEET_VPIN_FLIP -> vpinFlip(v)
        ScalpStrategy.FLEET_NO_BIAS -> noBias(v)
        ScalpStrategy.FLEET_NOWCAST -> nowcast(v)
        ScalpStrategy.FLEET_POLY_ARB -> polyArb(v)
        ScalpStrategy.FLEET_PERP_HEDGE -> perpHedge(v)
        ScalpStrategy.FLEET_HOURLY_DOMINANCE -> hourlyDominance(v)
        ScalpStrategy.FLEET_MACRO_REVERT -> macroRevert(v)
        ScalpStrategy.FLEET_PIN_GAMMA -> pinGamma(v)
        ScalpStrategy.FLEET_TIMESFM -> timesFm(v)
        else -> emptyList()
    }.filter { !(it.offBook.isEmpty() && st in MAKERS && !it.taker && v.nowMs < makersHaltedUntilMs) }

    // 1 · Locked-in BRTI boundary: the final minute's banked prints fix most of the average.
    private fun lockedIn(v: FleetView): List<FleetOrder> {
        val k = v.strike ?: return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        if (v.finalPrints.size < 30) return emptyList()
        val last = v.finalPrints.last()
        val p = FleetMath.lockedInProbYes(k, v.finalPrints, last, sd * last) ?: return emptyList()
        val out = ArrayList<FleetOrder>()
        for ((side, pm) in listOf("YES" to p, "NO" to 1 - p)) {
            val note = "k=${v.finalPrints.size} K_req=${"%.2f".format(FleetMath.requiredAverage(k, v.finalPrints))} P=${"%.3f".format(pm)}"
            val taken = takeIfEdge(v, side, pm, "sweep · $note")
            if (taken != null) { out += taken; continue }
            val bid = bidOf(v, side) ?: continue
            restIfEdge(v, side, ceilTick(bid + tickAt(bid)), pm, "bid · $note")?.let { out += it }
        }
        return out
    }

    // 2 · Late-spike fade: a jump at print k moves the average only (60 − k)/60 of it.
    private fun spikeFade(v: FleetView): List<FleetOrder> {
        val k = v.finalPrints.size
        val sd = v.sigma1sLog ?: return emptyList()
        if (k < 2 || k > 55) return emptyList()
        val strike = v.strike ?: return emptyList()
        val last = v.finalPrints[k - 1]
        val prev = v.finalPrints[k - 2]
        val jump = last - prev
        if (abs(jump) < 3.0 * sd * last) return emptyList()
        val before = FleetMath.lockedInProbYes(strike, v.finalPrints.copyOf(k - 1), prev, sd * prev) ?: return emptyList()
        val after = FleetMath.lockedInProbYes(strike, v.finalPrints, last, sd * last) ?: return emptyList()
        val mids = v.yesMids
        val m1 = mids.lastOrNull()?.takeIf { it.isFinite() } ?: return emptyList()
        val m0 = mids.getOrNull(mids.size - 3)?.takeIf { it.isFinite() } ?: return emptyList()
        val marketMove = m1 - m0
        val fairMove = after - before
        val mis = marketMove - fairMove
        if (abs(mis) <= 0.03) return emptyList()
        // The market ran further than the settlement can: buy the other side, resting.
        val side = if (mis > 0) "NO" else "YES"
        val pm = if (side == "YES") after else 1 - after
        val bid = bidOf(v, side) ?: return emptyList()
        return listOfNotNull(restIfEdge(v, side, bid, pm, "fade ${"%+.3f".format(mis)} (market ${"%+.3f".format(marketMove)} vs fair ${"%+.3f".format(fairMove)})"))
    }

    // 3 · 30-second VWAP slope momentum.
    private fun vwapSlope(v: FleetView): List<FleetOrder> {
        val sd = v.sigma1sLog ?: return emptyList()
        val series = v.ext.vwap1s.takeIf { a -> a.count { it.isFinite() } >= 20 }?.takeLast30() ?: v.index1s.takeLast30()
        val (beta, se) = FleetMath.olsSlope(series) ?: return emptyList()
        if (se <= 0) return emptyList()
        val z = beta / se
        if (abs(z) <= 2.0) return emptyList()
        val now = v.spot ?: return emptyList()
        val projected = now + 30.0 * beta
        val p = pSettle(v, projected, sd) ?: return emptyList()
        val side = if (beta > 0) "YES" else "NO"
        val pm = if (side == "YES") p else 1 - p
        val bid = bidOf(v, side) ?: return emptyList()
        return listOfNotNull(restIfEdge(v, side, bid, pm, "Z ${"%.1f".format(z)} · projects ${"%.0f".format(projected)}"))
    }

    private fun DoubleArray.takeLast30(): DoubleArray = if (size <= 30) this else copyOfRange(size - 30, size)

    // 4 · Deci-cent tail harvester: rest 99.1–99.6¢ on the side the average can no longer lose.
    private fun tailHarvest(v: FleetView): List<FleetOrder> {
        val k = v.strike ?: return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val prints = v.finalPrints
        if (prints.isEmpty()) return emptyList()
        val last = prints.last()
        if (!FleetMath.tailLocked(k, prints, last, sd * last)) return emptyList()
        val side = if (last >= FleetMath.requiredAverage(k, prints)) "YES" else "NO"
        val bid = bidOf(v, side) ?: 0.990
        val price = ceilTick((bid + FINE_TICK).coerceIn(0.991, 0.996))
        // Reversal chance under 0.05%: the plan's bound. The gate here is 0.3¢ (the tail pays 0.4–0.9¢).
        return listOfNotNull(restIfEdge(v, side, price, 0.9995, "locked: needs > 6σ to turn", theta = 0.003, fixed = 100))
    }

    // 5 · Student-t fat tails: the wing priced off a normal is too cheap.
    private fun fatTail(v: FleetView): List<FleetOrder> {
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val tau = v.tte / 900.0
        val z = FleetMath.displacement(spot, k, sd * sqrt(900.0), tau)
        if (abs(z) <= 1.8 || abs(z) >= 4.0) return emptyList()
        val wing = if (z > 0) "NO" else "YES"
        // The plan's F_t(−|z|; ν = 4.2): the raw t, whose wing is the "10–50× the normal" it trades on.
        val tailT = FleetMath.studentTCdf(-abs(z), NU)
        val gauss = FleetMath.normCdf(-abs(z))
        val bid = bidOf(v, wing) ?: return emptyList()
        val price = min(bid, floorTick(tailT - 0.015))
        if (price <= 0.0) return emptyList()
        return listOfNotNull(restIfEdge(v, wing, price, tailT, "z ${"%.2f".format(z)} · t₄.₂ ${"%.4f".format(tailT)} vs normal ${"%.4f".format(gauss)}"))
    }

    // 6 · Overnight theta: in quiet hours the trailing side is overpriced by a 24-hour volatility.
    private fun nightTheta(v: FleetView): List<FleetOrder> {
        val (omega, sigma24) = v.ext.hourVol ?: return emptyList()
        if (omega >= 0.65) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        if (abs(spot / k - 1) < 0.0015) return emptyList()
        val p = pSettle(v, spot, sigma24 * omega) ?: return emptyList()
        val lead = if (spot >= k) "YES" else "NO"
        val pm = if (lead == "YES") p else 1 - p
        val bid = bidOf(v, lead) ?: return emptyList()
        return listOfNotNull(restIfEdge(v, lead, bid, pm, "ω_h ${"%.2f".format(omega)} · seasonal P ${"%.3f".format(pm)}"))
    }

    // 7 · Realized vs implied volatility.
    private val ivGaps = ArrayDeque<Double>()

    private fun rvIv(v: FleetView): List<FleetOrder> {
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        val mid = v.yesMid ?: return emptyList()
        val tau = v.tte / 900.0
        val iv = FleetMath.impliedVol15m(mid, spot, k, tau) ?: return emptyList()
        val px = v.index1s
        val r5 = ArrayList<Double>()
        var i = px.size - 1
        while (i - 5 >= 0) {
            if (px[i].isFinite() && px[i - 5].isFinite()) r5 += ln(px[i] / px[i - 5])
            i -= 5
        }
        val rv5 = FleetMath.ewVol(r5.reversed().toDoubleArray()) ?: return emptyList()
        val rv = rv5 * sqrt(180.0)
        val gap = iv - rv
        synchronized(ivGaps) {
            ivGaps.addLast(gap)
            while (ivGaps.size > 600) ivGaps.removeFirst()
        }
        val hist = synchronized(ivGaps) { ivGaps.toDoubleArray() }
        if (hist.size < 60) return emptyList()
        val m = hist.average()
        val sdGap = sqrt(hist.sumOf { (it - m) * (it - m) } / (hist.size - 1))
        if (sdGap <= 0) return emptyList()
        val itm = if (spot >= k) "YES" else "NO"
        val side = when {
            gap > 1.5 * sdGap -> itm
            -gap > 1.5 * sdGap -> other(itm)
            else -> return emptyList()
        }
        val pRv = FleetMath.normCdf(ln(spot / k) / (rv * sqrt(tau.coerceAtLeast(1e-6))))
        val pm = if (side == "YES") pRv else 1 - pRv
        val bid = bidOf(v, side) ?: return emptyList()
        return listOfNotNull(restIfEdge(v, side, bid, pm, "IV ${"%.4f".format(iv)} vs RV ${"%.4f".format(rv)}"))
    }

    // 8 · Cross-asset correlation acceleration: the thin altcoin books lag Bitcoin when correlation jumps.
    private fun crossAsset(v: FleetView): List<FleetOrder> {
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        val rBtc = spot / k - 1
        var best: FleetOrder? = null
        for ((coin, q) in v.ext.alts) {
            val (btc, alt) = v.ext.pairReturns[coin] ?: continue
            val n = min(alt.size, btc.size)
            if (n < 36) continue
            val a = alt.copyOfRange(alt.size - n, alt.size)
            val b = btc.copyOfRange(btc.size - n, btc.size)
            val rho = FleetMath.correlation(a.copyOfRange(n - 30, n), b.copyOfRange(n - 30, n)) ?: continue
            val rho5 = FleetMath.correlation(a.copyOfRange(n - 35, n - 5), b.copyOfRange(n - 35, n - 5)) ?: continue
            if (rho <= 0.85 || rho - rho5 < 0.25) continue
            val sA = sqrt(a.map { it * it }.average())
            val sB = sqrt(b.map { it * it }.average())
            val pred = FleetMath.conditionalReturn(rho, sA, sB, rBtc)
            val (altNow, altOpen) = v.ext.altSpot[coin] ?: continue
            val altStrike = q.strike ?: altOpen
            val projected = altOpen * (1 + pred)
            val tau = v.tte / 900.0
            val sdLeft = (sA * sqrt(tau)).coerceAtLeast(1e-6)
            val p = FleetMath.normCdf(ln(projected / altStrike) / sdLeft)
            for ((side, pm, ask) in listOf(Triple("YES", p, q.yesAsk), Triple("NO", 1 - p, q.noAsk))) {
                val a0 = ask?.takeIf { it > 0 && it < 1 } ?: continue
                val fee = FleetMath.takerFeePer(10, a0)
                if (!FleetMath.passesGate(pm, a0, fee)) continue
                val c = FleetMath.kellyContracts(pm, a0, fee, FleetMath.BANKROLL_USD)
                if (c <= 0) continue
                val edge = pm - a0 - fee
                if (best == null || edge > best.pModel - best.price) {
                    val leg = OffBookLeg(OffBookLeg.Venue.KALSHI, q.id, side, a0, c, KalshiFeeTotal(c, a0), q.closeMs ?: v.nowMs)
                    best = FleetOrder(side, true, a0, c, null, pm, onBook = false, note = "$coin ρ ${"%.2f".format(rho)} (Δ ${"%+.2f".format(rho - rho5)}) · BTC ${"%+.3f".format(rBtc * 100)}% → $coin now ${"%.4f".format(altNow)}", offBook = listOf(leg))
                }
            }
        }
        // One correlated bet at a time.
        return listOfNotNull(best)
    }

    private fun KalshiFeeTotal(c: Int, p: Double) = KalshiFee.total(c, p, KalshiFee.TAKER_COEFFICIENT)

    // 9 · Avellaneda–Stoikov inventory market maker.
    private fun asMaker(v: FleetView): List<FleetOrder> {
        val mid = v.yesMid ?: return emptyList()
        val sigma = midSigma(v.yesMids) ?: return emptyList()
        val q = v.inventory(ScalpStrategy.FLEET_AS_MAKER) / QUOTE_CONTRACTS
        val (r, delta) = FleetMath.avellanedaStoikov(mid, q, 0.2, sigma, v.tte, 1.5)
        val fair = v.fairYes ?: mid
        val out = ArrayList<FleetOrder>()
        val yesBid = floorTick(r - delta / 2)
        val yesExit = ceilTick(r + delta / 2)
        if (v.restingAt(ScalpStrategy.FLEET_AS_MAKER, "YES").isEmpty()) {
            restIfEdge(v, "YES", yesBid, fair, "r ${"%.3f".format(r)} δ ${"%.3f".format(delta)} q ${"%.1f".format(q)}", exit = yesExit, fixed = QUOTE_CONTRACTS)?.let { out += it }
        }
        val noBid = floorTick(1.0 - (r + delta / 2))
        val noExit = ceilTick(1.0 - (r - delta / 2))
        if (v.restingAt(ScalpStrategy.FLEET_AS_MAKER, "NO").isEmpty()) {
            restIfEdge(v, "NO", noBid, 1 - fair, "r ${"%.3f".format(r)} δ ${"%.3f".format(delta)} q ${"%.1f".format(q)}", exit = noExit, fixed = QUOTE_CONTRACTS)?.let { out += it }
        }
        return out
    }

    /** Std per √second of the YES mid over [mids] (seconds with a quote both ends). */
    fun midSigma(mids: DoubleArray): Double? {
        var n = 0
        var ss = 0.0
        for (i in 1 until mids.size) {
            val a = mids[i - 1]; val b = mids[i]
            if (a.isFinite() && b.isFinite()) { ss += (b - a) * (b - a); n++ }
        }
        return if (n < 30) null else sqrt(ss / n).coerceAtLeast(0.0005)
    }

    // 10 · Seven-rung post-only ladder, first 10 minutes, while volatility is steady.
    private fun ladder(v: FleetView): List<FleetOrder> {
        val mids = v.yesMids
        // Steady: the last minute's volatility within 0.5–1.5× the minute before it.
        val half = mids.size / 2
        val recent = midSigma(mids.copyOfRange(half, mids.size)) ?: return emptyList()
        val before = midSigma(mids.copyOfRange(0, half + 1)) ?: return emptyList()
        if (recent / before !in 0.5..1.5) return emptyList()
        val out = ArrayList<FleetOrder>()
        val fair = v.fairYes ?: v.yesMid ?: return emptyList()
        for (side in listOf("YES", "NO")) {
            val best = bidOf(v, side) ?: continue
            val pSide = if (side == "YES") fair else 1 - fair
            val have = v.restingAt(ScalpStrategy.FLEET_LADDER, side)
            for (m in 0..6) {
                val (p, exit) = FleetMath.ladderRung(best, m)
                val px = floorTick(p)
                if (px <= 0.0 || have.any { abs(it - px) < 1e-6 }) continue
                restIfEdge(v, side, px, pSide, "rung $m", exit = ceilTick(exit), fixed = QUOTE_CONTRACTS)?.let { out += it }
            }
        }
        return out
    }

    // 11 · L2 order-book imbalance, top three levels.
    private fun obi(v: FleetView): List<FleetOrder> {
        val yb = v.yesBid ?: return emptyList()
        val ya = v.yesAsk ?: return emptyList()
        if (ya - yb < 0.03 - 1e-9) return emptyList()
        val o = FleetMath.obi(v.yesLevels, v.noLevels) ?: return emptyList()
        val micro = FleetMath.microPrice(yb, v.yesBidQty ?: 0.0, ya, v.yesAskQty ?: 0.0)
        // The 20-second expectation: the micro-price's lean off the mid, carried forward once more.
        val ahead = (micro + (micro - (yb + ya) / 2)).coerceIn(0.0, 1.0)
        return when {
            o > 0.65 -> listOfNotNull(restIfEdge(v, "YES", yb + 0.01, ahead, "OBI ${"%+.2f".format(o)} · micro ${"%.3f".format(micro)}", fixed = QUOTE_CONTRACTS))
            o < -0.65 -> {
                val nb = v.noBid ?: return emptyList()
                listOfNotNull(restIfEdge(v, "NO", nb + 0.01, 1 - ahead, "OBI ${"%+.2f".format(o)} · micro ${"%.3f".format(micro)}", fixed = QUOTE_CONTRACTS))
            }
            else -> emptyList()
        }
    }

    // 12 · VPIN toxicity: halt the makers and go with the informed flow.
    private fun vpinFlip(v: FleetView): List<FleetOrder> {
        val vp = v.vpin ?: return emptyList()
        if (vp <= 0.70) return emptyList()
        makersHaltedUntilMs = max(makersHaltedUntilMs, v.nowMs + 30_000L)
        val share = v.recentYesShare ?: return emptyList()
        val side = if (share >= 0.5) "YES" else "NO"
        val mids = v.yesMids
        val now = mids.lastOrNull()?.takeIf { it.isFinite() } ?: return emptyList()
        val then = mids.getOrNull(mids.size - 21)?.takeIf { it.isFinite() } ?: return emptyList()
        val velocity = (now - then) / 20.0 * (if (side == "YES") 1 else -1)
        val expected = velocity * 20.0
        val ask = askOf(v, side) ?: return emptyList()
        val bid = bidOf(v, side) ?: return emptyList()
        val fee = FleetMath.takerFeePer(10, ask)
        if (expected <= fee + (ask - bid)) return emptyList()
        return listOfNotNull(takeIfEdge(v, side, ask + expected, "VPIN ${"%.2f".format(vp)} · takers ${(share * 100).toInt()}% YES · +${"%.3f".format(expected)} in 20 s"))
    }

    // 13 · Behavioural YES over-betting: lottery YES under the strike wins less often than its price says.
    private fun noBias(v: FleetView): List<FleetOrder> {
        val mid = v.yesMid ?: return emptyList()
        if (mid !in 0.10..0.25) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        if (spot >= k) return emptyList()
        val eps = v.behavioural(mid) ?: return emptyList()
        if (eps < 0.025) return emptyList()
        val nb = v.noBid ?: return emptyList()
        val pm = 1 - (mid - eps)
        return listOfNotNull(restIfEdge(v, "NO", nb, pm, "ε ${"%.3f".format(eps)} at YES ${"%.2f".format(mid)}"))
    }

    // 14 · Venue-book index nowcast: the CF-style index from the exchange books, ahead of the index tick.
    private fun nowcast(v: FleetView): List<FleetOrder> {
        val (nc, at) = v.ext.nowcast ?: return emptyList()
        if (v.nowMs - at > 2_000L) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        // Only when the nowcast and the last index tick sit on opposite sides of the strike.
        if ((nc >= k) == (spot >= k)) return emptyList()
        val p = pSettle(v, nc) ?: return emptyList()
        val side = if (nc >= k) "YES" else "NO"
        val pm = if (side == "YES") p else 1 - p
        return listOfNotNull(takeIfEdge(v, side, pm, "nowcast ${"%.1f".format(nc)} vs index ${"%.1f".format(spot)} · K ${"%.1f".format(k)}"))
    }

    // 15 · Kalshi–Polymarket synthetic: YES here + Down there (or NO + Up) for less than $1.
    private fun polyArb(v: FleetView): List<FleetOrder> {
        val poly = v.ext.poly ?: return emptyList()
        if (v.nowMs - poly.updatedMs > STALE_MS) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        // Kalshi settles on BRTI, Polymarket on Chainlink: only trade when the price is clear of the strike.
        if (abs(spot / k - 1) <= 0.002) return emptyList()
        val gas = 0.002
        val basis = 0.005
        val out = ArrayList<FleetOrder>()
        for ((kSide, polyAsk, polySize, polySide) in listOf(
            Quad("YES", poly.downAsk, poly.downAskSize, "NO"),
            Quad("NO", poly.upAsk, poly.upAskSize, "YES")
        )) {
            val ka = askOf(v, kSide)?.takeIf { it > 0 && it < 1 } ?: continue
            val pa = polyAsk?.takeIf { it > 0 && it < 1 } ?: continue
            // As many pairs as both books show at those asks: no upper limit.
            val kSize = (if (kSide == "YES") v.yesAskQty else v.noAskQty) ?: ARB_CONTRACTS.toDouble()
            val c = min(kSize, polySize ?: 0.0).toInt()
            if (c <= 0) continue
            val kFee = FleetMath.takerFeePer(c, ka)
            val pFee = FleetMath.polymarketFeePer(pa, poly.feeRate)
            val total = ka + kFee + pa + pFee + gas + basis
            if (1.0 - total <= 0.014) continue
            val leg = OffBookLeg(OffBookLeg.Venue.POLYMARKET, poly.slug, polySide, pa, c, pFee * c + gas * c, v.nowMs + (v.tte * 1000).toLong())
            out += FleetOrder(kSide, true, ka, c, null, 1.0 - pa - pFee, "pair $kSide ${"%.3f".format(ka)} + Poly ${if (polySide == "YES") "Up" else "Down"} ${"%.3f".format(pa)} = ${"%.3f".format(total)} with costs", listOf(leg))
        }
        return out
    }

    private data class Quad(val a: String, val b: Double?, val c: Double?, val d: String)

    // 16 · Binary vs perp: rest on the binary the Student-t says is cheap; hedge its delta with KXBTCPERP on fill.
    private fun perpHedge(v: FleetView): List<FleetOrder> {
        val perp = v.ext.perp ?: return emptyList()
        if (v.nowMs - perp.updatedMs > STALE_MS) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val mid = v.yesMid ?: return emptyList()
        val tau = v.tte / 900.0
        val s15 = sd * sqrt(900.0)
        val fairT = FleetMath.studentTCdf(FleetMath.displacement(spot, k, s15, tau), NU)
        val mis = fairT - mid
        if (abs(mis) <= 0.02) return emptyList()
        val side = if (mis > 0) "YES" else "NO"
        val pm = if (side == "YES") fairT else 1 - fairT
        val bid = bidOf(v, side) ?: return emptyList()
        val o = restIfEdge(v, side, bid, pm, "t-fair ${"%.3f".format(fairT)} vs mid ${"%.3f".format(mid)}") ?: return emptyList()
        val delta = FleetMath.binaryDelta(spot, k, s15, tau, NU)
        // Hedge notional −C·Δ·S in Bitcoin, as perp contracts (opened when the binary fills).
        val btc = o.contracts * delta * (if (side == "YES") 1 else -1)
        val perpContracts = round(abs(btc) / perp.btcPerContract).toInt()
        if (perpContracts <= 0) return listOf(o)
        val hedgeSide = if (btc > 0) "NO" else "YES"
        val px = if (hedgeSide == "YES") perp.ask else perp.bid
        val leg = OffBookLeg(OffBookLeg.Venue.PERP, "KXBTCPERP", hedgeSide, px, perpContracts, px * perpContracts * perp.takerFeeRate, v.nowMs + (v.tte * 1000).toLong())
        return listOf(o.copy(offBook = listOf(leg), note = o.note + " · hedge ${if (hedgeSide == "YES") "long" else "short"} $perpContracts perp"))
    }

    // 17 · 15-minute vs hourly: the same settlement, nested strikes, bought for under $1.
    private fun hourlyDominance(v: FleetView): List<FleetOrder> {
        val k15 = v.strike ?: return emptyList()
        val out = ArrayList<FleetOrder>()
        for (h in v.ext.hourly) {
            val k1 = h.strike ?: continue
            // K15 ≤ K1h: {S ≥ K1h} ⊂ {S ≥ K15} → 15m YES + 1h NO always pays at least $1.
            // K15 ≥ K1h: {S ≥ K15} ⊂ {S ≥ K1h} → 15m NO + 1h YES.
            val (kSide, hSide, hAsk) = if (k15 <= k1) Triple("YES", "NO", h.noAsk) else Triple("NO", "YES", h.yesAsk)
            val ka = askOf(v, kSide)?.takeIf { it > 0 && it < 1 } ?: continue
            val ha = hAsk?.takeIf { it > 0 && it < 1 } ?: continue
            // As many pairs as both books show at those asks: no upper limit.
            val kSize = (if (kSide == "YES") v.yesAskQty else v.noAskQty) ?: ARB_CONTRACTS.toDouble()
            val hSize = (if (hSide == "YES") h.askSizeYes else h.askSizeNo) ?: ARB_CONTRACTS.toDouble()
            val c = min(kSize, hSize).toInt()
            if (c <= 0) continue
            val total = ka + FleetMath.takerFeePer(c, ka) + ha + FleetMath.takerFeePer(c, ha)
            if (1.0 - total < FleetMath.THETA_MIN) continue
            val leg = OffBookLeg(OffBookLeg.Venue.KALSHI, h.id, hSide, ha, c, KalshiFeeTotal(c, ha), h.closeMs ?: v.nowMs)
            out += FleetOrder(kSide, true, ka, c, null, 1.0 - ha, "15m $kSide @ K ${"%.2f".format(k15)} + 1h $hSide @ K ${"%.2f".format(k1)} = ${"%.3f".format(total)}", listOf(leg))
            break
        }
        return out
    }

    // 18 · Macro-print overshoot reversal.
    private fun macroRevert(v: FleetView): List<FleetOrder> {
        val (at, name) = v.ext.lastMacro ?: return emptyList()
        val since = (v.nowMs - at) / 1000.0
        if (since < 0 || since > 600) return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val spot = v.spot ?: return emptyList()
        val idx = v.index1s
        val back = (since + 5).toInt()
        val pre = idx.getOrNull(idx.size - 1 - back)?.takeIf { it.isFinite() } ?: return emptyList()
        val e = abs(spot - pre) / (pre * sd * sqrt(since.coerceAtLeast(1.0)))
        if (e <= 2.5 || since > 300) return emptyList()
        val ya = v.yesAsk ?: return emptyList()
        val yb = v.yesBid ?: return emptyList()
        if (ya - yb < 0.05 - 1e-9) return emptyList()
        val m = v.yesMids
        val n = m.size
        if (n < 21) return emptyList()
        val d1 = m[n - 1] - m[n - 11]
        val d0 = m[n - 11] - m[n - 21]
        if (!d1.isFinite() || !d0.isFinite() || !(d1 * d0 <= 0 || abs(d1) < 0.2 * abs(d0))) return emptyList()
        val reverted = pre + (spot - pre) * (1 - KAPPA_POST_PRINT)
        val p = pSettle(v, reverted) ?: return emptyList()
        // Whichever side the market overshot against the reverted price, bid 1¢ inside the blown-out spread.
        return listOf("YES" to p, "NO" to 1 - p).mapNotNull { (side, pm) ->
            bidOf(v, side)?.let { restIfEdge(v, side, it + 0.01, pm, "$name +${since.toInt()} s · E ${"%.1f".format(e)} · reverts to ${"%.0f".format(reverted)}") }
        }
    }

    // 19 · Near-even final-3-minute pin: fast two-sided quotes around the fair price.
    private fun pinGamma(v: FleetView): List<FleetOrder> {
        if (v.tte > 180) return emptyList()
        val spot = v.spot ?: return emptyList()
        val k = v.strike ?: return emptyList()
        if (abs(spot / k - 1) >= 0.0015) return emptyList()
        val mid = v.yesMid ?: return emptyList()
        if (mid !in 0.42..0.58) return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val fair = v.fairYes ?: return emptyList()
        val up = pSettle(v, spot + 1.0) ?: return emptyList()
        val dn = pSettle(v, spot - 1.0) ?: return emptyList()
        val delta = FleetMath.pinSpread((up - dn) / 2.0, spot, sd, v.tradeGap ?: 1.0)
        val out = ArrayList<FleetOrder>()
        for ((side, f) in listOf("YES" to fair, "NO" to 1 - fair)) {
            if (v.restingAt(ScalpStrategy.FLEET_PIN_GAMMA, side).isNotEmpty()) continue
            val px = floorTick(f - delta / 2)
            restIfEdge(v, side, px, f, "δ ${"%.3f".format(delta)} around ${"%.3f".format(f)}", exit = ceilTick(f + delta / 2), fixed = QUOTE_CONTRACTS)?.let { out += it }
        }
        return out
    }

    // 20 · TimesFM tick forecaster with a slow regime gate.
    private fun timesFm(v: FleetView): List<FleetOrder> {
        val (q, madeAt) = v.ext.forecast ?: return emptyList()
        val (w, by) = v.ext.regime ?: return emptyList()
        if (w < 0.60) return emptyList()
        val k = v.strike ?: return emptyList()
        val sd = v.sigma1sLog ?: return emptyList()
        val spot = v.spot ?: return emptyList()
        // The settlement averages the last minute: aim at its middle, 30 s before the close.
        val ahead = (v.tte - 30.0 + (v.nowMs - madeAt) / 1000.0).coerceAtLeast(1.0)
        val step = min(ahead.toInt(), q.size).coerceAtLeast(1)
        val extra = if (ahead > q.size) (spot * sd) * (spot * sd) * (ahead - q.size) else 0.0
        val p = FleetMath.probAboveFromQuantiles(q[step - 1], k, extra) ?: return emptyList()
        val out = ArrayList<FleetOrder>()
        for ((side, pm) in listOf("YES" to p, "NO" to 1 - p)) {
            val note = "TimesFM P ${"%.3f".format(pm)} · regime ${"%.2f".format(w)} ($by)"
            val o = if (v.tte > 240) {
                bidOf(v, side)?.let { restIfEdge(v, side, it, pm, "rest · $note", theta = 0.015) }
            } else {
                takeIfEdge(v, side, pm, "take · $note", theta = 0.015)
            }
            o?.let { out += it }
        }
        return out
    }

    /** Student-t degrees of freedom for 15-minute Bitcoin returns (the plan's ν = 4.2). */
    const val NU = 4.2
    /** Share of a post-print overshoot that reverts in the next 10 minutes (the plan's κ). */
    const val KAPPA_POST_PRINT = 0.18
}
