package com.dirk.kalshiodds.decision

import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Paper fleet s01–s20. One shared profitability gate, a 250 ms book-staleness guard,
 * and an in-process router that drops crossing self-trades.
 * KXBTC15M, KXETH15M, and KXSOL15M share the same signals. External venues stay stubs
 * until a feed is attached. Nothing in this file places a live order.
 */
object TwentyScalperFleet {
    const val STALE_MS = 250L
    const val THETA_MIN = 0.01
    const val FEE_M = 0.07
    const val QUARTER = 0.25
    const val MAX_CLIP = 25

    enum class Category { TERMINAL_BRTI, NON_GAUSSIAN, LOB_MM, CROSS_VENUE, EVENT_HYBRID }

    data class Spec(
        val id: String,
        val label: String,
        val blurb: String,
        val category: Category,
        /** No live feed. The signal stays quiet unless a paper stub input is supplied. */
        val externalStub: Boolean
    )

    val ALL: List<Spec> = listOf(
        Spec("s01-brti-bound", "s01 BRTI bound", "Last minute. Student-t fair of BRTI spot versus the strike. Paper only.", Category.TERMINAL_BRTI, false),
        Spec("s02-spike-fade", "s02 Spike fade", "Last two minutes. Fade a spot shock larger than about 2σ. Paper only.", Category.TERMINAL_BRTI, false),
        Spec("s03-vwap-slope", "s03 VWAP slope", "Trade with a steep short VWAP slope inside three minutes. Paper only.", Category.TERMINAL_BRTI, false),
        Spec("s04-deci-tail", "s04 Decile tail", "Final 90s. Fade a moneyness move past the 10% Student-t tail. Paper only.", Category.TERMINAL_BRTI, false),
        Spec("s05-student-tail", "s05 Student-t tail", "ν=4.2 fair versus the touch on BTC, ETH, and SOL 15m. Paper only.", Category.NON_GAUSSIAN, false),
        Spec("s06-diurnal-theta", "s06 Diurnal theta", "US-hours vol scale versus the quiet-hours scale. Paper only.", Category.NON_GAUSSIAN, false),
        Spec("s07-rv-iv-disp", "s07 RV–IV dispersion", "Realized spot vol versus the contract's implied move. Paper only.", Category.NON_GAUSSIAN, false),
        Spec("s08-corr-accel", "s08 Corr accel", "BTC/ETH return correlation speeding up. Quiet until both coins have ticks. Paper only.", Category.NON_GAUSSIAN, false),
        Spec("s09-as-mm", "s09 AS-MM", "Avellaneda–Stoikov reservation through the touch. Paper only.", Category.LOB_MM, false),
        Spec("s10-lob-ladder", "s10 LOB ladder", "Top-of-book depth ratio on the YES and NO ladders. Paper only.", Category.LOB_MM, false),
        Spec("s11-obi-micro", "s11 OBI micro", "Microprice minus mid. Paper only.", Category.LOB_MM, false),
        Spec("s12-vpin-flip", "s12 VPIN flip", "Volume-sync imbalance that changes sign. Paper only.", Category.LOB_MM, false),
        Spec("s13-no-surplus", "s13 NO surplus", "NO-bid size dominates the touch and YES is cheap to fair. Paper only.", Category.LOB_MM, false),
        Spec("s14-brti-nowcast", "s14 BRTI nowcast", "CF/BRTI fair versus spot fair. Stub until cfSpot is fresh. Paper only.", Category.CROSS_VENUE, false),
        Spec("s15-poly-arb", "s15 Poly arb", "Kalshi versus an external YES ask. Stub with no Polymarket feed. Paper only.", Category.CROSS_VENUE, true),
        Spec("s16-perp-delta", "s16 Perp delta", "Perp basis versus the strike. Stub with no perp feed. Paper only.", Category.CROSS_VENUE, true),
        Spec("s17-bracket-1h", "s17 Bracket 1h", "Hourly bracket only. 15m tickers do not trade it. Paper only.", Category.CROSS_VENUE, true),
        Spec("s18-macro-rev", "s18 Macro reversal", "Fade a supplied macro z-score. Stub with no calendar feed. Paper only.", Category.EVENT_HYBRID, true),
        Spec("s19-gamma-pin", "s19 Gamma pin", "Near the strike, buy a contract priced through fair. Paper only.", Category.EVENT_HYBRID, false),
        Spec("s20-timesfm-gate", "s20 TimesFM gate", "External forecast probability. Stub until a TimesFM p(YES) is attached. Paper only.", Category.EVENT_HYBRID, true)
    )

    init {
        require(ALL.size == 20)
        require(ALL.map { it.id }.distinct().size == 20)
        require(ALL.map { it.id.take(3) } == (1..20).map { "s%02d".format(it) })
    }

    val IDS: List<String> = ALL.map { it.id }
    private val ID_SET: Set<String> = IDS.toSet()
    private val BY_ID: Map<String, Spec> = ALL.associateBy { it.id }

    fun isFleet(id: String?): Boolean = id != null && id in ID_SET
    fun spec(id: String): Spec? = BY_ID[id]
    fun labelOf(id: String): String? = BY_ID[id]?.label

    /** KXBTC15M / KXETH15M / KXSOL15M, or BRACKET_1H when the ticker is an hourly bracket. */
    fun seriesOf(ticker: String): String? {
        val u = ticker.uppercase()
        return when {
            u.startsWith("KXBTC15M") -> "KXBTC15M"
            u.startsWith("KXETH15M") -> "KXETH15M"
            u.startsWith("KXSOL15M") -> "KXSOL15M"
            u.contains("1H") || u.contains("60M") -> "BRACKET_1H"
            else -> null
        }
    }

    fun listedOn(id: String, ticker: String): Boolean {
        val spec = BY_ID[id] ?: return false
        val series = seriesOf(ticker) ?: return false
        return if (spec.id == "s17-bracket-1h") series == "BRACKET_1H"
        else series == "KXBTC15M" || series == "KXETH15M" || series == "KXSOL15M"
    }

    /** YES ask implied by the best NO bid. NO ask is the mirror: 1 − best YES bid. */
    fun impliedAskFromBestNo(bestNoBid: Double): Double? {
        if (!bestNoBid.isFinite() || bestNoBid <= 0.0 || bestNoBid >= 1.0) return null
        return 1.0 - bestNoBid
    }

    /** Quadratic taker fee for one order, ceiled to the cent. m defaults to 0.07. */
    fun quadraticFeeUsd(contracts: Int, price: Double, m: Double = FEE_M): Double {
        if (contracts <= 0 || m <= 0.0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        val raw = m * contracts * price * (1.0 - price)
        return ceil(raw * 100.0 - 1e-12) / 100.0
    }

    data class Level(val price: Double, val size: Double)

    data class Walk(val filled: Int, val avg: Double, val slippage: Double, val notional: Double)

    /** Lift [qty] contracts from the ask ladder (best first). Slippage is avg − touch. */
    fun bookWalk(levels: List<Level>, qty: Int): Walk? {
        if (qty <= 0) return null
        val usable = levels.filter { it.price > 0.0 && it.price < 1.0 && it.size >= 1.0 }
        if (usable.isEmpty()) return null
        val touch = usable.first().price
        var left = qty
        var notional = 0.0
        var filled = 0
        for (lv in usable) {
            if (left <= 0) break
            val take = min(left, floor(lv.size).toInt())
            if (take <= 0) continue
            notional += take * lv.price
            filled += take
            left -= take
        }
        if (filled <= 0) return null
        val avg = notional / filled
        return Walk(filled, avg, avg - touch, notional)
    }

    /**
     * Full Kelly for a $1 binary is (p − c) / (1 − c), with c the all-in cost per contract.
     * Size is a quarter of that, in contracts, capped at [MAX_CLIP].
     */
    fun quarterKellyContracts(pWin: Double, costPerContract: Double, bankroll: Double, cap: Int = MAX_CLIP): Int {
        if (!pWin.isFinite() || !costPerContract.isFinite() || !bankroll.isFinite()) return 0
        if (pWin <= 0.0 || costPerContract <= 0.0 || costPerContract >= 1.0 || bankroll <= 0.0) return 0
        val edge = pWin - costPerContract
        if (edge <= 0.0) return 0
        val f = edge / (1.0 - costPerContract)
        val dollars = bankroll * QUARTER * f
        return floor(dollars / costPerContract).toInt().coerceIn(0, cap)
    }

    fun netEvPerContract(pWin: Double, avgFill: Double, contracts: Int): Double {
        if (contracts <= 0 || !pWin.isFinite() || !avgFill.isFinite()) return Double.NEGATIVE_INFINITY
        val fee = quadraticFeeUsd(contracts, avgFill) / contracts
        return pWin - avgFill - fee
    }

    fun bookStale(bookAgeMs: Long, gap: Boolean = false): Boolean =
        gap || bookAgeMs < 0L || bookAgeMs >= STALE_MS

    data class Gate(
        val pass: Boolean,
        val reason: String,
        val side: String,
        val pWin: Double,
        val touch: Double,
        val avgFill: Double,
        val slippage: Double,
        val feeUsd: Double,
        val netEv: Double,
        val contracts: Int
    )

    /**
     * Shared entry gate. Stale books fail closed. A pass needs a walkable ask,
     * net EV per contract ≥ [thetaMin], and a positive quarter-Kelly size.
     */
    fun evaluate(
        side: String,
        pWin: Double,
        ladder: List<Level>,
        bookAgeMs: Long,
        bankroll: Double,
        thetaMin: Double = THETA_MIN,
        gap: Boolean = false
    ): Gate {
        val blank = Gate(false, "no trade", side, pWin, 0.0, 0.0, 0.0, 0.0, Double.NEGATIVE_INFINITY, 0)
        if (bookStale(bookAgeMs, gap)) return blank.copy(reason = "stale book")
        if (side != "YES" && side != "NO") return blank.copy(reason = "bad side")
        if (!pWin.isFinite() || pWin <= 0.0 || pWin >= 1.0) return blank.copy(reason = "bad probability")
        val touchWalk = bookWalk(ladder, 1) ?: return blank.copy(reason = "no ask")
        val cost1 = touchWalk.avg + quadraticFeeUsd(1, touchWalk.avg)
        val want = quarterKellyContracts(pWin, cost1, bankroll)
        if (want < 1) return blank.copy(reason = "quarter-kelly zero", touch = touchWalk.avg, avgFill = touchWalk.avg)
        var qty = want
        var walked = bookWalk(ladder, qty) ?: return blank.copy(reason = "no ask", touch = touchWalk.avg)
        var ev = netEvPerContract(pWin, walked.avg, walked.filled)
        while (walked.filled > 1 && ev < thetaMin) {
            qty = walked.filled - 1
            walked = bookWalk(ladder, qty) ?: break
            ev = netEvPerContract(pWin, walked.avg, walked.filled)
        }
        val fee = quadraticFeeUsd(walked.filled, walked.avg)
        if (ev < thetaMin || walked.filled < 1) {
            return blank.copy(
                reason = "ev below theta",
                touch = touchWalk.avg,
                avgFill = walked.avg,
                slippage = walked.slippage,
                feeUsd = fee,
                netEv = ev,
                contracts = 0
            )
        }
        return Gate(
            pass = true,
            reason = "pass",
            side = side,
            pWin = pWin,
            touch = touchWalk.avg,
            avgFill = walked.avg,
            slippage = walked.slippage,
            feeUsd = fee,
            netEv = ev,
            contracts = walked.filled
        )
    }

    data class SignalCtx(
        val ticker: String = "KXBTC15M-TEST",
        val tauS: Double = 400.0,
        val spot: Double = 100_000.0,
        val strike: Double = 100_000.0,
        val sigmaPerSec: Double = 3e-5,
        val fairYes: Double = 0.5,
        val yesBid: Double = 0.49,
        val yesAsk: Double = 0.51,
        val yesBidSz: Double = 10.0,
        val yesAskSz: Double = 10.0,
        val vwapZ: Double = 0.0,
        val spikeZ: Double = 0.0,
        val rvRatio: Double = 1.0,
        val reservation: Double = 0.5,
        val ladderRatio: Double = 1.0,
        val microEdge: Double = 0.0,
        val vpin: Double = 0.0,
        val vpinFlip: Boolean = false,
        val flowSign: Int = 0,
        val noSurplus: Double = 0.5,
        val corrAccel: Double = 0.0,
        val hourUtc: Int = 12,
        val gamma: Double = 0.0,
        val z: Double = 0.0,
        val cfFairYes: Double? = null,
        val polyYesAsk: Double? = null,
        val perpBasis: Double? = null,
        val macroZ: Double? = null,
        val timesFmYes: Double? = null,
        val bracket1h: Boolean = false
    )

    data class Signal(val side: String, val pWin: Double)

    /** One scalper's paper view. Null means no trade, including a stub with no feed. */
    fun signal(id: String, ctx: SignalCtx): Signal? {
        if (!listedOn(id, ctx.ticker)) return null
        if (ctx.tauS <= 0.0) return null
        return when (id) {
            "s01-brti-bound" -> {
                if (ctx.tauS > 60.0 || abs(ctx.fairYes - 0.5) < 0.08) null
                else sideOf(ctx.fairYes)
            }
            "s02-spike-fade" -> {
                if (ctx.tauS > 120.0 || abs(ctx.spikeZ) < 2.2) null
                else if (ctx.spikeZ > 0) Signal("NO", (0.55 + 0.04 * min(3.0, ctx.spikeZ)).coerceAtMost(0.85))
                else Signal("YES", (0.55 + 0.04 * min(3.0, -ctx.spikeZ)).coerceAtMost(0.85))
            }
            "s03-vwap-slope" -> {
                if (ctx.tauS > 180.0 || abs(ctx.vwapZ) < 1.8) null
                else if (ctx.vwapZ > 0) Signal("YES", (0.55 + 0.03 * min(4.0, ctx.vwapZ)).coerceAtMost(0.85))
                else Signal("NO", (0.55 + 0.03 * min(4.0, -ctx.vwapZ)).coerceAtMost(0.85))
            }
            "s04-deci-tail" -> {
                if (ctx.tauS > 90.0 || abs(ctx.z) < 1.2816) null
                else if (ctx.z > 0) Signal("NO", 0.62) else Signal("YES", 0.62)
            }
            "s05-student-tail" -> {
                val yesEdge = ctx.fairYes - ctx.yesAsk
                val noAsk = (1.0 - ctx.yesBid).coerceIn(0.01, 0.99)
                val noEdge = (1.0 - ctx.fairYes) - noAsk
                when {
                    yesEdge >= noEdge && yesEdge > 0.02 -> Signal("YES", ctx.fairYes.coerceIn(0.02, 0.98))
                    noEdge > 0.02 -> Signal("NO", (1.0 - ctx.fairYes).coerceIn(0.02, 0.98))
                    else -> null
                }
            }
            "s06-diurnal-theta" -> {
                if (ctx.spot <= 0.0 || ctx.strike <= 0.0) null
                else {
                    val scale = if (ctx.hourUtc in 13..20) 1.25 else 0.80
                    val scaled = HftMathEngines.fairProbability(ctx.spot, ctx.strike, ctx.sigmaPerSec * scale, ctx.tauS)
                    if (abs(scaled - ctx.fairYes) < 0.03) null else sideOf(scaled)
                }
            }
            "s07-rv-iv-disp" -> {
                if (ctx.rvRatio < 1.35 || ctx.spot <= 0.0 || ctx.strike <= 0.0) null
                else if (ctx.spot >= ctx.strike) Signal("YES", min(0.90, ctx.fairYes + 0.04).coerceIn(0.05, 0.95))
                else Signal("NO", min(0.90, 1.0 - ctx.fairYes + 0.04).coerceIn(0.05, 0.95))
            }
            "s08-corr-accel" -> {
                if (abs(ctx.corrAccel) < 0.08) null
                else if (ctx.corrAccel > 0) sideOf(ctx.fairYes) else sideOf(1.0 - ctx.fairYes)
            }
            "s09-as-mm" -> when {
                ctx.reservation > ctx.yesAsk + 0.01 -> Signal("YES", ctx.reservation.coerceIn(0.05, 0.95))
                ctx.reservation < ctx.yesBid - 0.01 -> Signal("NO", (1.0 - ctx.reservation).coerceIn(0.05, 0.95))
                else -> null
            }
            "s10-lob-ladder" -> when {
                ctx.ladderRatio >= 1.8 -> Signal("YES", 0.60)
                ctx.ladderRatio <= 1.0 / 1.8 -> Signal("NO", 0.60)
                else -> null
            }
            "s11-obi-micro" -> when {
                ctx.microEdge >= 0.01 -> Signal("YES", (0.50 + ctx.microEdge * 4.0).coerceIn(0.05, 0.90))
                ctx.microEdge <= -0.01 -> Signal("NO", (0.50 - ctx.microEdge * 4.0).coerceIn(0.05, 0.90))
                else -> null
            }
            "s12-vpin-flip" -> {
                if (ctx.vpin < 0.55 || !ctx.vpinFlip || ctx.flowSign == 0) null
                else if (ctx.flowSign > 0) Signal("NO", 0.60) else Signal("YES", 0.60)
            }
            "s13-no-surplus" -> when {
                ctx.noSurplus >= 0.67 && ctx.fairYes > ctx.yesAsk + 0.02 -> Signal("YES", ctx.fairYes.coerceIn(0.05, 0.95))
                ctx.noSurplus <= 0.33 && (1.0 - ctx.fairYes) > (1.0 - ctx.yesBid) + 0.02 -> Signal("NO", (1.0 - ctx.fairYes).coerceIn(0.05, 0.95))
                else -> null
            }
            "s14-brti-nowcast" -> {
                val cf = ctx.cfFairYes ?: return null
                if (abs(cf - ctx.fairYes) < 0.03) null else sideOf(cf)
            }
            "s15-poly-arb" -> {
                val poly = ctx.polyYesAsk ?: return null
                val yesGap = poly - ctx.yesAsk
                val noGap = (1.0 - poly) - (1.0 - ctx.yesBid)
                when {
                    yesGap > 0.03 -> Signal("YES", poly.coerceIn(0.05, 0.95))
                    noGap > 0.03 -> Signal("NO", (1.0 - poly).coerceIn(0.05, 0.95))
                    else -> null
                }
            }
            "s16-perp-delta" -> {
                val basis = ctx.perpBasis ?: return null
                if (abs(basis) < 0.0015) null
                else if (basis > 0) Signal("YES", 0.62) else Signal("NO", 0.62)
            }
            "s17-bracket-1h" -> {
                if (!ctx.bracket1h) null else sideOf(ctx.fairYes)
            }
            "s18-macro-rev" -> {
                val z = ctx.macroZ ?: return null
                if (abs(z) < 1.5) null
                else if (z > 0) Signal("NO", 0.60) else Signal("YES", 0.60)
            }
            "s19-gamma-pin" -> {
                if (ctx.tauS > 180.0 || abs(ctx.z) > 0.35) null
                else if (ctx.yesAsk + 0.03 < ctx.fairYes) Signal("YES", ctx.fairYes.coerceIn(0.05, 0.95))
                else if ((1.0 - ctx.yesBid) + 0.03 < (1.0 - ctx.fairYes)) Signal("NO", (1.0 - ctx.fairYes).coerceIn(0.05, 0.95))
                else null
            }
            "s20-timesfm-gate" -> {
                val p = ctx.timesFmYes ?: return null
                if (abs(p - ctx.fairYes) < THETA_MIN && abs(p - ((ctx.yesBid + ctx.yesAsk) / 2.0)) < 0.03) null
                else sideOf(p)
            }
            else -> null
        }
    }

    private fun sideOf(pYes: Double): Signal? {
        val p = pYes.coerceIn(0.0, 1.0)
        if (abs(p - 0.5) < 0.04) return null
        return if (p >= 0.5) Signal("YES", p.coerceAtMost(0.98)) else Signal("NO", (1.0 - p).coerceAtMost(0.98))
    }

    /** Drops a YES/NO pair on the same ticker when the two limits can fill each other. Keeps the richer side. */
    object InternalRouter {
        data class Intent(
            val id: String,
            val ticker: String,
            val side: String,
            val price: Double,
            val contracts: Int,
            val netEv: Double
        )

        fun admit(intents: List<Intent>): List<Intent> {
            if (intents.size < 2) return intents
            val out = ArrayList<Intent>(intents.size)
            for ((_, group) in intents.groupBy { it.ticker.uppercase() }) {
                val yes = group.filter { it.side == "YES" }
                val no = group.filter { it.side == "NO" }
                val cross = yes.isNotEmpty() && no.isNotEmpty() &&
                    yes.any { y -> no.any { n -> y.price + n.price >= 1.0 - 1e-9 } }
                if (!cross) {
                    out += group
                    continue
                }
                val yesEv = yes.maxOf { it.netEv }
                val noEv = no.maxOf { it.netEv }
                out += if (yesEv >= noEv) yes else no
            }
            return out
        }
    }

    data class Snapshot(
        val ticker: String = "",
        val bookAgeMs: Long = -1L,
        val stale: Boolean = true,
        val admitted: List<String> = emptyList(),
        val stubbed: List<String> = emptyList()
    )

    private val flow = MutableStateFlow(Snapshot())
    val latest: StateFlow<Snapshot> = flow.asStateFlow()

    fun publish(s: Snapshot) {
        flow.value = s
    }

    /**
     * Paper book for the fleet. Several clips may be open on one scalper and side.
     * [PaperReentry] spaces them. External inputs stay null on the live path, so
     * those scalpers do not trade. [lossPause] is the window loss brake.
     */
    class FleetBook {
        @Volatile var polyYesAsk: Double? = null
        @Volatile var perpBasis: Double? = null
        @Volatile var macroZ: Double? = null
        @Volatile var timesFmYes: Double? = null
        /** Side + scalper code. A non-null reason refuses the open before inventory moves. */
        var lossPause: (side: String, code: String) -> String? = { _, _ -> null }

        private class Sample(val t: Long, val px: Double)
        private class OpenPos(val strategyId: String, val trade: ScalpTrade, val entry: Double, val atMs: Long, val cost: Double)
        private class Mkt {
            val mids = ArrayDeque<Sample>()
            val spots = ArrayDeque<Sample>()
            var prevBidSz = Double.NaN
            var prevAskSz = Double.NaN
            var buyVol = 0.0
            var sellVol = 0.0
            var flowSign = 0
            var flip = false
            var updates = 0
            val pos = ArrayList<OpenPos>()
        }

        private val books = HashMap<String, Mkt>()
        private val lastClipMs = HashMap<String, Long>()
        private val coinRet = HashMap<String, ArrayDeque<Double>>()
        private val coinPx = HashMap<String, Sample>()
        private var prevCorr = 0.0

        fun onSettled(ticker: String) {
            books[ticker.uppercase()]?.pos?.clear()
        }

        fun onQuote(q: ScalpRule.Quote, bankroll: Double, newId: () -> String): List<ScalpTrade> {
            val ticker = q.ticker.uppercase()
            val series = seriesOf(ticker)
            if (series == null) return emptyList()
            val age = q.nowMs - q.bookAtMs
            val stale = bookStale(age, q.bookGap)
            val m = books.getOrPut(ticker) { Mkt() }
            val out = ArrayList<ScalpTrade>()
            if (!stale) out += exitOpen(m, q)
            val stubbed = ArrayList<String>()
            val intents = ArrayList<Pair<InternalRouter.Intent, Gate>>()
            if (!stale && q.yesBid != null && q.yesAsk != null) {
                val ctx = contextOf(q, m)
                var spent = openCost()
                for (spec in ALL) {
                    if (!listedOn(spec.id, ticker)) continue
                    val sig = signal(spec.id, ctx)
                    if (sig == null) {
                        if (spec.externalStub || spec.id == "s14-brti-nowcast" && ctx.cfFairYes == null) stubbed += spec.id
                        continue
                    }
                    val ladder = q.askLadder(sig.side).map { Level(it.first, it.second) }
                    val gate = evaluate(sig.side, sig.pWin, ladder, age, bankroll)
                    if (!gate.pass) continue
                    val cost = gate.avgFill * gate.contracts + gate.feeUsd
                    if (spent + cost > bankroll + 1e-9) continue
                    val openN = m.pos.count { it.strategyId == spec.id && it.trade.side == sig.side }
                    val clock = "$ticker|${spec.id}|${sig.side}"
                    if (!PaperReentry.allowed(openN, lastClipMs[clock], q.nowMs)) continue
                    spent += cost
                    intents += InternalRouter.Intent(spec.id, ticker, sig.side, gate.avgFill, gate.contracts, gate.netEv) to gate
                }
            }
            val kept = InternalRouter.admit(intents.map { it.first }).map { it.id }.toSet()
            val admitted = ArrayList<String>()
            for ((intent, gate) in intents) {
                if (intent.id !in kept) continue
                val trade = open(m, q, intent.id, gate, newId) ?: continue
                out += trade
                admitted += intent.id
            }
            publish(Snapshot(ticker, age, stale, admitted, stubbed))
            return out
        }

        private fun openCost(): Double = books.values.sumOf { book -> book.pos.sumOf { p -> p.cost } }

        private fun contextOf(q: ScalpRule.Quote, m: Mkt): SignalCtx {
            val bid = q.yesBid ?: 0.5
            val ask = q.yesAsk ?: 0.5
            val bidSz = q.yesBidSize ?: 0.0
            val askSz = q.yesAskSize ?: 0.0
            val spot = q.spot ?: 0.0
            val strike = q.strike ?: spot
            val sig = q.sigmaPerSec?.takeIf { it > 0.0 } ?: HftMathEngines.perSecVol(HftMathEngines.DEFAULT_VOL15M)
            noteSpot(q, spot)
            push(m.mids, q.nowMs, (bid + ask) / 2.0, 60_000L)
            if (spot > 0.0) push(m.spots, q.nowMs, spot, 60_000L)
            val g = if (spot > 0.0 && strike > 0.0) HftMathEngines.greeks(spot, strike, sig, q.tauS.coerceAtLeast(1.0)) else null
            val fair = g?.fair ?: q.fair("YES") ?: 0.5
            val ofi = imbalance(m, bidSz, askSz)
            val quote = HftMathEngines.dosQuote(bid, ask, max(1.0, bidSz), max(1.0, askSz), fair, 0.0, ofi, q.tauS)
            val micro = HftMathEngines.vamp(bid, ask, max(1.0, bidSz), max(1.0, askSz))
            val mid = (bid + ask) / 2.0
            val bidDepth = (q.yesBidsDesc.sumOf { it.second }).takeIf { it > 0.0 } ?: bidSz
            val askDepth = (q.noBidsDesc.sumOf { it.second }).takeIf { it > 0.0 } ?: askSz
            val cfFair = if (q.cfFresh() && q.cfSpot != null && strike > 0.0) {
                HftMathEngines.fairProbability(q.cfSpot, strike, sig, q.tauS.coerceAtLeast(1.0))
            } else null
            return SignalCtx(
                ticker = q.ticker,
                tauS = q.tauS,
                spot = spot,
                strike = strike,
                sigmaPerSec = sig,
                fairYes = fair,
                yesBid = bid,
                yesAsk = ask,
                yesBidSz = bidSz,
                yesAskSz = askSz,
                vwapZ = slopeOf(m.mids),
                spikeZ = spikeOf(m.spots, q.nowMs, sig),
                rvRatio = rvRatio(m.spots, sig),
                reservation = quote.reservation,
                ladderRatio = if (askDepth <= 1e-9) 1.0 else bidDepth / askDepth,
                microEdge = micro - mid,
                vpin = vpinOf(m),
                vpinFlip = m.flip,
                flowSign = m.flowSign,
                noSurplus = if (bidSz + askSz <= 1e-9) 0.5 else askSz / (bidSz + askSz),
                corrAccel = corrAccel(),
                hourUtc = Instant.ofEpochMilli(q.nowMs).atZone(ZoneOffset.UTC).hour,
                gamma = g?.gamma ?: 0.0,
                z = g?.z ?: 0.0,
                cfFairYes = cfFair,
                polyYesAsk = polyYesAsk,
                perpBasis = perpBasis,
                macroZ = macroZ,
                timesFmYes = timesFmYes,
                bracket1h = seriesOf(q.ticker) == "BRACKET_1H"
            )
        }

        private fun imbalance(m: Mkt, bidSz: Double, askSz: Double): Double {
            m.updates++
            if (!m.prevBidSz.isNaN()) {
                val db = bidSz - m.prevBidSz
                val da = askSz - m.prevAskSz
                if (db >= 0) m.buyVol += db else m.sellVol += -db
                if (da <= 0) m.buyVol += -da else m.sellVol += da
                val sign = when {
                    m.buyVol > m.sellVol + 1e-9 -> 1
                    m.sellVol > m.buyVol + 1e-9 -> -1
                    else -> 0
                }
                m.flip = m.flowSign != 0 && sign != 0 && sign != m.flowSign
                if (sign != 0) m.flowSign = sign
            }
            m.prevBidSz = bidSz
            m.prevAskSz = askSz
            if (m.updates % 30 == 0) {
                m.buyVol = 0.0
                m.sellVol = 0.0
            }
            val den = max(1e-9, bidSz + askSz)
            return ((bidSz - askSz) / den).coerceIn(-5.0, 5.0)
        }

        private fun vpinOf(m: Mkt): Double {
            val tot = m.buyVol + m.sellVol
            if (tot <= 1e-9) return 0.0
            return abs(m.buyVol - m.sellVol) / tot
        }

        private fun noteSpot(q: ScalpRule.Quote, spot: Double) {
            if (spot <= 0.0) return
            val coin = ScalpParams.coinOf(q.ticker)
            val prev = coinPx[coin]
            if (prev != null && prev.px > 0.0 && q.nowMs - prev.t >= 1_000L) {
                val row = coinRet.getOrPut(coin) { ArrayDeque() }
                row.addLast(ln(spot / prev.px))
                while (row.size > 12) row.removeFirst()
            }
            coinPx[coin] = Sample(q.nowMs, spot)
        }

        private fun corrAccel(): Double {
            val a = coinRet["BTC"] ?: return 0.0
            val b = coinRet["ETH"] ?: return 0.0
            val n = min(a.size, b.size)
            if (n < 4) return 0.0
            val xs = a.toList().takeLast(n)
            val ys = b.toList().takeLast(n)
            var mx = 0.0
            var my = 0.0
            for (i in 0 until n) {
                mx += xs[i]
                my += ys[i]
            }
            mx /= n
            my /= n
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - mx
                val dy = ys[i] - my
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            if (sxx <= 1e-18 || syy <= 1e-18) return 0.0
            val c = (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
            val accel = c - prevCorr
            prevCorr = c
            return accel
        }

        private fun push(row: ArrayDeque<Sample>, t: Long, px: Double, keepMs: Long) {
            if (!px.isFinite() || px <= 0.0) return
            row.addLast(Sample(t, px))
            while (row.isNotEmpty() && t - row.first().t > keepMs) row.removeFirst()
            while (row.size > 40) row.removeFirst()
        }

        private fun slopeOf(mids: ArrayDeque<Sample>): Double {
            if (mids.size < 4) return 0.0
            val t0 = mids.first().t
            val times = DoubleArray(mids.size) { (mids.elementAt(it).t - t0) / 1000.0 }
            val px = DoubleArray(mids.size) { mids.elementAt(it).px }
            return HftMathEngines.vwapSlopeZ(times, px)
        }

        private fun spikeOf(spots: ArrayDeque<Sample>, now: Long, sig: Double): Double {
            if (spots.size < 2 || sig <= 0.0) return 0.0
            val cutoff = now - 5_000L
            var old = spots.first().px
            for (s in spots) if (s.t <= cutoff) old = s.px
            val nowPx = spots.last().px
            if (old <= 0.0 || nowPx <= 0.0) return 0.0
            return ln(nowPx / old) / (sig * sqrt(5.0))
        }

        private fun rvRatio(spots: ArrayDeque<Sample>, sig: Double): Double {
            if (spots.size < 4 || sig <= 0.0) return 1.0
            val rets = ArrayList<Double>()
            var prev = spots.first().px
            for (s in spots) {
                if (prev > 0.0 && s.px > 0.0 && s.px != prev) rets += ln(s.px / prev)
                prev = s.px
            }
            if (rets.size < 3) return 1.0
            var mean = 0.0
            for (r in rets) mean += r
            mean /= rets.size
            var v = 0.0
            for (r in rets) v += (r - mean) * (r - mean)
            val rv = sqrt(v / (rets.size - 1))
            return rv / sig
        }

        private fun exitOpen(m: Mkt, q: ScalpRule.Quote): List<ScalpTrade> {
            val out = ArrayList<ScalpTrade>()
            val keep = ArrayList<OpenPos>()
            for (pos in m.pos) {
                val bid = q.bid(pos.trade.side)
                if (bid == null || bid <= 0.01 || bid >= 0.99) {
                    keep += pos
                    continue
                }
                val held = (q.nowMs - pos.atMs) / 1000.0
                val id = pos.strategyId
                val terminal = id.startsWith("s01") || id.startsWith("s02") || id.startsWith("s03") || id.startsWith("s04")
                val net = bid - pos.entry - quadraticFeeUsd(1, bid) - quadraticFeeUsd(1, pos.entry)
                val flatten = if (terminal) q.tauS <= 1.5 else q.tauS <= 8.0
                if (flatten || net >= 0.04 || held >= 120.0) out += closeTrade(pos, bid, q.nowMs, if (flatten) "fleet flatten" else "fleet exit")
                else keep += pos
            }
            m.pos.clear()
            m.pos.addAll(keep)
            return out
        }

        private fun open(m: Mkt, q: ScalpRule.Quote, id: String, gate: Gate, newId: () -> String): ScalpTrade? {
            if (gate.contracts < 1) return null
            if (lossPause(gate.side, id) != null) return null
            val ticker = q.ticker.uppercase()
            val clock = "$ticker|$id|${gate.side}"
            val openN = m.pos.count { it.strategyId == id && it.trade.side == gate.side }
            if (!PaperReentry.allowed(openN, lastClipMs[clock], q.nowMs)) return null
            val px = gate.avgFill
            if (px <= 0.01 || px >= 0.99) return null
            val n = PaperClip.sized(gate.contracts)
            val fee = quadraticFeeUsd(n, px)
            val t = ScalpTrade(
                id = newId(),
                ticker = ticker,
                side = gate.side,
                state = ScalpState.OPEN,
                signalAtMs = q.nowMs,
                signalAsk = px,
                fairAtSignal = gate.pWin,
                contracts = n,
                entryPrice = px,
                entryFeeUsd = fee,
                entryAtMs = q.nowMs,
                note = "paper fleet $id · ev ${"%.4f".format(gate.netEv)} · slip ${"%.4f".format(gate.slippage)} · no live order",
                ruleVersion = "${ScalpRule.VERSION}|$id|P"
            )
            m.pos += OpenPos(id, t, px, q.nowMs, px * n + fee)
            lastClipMs[clock] = q.nowMs
            while (lastClipMs.size > 4_000) lastClipMs.remove(lastClipMs.keys.first())
            return t
        }

        private fun closeTrade(pos: OpenPos, exitPx: Double, at: Long, reason: String): ScalpTrade {
            val t = pos.trade
            val n = t.contracts
            val px = exitPx.coerceIn(0.01, 0.99)
            val fee = quadraticFeeUsd(n, px)
            val proceeds = px * n
            val net = proceeds - fee - ((t.entryPrice ?: 0.0) * n + t.entryFeeUsd)
            return t.copy(
                state = ScalpState.CLOSED,
                soldContracts = n,
                proceedsUsd = proceeds,
                exitFeeUsd = fee,
                exitDecidedAtMs = at,
                closedAtMs = at,
                netUsd = net,
                exitReason = reason
            )
        }
    }
}
