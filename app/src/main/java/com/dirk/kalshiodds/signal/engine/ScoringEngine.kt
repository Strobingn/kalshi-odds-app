package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.TickSource
import java.util.UUID
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Fair-value vs market-mid scoring for **crypto** contracts only.
 * Analysis only — never places orders.
 *
 * fairValue = blend of
 *   (a) DipHunter TFLite / fallback MLP YES probability
 *   (b) volume-flow from recent ticker / trade / REST ticks
 *   (c) related crypto-series mid (BTC ↔ ETH ↔ SOL) when 2+ are watched
 *   (d) tick velocity `Δmid / Δt` (and short acceleration) over last N ticks
 *   (e) order-book imbalance from local `orderbook_snapshot` / `orderbook_delta`
 *
 * delta = fairValue − marketMid (percentage points).
 * Emits [SignalAlert] when |delta| ≥ threshold, debounced per ticker.
 */
class ScoringEngine(
    private val model: DipHunterModel = DipHunterModel(context = null),
    val book: TickBook = TickBook(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    data class Score(
        val fairValuePp: Double,
        val marketMidPp: Double,
        val deltaPp: Double,
        val reason: String,
        val aiPp: Double?,
        val flowPp: Double?,
        val relatedPp: Double?,
        val velocityPp: Double? = null,
        val imbalancePp: Double? = null,
        val velocityPerSec: Double? = null,
        val accelerationPerSec: Double? = null,
        val imbalance: Double? = null
    )

    private val lastAlertMs = linkedMapOf<String, Long>()
    private val lastBookScoreMs = linkedMapOf<String, Long>()

    fun rememberMeta(ticker: String, closeTimeEpochMs: Long?, volume: Double?, openInterest: Double?) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        val last = book.last(ticker)
        val mid = last?.mid01 ?: return
        book.push(
            MarketTick(
                ticker = ticker,
                series = CryptoMarkets.inferSeries(ticker),
                yesBid = mid,
                yesAsk = mid,
                lastPrice = mid,
                volume = volume,
                openInterest = openInterest,
                closeTimeEpochMs = closeTimeEpochMs,
                source = TickSource.REST,
                receiveElapsedNanos = 0L
            )
        )
    }

    fun applySnapshot(
        ticker: String,
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int? = null
    ): LocalOrderBook? = book.applySnapshot(ticker, yesLevels, noLevels, seq)

    fun applyDelta(
        ticker: String,
        price: Double,
        delta: Double,
        side: String,
        seq: Int? = null
    ): LocalOrderBook? = book.applyDelta(ticker, price, delta, side, seq)

    fun score(tick: MarketTick, settings: SignalSettings, nowMs: Long = System.currentTimeMillis()): Score? {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return null
        if (!settings.isWatchedTicker(tick.ticker)) return null
        book.push(tick, nowMs)
        val mid01 = tick.mid01 ?: return null
        val midPp = mid01 * 100.0
        val close = tick.closeTimeEpochMs ?: book.closeTime(tick.ticker)
        val volume = tick.volume ?: book.volume(tick.ticker) ?: 0.0
        val oi = tick.openInterest ?: book.openInterest(tick.ticker) ?: 0.0

        val ai = runCatching {
            model.predict(
                ticker = tick.ticker,
                marketMid = mid01,
                volume = volume,
                closeEpochMs = close,
                nowMs = nowMs,
                openInterest = oi
            )
        }.getOrNull()
        val aiPp = ai?.yes?.times(100.0)

        val flow = book.flowScore(tick.ticker)
        val momentumPp = book.momentumPp(tick.ticker)
        val flowAdjPp = (midPp + 8.0 * tanh(flow) + 0.35 * momentumPp).coerceIn(2.0, 98.0)
        val related01 = book.relatedCryptoMid(tick.series, settings.watchedSeries)
        val relatedPp = related01?.times(100.0)

        val vel = book.velocityPerSec(tick.ticker)
        val acc = book.accelerationPerSec(tick.ticker)
        val velAdjPp = vel?.let {
            val velPpPerSec = it * 100.0
            val accPpPerSec = (acc ?: 0.0) * 100.0
            (midPp + 6.0 * tanh(velPpPerSec / 2.0) + 2.0 * tanh(accPpPerSec / 2.0)).coerceIn(2.0, 98.0)
        }
        val imb = book.imbalance(tick.ticker)
        val imbAdjPp = imb?.let { (midPp + 8.0 * it).coerceIn(2.0, 98.0) }

        var wAi = if (aiPp != null) W_AI else 0.0
        var wFlow = W_FLOW
        var wRel = if (relatedPp != null) W_RELATED else 0.0
        var wVel = if (velAdjPp != null) W_VELOCITY else 0.0
        var wImb = if (imbAdjPp != null) W_IMBALANCE else 0.0
        val wSum = wAi + wFlow + wRel + wVel + wImb
        if (wSum < 1e-9) return null
        wAi /= wSum
        wFlow /= wSum
        wRel /= wSum
        wVel /= wSum
        wImb /= wSum

        val fair = (
            (aiPp ?: 0.0) * wAi +
                flowAdjPp * wFlow +
                (relatedPp ?: 0.0) * wRel +
                (velAdjPp ?: 0.0) * wVel +
                (imbAdjPp ?: 0.0) * wImb
            ).coerceIn(2.0, 98.0)
        val delta = fair - midPp
        val reason = buildReason(
            aiPp = aiPp,
            flow = flow,
            momentumPp = momentumPp,
            relatedPp = relatedPp,
            midPp = midPp,
            fairPp = fair,
            deltaPp = delta,
            velocityPerSec = vel,
            imbalance = imb
        )
        return Score(
            fairValuePp = fair,
            marketMidPp = midPp,
            deltaPp = delta,
            reason = reason,
            aiPp = aiPp,
            flowPp = flowAdjPp,
            relatedPp = relatedPp,
            velocityPp = velAdjPp,
            imbalancePp = imbAdjPp,
            velocityPerSec = vel,
            accelerationPerSec = acc,
            imbalance = imb
        )
    }

    fun maybeAlert(
        tick: MarketTick,
        settings: SignalSettings,
        nowMs: Long = System.currentTimeMillis()
    ): SignalAlert? {
        val scored = score(tick, settings, nowMs) ?: return null
        if (abs(scored.deltaPp) < settings.edgeThresholdPp) return null
        val last = lastAlertMs[tick.ticker] ?: 0L
        if (nowMs - last < settings.debounceMs) return null
        lastAlertMs[tick.ticker] = nowMs
        return SignalAlert(
            id = idFactory(),
            ticker = tick.ticker,
            series = tick.series,
            deltaPp = scored.deltaPp,
            fairValuePp = scored.fairValuePp,
            marketMidPp = scored.marketMidPp,
            reason = scored.reason,
            createdAtMs = nowMs,
            receiveElapsedNanos = tick.receiveElapsedNanos
        )
    }

    fun maybeAlertFromBook(
        ticker: String,
        settings: SignalSettings,
        receiveElapsedNanos: Long,
        nowMs: Long = System.currentTimeMillis()
    ): SignalAlert? {
        val last = lastBookScoreMs[ticker] ?: 0L
        if (nowMs - last < BOOK_SCORE_MIN_INTERVAL_MS) return null
        val tick = book.tickFromBook(ticker, receiveElapsedNanos, nowMs) ?: return null
        lastBookScoreMs[ticker] = nowMs
        return maybeAlert(tick, settings, nowMs)
    }

    private fun buildReason(
        aiPp: Double?,
        flow: Double,
        momentumPp: Double,
        relatedPp: Double?,
        midPp: Double,
        fairPp: Double,
        deltaPp: Double,
        velocityPerSec: Double?,
        imbalance: Double?
    ): String {
        val parts = mutableListOf<String>()
        if (aiPp != null) {
            parts += String.format(java.util.Locale.US, "AI %.0f%% vs mkt %.0f%%", aiPp, midPp)
        } else {
            parts += String.format(java.util.Locale.US, "mkt %.0f%%", midPp)
        }
        parts += when {
            flow > 0.25 -> "flow YES"
            flow < -0.25 -> "flow NO"
            abs(momentumPp) >= 2.0 -> String.format(java.util.Locale.US, "mom %+.1fpp", momentumPp)
            else -> "flow flat"
        }
        val velPp = velocityPerSec?.times(100.0)
        if (velPp != null && abs(velPp) >= 0.4) {
            parts += String.format(java.util.Locale.US, "vel %+.1fpp/s", velPp)
        }
        if (imbalance != null && abs(imbalance) >= 0.12) {
            val label = if (imbalance >= 0) "book bid" else "book ask"
            parts += String.format(java.util.Locale.US, "%s %+.0f%%", label, imbalance * 100.0)
        }
        if (relatedPp != null) {
            val label = if (relatedPp >= midPp) "related crypto higher" else "related crypto lower"
            parts += String.format(java.util.Locale.US, "%s (%.0f%%)", label, relatedPp)
        }
        parts += String.format(java.util.Locale.US, "Δ %+.1fpp (fv %.0f%%)", deltaPp, fairPp)
        return parts.joinToString(" · ")
    }

    companion object {
        const val W_AI = 0.40
        const val W_FLOW = 0.20
        const val W_RELATED = 0.12
        const val W_VELOCITY = 0.16
        const val W_IMBALANCE = 0.12
        /** Book deltas update depth immediately; re-score at most this often. */
        const val BOOK_SCORE_MIN_INTERVAL_MS = 250L
    }
}
