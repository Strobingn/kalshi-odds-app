package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper-only recorder. Writes one prediction-ledger row per coin per 15-minute
 * window from the scalper's on-device UP forecast. Never places an order and
 * never reads or writes a live ticket.
 *
 * Cadence matches the decision ledger: at most every 60 seconds, or every 15
 * seconds in the last three minutes. The calibration reader keeps the latest
 * of those rows whose timestamp is still at or before the close
 * ([WindowCalibration.HORIZON]).
 */
class ScalpForecastRecorder(
    private val insert: (LedgerRow) -> Boolean,
    private val insertBatch: ((List<LedgerRow>) -> Int)? = null
) {
    private val lastWriteMs = ConcurrentHashMap<String, Long>()

    /**
     * [pYes] is P(UP) in 0..1 for that quote (GBM when it is loaded, otherwise
     * the caller passes spot-implied fair). A null or non-probability is skipped.
     */
    fun observe(quotes: List<ScalpRule.Quote>, pYes: (ScalpRule.Quote) -> Double?) {
        if (quotes.isEmpty()) return
        pruneClosed(quotes.maxOf { it.nowMs })
        val open = quotes.filter { isFifteen(it.ticker) && it.closeMs > it.nowMs }
        if (open.isEmpty()) return
        val pending = ArrayList<Triple<String, Long, LedgerRow>>()
        for ((coin, rows) in open.groupBy { ScalpParams.coinOf(it.ticker) }) {
            val quote = pick(rows) ?: continue
            val p = pYes(quote)?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: continue
            val key = WindowCalibration.key(coin, quote.closeMs)
            if (!due(key, quote.nowMs, quote.tauS)) continue
            pending += Triple(key, quote.nowMs, row(quote, p))
        }
        flush(pending)
    }

    /** One transaction for every coin that is due on this tick. A failed batch does not burn cadence. */
    private fun flush(pending: List<Triple<String, Long, LedgerRow>>) {
        if (pending.isEmpty()) return
        val batch = insertBatch
        if (batch != null) {
            val stored = runCatching { batch(pending.map { it.third }) }.getOrDefault(0)
            if (stored == pending.size) pending.forEach { lastWriteMs[it.first] = it.second }
            return
        }
        for ((key, at, row) in pending) {
            val stored = runCatching { insert(row) }.getOrDefault(false)
            if (stored) lastWriteMs[key] = at
        }
    }

    /** Soonest open window, then the at-the-money strike. A fresh book is required. An exact ATM tie keeps the freshest book. */
    fun pick(quotes: List<ScalpRule.Quote>): ScalpRule.Quote? {
        val now = quotes.maxOfOrNull { it.nowMs } ?: return null
        val close = quotes.filter { it.closeMs > it.nowMs }.minOfOrNull { it.closeMs } ?: return null
        val window = quotes.filter { it.closeMs == close }
        val fresh = window.filter { now - it.bookAtMs in 0..AiDirection.MAX_BOOK_AGE_MS }
        if (fresh.isEmpty()) return null
        val spot = fresh.filter { it.spot != null && it.spot.isFinite() && it.spot > 0.0 }
            .maxByOrNull { it.bookAtMs }?.spot
        AtmStrike.pick(
            fresh,
            { AtmStrike.priceDistance(it.strike, spot) },
            { Double.POSITIVE_INFINITY }
        )?.let { return it }
        val nearest = fresh.minOf { AtmStrike.priceDistance(it.strike, spot) }
        return fresh.filter { AtmStrike.priceDistance(it.strike, spot) == nearest }.maxByOrNull { it.bookAtMs }
    }

    fun yesMid(quote: ScalpRule.Quote): Double? {
        val bid = quote.yesBid?.takeIf { it.isFinite() }
        val ask = quote.yesAsk?.takeIf { it.isFinite() }
        return when {
            bid != null && ask != null -> ((bid + ask) / 2.0).coerceIn(0.0, 1.0)
            ask != null -> ask.coerceIn(0.0, 1.0)
            bid != null -> bid.coerceIn(0.0, 1.0)
            else -> null
        }
    }

    /** Drop cadence keys once that window's close is no longer in the future. */
    private fun pruneClosed(nowMs: Long) {
        val it = lastWriteMs.keys.iterator()
        while (it.hasNext()) {
            val close = it.next().substringAfter('|', "").toLongOrNull() ?: continue
            if (close <= nowMs) it.remove()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun trackedCloses(): Set<Long> =
        lastWriteMs.keys.mapNotNull { it.substringAfter('|', "").toLongOrNull() }.toSet()

    private fun due(key: String, nowMs: Long, secondsLeft: Double): Boolean {
        val prev = lastWriteMs[key] ?: return true
        val cadence = if (secondsLeft <= 180.0) FAST_MS else SLOW_MS
        return nowMs - prev >= cadence
    }

    private fun row(quote: ScalpRule.Quote, pYes: Double): LedgerRow = LedgerRow(
        timestampMs = quote.nowMs,
        modelVersion = MODEL,
        ticker = quote.ticker.uppercase(),
        series = CryptoMarkets.inferSeries(quote.ticker),
        settlementRule = "15m window",
        settlementSource = null,
        secondsRemaining = quote.tauS,
        spot = quote.spot,
        targetStrike = quote.strike,
        zDistance = null,
        rawModelProb = pYes,
        calibratedProb = null,
        marketMid = yesMid(quote),
        bid = quote.yesBid,
        ask = quote.yesAsk,
        spread = null,
        depthAtBest = null,
        imbalance = null,
        bookAgeMs = quote.nowMs - quote.bookAtMs,
        decision = "FORECAST",
        sizeContracts = null,
        reasonCodes = MODEL,
        finalProb = pYes,
        side = "YES",
        closeTimeMs = quote.closeMs,
        yesAsk = quote.yesAsk
    )

    private fun isFifteen(ticker: String): Boolean =
        CryptoMarkets.FIFTEEN_SERIES.any { ticker.uppercase().startsWith(it) }

    companion object {
        const val MODEL = "scalp-window-forecast"
        const val SLOW_MS = 60_000L
        const val FAST_MS = 15_000L
    }
}
