package com.dirk.kalshiodds.decision

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Paper book for DOS-MM, TS-Arb, MRLA-CS, and the vol smile.
 * One open clip per algo per ticker. Nothing here places a live order.
 */
object DosMmEngine {
    data class Score(val id: String, val label: String, val netUsd: Double, val trades: Int, val wins: Int)

    data class Telemetry(
        val ready: Boolean = false,
        val ticker: String = "",
        val tau: Double = 0.0,
        val z: Double = 0.0,
        val fair: Double = 0.0,
        val delta: Double = 0.0,
        val gamma: Double = 0.0,
        val theta: Double = 0.0,
        val sigmaP: Double = 0.0,
        val vamp: Double = 0.0,
        val reservation: Double = 0.0,
        val halfSpread: Double = 0.0,
        val myBid: Double = 0.0,
        val myAsk: Double = 0.0,
        val inventory: Double = 0.0,
        val guard: String = "—",
        val ofi5: Double = 0.0,
        val ofiHat: Double = 0.0,
        val bounce: Double = 0.0,
        val cmdr: Double = 0.0,
        val alpha: Double = 0.0,
        val locked: Double = 0.0,
        val sHat: Double = 0.0,
        val kEff: Double = 0.0,
        val zTerm: Double = 0.0,
        val pTerm: Double = 0.0,
        val zVwap: Double = 0.0,
        val winNet: Double = 0.0,
        val lossNet: Double = 0.0,
        val pMin: Double = 0.0,
        val fStar: Double = 0.0,
        val cOpt: Int = 0,
        val gates: List<HftMathEngines.RiskGate> = emptyList(),
        val scores: List<Score> = emptyScores(),
        val mode: String = "—"
    )

    private val flow = MutableStateFlow(Telemetry())
    val latest: StateFlow<Telemetry> = flow.asStateFlow()

    fun publish(t: Telemetry) {
        flow.value = t
    }

    fun emptyScores(): List<Score> = listOf(
        Score("dos-mm", "DOS-MM", 0.0, 0, 0),
        Score("ts-arb", "TS-Arb", 0.0, 0, 0),
        Score("mrla-cs", "MRLA-CS", 0.0, 0, 0),
        Score("iv-smile", "IV smile", 0.0, 0, 0)
    )
}

class HftBook {
    private class Sample(val t: Long, val px: Double, val sz: Double)
    private class Pos(
        val trade: ScalpTrade,
        val atMs: Long,
        val entry: Double,
        val fair: Double,
        val limit: Double,
        val flowSign: Double,
        val entrySpot: Double
    )

    private class Mkt {
        val ofi = HftMathEngines.OfiBuffer()
        val mids = ArrayDeque<Sample>()
        val spots = ArrayDeque<Sample>()
        val rets = ArrayDeque<Double>()
        val locked = ArrayList<Double>()
        var lastLockMs = 0L
        var restBid: Double? = null
        var restAsk: Double? = null
        var mrlaSide: String? = null
        var mrlaPx: Double? = null
        val pos = HashMap<String, Pos>()
    }

    private val books = HashMap<String, Mkt>()
    private val coinRets = HashMap<String, ArrayDeque<Double>>()
    private val coinSpot = HashMap<String, Sample>()
    private var pnl = 0.0
    private val scores = DosMmEngine.emptyScores().toMutableList()
    private var snap = DosMmEngine.Telemetry()

    fun telemetry(): DosMmEngine.Telemetry = snap

    fun onQuote(q: ScalpRule.Quote, bankroll: Double, newId: () -> String): List<ScalpTrade> {
        val coin = ScalpParams.coinOf(q.ticker)
        if (coin != "BTC" && coin != "ETH" && coin != "SOL") return emptyList()
        val bid = q.yesBid
        val ask = q.yesAsk
        if (bid == null || ask == null || !bid.isFinite() || !ask.isFinite()) return emptyList()
        val out = ArrayList<ScalpTrade>()
        val m = books.getOrPut(q.ticker.uppercase()) { Mkt() }
        val spot = q.spot ?: 0.0
        val strike = q.strike ?: spot
        val sig = q.sigmaPerSec?.takeIf { it > 0.0 } ?: HftMathEngines.perSecVol(HftMathEngines.DEFAULT_VOL15M)
        val vol15 = HftMathEngines.vol15mOf(sig)
        noteCoin(coin, q.nowMs, spot)
        push(m.mids, q.nowMs, (bid + ask) / 2.0, (q.yesBidSize ?: 0.0) + (q.yesAskSize ?: 0.0), 40_000L)
        if (spot > 0.0) push(m.spots, q.nowMs, spot, 1.0, 40_000L)
        val ofiHat = m.ofi.update(bid, q.yesBidSize ?: 0.0, ask, q.yesAskSize ?: 0.0)
        val ofi5 = levelOfi(m, q, ofiHat)
        val dMid = delta(m.mids, q.nowMs, 10_000L)
        val dLn = logDelta(m.spots, q.nowMs, 10_000L)
        val sig10 = HftMathEngines.sigma10s(m.rets.toList(), vol15)
        val prop = HftMathEngines.propagator(ofiHat, dMid, dLn, sig10, 10.0)
        if (q.tauS <= 60.0 && q.tauS > 0.0 && spot > 0.0 && q.nowMs - m.lastLockMs >= 1000L) {
            m.locked += spot
            m.lastLockMs = q.nowMs
        }
        if (q.tauS > 60.0) m.locked.clear()
        val lockedBar = if (m.locked.isEmpty()) spot else m.locked.average()
        val alpha = if (q.tauS in 0.0..60.0) HftMathEngines.settlementAlpha(q.tauS) else 0.0
        val kEff = if (alpha > 0.0) HftMathEngines.kEff(strike, lockedBar, alpha) else strike
        val sHat = if (spot > 0.0) HftMathEngines.sHat(spot, lockedBar, alpha) else 0.0
        val g = if (spot > 0.0 && strike > 0.0) HftMathEngines.greeks(spot, strike, sig, q.tauS.coerceAtLeast(0.0)) else null
        val fair = g?.fair ?: 0.5
        val zTerm = if (q.tauS in 0.0..60.0 && spot > 0.0 && kEff > 0.0) HftMathEngines.zTerm(spot, kEff, sig, q.tauS) else g?.z ?: 0.0
        val pTerm = if (q.tauS in 0.0..60.0) HftSignals.studentTCdf(zTerm).coerceIn(0.01, 0.99) else fair
        val zVwap = slopeZ(m.mids)
        val trip = HftMathEngines.makerInTakerStop()
        val kelly = HftMathEngines.kelly(maxOf(fair, pTerm), trip.winNet, trip.lossNet, trip.collateral, bankroll, q.tauS)
        val spread = (ask - bid).coerceAtLeast(0.0)
        val age = (q.nowMs - q.bookAtMs).coerceAtLeast(0L)
        val corr = corrBtcEth()
        val inv = inventory(m)
        val gates = HftMathEngines.riskGates(inv, pnl, HftMathEngines.DRAWDOWN_CEILING, kelly.contracts, spread, age, corr)
        val guard = HftMathEngines.dosQuote(bid, ask, q.yesBidSize ?: 1.0, q.yesAskSize ?: 1.0, fair, inv, ofiHat, q.tauS)
        val structural = q.fresh() && !q.bookGap && spread <= 0.12 &&
            corr <= HftMathEngines.STRESS_CORR && pnl >= -HftMathEngines.DRAWDOWN_CEILING
        var mode = "WATCH"
        if (guard.guard != null) {
            mode = guard.guard
            m.restBid = null
            m.restAsk = null
            m.mrlaSide = null
            m.pos["dos-mm"]?.let { pos ->
                val exitPx = q.bid(pos.trade.side)
                if (exitPx != null && exitPx > 0.01 && exitPx < 0.99) {
                    out += close(m, "dos-mm", exitPx, HftMathEngines.TAKER, guard.guard, q.nowMs)
                }
            }
        } else if (structural) {
            manageDos(m, q, guard, kelly.contracts, newId)?.let { out += it }
            if (m.pos["dos-mm"] == null && m.restBid != null) mode = "MAKER_QUOTE"
        } else {
            m.restBid = null
            m.restAsk = null
        }
        if (structural) {
            val ts = tryTs(m, q, pTerm, fair, spread, zVwap, bankroll, newId)
            if (ts != null) {
                out += ts
                mode = if (ts.side == "YES") "TAKER_ARB_BUY_YES" else "TAKER_ARB_BUY_NO"
            }
            val mr = tryMrla(m, q, prop, dLn, spot, newId)
            if (mr != null) {
                out += mr
                if (mode == "WATCH" || mode == "MAKER_QUOTE") mode = "MRLA_CONTRARIAN"
            }
            val iv = trySmile(m, q, fair, sig, ofiHat, dLn, sig10, bankroll, newId)
            if (iv != null) {
                out += iv
                if (mode == "WATCH" || mode == "MAKER_QUOTE") mode = "IV_SMILE"
            }
        }
        exitTimed(m, q, out)
        snap = DosMmEngine.Telemetry(
            ready = g != null,
            ticker = q.ticker.uppercase(),
            tau = q.tauS,
            z = g?.z ?: 0.0,
            fair = fair,
            delta = g?.delta ?: 0.0,
            gamma = g?.gamma ?: 0.0,
            theta = g?.theta ?: 0.0,
            sigmaP = g?.sigmaP ?: 0.0,
            vamp = guard.vamp,
            reservation = guard.reservation,
            halfSpread = guard.halfSpread,
            myBid = guard.bid,
            myAsk = guard.ask,
            inventory = inventory(m),
            guard = guard.guard ?: "QUOTING",
            ofi5 = ofi5,
            ofiHat = ofiHat,
            bounce = prop.bounce,
            cmdr = prop.cmdr,
            alpha = alpha,
            locked = lockedBar,
            sHat = sHat,
            kEff = kEff,
            zTerm = zTerm,
            pTerm = pTerm,
            zVwap = zVwap,
            winNet = trip.winNet,
            lossNet = trip.lossNet,
            pMin = trip.pMin,
            fStar = kelly.fStar,
            cOpt = kelly.contracts,
            gates = gates,
            scores = scores.toList(),
            mode = mode
        )
        return out
    }

    private fun manageDos(m: Mkt, q: ScalpRule.Quote, quote: HftMathEngines.DosQuote, cOpt: Int, newId: () -> String): ScalpTrade? {
        val held = m.pos["dos-mm"]
        val bid = q.yesBid ?: return null
        val ask = q.yesAsk ?: return null
        if (held == null) {
            val oldBid = m.restBid
            val oldAsk = m.restAsk
            val buy = oldBid != null && ask <= oldBid
            val sell = oldAsk != null && bid >= oldAsk
            m.restBid = quote.bid
            m.restAsk = quote.ask
            val depth = if (buy) q.yesAskSize ?: 0.0 else q.yesBidSize ?: 0.0
            val n = clip(maxOf(cOpt, dosClip(q.tauS)), depth, 25)
            if (n < 1) return null
            if (buy) return open(m, "dos-mm", "YES", oldBid ?: quote.bid, n, 0.0, q, quote.reservation, newId, "DOS-MM maker bid")
            if (sell) {
                val px = 1.0 - (oldAsk ?: quote.ask)
                if (px <= 0.01 || px >= 0.99) return null
                return open(m, "dos-mm", "NO", px, n, 0.0, q, 1.0 - quote.reservation, newId, "DOS-MM maker ask")
            }
            return null
        }
        val sideBid = q.bid(held.trade.side) ?: return null
        val target = held.entry + 0.02
        if (sideBid + 1e-9 >= target && target < 0.99) {
            m.restBid = null
            m.restAsk = null
            return close(m, "dos-mm", min(sideBid, target.coerceAtLeast(0.02)), 0.0, "DOS-MM maker exit", q.nowMs)
        }
        return null
    }

    private fun dosClip(tau: Double): Int {
        val k = HftMathEngines.kelly(0.55, 0.02, 0.02, 0.50, 20_000.0, tau)
        return k.contracts
    }

    private fun tryTs(
        m: Mkt, q: ScalpRule.Quote, pTerm: Double, fair: Double, spread: Double, zVwap: Double, bankroll: Double, newId: () -> String
    ): ScalpTrade? {
        if (m.pos["ts-arb"] != null) return null
        val yesAsk = q.yesAsk ?: return null
        val noAsk = q.ask("NO") ?: return null
        val sideName = HftMathEngines.arbSide(pTerm, fair, yesAsk, noAsk, spread, zVwap, q.tauS) ?: return null
        val buyYes = sideName == "TAKER_ARB_BUY_YES"
        val ask = if (buyYes) yesAsk else noAsk
        val lessonSide = if (buyYes) "YES" else "NO"
        if (!ScalpRule.buyPriceOk(ask) && !FairGapTune.lessonAllowed(q, lessonSide, ask, if (buyYes) pTerm else 1.0 - pTerm, 20, variantId = "ts-arb")) return null
        val p = if (buyYes) pTerm else 1.0 - pTerm
        val trip = HftMathEngines.makerInTakerStop()
        val k = HftMathEngines.kelly(p, trip.winNet, trip.lossNet, ask + HftMathEngines.fee(1, ask, HftMathEngines.TAKER), bankroll, q.tauS)
        val depth = if (buyYes) q.yesAskSize ?: 0.0 else q.askSize("NO") ?: 0.0
        val n = clip(k.contracts, depth, 20)
        if (n < 1 || ask > HftMathEngines.ASK_CAP) return null
        return open(m, "ts-arb", if (buyYes) "YES" else "NO", ask, n, HftMathEngines.TAKER, q, p, newId, sideName)
    }

    private fun tryMrla(m: Mkt, q: ScalpRule.Quote, prop: HftMathEngines.Propagator, dLn: Double, spot: Double, newId: () -> String): ScalpTrade? {
        val held = m.pos["mrla-cs"]
        if (held != null) {
            val bid = q.bid(held.trade.side) ?: return null
            val ageS = (q.nowMs - held.atMs) / 1000.0
            val spotFollows = spot > 0.0 && held.entrySpot > 0.0 && kotlin.math.sign(ln(spot / held.entrySpot)) == kotlin.math.sign(held.flowSign) && abs(ln(spot / held.entrySpot)) > 1e-6
            if (bid + 1e-9 >= held.entry + 0.02) {
                return close(m, "mrla-cs", min(bid, held.entry + 0.02), 0.0, "MRLA +2¢", q.nowMs)
            }
            if (ageS >= 15.0 && spotFollows) {
                return close(m, "mrla-cs", bid, HftMathEngines.TAKER, "MRLA spot followed", q.nowMs)
            }
            return null
        }
        if (!prop.triggered) return null
        val side = if (prop.v0 < 0.0) "NO" else "YES"
        val px = q.bid(side) ?: return null
        val mrFair = q.fair(side) ?: (px + 0.04)
        if (!ScalpRule.buyPriceOk(px) && !FairGapTune.lessonAllowed(q, side, px, mrFair, 1, variantId = "mrla-cs")) return null
        if (m.mrlaSide == side && m.mrlaPx != null && (q.ask(side) ?: 1.0) <= m.mrlaPx!! + 1e-9) {
            m.mrlaSide = null
            val flow = if (prop.v0 < 0.0) 1.0 else -1.0
            val t = open(m, "mrla-cs", side, px, 1, 0.0, q, px + 0.02, newId, "MRLA maker") ?: return null
            m.pos["mrla-cs"] = m.pos.getValue("mrla-cs").let { Pos(it.trade, it.atMs, it.entry, it.fair, it.limit, flow, q.spot ?: it.entrySpot) }
            return t
        }
        m.mrlaSide = side
        m.mrlaPx = px
        return null
    }

    private fun trySmile(
        m: Mkt, q: ScalpRule.Quote, fair: Double, sig: Double, ofi: Double, dLn: Double, sig10: Double, bankroll: Double, newId: () -> String
    ): ScalpTrade? {
        val held = m.pos["iv-smile"]
        if (held != null) {
            val bid = q.bid(held.trade.side) ?: return null
            if (bid + 1e-9 >= held.limit) {
                return close(m, "iv-smile", min(bid, held.limit), 0.0, "IV limit", q.nowMs)
            }
            return null
        }
        val spot = q.spot ?: return null
        val strike = q.strike ?: return null
        val spotZ = if (sig10 > 1e-8) dLn / sig10 else 0.0
        val smile = HftMathEngines.smileFair(spot, strike, sig, q.tauS.coerceAtLeast(1.0), ofi, spotZ)
        val yesEdge = HftMathEngines.smileEdge(smile, q.yesAsk ?: return null, 10)
        val noAsk = q.ask("NO") ?: return null
        val noEdge = HftMathEngines.smileEdge(1.0 - smile, noAsk, 10)
        val buyYes = yesEdge > HftMathEngines.SMILE_EDGE && (q.yesAsk ?: 1.0) <= 0.88
        val buyNo = noEdge > HftMathEngines.SMILE_EDGE && noAsk <= 0.88 && noEdge > yesEdge
        if (!buyYes && !buyNo) return null
        val side = if (buyYes && !buyNo) "YES" else if (buyNo) "NO" else "YES"
        val ask = if (side == "YES") q.yesAsk ?: return null else noAsk
        val p = if (side == "YES") smile else 1.0 - smile
        if (!ScalpRule.buyPriceOk(ask) && !FairGapTune.lessonAllowed(q, side, ask, p, 10, variantId = "iv-smile")) return null
        val trip = HftMathEngines.makerInTakerStop()
        val k = HftMathEngines.kelly(p, trip.winNet, trip.lossNet, ask + HftMathEngines.fee(1, ask, HftMathEngines.TAKER), bankroll, q.tauS)
        val depth = q.askSize(side) ?: 0.0
        val n = clip(k.contracts, depth, 10)
        if (n < 1) return null
        val limit = if (side == "YES") HftMathEngines.smileExit(smile) else HftMathEngines.smileExit(1.0 - smile)
        val t = open(m, "iv-smile", side, ask, n, HftMathEngines.TAKER, q, p, newId, "IV smile") ?: return null
        m.pos["iv-smile"] = Pos(t, q.nowMs, ask, p, limit, 0.0, spot)
        return t
    }

    private fun exitTimed(m: Mkt, q: ScalpRule.Quote, out: MutableList<ScalpTrade>) {
        val ts = m.pos["ts-arb"] ?: return
        if (q.tauS > 15.0) return
        val bid = q.bid(ts.trade.side) ?: return
        if (bid <= 0.01 || bid >= 0.99) return
        out += close(m, "ts-arb", bid, HftMathEngines.TAKER, "TS-Arb tau", q.nowMs)
    }

    private fun open(
        m: Mkt, id: String, side: String, px: Double, n: Int, mFee: Double, q: ScalpRule.Quote, fair: Double, newId: () -> String, note: String
    ): ScalpTrade? {
        if (m.pos[id] != null || n < 1 || px <= 0.01 || px >= 0.99) return null
        val feeTotal = HftMathEngines.fee(n, px, mFee) * n
        val t = ScalpTrade(
            id = newId(),
            ticker = q.ticker.uppercase(),
            side = side,
            state = ScalpState.OPEN,
            signalAtMs = q.nowMs,
            signalAsk = px,
            fairAtSignal = fair,
            contracts = n,
            entryPrice = px,
            entryFeeUsd = feeTotal,
            entryAtMs = q.nowMs,
            note = note,
            ruleVersion = "${ScalpRule.VERSION}|$id|P"
        )
        m.pos[id] = Pos(t, q.nowMs, px, fair, fair, 0.0, q.spot ?: 0.0)
        return t
    }

    private fun close(m: Mkt, id: String, exitPx: Double, mFee: Double, reason: String, at: Long): ScalpTrade {
        val pos = m.pos.remove(id) ?: error("missing $id")
        val t = pos.trade
        val n = t.contracts
        val feeTotal = HftMathEngines.fee(n, exitPx.coerceIn(0.01, 0.99), mFee) * n
        val proceeds = exitPx * n
        val net = proceeds - feeTotal - ((t.entryPrice ?: 0.0) * n + t.entryFeeUsd)
        pnl += net
        val i = scores.indexOfFirst { it.id == id }
        if (i >= 0) {
            val s = scores[i]
            scores[i] = s.copy(netUsd = s.netUsd + net, trades = s.trades + 1, wins = s.wins + if (net > 0.0) 1 else 0)
        }
        return t.copy(
            state = ScalpState.CLOSED,
            soldContracts = n,
            proceedsUsd = proceeds,
            exitFeeUsd = feeTotal,
            exitDecidedAtMs = at,
            closedAtMs = at,
            netUsd = net,
            exitReason = reason
        )
    }

    private fun inventory(m: Mkt): Double {
        val p = m.pos["dos-mm"] ?: return 0.0
        val n = p.trade.contracts.toDouble()
        return if (p.trade.side == "YES") n else -n
    }

    private fun clip(cOpt: Int, depth: Double, cap: Int): Int {
        if (depth < 1.0) return 0
        val use = cOpt.coerceAtLeast(1)
        val d = depth.toInt().coerceAtLeast(1)
        return min(use, min(d, cap)).coerceAtLeast(1)
    }

    private fun levelOfi(m: Mkt, q: ScalpRule.Quote, fallback: Double): Double {
        val bids = q.yesBidsDesc
        val asks = q.noBidsDesc
        if (bids.size < 2 && asks.size < 2) return fallback
        val n = min(5, maxOf(bids.size, asks.size))
        if (n < 2) return fallback
        val levels = ArrayList<HftMathEngines.OfLevel>(n)
        for (i in 0 until n) {
            val b = bids.getOrNull(i)
            val a = asks.getOrNull(i)
            val bidPx = b?.first ?: q.yesBid ?: 0.0
            val bidSz = b?.second ?: 0.0
            val askPx = a?.let { 1.0 - it.first } ?: q.yesAsk ?: 0.0
            val askSz = a?.second ?: 0.0
            val e = if (i == 0) fallback else 0.0
            levels += HftMathEngines.OfLevel(e, 0.0, maxOf(1e-6, (bidSz + askSz) / 2.0))
            if (bidPx < 0 || askPx < 0) continue
        }
        return if (levels.size >= 2) HftMathEngines.ofiWeighted(levels) else fallback
    }

    private fun noteCoin(coin: String, t: Long, spot: Double) {
        if (spot <= 0.0) return
        val prev = coinSpot[coin]
        if (prev != null && t - prev.t >= 1000L && prev.px > 0.0) {
            val r = ln(spot / prev.px)
            val row = coinRets.getOrPut(coin) { ArrayDeque() }
            row.addLast(r)
            while (row.size > 30) row.removeFirst()
            books.values.firstOrNull()?.rets?.let { /* per-market rets filled below */ }
        }
        coinSpot[coin] = Sample(t, spot, 1.0)
    }

    private fun push(row: ArrayDeque<Sample>, t: Long, px: Double, sz: Double, keepMs: Long) {
        val prev = row.lastOrNull()
        if (prev != null && px > 0.0 && prev.px > 0.0 && t > prev.t) {
            // log return is recorded on the market that owns the deque via spots
        }
        row.addLast(Sample(t, px, sz))
        while (row.isNotEmpty() && t - row.first().t > keepMs) row.removeFirst()
    }

    private fun delta(row: ArrayDeque<Sample>, now: Long, window: Long): Double {
        if (row.isEmpty()) return 0.0
        val cutoff = now - window
        var old = row.first().px
        for (s in row) if (s.t <= cutoff) old = s.px
        return row.last().px - old
    }

    private fun logDelta(row: ArrayDeque<Sample>, now: Long, window: Long): Double {
        if (row.size < 2) return 0.0
        val cutoff = now - window
        var old = row.first().px
        for (s in row) if (s.t <= cutoff) old = s.px
        val nowPx = row.last().px
        if (old <= 0.0 || nowPx <= 0.0) return 0.0
        val r = ln(nowPx / old)
        return r
    }

    private fun slopeZ(row: ArrayDeque<Sample>): Double {
        if (row.size < 4) return 0.0
        val t0 = row.first().t
        val times = DoubleArray(row.size) { (row.elementAt(it).t - t0) / 1000.0 }
        val px = DoubleArray(row.size) { row.elementAt(it).px }
        return HftMathEngines.vwapSlopeZ(times, px)
    }

    private fun corrBtcEth(): Double {
        val a = coinRets["BTC"] ?: return 0.0
        val b = coinRets["ETH"] ?: return 0.0
        val n = min(a.size, b.size)
        if (n < 30) return 0.0
        val aa = a.toList().takeLast(n)
        val bb = b.toList().takeLast(n)
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) {
            ma += aa[i]
            mb += bb[i]
        }
        ma /= n
        mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = aa[i] - ma
            val xb = bb[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        if (da <= 0.0 || db <= 0.0) return 0.0
        return num / sqrt(da * db)
    }
}
