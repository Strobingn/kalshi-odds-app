package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

object PaperScalpPolicy {
    const val START_CASH = 20_000.0
    const val VERSION = 2
    const val TRAIL = .02
    const val MIN_NET_EDGE = .01
    const val LATENCY_MS = 250L // declared simulation assumption, not measured exchange latency
    fun below50(price: Double) = price.isFinite() && price > 0 && price < .50 - 1e-9
    fun stop(entry: Double, highBid: Double, prior: Double = 0.0) = max(prior, max(.001, max(entry, highBid) - TRAIL))
    // New positions get room for ordinary spread/noise. Freeze distances at entry, never widen a live stop.
    fun distances(q: PaperDepth, side: String, history: List<PaperDepth>): Pair<Double, Double> {
        val bids = (history.filter { q.atMs - it.atMs <= 30_000 }.map { it.bid(side) } + q.bid(side)).filter { it > 0 }
        val steps = bids.zipWithNext { a, b -> kotlin.math.abs(a - b) }.sorted()
        val noise = if (steps.isEmpty()) 0.0 else steps[(steps.lastIndex * .8).toInt()]
        val spread = ((q.ask(side) ?: q.bid(side)) - q.bid(side)).coerceAtLeast(0.0)
        return max(.06, spread * 3 + noise * 3).coerceAtMost(.10) to max(TRAIL, noise * 2).coerceAtMost(.04)
    }
    fun feePerContract(price: Double) = LiveOrderSizer.feeUsd(100, price) / 100
    fun bucket(price: Double) = when { price < .25 -> "<25"; price < .40 -> "25–40"; else -> "40–50" }
}

/** Each entry family has four explicit sizing experiments. Copies are not independent signal evidence. */
data class PaperScalper(val id: String, val family: String, val fraction: Double, val name: String)
object PaperScalpCatalog {
    val ALL = listOf("cheap", "dip", "momentum", "forecast-fade", "adaptive").flatMap { family ->
        listOf(.15, .35, .70, 1.0).map { fraction ->
            PaperScalper("$family-${(fraction * 100).toInt()}", family, fraction,
                "${when (family) { "cheap" -> "Fair-value discount"; "dip" -> "Dip reclaim"; "momentum" -> "Book momentum"; "forecast-fade" -> "Forecast fade"; else -> "Adaptive selector" }} · ${(fraction * 100).toInt()}% ceiling")
        }
    }
}

@Serializable
data class PaperPending(val ticker: String, val side: String, val setup: String, val limit: Double,
                        val budget: Double, val atMs: Long, val revision: Long, val closeMs: Long, val policyVersion: Int = 1, val expectedBid: Double? = null)
@Serializable
data class PaperScalpPosition(val id: String, val ticker: String, val side: String, val setup: String,
    val count: Int, val remaining: Int, val entry: Double, val entryValue: Double, val entryFee: Double,
    val openedMs: Long, val closeMs: Long, val highBid: Double, val lowBid: Double,
    val lastBid: Double, val markAtMs: Long, val stop: Double,
    val exitReason: String? = null, val triggerMs: Long? = null, val triggerRevision: Long? = null,
    val netProceeds: Double = 0.0, val exitFees: Double = 0.0,
    val policyVersion: Int = 1, val riskDistance: Double = .02, val trailDistance: Double = .02, val trailArmed: Boolean = false)
@Serializable
data class PaperScalpResult(val id: String, val ticker: String, val setup: String, val side: String,
    val count: Int, val entry: Double, val netUsd: Double, val feesUsd: Double, val maxFavorable: Double,
    val maxAdverse: Double, val reason: String, val atMs: Long, val policyVersion: Int = 1)
@Serializable
data class PaperLiquidity(val ticker: String, val side: String, val price: Double, val displayed: Double, val available: Double)
@Serializable
data class PaperCurvePoint(val atMs: Long, val equity: Double, val realized: Double, val fees: Double, val peakEquity: Double = equity)
@Serializable
data class PaperScalpAccount(val id: String, val cash: Double = PaperScalpPolicy.START_CASH,
    val positions: List<PaperScalpPosition> = emptyList(), val pending: List<PaperPending> = emptyList(),
    val results: List<PaperScalpResult> = emptyList(), val closed: Int = 0, val wins: Int = 0,
    val realized: Double = 0.0, val fees: Double = 0.0, val noFills: Int = 0,
    val liquidity: List<PaperLiquidity> = emptyList(),
    val note: String = "Waiting for a fresh Bitcoin order book",
    val curve: List<PaperCurvePoint> = emptyList(), val policyStartedMs: Long = 0,
    val policyStartEquity: Double = 0.0, val policyStartRealized: Double = 0.0, val policyStartFees: Double = 0.0,
    val policyStartClosed: Int = 0, val policyStartWins: Int = 0,
    val peakEquity: Double = 0.0, val maxDrawdown: Double = 0.0) {
    fun equity() = cash + positions.sumOf { it.remaining * it.lastBid - LiveOrderSizer.feeUsd(it.remaining, it.lastBid) }
    fun pnl() = equity() - PaperScalpPolicy.START_CASH
}
@Serializable
data class PaperScalpState(val accounts: List<PaperScalpAccount> = PaperScalpCatalog.ALL.map { PaperScalpAccount(it.id) },
    val paused: Boolean = false, val source: String = "production", val status: String = "Waiting for books", val lastQuoteMs: Long = 0)

data class PaperLesson(val n: Int, val meanNet: Double, val sizeScale: Double, val target: Double,
                       val windows: Int, val upperMean: Double) {
    val label: String get() = "$n completed · $windows windows · ${"%+.2f".format(java.util.Locale.US, meanNet * 100)}¢/ct · size ${"%.0f".format(java.util.Locale.US, sizeScale * 100)}%"
}
object PaperScalpLearning {
    fun lesson(results: List<PaperScalpResult>, setup: String, price: Double): PaperLesson {
        val trades = results.filter { it.policyVersion == PaperScalpPolicy.VERSION && it.setup == setup && PaperScalpPolicy.bucket(it.entry) == PaperScalpPolicy.bucket(price) }.takeLast(80)
        if (trades.isEmpty()) return PaperLesson(0, 0.0, 1.0, .04, 0, .10)
        val values = trades.map { it.netUsd / it.count }
        val mean = values.average()
        // Group by complete contract window: repeated scalps on one window are correlated.
        val blocks = trades.groupBy { it.ticker }.values.map { block -> block.sumOf { it.netUsd } / block.sumOf { it.count } }
        val se = if (blocks.size < 2) .10 else sqrt(blocks.sumOf { (it - blocks.average()) * (it - blocks.average()) } / (blocks.size - 1) / blocks.size)
        val shrunk = values.sum() / (values.size + 5.0)
        val scale = (1 + shrunk * 20).coerceIn(.10, 1.0)
        val wins = trades.filter { it.netUsd > 0 }.map { it.maxFavorable }.sorted()
        val target = if (wins.isEmpty()) .04 else wins[wins.size / 2].coerceIn(.03, .20)
        return PaperLesson(trades.size, mean, scale, target, blocks.size, mean + 2 * se)
    }
}

/** A snapshot contains outcome BIDS. Asks are derived from the opposite bids; no trade endpoints exist here. */
data class PaperDepth(val ticker: String, val atMs: Long, val revision: Long, val closeMs: Long,
                      val yes: List<Pair<Double, Double>>, val no: List<Pair<Double, Double>>,
                      val fairYes: Double? = null) {
    fun bids(side: String) = (if (side == "YES") yes else no).sortedByDescending { it.first }
    fun asks(side: String) = (if (side == "YES") no else yes).map { (p, n) -> (1 - p) to n }.sortedBy { it.first }
    fun bid(side: String) = bids(side).firstOrNull()?.first ?: 0.0
    fun ask(side: String) = asks(side).firstOrNull()?.first
    fun fair(side: String) = fairYes?.let { if (side == "YES") it else 1 - it }
    fun valid() = (yes + no).all { (p, q) -> p.isFinite() && p > 0 && p < 1 && q.isFinite() && q > 0 }
}

data class PaperFill(val count: Int = 0, val value: Double = 0.0, val fees: Double = 0.0, val levels: List<Pair<Double, Double>> = emptyList())
object PaperBookWalk {
    /** Non-leveraged, fee-inclusive taker simulation. Size can use the whole available paper bankroll. */
    fun buy(levels: List<Pair<Double, Double>>, limit: Double, budget: Double): PaperFill {
        if (!budget.isFinite() || budget <= 0 || !PaperScalpPolicy.below50(limit)) return PaperFill()
        var fill = PaperFill()
        for ((price, depth) in levels.sortedBy { it.first }) {
            if (price > limit + 1e-9 || !PaperScalpPolicy.below50(price)) break
            if (!depth.isFinite() || depth < 1) continue
            val funds = budget - fill.value - fill.fees
            val maxVisible = floor(depth).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            var lo = 0
            var hi = minOf(maxVisible, floor(funds.coerceAtLeast(0.0) / price).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
            while (lo < hi) {
                val mid = lo + (hi - lo) / 2 + (hi - lo) % 2
                if (mid * price + LiveOrderSizer.feeUsd(mid, price) <= funds + 1e-9) lo = mid else hi = mid - 1
            }
            if (lo > 0) fill = PaperFill(fill.count + lo, fill.value + lo * price, fill.fees + LiveOrderSizer.feeUsd(lo, price), fill.levels + (price to lo.toDouble()))
        }
        return fill
    }
    fun sell(levels: List<Pair<Double, Double>>, count: Int): PaperFill {
        var fill = PaperFill()
        for ((price, depth) in levels.sortedByDescending { it.first }) {
            if (fill.count >= count) break
            if (!price.isFinite() || price <= 0 || price >= 1 || !depth.isFinite() || depth < 1) continue
            val n = minOf(count - fill.count, floor(depth).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
            fill = PaperFill(fill.count + n, fill.value + n * price, fill.fees + LiveOrderSizer.feeUsd(n, price), fill.levels + (price to n.toDouble()))
        }
        return fill
    }
}

/** All orders are virtual. Persisted high-water marks and triggered exits survive restarts. */
class AdaptivePaperScalp(initial: PaperScalpState = PaperScalpState(),
                         private val persist: (PaperScalpState) -> Unit = {},
                         private val idFactory: () -> String = { UUID.randomUUID().toString() }) {
    private val _state = MutableStateFlow(initial.copy(accounts = PaperScalpCatalog.ALL.map { algo ->
        (initial.accounts.firstOrNull { it.id == algo.id } ?: PaperScalpAccount(algo.id)).let { a ->
            // Cancel old unfilled decisions; existing positions retain their original stop contract.
            a.copy(pending = a.pending.filter { it.policyVersion == PaperScalpPolicy.VERSION })
        }
    }))
    val state = _state.asStateFlow()
    private val histories = mutableMapOf<String, ArrayDeque<PaperDepth>>()
    private val lastRevision = mutableMapOf<String, Long>()
    @Synchronized fun snapshot() = _state.value
    @Synchronized fun setPaused(paused: Boolean) = publish(_state.value.copy(paused = paused))
    @Synchronized fun status(message: String) { _state.value = _state.value.copy(status = message) }
    @Synchronized fun openTickers() = _state.value.accounts.flatMap { it.positions.map { p -> p.ticker } }.toSet()
    @Synchronized fun flush() = persist(_state.value)
    @Synchronized fun onBook(q: PaperDepth, nowMs: Long = q.atMs) {
        if (!q.ticker.startsWith("KXBTC15M-") || !q.valid() || q.atMs > nowMs || nowMs - q.atMs > 3000 || q.atMs >= q.closeMs) return
        if (lastRevision[q.ticker]?.let { q.revision <= it } == true) return
        lastRevision[q.ticker] = q.revision
        val hist = histories.getOrPut(q.ticker) { ArrayDeque() }
        while (hist.firstOrNull()?.let { q.atMs - it.atMs > 180_000 } == true) hist.removeFirst()
        val recent = hist.toList()
        hist.addLast(q)
        while (hist.size > 400) hist.removeFirst()
        histories.keys.filter { it != q.ticker }.toList().forEach { histories.remove(it); lastRevision.remove(it) }
        val accounts = _state.value.accounts.map { old ->
            val a = baseline(old, nowMs)
            val next = step(a, PaperScalpCatalog.ALL.first { it.id == a.id }, q, recent)
            sample(next, nowMs, a.closed, force = next.positions.map { it.remaining } != a.positions.map { it.remaining })
        }
        publish(_state.value.copy(accounts = accounts, status = "Receiving fresh book depth", lastQuoteMs = nowMs))
    }
    private fun step(a: PaperScalpAccount, algo: PaperScalper, q: PaperDepth, history: List<PaperDepth>): PaperScalpAccount {
        var account = refreshLiquidity(a, q)
        var acted = false
        val pending = a.pending.firstOrNull { it.ticker == q.ticker }
        if (pending != null && q.revision != pending.revision && q.atMs - pending.atMs >= PaperScalpPolicy.LATENCY_MS) {
            val rechecked = choose(algo, account, q, history)
            val proposed = if (_state.value.paused || q.atMs >= pending.closeMs || rechecked?.side != pending.side || rechecked?.setup != pending.setup) PaperFill()
                else PaperBookWalk.buy(available(q, account).asks(pending.side), pending.limit, minOf(account.cash, pending.budget))
            val target = minOf(pending.expectedBid ?: 0.0, rechecked?.expectedBid ?: 0.0)
            val netAtTarget = proposed.count * target - LiveOrderSizer.feeUsd(proposed.count, target) - proposed.value - proposed.fees
            val fill = if (proposed.count > 0 && netAtTarget >= proposed.count * PaperScalpPolicy.MIN_NET_EDGE) proposed else PaperFill()
            account = account.copy(pending = account.pending.filterNot { it == pending })
            acted = true
            if (fill.count > 0) {
                account = consume(account, q.ticker, if (pending.side == "YES") "NO" else "YES", fill.levels.map { (px, n) -> (1 - px) to n })
                val avg = fill.value / fill.count
                val bid = q.bid(pending.side)
                val (risk, trail) = PaperScalpPolicy.distances(q, pending.side, history)
                val position = PaperScalpPosition(idFactory(), q.ticker, pending.side, pending.setup,
                    fill.count, fill.count, avg, fill.value, fill.fees, q.atMs, q.closeMs,
                    bid, bid, bid, q.atMs, max(.001, avg - risk),
                    policyVersion = PaperScalpPolicy.VERSION, riskDistance = risk, trailDistance = trail)
                account = account.copy(cash = (account.cash - fill.value - fill.fees).coerceAtLeast(0.0),
                    positions = account.positions + position, fees = account.fees + fill.fees,
                    note = "BUY ${pending.side} ${fill.count} ct @ ${fmt(avg * 100)}¢; initial protective stop active")
            } else account = account.copy(noFills = account.noFills + 1, note = "No fill: later book moved or lacked affordable size")
        }
        for (position in account.positions.filter { it.ticker == q.ticker }) {
            val bid = q.bid(position.side)
            var p = position.copy(highBid = max(position.highBid, bid), lowBid = minOf(position.lowBid, bid), lastBid = bid, markAtMs = q.atMs)
            if (p.policyVersion < PaperScalpPolicy.VERSION) {
                p = p.copy(stop = PaperScalpPolicy.stop(p.entry, p.highBid, p.stop))
            } else {
                val proposed = (p.highBid - p.trailDistance).coerceAtLeast(.001)
                val netAtTrail = p.netProceeds + p.remaining * proposed - LiveOrderSizer.feeUsd(p.remaining, proposed) - p.entryValue - p.entryFee
                val armed = p.trailArmed || netAtTrail >= p.count * PaperScalpPolicy.MIN_NET_EDGE
                p = p.copy(trailArmed = armed, stop = if (armed) max(p.stop, proposed) else p.stop)
            }
            if (p.exitReason == null) {
                val proceeds = PaperBookWalk.sell(available(q, account).bids(p.side), p.remaining)
                val netIfFullySold = if (proceeds.count == p.remaining) p.netProceeds + proceeds.value - proceeds.fees - p.entryValue - p.entryFee else Double.NEGATIVE_INFINITY
                val lesson = PaperScalpLearning.lesson(account.results, p.setup, p.entry)
                val fairGone = q.fair(p.side)?.let { it <= bid } == true
                val fading = history.lastOrNull()?.bid(p.side)?.let { bid < it - 1e-9 } == true
                val reason = when {
                    bid <= p.stop + 1e-9 -> when {
                        p.policyVersion < PaperScalpPolicy.VERSION -> "Legacy 2¢ trailing stop"
                        p.trailArmed -> "Profit trailing stop"
                        else -> "Initial protective stop"
                    }
                    netIfFullySold >= p.count * PaperScalpPolicy.MIN_NET_EDGE && (fairGone || (fading && bid - p.entry >= lesson.target)) -> "AI profit exit"
                    else -> null
                }
                if (reason != null) p = p.copy(exitReason = reason, triggerMs = q.atMs, triggerRevision = q.revision)
            }
            // Never fill on the trigger snapshot. At least 250 ms and a newer book are required.
            if (p.exitReason != null && q.revision != p.triggerRevision && q.atMs - (p.triggerMs ?: q.atMs) >= PaperScalpPolicy.LATENCY_MS) {
                val fill = PaperBookWalk.sell(available(q, account).bids(p.side), p.remaining)
                if (fill.count > 0) {
                    account = consume(account, q.ticker, p.side, fill.levels)
                    p = p.copy(remaining = p.remaining - fill.count, netProceeds = p.netProceeds + fill.value - fill.fees, exitFees = p.exitFees + fill.fees)
                    account = account.copy(cash = account.cash + fill.value - fill.fees, fees = account.fees + fill.fees,
                        note = "SELL ${p.side} ${fill.count} ct; ${p.exitReason}; ${p.remaining} remaining")
                    acted = true
                }
            }
            account = account.copy(positions = account.positions.map { if (it.id == p.id) p else it })
            if (p.remaining == 0) { account = completed(account, p, q.atMs, p.exitReason ?: "exit"); acted = true }
        }
        // No entry-minute, max-hold, bet-count, live-dollar cap, or approval gate.
        if (!acted && !_state.value.paused && account.positions.none { it.ticker == q.ticker } && account.pending.none { it.ticker == q.ticker } && account.cash > 0) {
            val candidate = choose(algo, account, q, history)
            if (candidate == null) account = account.copy(note = "Waiting: need a fee-adjusted edge, confirmed signal and no loss cooldown")
            if (candidate != null) {
                val (side, setup, ask) = candidate
                val lesson = PaperScalpLearning.lesson(account.results, setup, ask)
                val reserved = account.pending.sumOf { it.budget }
                val budget = max(0.0, account.cash - reserved) * algo.fraction * lesson.sizeScale
                if (PaperBookWalk.buy(available(q, account).asks(side), ask, budget).count > 0) account = account.copy(
                    pending = account.pending + PaperPending(q.ticker, side, setup, ask, budget, q.atMs, q.revision, q.closeMs, PaperScalpPolicy.VERSION, candidate.expectedBid),
                    note = "AI chose $setup $side, budget $${fmt(budget)}; ${lesson.label}")
            }
        }
        return account
    }
    private fun refreshLiquidity(a: PaperScalpAccount, q: PaperDepth): PaperScalpAccount {
        val old = a.liquidity.filter { it.ticker == q.ticker }
        val levels = listOf("YES" to q.yes, "NO" to q.no).flatMap { (side, levels) -> levels.map { (price, size) ->
            val prior = old.firstOrNull { it.side == side && kotlin.math.abs(it.price - price) < 1e-8 }
            // Identical books do not replenish virtual consumed liquidity. Only observed additions count.
            val available = if (prior == null) size else minOf(size, prior.available + max(0.0, size - prior.displayed))
            PaperLiquidity(q.ticker, side, price, size, available)
        } }
        val retained = a.positions.map { it.ticker }.toSet() + a.pending.map { it.ticker }
        return a.copy(liquidity = a.liquidity.filter { it.ticker != q.ticker && it.ticker in retained } + levels)
    }
    private fun available(q: PaperDepth, a: PaperScalpAccount): PaperDepth = q.copy(
        yes = a.liquidity.filter { it.ticker == q.ticker && it.side == "YES" && it.available > 0 }.map { it.price to it.available },
        no = a.liquidity.filter { it.ticker == q.ticker && it.side == "NO" && it.available > 0 }.map { it.price to it.available })
    private fun consume(a: PaperScalpAccount, ticker: String, side: String, used: List<Pair<Double, Double>>) = a.copy(
        liquidity = a.liquidity.map { l -> if (l.ticker != ticker || l.side != side) l else l.copy(available =
            (l.available - used.filter { kotlin.math.abs(it.first - l.price) < 1e-8 }.sumOf { it.second }).coerceAtLeast(0.0)) })
    private data class Candidate(val side: String, val setup: String, val ask: Double, val score: Double, val expectedBid: Double)
    private fun choose(algo: PaperScalper, a: PaperScalpAccount, q: PaperDepth, history: List<PaperDepth>): Candidate? {
        val losses = a.results.filter { it.policyVersion == PaperScalpPolicy.VERSION && it.ticker == q.ticker }
        val lastLoss = losses.lastOrNull { it.netUsd < 0 }
        if (lastLoss != null) {
            val streak = losses.asReversed().takeWhile { it.netUsd < 0 }.size.coerceAtLeast(1)
            if (q.atMs - lastLoss.atMs < minOf(300_000L, 60_000L * streak)) return null
        }
        val yb = q.bid("YES"); val ya = q.ask("YES")
        val forecast = if (ya != null && yb > 0 && ya > yb) {
            val sample = ShortHorizonForecaster.Sample(q.atMs, (yb + ya) / 2,
                q.bids("YES").take(3).sumOf { it.second }, q.bids("NO").take(3).sumOf { it.second })
            val samples = history.mapNotNull { h -> h.ask("YES")?.let { ShortHorizonForecaster.Sample(h.atMs, (h.bid("YES") + it) / 2, 0.0, 0.0) } }
            ShortHorizonForecaster.predict("BTC", ShortHorizonForecaster.horizonFor("BTC"), ShortHorizonForecaster.features(sample, samples, q.closeMs))
        } else null
        val candidates = mutableListOf<Candidate>()
        for (side in listOf("YES", "NO")) {
            val ask = q.ask(side) ?: continue
            val bid = q.bid(side)
            if (!PaperScalpPolicy.below50(ask) || bid <= 0 || ask < bid || ask - bid >= .02 - 1e-9) continue
            val recent = history.filter { q.atMs - it.atMs <= 30_000 }
            val bids = recent.map { it.bid(side) }.filter { it > 0 }
            val recentLow = bids.minOrNull()
            val recentHigh = bids.maxOrNull()
            val bounce = bids.size >= 3 && recentHigh!! - recentLow!! >= .03 && bid >= recentLow + .005 && bid > bids.last()
            val rising = bids.size >= 3 && bid - bids.takeLast(3).first() >= .01 && bid > bids.last()
            val fadeSide = forecast?.let { if (it < 0) "YES" else "NO" }
            val setups = if (algo.family == "adaptive") listOf("cheap", "dip", "momentum", "forecast-fade") else listOf(algo.family)
            for (setup in setups) {
                val eligible = when (setup) { "cheap" -> q.fair(side) != null; "dip" -> bounce; "momentum" -> rising;
                    "forecast-fade" -> fadeSide == side && kotlin.math.abs(forecast ?: 0.0) >= ask - bid + .01; else -> false }
                if (!eligible) continue
                val lesson = PaperScalpLearning.lesson(a.results, setup, ask)
                // Cheap alone is not an edge. Require an estimated executable BID to cover both fees + 1¢ net.
                // Range/momentum and the frozen forecast are hypotheses, not calibrated win probabilities.
                val estimatedBid = when (setup) {
                    "cheap" -> q.fair(side) ?: continue
                    "dip" -> recentHigh ?: continue
                    "momentum" -> bid + (bid - (bids.takeLast(3).firstOrNull() ?: bid)).coerceAtMost(.10)
                    else -> bid + kotlin.math.abs(forecast ?: 0.0)
                }.coerceIn(.001, .999)
                val expectedNet = estimatedBid - ask - PaperScalpPolicy.feePerContract(ask) - PaperScalpPolicy.feePerContract(estimatedBid)
                if (!expectedNet.isFinite() || expectedNet < PaperScalpPolicy.MIN_NET_EDGE) continue
                if (setup != "cheap" && (recent.size < 3 || q.atMs - recent.first().atMs < 1000)) continue
                if (lesson.n >= 8 && lesson.windows >= 3 && lesson.upperMean < 0) continue
                val prior = expectedNet
                val learned = lesson.meanNet * lesson.n / (lesson.n + 5.0)
                val exploratory = .003 / sqrt(lesson.n + 1.0)
                candidates += Candidate(side, setup, ask, prior + learned + exploratory, estimatedBid)
            }
        }
        return candidates.maxByOrNull { it.score }
    }
    private fun completed(a: PaperScalpAccount, p: PaperScalpPosition, now: Long, reason: String): PaperScalpAccount {
        val net = p.netProceeds - p.entryValue - p.entryFee
        val result = PaperScalpResult(p.id, p.ticker, p.setup, p.side, p.count, p.entry, net, p.entryFee + p.exitFees,
            (p.highBid - p.entry).coerceAtLeast(0.0), (p.entry - p.lowBid).coerceAtLeast(0.0), reason, now, p.policyVersion)
        return a.copy(positions = a.positions.filterNot { it.id == p.id }, results = (a.results + result).takeLast(100),
            closed = a.closed + 1, wins = a.wins + if (net > 0) 1 else 0, realized = a.realized + net,
            note = "$reason · net $${fmt(net)}; feedback updated")
    }
    @Synchronized fun settle(ticker: String, yesWon: Boolean, atMs: Long) {
        val accounts = _state.value.accounts.map { account ->
            if (account.positions.none { it.ticker == ticker } && account.pending.none { it.ticker == ticker }) return@map account
            var a = baseline(account, atMs).copy(pending = account.pending.filterNot { it.ticker == ticker })
            for (p in account.positions.filter { it.ticker == ticker }) {
                val payout = if ((p.side == "YES") == yesWon) p.remaining.toDouble() else 0.0
                a = a.copy(cash = a.cash + payout)
                a = completed(a, p.copy(remaining = 0, netProceeds = p.netProceeds + payout), atMs,
                    if (p.exitReason != null) "${p.exitReason}: unfilled remainder settled" else "Official settlement")
            }
            sample(a, atMs, account.closed, force = a.closed != account.closed)
        }
        publish(_state.value.copy(accounts = accounts))
    }
    private fun baseline(a: PaperScalpAccount, at: Long): PaperScalpAccount = if (a.policyStartedMs != 0L) a else a.copy(
        policyStartedMs = at, policyStartEquity = a.equity(), policyStartRealized = a.realized,
        policyStartFees = a.fees, policyStartClosed = a.closed, policyStartWins = a.wins, peakEquity = a.equity(),
        curve = listOf(PaperCurvePoint(at, a.equity(), a.realized, a.fees)))
    private fun sample(a: PaperScalpAccount, at: Long, priorClosed: Int, force: Boolean = false): PaperScalpAccount {
        val equity = a.equity()
        val peak = max(a.peakEquity, equity)
        val record = force || priorClosed != a.closed || a.curve.lastOrNull()?.let { at - it.atMs >= 15_000 } != false
        return a.copy(peakEquity = peak, maxDrawdown = max(a.maxDrawdown, peak - equity),
            curve = if (record) (a.curve + PaperCurvePoint(at, equity, a.realized, a.fees, peak)).takeLast(1440) else a.curve)
    }
    @Synchronized fun expirePending(nowMs: Long) {
        val accounts = _state.value.accounts.map { a ->
            val expired = a.pending.count { it.closeMs <= nowMs }
            if (expired == 0) a else a.copy(pending = a.pending.filter { it.closeMs > nowMs }, noFills = a.noFills + expired)
        }
        if (accounts != _state.value.accounts) publish(_state.value.copy(accounts = accounts))
    }
    private fun publish(next: PaperScalpState) {
        if (next == _state.value) return
        persist(next) // checkpoint before publishing a changed stop or fill
        _state.value = next
    }
    private fun fmt(n: Double) = "%.2f".format(java.util.Locale.US, n)
}
