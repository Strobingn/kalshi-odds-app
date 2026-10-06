package com.dirk.kalshiodds.prediction.ledger

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.random.Random

/**
 * Is the model better than Kalshi's own price? Scores every settled call
 * twice, the model's P(YES) and the market mid, and compares them on the
 * same windows.
 *
 * Brier = mean (forecast − outcome)²; lower is better. The difference
 * (model − market) gets a 95% interval by resampling whole UTC days, since
 * windows on the same day move together. The model "beats the market" only
 * when that whole interval is below zero and there are at least
 * [MIN_DAYS] days; otherwise the market price is the better forecast.
 */
object LedgerReport {
    const val MIN_DAYS = 6
    const val MIN_ROWS = 50
    private const val BOOT = 2_000
    private const val DAY_MS = 86_400_000L

    data class Bucket(
        /** Bucket range in %, e.g. 40..50. */
        val lowPct: Int,
        val highPct: Int,
        val n: Int,
        /** Mean forecast in this bucket, 0–1. */
        val meanForecast: Double,
        /** Share that settled YES, 0–1. */
        val actualYes: Double
    )

    enum class Verdict { NOT_ENOUGH_DATA, MODEL_BETTER, MARKET_BETTER, NO_DIFFERENCE }

    data class Result(
        val n: Int,
        val days: Int,
        val modelBrier: Double,
        val marketBrier: Double,
        /** model − market; negative means the model was better. */
        val diff: Double,
        val diffLow: Double?,
        val diffHigh: Double?,
        val verdict: Verdict,
        val modelBuckets: List<Bucket>,
        val marketBuckets: List<Bucket>,
        /** Same comparison on the calls the app would have bet on. */
        val betN: Int,
        val betModelBrier: Double?,
        val betMarketBrier: Double?,
        val versions: Int
    ) {
        val headline: String
            get() = when (verdict) {
                Verdict.NOT_ENOUGH_DATA -> "Not enough settled calls yet ($n calls, $days days; need $MIN_ROWS calls over $MIN_DAYS days)"
                Verdict.MODEL_BETTER -> "The model beat Kalshi's price on these $n calls"
                Verdict.MARKET_BETTER -> "Kalshi's price was the better forecast on these $n calls"
                Verdict.NO_DIFFERENCE -> "No clear difference from Kalshi's price on these $n calls"
            }
    }

    fun compute(rows: List<LedgerRow>, seed: Int = 7): Result? {
        val r = rows.filter { it.outcome == "yes" || it.outcome == "no" }
        if (r.isEmpty()) return null
        val model = r.map { sq(it.modelYes - it.yes) }
        val market = r.map { sq(it.marketMid - it.yes) }
        val modelBrier = model.average()
        val marketBrier = market.average()
        val diff = modelBrier - marketBrier

        val byDay = r.indices.groupBy { r[it].settledAtMs / DAY_MS }.values.toList()
        val days = byDay.size
        var low: Double? = null
        var high: Double? = null
        if (days >= 2) {
            val rnd = Random(seed)
            val stats = DoubleArray(BOOT) {
                var sum = 0.0
                var cnt = 0
                repeat(days) {
                    for (i in byDay[rnd.nextInt(days)]) {
                        sum += model[i] - market[i]
                        cnt++
                    }
                }
                if (cnt == 0) 0.0 else sum / cnt
            }
            stats.sort()
            low = stats[(0.025 * (BOOT - 1)).toInt()]
            high = stats[(0.975 * (BOOT - 1)).toInt()]
        }
        val verdict = when {
            r.size < MIN_ROWS || days < MIN_DAYS || low == null || high == null -> Verdict.NOT_ENOUGH_DATA
            high < 0.0 -> Verdict.MODEL_BETTER
            low > 0.0 -> Verdict.MARKET_BETTER
            else -> Verdict.NO_DIFFERENCE
        }
        val bets = r.filter { it.wouldBet == true }
        return Result(
            n = r.size,
            days = days,
            modelBrier = modelBrier,
            marketBrier = marketBrier,
            diff = diff,
            diffLow = low,
            diffHigh = high,
            verdict = verdict,
            modelBuckets = buckets(r) { it.modelYes },
            marketBuckets = buckets(r) { it.marketMid },
            betN = bets.size,
            betModelBrier = bets.takeIf { it.isNotEmpty() }?.map { sq(it.modelYes - it.yes) }?.average(),
            betMarketBrier = bets.takeIf { it.isNotEmpty() }?.map { sq(it.marketMid - it.yes) }?.average(),
            versions = r.map { it.modelVersion }.distinct().size
        )
    }

    /** Ten 10-point buckets; empty buckets are left out. */
    fun buckets(rows: List<LedgerRow>, forecast: (LedgerRow) -> Double): List<Bucket> =
        rows.groupBy { floor(forecast(it).coerceIn(0.0, 0.9999) * 10).toInt() }
            .toSortedMap()
            .map { (b, rs) ->
                Bucket(
                    lowPct = b * 10,
                    highPct = b * 10 + 10,
                    n = rs.size,
                    meanForecast = rs.map(forecast).average(),
                    actualYes = rs.map { it.yes.toDouble() }.average()
                )
            }

    /** UTC day label for a timestamp, for exports. */
    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    private fun sq(x: Double) = x * x
}
