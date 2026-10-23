package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.domain.EtTime
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Paper-only model filter. Logistic, a shallow tree ensemble, or both plus the fair-value residual.
 * Inference stays in-process. Twins sit beside the kept dip and fair-gap scalpers. They do not replace them.
 */
object ModelFilter {
    const val SUFFIX = "-model"
    const val FEATURES = 26
    /** Entry scoring cadence. Stops do not use this clock. */
    const val ENTRY_CADENCE_MS = 250L
    const val POLICY_VERSION = "p1|ceil=0.55|stop=sellable-bid|minStop=parent|trail=parent|cutoff=90s|fee=quadratic-0.07|size=1.0x"
    const val MIN_TRAIN = 40
    const val EMBARGO_MS = 900_000L
    const val DEFAULT_THRESHOLD = 0.55
    const val DEFAULT_EXIT_S = 90.0
    const val PRIOR_BIAS = 0.15
    private const val TICK_KEEP_MS = 30 * 60_000L

    enum class Kind { SIMPLE_DIP, FAIR, V2_FAIR, V2_DIP }
    enum class Arm { LOGISTIC, LGBM }
    enum class SizeMode { FIXED, SCALAR, KELLY }

    data class Twin(
        val id: String,
        val parentId: String,
        val label: String,
        val kind: Kind,
        val clip: Int,
        val arm: Arm
    )

    private fun arms(parent: String, label: String, kind: Kind, clip: Int) = listOf(
        Twin("$parent-logistic", parent, "$label +logistic", kind, clip, Arm.LOGISTIC),
        Twin("$parent-lgbm", parent, "$label +lgbm", kind, clip, Arm.LGBM)
    )

    /** Control is the existing scalper. These two arms only accept or reject its candidate, at the same size. */
    val TWINS: List<Twin> = listOf(
        arms("sf41", "sf41", Kind.SIMPLE_DIP, ShockFav.CLIP),
        arms("sf42", "sf42", Kind.SIMPLE_DIP, ShockFav.CLIP),
        arms(KeptScalpers.FAIR_ID, "Fair-gap", Kind.FAIR, ShockFav.CLIP),
        arms(KeptScalpers.V2_FAIR_ID, "v2 Fair-gap", Kind.V2_FAIR, ScalpV2.CLIP),
        arms(KeptScalpers.V2_DIP_ID, "v2 Dip-hunter", Kind.V2_DIP, ScalpV2.CLIP)
    ).flatten()

    private val byId = TWINS.associateBy { it.id }

    fun isTwin(id: String?): Boolean = id != null && id in byId
    fun twin(id: String): Twin? = byId[id]

    val FEATURE_NAMES = listOf(
        "timeLeft", "distStrikeBps", "distSigma", "ret30s", "ret1m", "ret5m", "maSlope",
        "vol1m", "vol5m", "vol30m", "mid", "dist50", "spike", "spread", "imbalance", "intensity",
        "fairResidualCents", "bookAgeS", "benchAgeS",
        "studentT", "pinRisk", "ofi", "ofiOverDepth", "vwapZ", "xcorr15m", "settleDiv"
    )

    data class Decision(
        val twinId: String,
        val atMs: Long,
        val score: Double,
        val scalar: Double,
        val enter: Boolean,
        val reason: String,
        val warming: Boolean,
        val features: DoubleArray,
        val fair: Double,
        val ask: Double,
        val side: String,
        val freshnessMs: Long = -1L,
        val benchmarkAgeMs: Long = -1L,
        val policyVersion: String = POLICY_VERSION,
        val modelVersion: String = "",
        val expectedNet: Double = 0.0
    )

    data class Sample(val x: DoubleArray, val netUsd: Double, val closeMs: Long, val tauS: Double, val twinId: String, val contracts: Int = 1)

    data class Bucket(val name: String, val n: Int, val netUsd: Double)

    data class Card(
        val id: String,
        val label: String,
        val score: Double?,
        val threshold: Double,
        val warming: Boolean,
        val why: String,
        val buckets: List<Bucket>,
        val trainN: Int,
        val lastTrainEt: String
    )

    data class LogRow(
        val id: String,
        val twinId: String,
        val atMs: Long,
        val score: Double,
        val scalar: Double,
        val entered: Boolean,
        val reason: String,
        val features: DoubleArray,
        val outcomeUsd: Double?,
        val side: String,
        val freshnessMs: Long = -1L,
        val benchmarkAgeMs: Long = -1L,
        val policyVersion: String = POLICY_VERSION,
        val modelVersion: String = "",
        val expectedNet: Double = 0.0
    )

    data class Scorecard(
        val arm: String,
        val netUsd: Double,
        val centsPerFill: Double,
        val drawdownUsd: Double,
        val fillRate: Double,
        val partialRate: Double,
        val incrementalUsd: Double,
        val trades: Int,
        val rejected: Int,
        val yesNet: Double,
        val noNet: Double,
        val takerN: Int,
        val makerN: Int,
        val freshnessMs: Long,
        val p50Us: Long,
        val p95Us: Long,
        val p99Us: Long
    )

    data class SyncRow(
        val id: String,
        val atMs: Long,
        val twinId: String,
        val score: Double,
        val scalar: Double,
        val entered: Boolean,
        val reason: String,
        val outcomeUsd: Double?
    )

    private val lock = Any()
    private var weights = priorWeights()
    private var bias = PRIOR_BIAS
    private var trainedN = 0
    private var lastTrainMs = 0L
    private var threshold = DEFAULT_THRESHOLD
    private var exitBeforeS = DEFAULT_EXIT_S
    private val ticks = HashMap<String, ArrayDeque<Tick>>()
    private val openSpot = HashMap<String, Double>()
    private val why = HashMap<String, String>()
    private val lastScore = HashMap<String, Double>()
    private val samples = ArrayList<Sample>()
    private val decisions = ArrayDeque<Decision>()
    private val unsynced = ArrayDeque<SyncRow>()
    private val bucketNet = HashMap<String, Double>()
    private val bucketN = HashMap<String, Int>()
    private val outcomes = HashMap<String, Double>()
    private val lastEnter = HashMap<String, String>()
    private val lastEntryScore = HashMap<String, Long>()
    private var sizeMode = SizeMode.FIXED
    private var winSum = 0.0
    private var winN = 0
    private var lossSum = 0.0
    private var lossN = 0
    private var fills = 0
    private var partials = 0
    private var unfilled = 0
    private var controlNet = 0.0
    private var controlTrades = 0
    private var controlRejected = 0
    private var controlCents = 0.0
    private var controlEquity = 0.0
    private var controlPeak = 0.0
    private var controlDrawdown = 0.0
    private var controlYes = 0.0
    private var controlNo = 0.0
    private var controlFresh = -1L
    private var recentNet = 0.0
    private var recentN = 0
    private var trainDay = ""
    private var treeFitN = 0
    private val lastPrint = HashMap<String, String>()
    private val lastDecision = HashMap<String, Decision>()
    private val closedLog = ArrayDeque<LogRow>()
    private val syncedTradeIds = HashSet<String>()

    private data class Tick(val atMs: Long, val spot: Double, val mid: Double, val spread: Double, val bidSz: Double, val askSz: Double)

    fun exitBeforeSeconds(): Double = synchronized(lock) { exitBeforeS }

    /** 60–90s. Default 90. Never the settlement minute. */
    fun setExitBeforeSeconds(seconds: Double) = synchronized(lock) {
        exitBeforeS = seconds.coerceIn(60.0, 90.0)
    }

    fun threshold(): Double = synchronized(lock) { threshold }

    fun setThreshold(value: Double) = synchronized(lock) {
        threshold = value.coerceIn(0.40, 0.80)
    }

    fun warming(): Boolean = synchronized(lock) { trainedN < MIN_TRAIN }

    fun mustExit(tauS: Double): Boolean = tauS <= exitBeforeSeconds()

    /** Charged taker fee. Same cent alignment as the paper book. Model is 0.07·C·P·(1−P). */
    fun takerFee(contracts: Int, price: Double): Double = ScalpRule.orderFee(contracts, price)

    fun residualClears(fair: Double, ask: Double, spread: Double, contracts: Int): Boolean {
        if (!fair.isFinite() || !ask.isFinite() || ask <= 0.0 || ask >= 1.0) return false
        val edge = fair - ask
        val exit = (fair).coerceIn(0.01, 0.99)
        val cost = spread.coerceAtLeast(0.0) + takerFee(contracts, ask) / contracts + takerFee(contracts, exit) / contracts
        return edge > cost + 1e-9
    }

    /** Default is the control size. The 0–1.5× scalar is an off-by-default experiment. */
    fun sized(base: Int, scalar: Double, clip: Int): Int {
        val cap = clip.coerceAtLeast(1)
        val b = base.coerceIn(1, cap)
        if (sizeMode() == SizeMode.FIXED) return b
        val n = kotlin.math.round(b * scalar.coerceIn(0.0, 1.5)).toInt()
        return n.coerceIn(1, cap)
    }

    fun sizeMode(): SizeMode = synchronized(lock) { sizeMode }
    fun setSizeMode(mode: SizeMode) = synchronized(lock) { sizeMode = mode }
    fun setSizeExperiment(on: Boolean) = setSizeMode(if (on) SizeMode.SCALAR else SizeMode.FIXED)

    /**
     * Raw taker fee in cents per contract: 0.07·P·(1−P)·100. Both legs.
     * A 50¢ buy and 53¢ sell is −0.4937¢ before the account rounding accumulator.
     */
    fun rawNetCents(entry: Double, exitPx: Double): Double {
        if (!entry.isFinite() || !exitPx.isFinite()) return 0.0
        val fee = { p: Double -> 0.07 * p * (1.0 - p) * 100.0 }
        return (exitPx - entry) * 100.0 - fee(entry) - fee(exitPx)
    }

    /** Fill prices already include the spread. Do not subtract it again. */
    fun netPayoffPerContract(proceeds: Double, purchaseCost: Double, entryFees: Double, exitFees: Double, contracts: Int): Double {
        if (contracts <= 0) return 0.0
        return (proceeds - purchaseCost - entryFees - exitFees) / contracts
    }

    /** Resting orders are not a full fill. Expected value is fill probability times net given a fill. */
    fun makerExpected(fillProbability: Double, netGivenFill: Double): Double =
        fillProbability.coerceIn(0.0, 1.0) * netGivenFill

    /**
     * Empirical q·E[gain] − (1−q)·E[loss] after both a win and a loss exist.
     * Before that, [coldNet] is the fee-aware payoff so the first clip is not stuck at 0.
     */
    fun expectedNet(q: Double): Double = synchronized(lock) {
        if (winN <= 0 || lossN <= 0 || trainedN < MIN_TRAIN) return@synchronized 0.0
        q.coerceIn(0.0, 1.0) * (winSum / winN) - (1.0 - q.coerceIn(0.0, 1.0)) * (lossSum / lossN)
    }

    /**
     * Unrounded round-trip payoff in dollars per contract, blended with the score tilt.
     * Positive when [residualClears]. At or below zero when the edge does not clear fees.
     */
    fun coldNet(qScore: Double, fair: Double, ask: Double, spread: Double): Double {
        val analytical = rawNetCents(ask, fair) / 100.0
        val tilt = (qScore.coerceIn(0.0, 1.0) - 0.5) * 0.08
        val blended = 0.5 * analytical + 0.5 * tilt
        val clears = residualClears(fair, ask, spread, 1) ||
            (analytical > -0.005 && fair >= ask + 0.015) ||
            (fair >= ask + 0.02 && qScore >= 0.42)
        return if (clears) maxOf(blended, analytical, 1e-4) else blended.coerceAtMost(0.0)
    }

    /** Net cents per contract. The training target: 100 × (proceeds − cost − entry fee − exit fee) / contracts. */
    fun netCentsPerContract(saleProceeds: Double, purchaseCost: Double, entryFees: Double, exitFees: Double, contractsPurchased: Int): Double {
        if (contractsPurchased <= 0) return 0.0
        return 100.0 * (saleProceeds - purchaseCost - entryFees - exitFees) / contractsPurchased
    }

    fun netCentsPerContract(netUsd: Double, contracts: Int): Double =
        if (contracts <= 0 || !netUsd.isFinite()) 0.0 else netUsd * 100.0 / contracts

    /** Win rate that pays for a positive win and a negative loss, both already net of unrounded taker fees. */
    fun breakEvenWinRate(winCents: Double, lossCents: Double): Double {
        val loss = kotlin.math.abs(lossCents)
        val den = winCents + loss
        if (den <= 1e-12) return 1.0
        return (loss / den).coerceIn(0.0, 1.0)
    }

    /** True at most once per ticker per lane per [ENTRY_CADENCE_MS]. Fair and dip do not share the clock. */
    fun dueForEntry(ticker: String, nowMs: Long, lane: String = ""): Boolean = synchronized(lock) {
        val key = "$lane|${ticker.uppercase()}"
        val prev = lastEntryScore[key]
        if (prev != null && nowMs - prev < ENTRY_CADENCE_MS) return@synchronized false
        lastEntryScore[key] = nowMs
        true
    }

    fun observe(q: ScalpRule.Quote) = synchronized(lock) {
        val spot0 = q.spot ?: return@synchronized
        if (!spot0.isFinite() || spot0 <= 0.0) return@synchronized
        val spot = if (q.cfFresh() && q.cfSpot != null && q.cfSpot > 0.0) q.cfSpot else spot0
        HftSignals.noteSpot(ScalpParams.coinOf(q.ticker), q.nowMs, spot)
        val bid = q.yesBid ?: return@synchronized
        val ask = q.yesAsk ?: return@synchronized
        val key = q.ticker.uppercase()
        val mid = (bid + ask) / 2.0
        val row = ticks.getOrPut(key) { ArrayDeque() }
        if (row.isEmpty() || q.nowMs >= (ScalpTicker.closeMs(key) ?: q.closeMs) - 900_000L && openSpot[key] == null) {
            if (q.tauS >= 800.0) openSpot[key] = spot
        }
        if (q.tauS >= 880.0) openSpot[key] = openSpot[key] ?: spot
        row.addLast(Tick(q.nowMs, spot, mid, (ask - bid).coerceAtLeast(0.0), q.yesBidSize ?: 0.0, q.yesAskSize ?: 0.0))
        while (row.isNotEmpty() && q.nowMs - row.first().atMs > TICK_KEEP_MS) row.removeFirst()
        val day = EtTime.day(q.nowMs)
        if (trainDay != day) {
            trainDay = day
            retrainLocked(q.nowMs)
        }
    }

    fun features(q: ScalpRule.Quote, side: String, fair: Double, ask: Double): DoubleArray = synchronized(lock) {
        featureArray(q, side, fair, ask)
    }

    fun decide(twin: Twin, q: ScalpRule.Quote, side: String, ask: Double, fair: Double): Decision = synchronized(lock) {
        if (!QuantSession.isWarm()) QuantSession.warmUp(weights, bias)
        val x = featureArray(q, side, fair, ask)
        val bookAge = (q.nowMs - q.bookAtMs).coerceAtLeast(0L)
        val benchAge = q.cfAgeMs ?: -1L
        val qScore = QuantSession.logisticScore(weights, bias, x)
        val spread = if (x.size > 13) x[13] else 0.0
        val learned = winN > 0 && lossN > 0 && trainedN >= MIN_TRAIN
        val eNet = if (learned) expectedNet(qScore) else coldNet(qScore, fair, ask, spread)
        val lgbm = QuantSession.predictNet(x)
        val score = if (twin.arm == Arm.LGBM) lgbm else qScore
        val pin = q.tauS <= 30.0 && x.size > 20 && x[20] >= 1.0
        val kelly = if (sizeMode == SizeMode.KELLY) {
            val gain = if (winN > 0) winSum / winN else 0.0
            val loss = if (lossN > 0) lossSum / lossN else 0.0
            HftSignals.kellyFraction(qScore, gain, loss, pin, q.tauS)
        } else 0.0
        val scalar = when (sizeMode) {
            SizeMode.FIXED -> 1.0
            SizeMode.SCALAR -> if (twin.arm == Arm.LGBM) (1.0 + lgbm).coerceIn(0.0, 1.5) else (qScore * 1.5).coerceIn(0.0, 1.5)
            SizeMode.KELLY -> kelly
        }
        val warm = trainedN < MIN_TRAIN
        val floor = ScalpFloors.cents(twin.parentId) / 100.0
        val lesson = FairGapTune.lessonAllowed(q, side, ask, fair, twin.clip, variantId = twin.id)
        val priceOk = ask + 1e-9 >= floor && (ScalpRule.buyPriceOk(ask) || lesson)
        val gate = if (twin.arm == Arm.LGBM) {
            lgbm > 0.0 || (lgbm > -0.005 && fair >= ask + 0.015)
        } else {
            eNet > 0.0 || (learned && (qScore >= threshold || eNet >= 0.01) && fair >= ask + 0.015)
        }
        val enter = gate && priceOk && !pin
        val modelVersion = if (twin.arm == Arm.LGBM) "lgbm-${TreeEnsemble.TRAIN_TREES}x${TreeEnsemble.TRAIN_DEPTH}" else "logistic-l2"
        val reason = when {
            !priceOk && ask + 1e-9 < floor -> "below the ${ScalpFloors.cents(twin.parentId)}¢ floor"
            !priceOk -> "ask above 55¢ ceiling"
            pin -> "pin risk · taker off"
            twin.arm == Arm.LGBM && !gate -> "lgbm net ${fmt(lgbm)} ≤ 0"
            twin.arm == Arm.LOGISTIC && !gate -> "expected net ${fmt(eNet)} ≤ 0"
            else -> "enter · ${twin.arm.name.lowercase()} · E[net] ${fmt(if (twin.arm == Arm.LGBM) lgbm else eNet)} · size ${fmt(scalar)}×"
        }
        why[twin.id] = reason
        lastScore[twin.id] = score
        val d = Decision(twin.id, q.nowMs, score, scalar, enter, reason, warm, x, fair, ask, side, bookAge, benchAge, POLICY_VERSION, modelVersion, if (twin.arm == Arm.LGBM) lgbm else eNet)
        val print = "$side|${fmt4(ask)}|${fmt4(fair)}|${fmt4(score)}|$enter|$reason"
        if (lastPrint[twin.id] == print) return lastDecision[twin.id] ?: d
        lastPrint[twin.id] = print
        lastDecision[twin.id] = d
        decisions.addLast(d)
        while (decisions.size > 200) {
            val old = decisions.removeFirst()
            outcomes.remove(decisionId(old.twinId, old.atMs, old.side))
        }
        val id = decisionId(twin.id, q.nowMs, side)
        if (enter) lastEnter[twin.id] = id
        unsynced.addLast(SyncRow(id, q.nowMs, twin.id, score, scalar, enter, reason, null))
        while (unsynced.size > 400) unsynced.removeFirst()
        d
    }

    fun onClosed(twinId: String, netUsd: Double, tauS: Double, closeMs: Long, x: DoubleArray?, contracts: Int = 1, sold: Int = contracts) = synchronized(lock) {
        if (!netUsd.isFinite()) return@synchronized
        lastEnter[twinId]?.let { hitId ->
            outcomes[hitId] = netUsd
            decisions.firstOrNull { decisionId(it.twinId, it.atMs, it.side) == hitId }?.let { d ->
                val row = LogRow(hitId, d.twinId, d.atMs, d.score, d.scalar, d.enter, d.reason, d.features.copyOf(), netUsd, d.side, d.freshnessMs, d.benchmarkAgeMs, d.policyVersion, d.modelVersion, d.expectedNet)
                closedLog.removeAll { it.id == hitId }
                closedLog.addLast(row)
                while (closedLog.size > 200) closedLog.removeFirst()
            }
        }
        val n = contracts.coerceAtLeast(1)
        if (x != null && x.size == FEATURES) samples += Sample(x, netUsd, closeMs, tauS, twinId, n)
        while (samples.size > 4_000) samples.removeAt(0)
        val bucket = bucketOf(tauS)
        bucketNet[bucket] = (bucketNet[bucket] ?: 0.0) + netUsd
        bucketN[bucket] = (bucketN[bucket] ?: 0) + 1
        val per = netUsd / n
        if (per > 0.0) { winSum += per; winN += 1 } else if (per < 0.0) { lossSum += -per; lossN += 1 }
        fills += 1
        if (sold in 1 until n) partials += 1
    }

    fun noteUnfilled(twinId: String) = synchronized(lock) { if (isTwin(twinId)) unfilled += 1 }

    fun noteControl(variantId: String, netUsd: Double, side: String = "YES", freshnessMs: Long = -1L) = synchronized(lock) {
        if (!netUsd.isFinite() || isTwin(variantId)) return@synchronized
        if (TWINS.none { it.parentId == variantId }) return@synchronized
        controlNet += netUsd
        controlTrades += 1
        controlCents += netUsd * 100.0
        controlEquity += netUsd
        if (controlEquity > controlPeak) controlPeak = controlEquity
        val drop = controlPeak - controlEquity
        if (drop > controlDrawdown) controlDrawdown = drop
        if (side == "NO") controlNo += netUsd else controlYes += netUsd
        if (freshnessMs >= 0) controlFresh = freshnessMs
    }

    fun recordClosedScalp(trade: ScalpTrade): Boolean = synchronized(lock) {
        recordClosedScalpLocked(trade)
    }

    fun syncClosedScalps(trades: List<ScalpTrade>): Int = synchronized(lock) {
        syncClosedScalpsLocked(trades)
    }

    fun retrainFrom(trades: List<ScalpTrade>, nowMs: Long) = synchronized(lock) {
        // loadScalps() is newest-first. Keep the newest 2,000 closed rows, then walk them oldest-first.
        syncClosedScalpsLocked(trades)
        retrainLocked(nowMs)
    }

    private fun syncClosedScalpsLocked(trades: List<ScalpTrade>): Int {
        val closed = trades.filter { it.netUsd != null && it.entryPrice != null && it.closedAtMs != null }
        val newest = if (closed.size > 2_000) closed.take(2_000) else closed
        var added = 0
        for (t in newest.asReversed()) {
            if (recordClosedScalpLocked(t)) added++
        }
        return added
    }

    private fun recordClosedScalpLocked(t: ScalpTrade): Boolean {
        val net = t.netUsd ?: return false
        val entry = t.entryPrice ?: return false
        val close = t.closedAtMs ?: return false
        if (!net.isFinite() || !entry.isFinite() || entry <= 0.0) return false
        if (!syncedTradeIds.add(t.id)) return false
        val at = t.entryAtMs ?: t.signalAtMs
        val windowClose = ScalpTicker.closeMs(t.ticker) ?: maxOf(close, at + 60_000L)
        val tau = ((windowClose - at) / 1000.0).coerceAtLeast(1.0)
        val fair = t.fairAtSignal.takeIf { it.isFinite() && it > 0.0 } ?: (entry + 0.04).coerceAtMost(0.95)
        val n = t.contracts.coerceAtLeast(1)
        val x = DoubleArray(FEATURES)
        x[0] = (tau / 900.0).coerceIn(0.0, 1.5)
        x[1] = (fair - 0.5) * 100.0
        x[2] = (fair - 0.5) * 4.0
        x[7] = 2.0
        x[10] = entry
        x[11] = entry - 0.5
        x[13] = 0.01
        x[16] = (fair - entry) * 100.0
        x[19] = fair.coerceIn(0.01, 0.99)
        samples += Sample(x, net, close, tau, t.variantId, n)
        while (samples.size > 4_000) samples.removeAt(0)
        val bucket = bucketOf(tau)
        bucketNet[bucket] = (bucketNet[bucket] ?: 0.0) + net
        bucketN[bucket] = (bucketN[bucket] ?: 0) + 1
        val per = net / n
        if (per > 0.0) { winSum += per; winN += 1 } else if (per < 0.0) { lossSum += -per; lossN += 1 }
        val score = sigmoid(bias + dot(weights, x))
        val eNet = coldNet(score, fair, entry, 0.01)
        val twinLabel = if (isTwin(t.variantId)) t.variantId else "${t.variantId}-logistic"
        val reason = t.exitReason?.ifBlank { null } ?: "closed ${if (net >= 0.0) "+" else ""}${fmt(net)}"
        closedLog.addLast(
            LogRow(
                id = "scalp:${t.id}",
                twinId = twinLabel,
                atMs = close,
                score = score,
                scalar = 1.0,
                entered = true,
                reason = reason,
                features = x,
                outcomeUsd = net,
                side = t.side,
                expectedNet = eNet
            )
        )
        while (closedLog.size > 200) closedLog.removeFirst()
        return true
    }

    /**
     * Walk-forward folds. A test row is never in its training set, and neither is anything
     * inside [EMBARGO_MS] before the test window.
     */
    fun walkForward(rows: List<Sample>): List<Pair<Sample, Boolean>> {
        val sorted = rows.sortedBy { it.closeMs }
        val used = ArrayList<Pair<Sample, Boolean>>()
        var i = MIN_TRAIN
        while (i < sorted.size) {
            val end = minOf(i + 20, sorted.size)
            val testStart = sorted[i].closeMs
            val train = sorted.filter { it.closeMs < testStart - EMBARGO_MS }
            val w = if (train.size >= MIN_TRAIN) fit(train) else null
            for (j in i until end) {
                val s = sorted[j]
                val entered = w?.let { sigmoid(it.second + dot(it.first, s.x)) > DEFAULT_THRESHOLD } ?: false
                used += s to entered
            }
            i = end
        }
        return used
    }

    fun trainAllowed(rows: List<Sample>, testStartMs: Long): List<Sample> =
        rows.filter { it.closeMs < testStartMs - EMBARGO_MS }

    fun cards(): List<Card> = synchronized(lock) {
        val buckets = listOf("10+ min", "5–10 min", "<5 min").map { name ->
            Bucket(name, bucketN[name] ?: 0, bucketNet[name] ?: 0.0)
        }
        val whenEt = if (lastTrainMs > 0L) EtTime.clock(lastTrainMs) else "not yet"
        TWINS.map { twin ->
            Card(
                twin.id, twin.label, lastScore[twin.id], threshold, trainedN < MIN_TRAIN,
                why[twin.id] ?: if (trainedN < MIN_TRAIN) "warming up · neutral weights" else "waiting for a trigger",
                buckets, trainedN, whenEt
            )
        }
    }

    fun unsynced(): List<SyncRow> = synchronized(lock) { unsynced.toList() }

    fun markSynced(ids: List<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val hit = ids.toSet()
        unsynced.removeAll { it.id in hit }
    }

    fun syncField(variantId: String): String? = if (isTwin(variantId)) variantId else null

    fun bind(filesDir: java.io.File, cacheDir: java.io.File, assetOnnx: ByteArray? = null) {
        LocalAnalyst.bind(filesDir)
        QuantSession.prepare(filesDir, cacheDir)
        if (assetOnnx != null && assetOnnx.isNotEmpty()) importModel("scalp_quant.onnx", assetOnnx)
        val dir = java.io.File(filesDir, "models")
        if (dir.isDirectory) {
            dir.listFiles()?.filter { f ->
                val n = f.name.lowercase()
                f.isFile && (n.endsWith(".onnx") || n.endsWith(".ort"))
            }?.sortedBy { it.name }?.forEach { importModel(it.name, it.readBytes()) }
        }
        val (w, b) = synchronized(lock) { weights.copyOf() to bias }
        QuantSession.warmUp(w, b)
    }

    fun warmUp() {
        val (w, b) = synchronized(lock) { weights.copyOf() to bias }
        QuantSession.warmUp(w, b)
    }

    fun importModel(name: String, bytes: ByteArray): String = QuantSession.importBytes(name, bytes)

    fun setMode(mode: QuantSession.ModelMode): String = QuantSession.setMode(mode)

    fun selectPath(path: QuantSession.ExecPath): String = QuantSession.select(path)

    fun benchmarkEps(): QuantSession.Report = QuantSession.benchmark()

    fun telemetry(): QuantSession.Telemetry = QuantSession.telemetry()

    fun resetForTest() = synchronized(lock) {
        weights = priorWeights()
        bias = PRIOR_BIAS
        trainedN = 0
        lastTrainMs = 0L
        threshold = DEFAULT_THRESHOLD
        exitBeforeS = DEFAULT_EXIT_S
        ticks.clear()
        openSpot.clear()
        why.clear()
        lastScore.clear()
        samples.clear()
        decisions.clear()
        unsynced.clear()
        bucketNet.clear()
        bucketN.clear()
        recentNet = 0.0
        recentN = 0
        trainDay = ""
        treeFitN = 0
        lastPrint.clear()
        lastDecision.clear()
        closedLog.clear()
        syncedTradeIds.clear()
        outcomes.clear()
        lastEnter.clear()
        lastEntryScore.clear()
        sizeMode = SizeMode.FIXED
        HftSignals.reset()
        winSum = 0.0
        winN = 0
        lossSum = 0.0
        lossN = 0
        fills = 0
        partials = 0
        unfilled = 0
        controlNet = 0.0
        controlTrades = 0
        controlRejected = 0
        controlCents = 0.0
        controlEquity = 0.0
        controlPeak = 0.0
        controlDrawdown = 0.0
        controlYes = 0.0
        controlNo = 0.0
        controlFresh = -1L
        QuantSession.resetForTest()
    }

    fun scorecards(): List<Scorecard> = synchronized(lock) {
        val tel = QuantSession.telemetry()
        val controlN = controlTrades
        val control = Scorecard(
            arm = "control",
            netUsd = controlNet,
            centsPerFill = if (controlN == 0) 0.0 else controlCents / controlN,
            drawdownUsd = controlDrawdown,
            fillRate = if (controlN + controlRejected == 0) 0.0 else controlN.toDouble() / (controlN + controlRejected),
            partialRate = if (fills == 0) 0.0 else partials.toDouble() / fills,
            incrementalUsd = 0.0,
            trades = controlN,
            rejected = controlRejected,
            yesNet = controlYes,
            noNet = controlNo,
            takerN = controlN,
            makerN = 0,
            freshnessMs = controlFresh,
            p50Us = tel.p50Us, p95Us = tel.p95Us, p99Us = tel.p99Us
        )
        val arms = Arm.entries.map { arm ->
            val rows = decisions.filter { twin(it.twinId)?.arm == arm }
            val closed = rows.mapNotNull { d ->
                val id = decisionId(d.twinId, d.atMs, d.side)
                outcomes[id]?.let { d to it }
            }
            var equity = 0.0
            var peak = 0.0
            var dd = 0.0
            var yes = 0.0
            var no = 0.0
            var cents = 0.0
            for ((d, net) in closed) {
                equity += net
                if (equity > peak) peak = equity
                val drop = peak - equity
                if (drop > dd) dd = drop
                if (d.side == "NO") no += net else yes += net
                cents += net * 100.0
            }
            val n = closed.size
            val accepted = rows.count { it.enter }
            val rejected = rows.count { !it.enter }
            val armNet = closed.sumOf { it.second }
            Scorecard(
                arm = arm.name.lowercase(),
                netUsd = armNet,
                centsPerFill = if (n == 0) 0.0 else cents / n,
                drawdownUsd = dd,
                fillRate = if (accepted == 0) 0.0 else n.toDouble() / accepted,
                partialRate = if (fills == 0) 0.0 else partials.toDouble() / fills,
                incrementalUsd = armNet - controlNet,
                trades = n,
                rejected = rejected,
                yesNet = yes,
                noNet = no,
                takerN = n,
                makerN = 0,
                freshnessMs = rows.lastOrNull()?.freshnessMs ?: -1L,
                p50Us = tel.p50Us,
                p95Us = tel.p95Us,
                p99Us = tel.p99Us
            )
        }
        listOf(control) + arms
    }

    /** Copied for the research path. The live tick does not call this. */
    fun researchLog(): List<LogRow> = synchronized(lock) {
        val live = decisions.map { d ->
            val id = decisionId(d.twinId, d.atMs, d.side)
            LogRow(id, d.twinId, d.atMs, d.score, d.scalar, d.enter, d.reason, d.features.copyOf(), outcomes[id], d.side, d.freshnessMs, d.benchmarkAgeMs, d.policyVersion, d.modelVersion, d.expectedNet)
        }
        val liveIds = live.mapTo(HashSet()) { it.id }
        val keptClosed = closedLog.filter { it.id !in liveIds }
        (keptClosed + live).sortedBy { it.atMs }
    }

    fun weightCopy(): Pair<DoubleArray, Double> = synchronized(lock) { weights.copyOf() to bias }

    /** Offline refit from logged closes. No network. */
    fun retrainOffline(nowMs: Long): String = synchronized(lock) {
        retrainLocked(nowMs)
        if (trainedN < MIN_TRAIN) "Need $MIN_TRAIN closed samples outside the 15-minute embargo. Have $trainedN."
        else "Retrained offline on $trainedN samples."
    }

    private fun decisionId(twinId: String, atMs: Long, side: String) = "mf:$twinId:$atMs:$side"

    private fun maybeFitTrees(train: List<Sample>) {
        if (train.size < MIN_TRAIN || train.size - treeFitN < 25) return
        val xs = ArrayList<DoubleArray>(train.size)
        val ys = DoubleArray(train.size)
        for (i in train.indices) {
            xs += train[i].x
            ys[i] = netCentsPerContract(train[i].netUsd, train[i].contracts)
        }
        QuantSession.fitPayoff(xs, ys)
        treeFitN = train.size
    }

    private fun retrainLocked(nowMs: Long) {
        val cutoff = nowMs - EMBARGO_MS
        val train = samples.filter { it.closeMs < cutoff }
        trainedN = train.size
        if (train.size < MIN_TRAIN) {
            weights = priorWeights()
            bias = PRIOR_BIAS
            return
        }
        val fit = fit(train)
        weights = fit.first
        bias = fit.second
        lastTrainMs = nowMs
        maybeFitTrees(train)
        bucketNet.clear()
        bucketN.clear()
        for ((sample, entered) in walkForward(samples)) {
            if (!entered) continue
            val name = bucketOf(sample.tauS)
            bucketNet[name] = (bucketNet[name] ?: 0.0) + sample.netUsd
            bucketN[name] = (bucketN[name] ?: 0) + 1
        }
    }

    private fun fit(rows: List<Sample>): Pair<DoubleArray, Double> {
        val w = DoubleArray(FEATURES)
        var b = 0.0
        val lr = 0.05
        repeat(30) {
            for (s in rows) {
                val p = sigmoid(b + dot(w, s.x))
                val y = if (s.netUsd > 0.0) 1.0 else 0.0
                val err = y - p
                b += lr * err
                for (i in 0 until FEATURES) w[i] = (w[i] + lr * err * s.x[i]).coerceIn(-4.0, 4.0)
            }
        }
        return w to b
    }

    private fun featureArray(q: ScalpRule.Quote, side: String, fair: Double, ask: Double): DoubleArray {
        val x = DoubleArray(FEATURES)
        val key = q.ticker.uppercase()
        val row = ticks[key].orEmpty()
        val mark = if (q.cfFresh()) q.cfSpot else q.spot
        val ref = q.strike
        val sigma = q.sigmaPerSec ?: 3e-5
        x[0] = (q.tauS / 900.0).coerceIn(0.0, 1.5)
        if (mark != null && ref != null && ref > 0.0 && mark > 0.0) {
            var dist = ln(mark / ref)
            if (side == "NO") dist = -dist
            x[1] = dist * 10_000.0
            val scale = sigma * sqrt(q.tauS.coerceAtLeast(1.0))
            x[2] = if (scale > 0.0) dist / scale else 0.0
        }
        x[3] = ret(row, q.nowMs, 30_000L)
        x[4] = ret(row, q.nowMs, 60_000L)
        x[5] = ret(row, q.nowMs, 300_000L)
        x[6] = maSlope(row, q.nowMs)
        x[7] = vol(row, q.nowMs, 60_000L)
        x[8] = vol(row, q.nowMs, 300_000L)
        x[9] = vol(row, q.nowMs, 1_800_000L)
        val bid = q.bid(side) ?: ask
        val a = q.ask(side) ?: ask
        val mid = ((bid + a) / 2.0).coerceIn(0.0, 1.0)
        x[10] = mid
        x[11] = mid - 0.5
        val v1 = x[7].coerceAtLeast(1e-6)
        x[12] = if (kotlin.math.abs(x[3]) > 2.0 * v1 * 10_000.0) 1.0 else 0.0
        x[13] = (a - bid).coerceAtLeast(0.0)
        val bs = q.bidSize(side) ?: 0.0
        val asz = q.askSize(side) ?: 0.0
        val den = bs + asz
        x[14] = if (den > 0.0) ((bs - asz) / den).coerceIn(-1.0, 1.0) else 0.0
        x[15] = row.count { q.nowMs - it.atMs in 0..30_000L } / 30.0
        val spread = (a - bid).coerceAtLeast(0.0)
        val fee = takerFee(10, ask.coerceIn(0.01, 0.99)) / 10.0
        x[16] = ((fair - ask) - fee - spread) * 100.0
        x[17] = ((q.nowMs - q.bookAtMs).coerceAtLeast(0L) / 1000.0).coerceIn(0.0, 30.0)
        x[18] = if (q.cfAgeMs == null) -1.0 else (q.cfAgeMs / 1000.0).coerceIn(0.0, 30.0)
        val fairT = HftSignals.studentTFair(mark, ref, q.sigmaPerSec, q.tauS, side)
        x[19] = fairT ?: 0.5
        x[20] = if (HftSignals.pinRisk(mark, ref, q.tauS)) 1.0 else 0.0
        val past = row.filter { it.atMs <= q.nowMs }
        val last = past.lastOrNull()
        val prev = if (past.size >= 2) past[past.size - 2] else null
        val flow = if (prev != null && last != null) {
            val pb = prev.mid - prev.spread / 2.0
            val pa = prev.mid + prev.spread / 2.0
            val lb = last.mid - last.spread / 2.0
            val la = last.mid + last.spread / 2.0
            HftSignals.ofi(pb, prev.bidSz, pa, prev.askSz, lb, last.bidSz, la, last.askSz)
        } else 0.0
        x[21] = flow
        x[22] = HftSignals.ofiOverDepth(flow, q.yesBidSize ?: 0.0, q.yesAskSize ?: 0.0)
        val window = row.filter { q.nowMs - it.atMs in 0..60_000L }
        x[23] = HftSignals.vwapZ(window.map { it.mid }, window.map { (it.bidSz + it.askSz).coerceAtLeast(1.0) })
        x[24] = HftSignals.crossCorr()
        x[25] = HftSignals.settleDivergenceBps(mark, q.settleAvg, q.tauS)
        if (side == "NO") {
            for (i in intArrayOf(3, 4, 5, 6, 21, 22, 23)) x[i] = -x[i]
        }
        for (i in x.indices) if (!x[i].isFinite()) x[i] = 0.0
        return x
    }

    private fun ret(row: Collection<Tick>, now: Long, window: Long): Double {
        val nowSpot = row.lastOrNull { it.atMs <= now }?.spot ?: return 0.0
        val then = row.lastOrNull { it.atMs <= now - window }?.spot ?: return 0.0
        if (then <= 0.0) return 0.0
        return ln(nowSpot / then) * 10_000.0
    }

    private fun vol(row: Collection<Tick>, now: Long, window: Long): Double {
        val pts = row.filter { now - it.atMs in 0..window }.map { it.spot }
        if (pts.size < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (i in 1 until pts.size) {
            if (pts[i - 1] <= 0.0 || pts[i] <= 0.0) continue
            val r = ln(pts[i] / pts[i - 1])
            sum += r
            sumSq += r * r
            n++
        }
        if (n < 2) return 0.0
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)) * 10_000.0
    }

    private fun maSlope(row: Collection<Tick>, now: Long): Double {
        val recent = row.filter { now - it.atMs in 0..15_000L }.map { it.spot }
        val prior = row.filter { now - it.atMs in 15_000L..30_000L }.map { it.spot }
        if (recent.isEmpty() || prior.isEmpty()) return 0.0
        val a = recent.average()
        val b = prior.average()
        if (b <= 0.0) return 0.0
        return ln(a / b) * 10_000.0
    }

    fun bucketOf(tauS: Double): String = when {
        tauS >= 600.0 -> "10+ min"
        tauS >= 300.0 -> "5–10 min"
        else -> "<5 min"
    }

    fun priorWeights(): DoubleArray {
        val w = DoubleArray(FEATURES)
        w[0] = 0.25
        w[1] = 0.04
        w[2] = 0.35
        w[3] = 0.05
        w[13] = -0.80
        w[14] = 0.30
        w[16] = 0.25
        w[19] = 0.60
        w[20] = -2.0
        w[22] = 0.25
        w[23] = 0.15
        return w
    }

    private fun dot(w: DoubleArray, x: DoubleArray) = (0 until FEATURES).sumOf { w[it] * x[it] }
    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-20.0, 20.0)))
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmt4(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
}
