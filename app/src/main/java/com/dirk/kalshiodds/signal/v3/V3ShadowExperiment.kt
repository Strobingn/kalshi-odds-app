package com.dirk.kalshiodds.signal.v3

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Version-three is a shadow-only, Bitcoin-only evaluation lane.  It has no
 * TicketSession or Kalshi client dependency, so collecting a V3 row can never
 * place, amend, or cancel an order.
 *
 * The probability is deliberately anchored to the executable market quote:
 *
 *   logit(v3) = logit(mid) + 0.35 * (logit(settlement prior) - logit(mid))
 *               + bounded(spot return - quote return)
 *
 * The two corrections are recorded separately.  They are hypotheses to be
 * evaluated after settlement, not fitted on-device "AI" weights.
 */
object V3ShadowExperiment {
    const val MODEL_VERSION = "v3-shadow-market-offset-r1"
    const val SAFETY_MARGIN_USD = 0.03
    const val MAX_SERIES_POINTS = 360
    private const val PRIOR_WEIGHT = 0.35
    private const val MAX_PRIOR_LOGIT = 0.55
    private const val MAX_LAG_LOGIT = 0.25
    private const val LAG_SCALE = 35.0

    data class Evaluation(
        val ticker: String,
        val observedAtMs: Long,
        val marketYes: Double,
        val settlementPriorYes: Double?,
        val fairYes: Double,
        val priorLogitAdjustment: Double,
        val lagLogitAdjustment: Double,
        val spotReturn5s: Double?,
        val quoteReturn5s: Double?,
        val spotReturn15s: Double?,
        val quoteReturn15s: Double?,
        val side: String?,
        val ask: Double?,
        val fee: Double?,
        val edgeVsMid: Double?,
        val netEv: Double?,
        val gateReason: String
    )

    private data class QuoteSample(val atMs: Long, val mid: Double, val spot: Double?)

    private val samples = LinkedHashMap<String, ArrayDeque<QuoteSample>>()

    /** Pure except for a short rolling, in-memory quote history used for lag. */
    @Synchronized
    fun evaluate(market: MarketUiModel, nowMs: Long = System.currentTimeMillis()): Evaluation? {
        if (!market.ticker.uppercase().startsWith("KXBTC15M")) return null
        val mid = market.yesProbabilityPercent?.div(100.0)?.takeIf { it in 0.0..1.0 } ?: return null
        // Drop inactive windows instead of retaining one deque for every market forever.
        samples.entries.removeAll { (_, values) ->
            values.lastOrNull()?.let { nowMs - it.atMs > 45_000L } != false
        }
        val history = samples.getOrPut(market.ticker.uppercase()) { ArrayDeque() }
        if (history.lastOrNull()?.atMs?.let { nowMs < it } == true) history.clear()
        val spot = market.spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        val past5s = history.lastOrNull { nowMs - it.atMs in 4_000L..8_000L }
        val past15s = history.lastOrNull { nowMs - it.atMs in 12_000L..18_000L }
        val spotReturn5s = if (spot != null && past5s?.spot != null) spot / past5s.spot - 1.0 else null
        val quoteReturn5s = past5s?.let { mid - it.mid }
        val spotReturn15s = if (spot != null && past15s?.spot != null) spot / past15s.spot - 1.0 else null
        val quoteReturn15s = past15s?.let { mid - it.mid }
        history.addLast(QuoteSample(nowMs, mid, spot))
        while (history.size > 40 || history.firstOrNull()?.let { nowMs - it.atMs > 45_000L } == true) {
            history.removeFirst()
        }

        // The CF final-minute average is preferred only when it is actually
        // present. Otherwise digitalFair is the pre-existing spot-to-strike
        // prior. Neither uses aiYesPercent/heavy-ML outputs in V3.
        val settlementPrior = when {
            (market.finalMinuteSamples ?: 0) > 0 -> market.finalMinuteFairPp
            else -> market.digitalFairPp
        }?.div(100.0)?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val priorAdjustment = settlementPrior?.let {
            ((logit(it) - logit(mid)) * PRIOR_WEIGHT).coerceIn(-MAX_PRIOR_LOGIT, MAX_PRIOR_LOGIT)
        } ?: 0.0
        val lagDeltas = listOfNotNull(
            if (spotReturn5s != null && quoteReturn5s != null) spotReturn5s - quoteReturn5s else null,
            if (spotReturn15s != null && quoteReturn15s != null) spotReturn15s - quoteReturn15s else null
        )
        val lagAdjustment = if (lagDeltas.isNotEmpty()) {
            (lagDeltas.average() * LAG_SCALE).coerceIn(-MAX_LAG_LOGIT, MAX_LAG_LOGIT)
        } else {
            0.0
        }
        val fairYes = sigmoid(logit(mid) + priorAdjustment + lagAdjustment)

        val yes = candidate("YES", fairYes, mid, market.yesAsk, market.spreadDollars)
        val no = candidate("NO", 1.0 - fairYes, 1.0 - mid, market.noAsk, market.spreadDollars)
        val best = listOfNotNull(yes, no).maxByOrNull { it.netEv }
        val gate = when {
            market.closeTimeEpochMs?.let { nowMs >= it } == true ||
                market.status?.lowercase() in setOf("closed", "settled", "finalized", "determined") ->
                "Observe only: market is closed"
            settlementPrior == null -> "Observe only: settlement/spot prior unavailable"
            best == null -> "Observe only: no executable ask"
            best.edgeVsMid < (market.spreadDollars ?: Double.POSITIVE_INFINITY) + best.fee + SAFETY_MARGIN_USD ->
                "Observe only: edge does not clear spread + fee + 3¢"
            best.netEv < SAFETY_MARGIN_USD -> "Observe only: net EV at ask is below 3¢"
            else -> "Shadow entry: exact ask clears all V3 gates"
        }
        return Evaluation(
            ticker = market.ticker,
            observedAtMs = nowMs,
            marketYes = mid,
            settlementPriorYes = settlementPrior,
            fairYes = fairYes,
            priorLogitAdjustment = priorAdjustment,
            lagLogitAdjustment = lagAdjustment,
            spotReturn5s = spotReturn5s,
            quoteReturn5s = quoteReturn5s,
            spotReturn15s = spotReturn15s,
            quoteReturn15s = quoteReturn15s,
            side = best?.side,
            ask = best?.ask,
            fee = best?.fee,
            edgeVsMid = best?.edgeVsMid,
            netEv = best?.netEv,
            gateReason = gate
        )
    }

    private data class Candidate(
        val side: String,
        val ask: Double,
        val fee: Double,
        val edgeVsMid: Double,
        val netEv: Double
    )

    private fun candidate(
        side: String,
        fairSide: Double,
        midSide: Double,
        rawAsk: Double?,
        spread: Double?
    ): Candidate? {
        val ask = KalshiPrice.usable(rawAsk) ?: return null
        val fee = KalshiFee.total(1, ask)
        val edgeVsMid = fairSide - midSide
        val netEv = fairSide - ask - fee
        // A crossed/stale book is not an entry. It may remain an observation.
        if (spread != null && spread < 0.0) return null
        return Candidate(side, ask, fee, edgeVsMid, netEv)
    }

    private fun sigmoid(value: Double): Double {
        val clipped = value.coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-clipped))
    }

    private fun logit(probability: Double): Double {
        val p = probability.coerceIn(0.0001, 0.9999)
        return ln(p / (1.0 - p))
    }
}

@Serializable
data class V3Observation(
    val ticker: String,
    val observedAtMs: Long,
    val marketYes: Double,
    val fairYes: Double,
    val settlementPriorYes: Double? = null,
    val priorLogitAdjustment: Double,
    val lagLogitAdjustment: Double,
    val spotReturn5s: Double? = null,
    val quoteReturn5s: Double? = null,
    val spotReturn15s: Double? = null,
    val quoteReturn15s: Double? = null,
    val gateReason: String
)

@Serializable
data class V3ShadowFill(
    val id: String,
    val ticker: String,
    val side: String,
    val entryAsk: Double,
    val entryFee: Double,
    val marketYes: Double,
    val fairYes: Double,
    val netEvAtEntry: Double,
    val createdAtMs: Long,
    val modelVersion: String = V3ShadowExperiment.MODEL_VERSION,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlPerContract: Double? = null,
    val settledAtMs: Long? = null
)

@Serializable
data class V3ShadowState(
    val observations: List<V3Observation> = emptyList(),
    val fills: List<V3ShadowFill> = emptyList(),
    val lastMessage: String = "V3 waiting for a Bitcoin quote"
) {
    val openCount: Int get() = fills.count { !it.settled }
    val settled: List<V3ShadowFill> get() = fills.filter { it.settled && it.outcome != "void" }
    val pnlPerContract: Double get() = settled.sumOf { it.pnlPerContract ?: 0.0 }
    val modelBrier: Double? get() = brier { it.fairYes }
    val marketBrier: Double? get() = brier { it.marketYes }

    private fun brier(probability: (V3ShadowFill) -> Double): Double? {
        if (settled.isEmpty()) return null
        return settled.map { fill ->
            val outcome = if (fill.outcome == "yes") 1.0 else 0.0
            val error = probability(fill) - outcome
            error * error
        }.average()
    }
}

class V3ShadowBook(
    initial: V3ShadowState = V3ShadowState(),
    private val persist: (V3ShadowState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<V3ShadowState> = _state.asStateFlow()

    fun snapshot(): V3ShadowState = _state.value

    fun openTickers(): Set<String> = _state.value.fills.asSequence()
        .filterNot { it.settled }
        .map { it.ticker }
        .toSet()

    fun observeAll(markets: Collection<MarketUiModel>, nowMs: Long = System.currentTimeMillis()) {
        markets.forEach { observe(it, nowMs) }
    }

    fun observe(market: MarketUiModel, nowMs: Long = System.currentTimeMillis()) {
        val evaluation = V3ShadowExperiment.evaluate(market, nowMs) ?: return
        synchronized(lock) {
            val current = _state.value
            val recentIndex = current.observations.indexOfLast { it.ticker.equals(evaluation.ticker, true) }
            val recentSameTicker = current.observations.getOrNull(recentIndex)
            val observation = V3Observation(
                ticker = evaluation.ticker,
                observedAtMs = evaluation.observedAtMs,
                marketYes = evaluation.marketYes,
                fairYes = evaluation.fairYes,
                settlementPriorYes = evaluation.settlementPriorYes,
                priorLogitAdjustment = evaluation.priorLogitAdjustment,
                lagLogitAdjustment = evaluation.lagLogitAdjustment,
                spotReturn5s = evaluation.spotReturn5s,
                quoteReturn5s = evaluation.quoteReturn5s,
                spotReturn15s = evaluation.spotReturn15s,
                quoteReturn15s = evaluation.quoteReturn15s,
                gateReason = evaluation.gateReason
            )
            val observations = if (recentSameTicker != null && nowMs - recentSameTicker.observedAtMs in 0L until 750L) {
                current.observations.filterIndexed { index, _ -> index != recentIndex } + observation
            } else {
                (current.observations + observation).takeLast(V3ShadowExperiment.MAX_SERIES_POINTS)
            }
            val canEnter = evaluation.gateReason.startsWith("Shadow entry") &&
                current.fills.none { !it.settled && it.ticker.equals(evaluation.ticker, true) }
            val nextFill = if (canEnter) {
                val side = requireNotNull(evaluation.side)
                val ask = requireNotNull(evaluation.ask)
                val fee = requireNotNull(evaluation.fee)
                V3ShadowFill(
                    id = idFactory(),
                    ticker = evaluation.ticker,
                    side = side,
                    entryAsk = ask,
                    entryFee = fee,
                    marketYes = evaluation.marketYes,
                    fairYes = evaluation.fairYes,
                    netEvAtEntry = requireNotNull(evaluation.netEv),
                    createdAtMs = nowMs
                )
            } else null
            publish(
                current.copy(
                    observations = observations,
                    fills = if (nextFill == null) current.fills else listOf(nextFill) + current.fills,
                    lastMessage = nextFill?.let {
                        "V3 shadow ${it.side} ${it.ticker} @ ${"%.1f".format(java.util.Locale.US, it.entryAsk * 100)}¢ · no order sent"
                    } ?: evaluation.gateReason
                )
            )
        }
    }

    fun settle(ticker: String, result: String, settledAtMs: Long = System.currentTimeMillis()) {
        val normalized = result.trim().lowercase()
        if (normalized !in setOf("yes", "no", "void")) return
        synchronized(lock) {
            val current = _state.value
            var changed = false
            val fills = current.fills.map { fill ->
                if (fill.settled || !fill.ticker.equals(ticker, true)) return@map fill
                changed = true
                val won = when (normalized) {
                    "yes" -> fill.side == "YES"
                    "no" -> fill.side == "NO"
                    else -> null
                }
                val pnl = when {
                    normalized == "void" -> 0.0
                    won == true -> 1.0 - fill.entryAsk - fill.entryFee
                    else -> -fill.entryAsk - fill.entryFee
                }
                fill.copy(settled = true, outcome = normalized, won = won, pnlPerContract = pnl, settledAtMs = settledAtMs)
            }
            if (changed) {
                publish(current.copy(fills = fills, lastMessage = "V3 settled $ticker · $normalized · no order sent"))
            }
        }
    }

    private fun publish(next: V3ShadowState) {
        _state.value = next
        persist(next)
    }
}
