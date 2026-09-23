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
 *   (b) volume-flow / momentum from recent ticks
 *   (c) related crypto-series mid (BTC ↔ ETH ↔ SOL) when 2+ are watched
 *
 * delta = fairValue − marketMid (percentage points).
 * Emits [SignalAlert] when |delta| ≥ threshold, debounced per ticker.
 */
class ScoringEngine(
    private val model: DipHunterModel = DipHunterModel(context = null),
    private val book: TickBook = TickBook(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    data class Score(
        val fairValuePp: Double,
        val marketMidPp: Double,
        val deltaPp: Double,
        val reason: String,
        val aiPp: Double?,
        val flowPp: Double?,
        val relatedPp: Double?
    )

    private val lastAlertMs = linkedMapOf<String, Long>()

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

        var wAi = if (aiPp != null) W_AI else 0.0
        var wFlow = W_FLOW
        var wRel = if (relatedPp != null) W_RELATED else 0.0
        val wSum = wAi + wFlow + wRel
        if (wSum < 1e-9) return null
        wAi /= wSum
        wFlow /= wSum
        wRel /= wSum

        val fair = (
            (aiPp ?: 0.0) * wAi +
                flowAdjPp * wFlow +
                (relatedPp ?: 0.0) * wRel
            ).coerceIn(2.0, 98.0)
        val delta = fair - midPp
        val reason = buildReason(aiPp, flow, momentumPp, relatedPp, midPp, fair, delta)
        return Score(
            fairValuePp = fair,
            marketMidPp = midPp,
            deltaPp = delta,
            reason = reason,
            aiPp = aiPp,
            flowPp = flowAdjPp,
            relatedPp = relatedPp
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

    private fun buildReason(
        aiPp: Double?,
        flow: Double,
        momentumPp: Double,
        relatedPp: Double?,
        midPp: Double,
        fairPp: Double,
        deltaPp: Double
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
        if (relatedPp != null) {
            val label = if (relatedPp >= midPp) "related crypto higher" else "related crypto lower"
            parts += String.format(java.util.Locale.US, "%s (%.0f%%)", label, relatedPp)
        }
        parts += String.format(java.util.Locale.US, "Δ %+.1fpp (fv %.0f%%)", deltaPp, fairPp)
        return parts.joinToString(" · ")
    }

    companion object {
        const val W_AI = 0.55
        const val W_FLOW = 0.30
        const val W_RELATED = 0.15
    }
}
