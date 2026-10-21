package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.paper.PaperFill
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * AI UP calibration samples for the Scalper charts.
 *
 * Horizon is [HORIZON]: one forecast per coin per 15-minute window, the row
 * with the latest timestamp that is still at or before that window's close.
 * The scalper writes that row at most every 60 seconds, and every 15 seconds
 * in the last three minutes, so the kept row is the last forecast before close.
 *
 * Sources, in priority order when a window appears in more than one:
 * the prediction ledger (`final_prob` vs `settlement_result`), then a scalp
 * trade that was held to settlement and stored an AI chance, then a settled
 * paper fill with an AI chance. Only the last 30 days of closes are kept.
 */
object WindowCalibration {
    const val HORIZON = "last-before-close"
    const val MIN_SAMPLES = 10
    const val LOOKBACK_MS = 30L * 86_400_000L
    private val ET: ZoneId = ZoneId.of("America/New_York")

    data class Sample(
        val key: String,
        val coin: String,
        val closeMs: Long,
        val atMs: Long,
        val aiUp: Double,
        val settledUp: Boolean,
        val marketUp: Double?
    )

    data class Report(
        val samples: List<Sample> = emptyList(),
        val brier: Double? = null,
        val marketBrier: Double? = null,
        val marketSamples: Int = 0
    ) {
        val count: Int get() = samples.size
        val collecting: Boolean get() = count < MIN_SAMPLES

        fun collectingLabel(): String = "Collecting: $count of $MIN_SAMPLES windows settled"

        /** AI Brier, and the market's own Brier when a market price was stored. */
        fun brierLabel(): String? {
            val ai = brier ?: return null
            val base = String.format(Locale.US, "Brier %.3f", ai)
            val market = marketBrier ?: return base
            return if (marketSamples in 1 until count) {
                base + String.format(Locale.US, " · market %.3f (n=%d)", market, marketSamples)
            } else {
                base + String.format(Locale.US, " · market %.3f", market)
            }
        }

        /**
         * AI UP as a 0–1 fraction. Samples store percent (0.4 means 0.4%).
         * [com.dirk.kalshiodds.ui.charts.ChartGallery.calibration] runs values through
         * `scalePct`, which treats anything ≤ 1 as a fraction, so passing the percent
         * straight through put 0.4% in the 40% bucket and 1% in the 100% bucket.
         */
        fun pairs(): List<Pair<Double, Boolean>> = samples.map { (it.aiUp / 100.0) to it.settledUp }
    }

    fun report(samples: List<Sample>): Report {
        val withMarket = samples.filter { it.marketUp != null }
        return Report(
            samples = samples.sortedBy { it.closeMs },
            brier = score(samples) { it.aiUp },
            marketBrier = score(withMarket) { it.marketUp },
            marketSamples = withMarket.size
        )
    }

    fun merge(
        ledger: List<LedgerRow>,
        scalps: List<ScalpTrade>,
        fills: List<PaperFill>,
        nowMs: Long,
        lookbackMs: Long = LOOKBACK_MS
    ): Report {
        val since = nowMs - lookbackMs
        val byWindow = LinkedHashMap<String, Sample>()
        for (sample in fromPaper(fills, nowMs, since)) byWindow[sample.key] = sample
        for (sample in fromScalp(scalps, nowMs, since)) byWindow[sample.key] = sample
        for (sample in fromLedger(ledger, nowMs, since)) byWindow[sample.key] = sample
        return report(byWindow.values.toList())
    }

    /** Preview / injected pairs. No market price, so no market Brier. */
    fun fromPairs(pairs: List<Pair<Double, Boolean>>): Report {
        val samples = pairs.mapIndexedNotNull { i, (raw, up) ->
            val ai = scalePct(raw) ?: return@mapIndexedNotNull null
            Sample("pair|$i", "", 0L, i.toLong(), ai, up, null)
        }
        return report(samples)
    }

    /**
     * Ledger rows already reduced to the last-before-close candidate set are fine.
     * Extra rows for the same window are reduced again here.
     */
    fun fromLedger(rows: List<LedgerRow>, nowMs: Long, sinceMs: Long): List<Sample> {
        val best = LinkedHashMap<String, LedgerRow>()
        for (row in rows) {
            if (!isFifteen(row.series, row.ticker)) continue
            val close = windowClose(row) ?: continue
            if (close < sinceMs || close > nowMs) continue
            if (row.timestampMs > close) continue
            if (row.outcomeYes == null || scalePct(row.finalProb) == null) continue
            val key = key(coinOf(row.series, row.ticker), close)
            val prev = best[key]
            if (prev == null || row.timestampMs > prev.timestampMs ||
                (row.timestampMs == prev.timestampMs && row.id > prev.id)
            ) {
                best[key] = row
            }
        }
        return best.map { (key, row) ->
            val close = windowClose(row)!!
            Sample(
                key = key,
                coin = coinOf(row.series, row.ticker),
                closeMs = close,
                atMs = row.timestampMs,
                aiUp = scalePct(row.finalProb)!!,
                settledUp = row.outcomeYes!!,
                marketUp = scalePct(row.marketMid)
            )
        }
    }

    fun fromScalp(trades: List<ScalpTrade>, nowMs: Long, sinceMs: Long): List<Sample> {
        data class Built(val sample: Sample, val primary: Boolean)
        val built = ArrayList<Built>()
        for (trade in trades) {
            val sample = scalpSample(trade, nowMs, sinceMs) ?: continue
            built += Built(sample, trade.isPrimary)
        }
        return built.groupBy { it.sample.key }.map { (_, rows) ->
            rows.maxWith(compareBy<Built> { it.primary }.thenBy { it.sample.atMs }).sample
        }
    }

    fun fromPaper(fills: List<PaperFill>, nowMs: Long, sinceMs: Long): List<Sample> {
        val best = LinkedHashMap<String, PaperFill>()
        for (fill in fills) {
            if (paperSample(fill, nowMs, sinceMs) == null) continue
            val close = ScalpTicker.closeMs(fill.ticker) ?: continue
            val key = key(ScalpParams.coinOf(fill.ticker), close)
            val prev = best[key]
            if (prev == null || fill.createdAtMs >= prev.createdAtMs) best[key] = fill
        }
        return best.values.map { paperSample(it, nowMs, sinceMs)!! }
    }

    /** One synthetic window per Eastern day so the correlation chart can see more than the latest 48 windows. */
    fun windowsFromDaily(days: List<ScalpDaily>): List<ScalpModels.WindowRow> =
        days.groupBy { it.dayEt }.mapNotNull { (day, rows) ->
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@mapNotNull null
            val close = date.atTime(12, 0).atZone(ET).toInstant().toEpochMilli()
            val net = LinkedHashMap<ScalpModels.Model, Double>()
            for (row in rows) {
                val model = ScalpModels.modelByCode(row.scalperId) ?: continue
                if (!row.netUsd.isFinite()) continue
                net[model] = (net[model] ?: 0.0) + row.netUsd
            }
            if (net.isEmpty()) return@mapNotNull null
            ScalpModels.WindowRow(day, close, net, emptyMap(), net.maxByOrNull { it.value }?.key)
        }.sortedBy { it.closeMs }

    private fun scalpSample(trade: ScalpTrade, nowMs: Long, sinceMs: Long): Sample? {
        if (trade.state != ScalpState.CLOSED) return null
        if (!isFifteen(null, trade.ticker)) return null
        val close = ScalpTicker.closeMs(trade.ticker) ?: return null
        if (close < sinceMs || close > nowMs) return null
        val ai = scalpAiUp(trade) ?: return null
        val settledUp = settledUp(trade) ?: return null
        val yes = !trade.side.equals("NO", ignoreCase = true)
        return Sample(
            key = key(trade.coin, close),
            coin = trade.coin,
            closeMs = close,
            atMs = trade.signalAtMs,
            aiUp = ai,
            settledUp = settledUp,
            marketUp = marketUp(yes, trade.signalAsk, trade.signalAsk)
        )
    }

    /** Held-to-settlement only. An early exit does not say whether UP settled. */
    private fun settledUp(trade: ScalpTrade): Boolean? {
        val sale = ScalpFills.sales(trade).lastOrNull { it.settlement } ?: return null
        val sideWon = sale.price >= 0.5
        val yes = !trade.side.equals("NO", ignoreCase = true)
        return if (yes) sideWon else !sideWon
    }

    /** Scalp `fairAtSignal` is a side probability in (0, 1]. Percents and non-probabilities are not an AI chance. */
    private fun scalpAiUp(trade: ScalpTrade): Double? {
        val p = trade.fairAtSignal.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 } ?: return null
        val yes = !trade.side.equals("NO", ignoreCase = true)
        return if (yes) p * 100.0 else (1.0 - p) * 100.0
    }

    private fun paperSample(fill: PaperFill, nowMs: Long, sinceMs: Long): Sample? {
        if (!fill.settled) return null
        val won = fill.won ?: return null
        if (!isFifteen(null, fill.ticker)) return null
        val close = ScalpTicker.closeMs(fill.ticker) ?: return null
        if (close < sinceMs || close > nowMs) return null
        val yes = !fill.side.equals("NO", ignoreCase = true)
        val ai = aiUp(yes, fill.aiPct) ?: return null
        return Sample(
            key = key(ScalpParams.coinOf(fill.ticker), close),
            coin = ScalpParams.coinOf(fill.ticker),
            closeMs = close,
            atMs = fill.createdAtMs,
            aiUp = ai,
            settledUp = if (yes) won else !won,
            marketUp = marketUp(yes, fill.marketPct, fill.limitPrice)
        )
    }

    private fun score(samples: List<Sample>, prob: (Sample) -> Double?): Double? {
        if (samples.isEmpty()) return null
        var sum = 0.0
        var n = 0
        for (sample in samples) {
            val p = prob(sample) ?: continue
            val x = (p / 100.0).coerceIn(0.0, 1.0)
            val y = if (sample.settledUp) 1.0 else 0.0
            val d = x - y
            sum += d * d
            n++
        }
        return if (n == 0) null else sum / n
    }

    /**
     * Prefer the stored epoch. Ticker text is a wall clock (`yyMMMddHHmm`) zoned in
     * ET, and [java.time.LocalDateTime.atZone] keeps only the first 1 AM–2 AM when
     * clocks fall back, so the repeated hour would share one close.
     */
    fun windowClose(row: LedgerRow): Long? = row.closeTimeMs ?: ScalpTicker.closeMs(row.ticker)

    fun isFifteen(series: String?, ticker: String): Boolean {
        val blob = "${series.orEmpty()} $ticker".uppercase()
        return CryptoMarkets.FIFTEEN_SERIES.any { blob.contains(it) }
    }

    fun coinOf(series: String?, ticker: String): String = ScalpParams.coinOf(
        ticker.ifBlank { series.orEmpty() }.ifBlank { "BTC" }
    )

    fun key(coin: String, closeMs: Long): String = "$coin|$closeMs"

    fun scalePct(raw: Double?): Double? {
        val v = raw?.takeIf { it.isFinite() } ?: return null
        val pct = if (v <= 1.0 + 1e-9) v * 100.0 else v
        return pct.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    fun aiUp(sideYes: Boolean, stored: Double?): Double? {
        val p = scalePct(stored) ?: return null
        return if (sideYes) p else 100.0 - p
    }

    fun marketUp(sideYes: Boolean, marketPct: Double?, entryAsk: Double?): Double? {
        val m = scalePct(marketPct) ?: return null
        val ask = entryAsk?.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100.0)
        if (ask == null) return if (sideYes) m else null
        val asSide = abs(m - ask) <= abs(m - (100.0 - ask))
        val sidePct = if (asSide) m else 100.0 - m
        return if (sideYes) sidePct else 100.0 - sidePct
    }
}
