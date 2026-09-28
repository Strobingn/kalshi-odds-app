package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.ml.PolicyEval
import java.time.Instant
import java.time.ZoneId

/**
 * Phone-readable post-settlement scorecard numbers.
 * Void outcomes are excluded from hit-rate / Brier / edge averages.
 */
object ScorecardMetrics {
    data class WindowStats(
        val hits: Int = 0,
        val total: Int = 0,
        val hitRate: Double? = null,
        /** Mean probability Brier expressed in picked-side coordinates. Equal to P(YES) Brier. */
        val brier: Double? = null,
        /** Mean P(YES) / P(UP) Brier. Scorecard-only; hidden when N < 20. */
        val pUpBrier: Double? = null,
        val avgEdgeWhenRight: Double? = null,
        val avgEdgeWhenWrong: Double? = null
    ) {
        val label: String
            get() = if (total <= 0) "—" else "$hits/$total"
        val showBrier: Boolean
            get() = total >= MIN_BRIER_DISPLAY
    }

    data class SeriesStats(
        val series: String,
        val label: String,
        val stats: WindowStats
    )

    data class Breakdown(
        val key: String,
        val label: String,
        val n: Int,
        val hitRate: Double?,
        val modelBrier: Double?,
        val marketBrier: Double?,
        val pnlUsd: Double?,
        val enoughData: Boolean
    ) {
        val honestLabel: String
            get() = if (!enoughData) {
                "Not enough data (${n}/${MIN_BUCKET_SAMPLES})"
            } else {
                "${n} settled"
            }
    }

    data class Honest(
        val n: Int,
        val modelBrier: Double?,
        val marketBrier: Double?,
        val hitRate: Double?,
        val avgEdgeWhenRight: Double?,
        val avgEdgeWhenWrong: Double?,
        val enoughData: Boolean,
        val perAsset: List<SeriesStats>,
        val perCoin: List<Breakdown> = emptyList(),
        val perTimeOfDay: List<Breakdown> = emptyList(),
        val sideBrier: Double? = null,
        val hits: Int = 0
    ) {
        val showBrier: Boolean
            get() = n >= MIN_BRIER_DISPLAY
    }

    data class Snapshot(
        val daily: WindowStats,
        val rolling: WindowStats,
        val allTime: WindowStats,
        val perSeries: List<SeriesStats>,
        val sampleCount: Int,
        val openCount: Int,
        val voidCount: Int,
        val calibrationReady: Boolean,
        val temperature: Double?,
        val calibrationSamples: Int,
        val policy: PolicyEval.Scorecard? = null,
        val honest: Honest = Honest(0, null, null, null, null, null, false, emptyList())
    )

    fun compute(
        entries: List<PredictionLogEntry>,
        nowMs: Long = System.currentTimeMillis(),
        calibration: Calibrator.State = Calibrator.State(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        policyStakeUsd: Double = SignalConstants.DEFAULT_POLICY_EVAL_STAKE_USD,
        edgeThresholdPp: Double = 5.0,
        minConfidence: Double = SignalConstants.DEFAULT_MIN_CONFIDENCE,
        requireUncertaintyPass: Boolean = false,
        maxUncertainty: Double = SignalConstants.DEFAULT_MAX_UNCERTAINTY
    ): Snapshot {
        val settled = settledScoredPicks(entries)
        val voids = entries.count { it.outcome.equals("void", true) }
        val open = entries.count { it.outcome == null }
        val dayStart = startOfLocalDayMs(nowMs, zoneId)
        val rollingStart = nowMs - SignalConstants.SCORECARD_ROLLING_DAYS * 86_400_000L
        return Snapshot(
            daily = window(settled.filter { settledAt(it) >= dayStart }),
            rolling = window(settled.filter { settledAt(it) >= rollingStart }),
            allTime = window(settled),
            perSeries = settled.groupBy { it.series.ifBlank { "unknown" } }
                .toSortedMap()
                .map { (series, rows) ->
                    SeriesStats(series = series, label = seriesLabel(series), stats = window(rows))
                },
            sampleCount = settled.size,
            openCount = open,
            voidCount = voids,
            calibrationReady = calibration.ready,
            temperature = if (calibration.ready) calibration.temperature else null,
            calibrationSamples = calibration.sampleCount,
            honest = honest(settled, zoneId),
            policy = PolicyEval.evaluate(
                entries = entries,
                stakeUsd = policyStakeUsd,
                edgeThresholdPp = edgeThresholdPp,
                minConfidence = minConfidence,
                requireUncertaintyPass = requireUncertaintyPass,
                maxUncertainty = maxUncertainty
            )
        )
    }

    const val MIN_HONEST_SAMPLES = 100
    const val MIN_BUCKET_SAMPLES = SignalConstants.SCORECARD_BUCKET_MIN_SAMPLES
    const val MIN_BRIER_DISPLAY = 20

    /** Fixed home / scorecard coin order. */
    val COIN_ORDER: List<String> = listOf("BTC", "SOL", "ETH")

    /**
     * Settled directional picks that count on the scorecard.
     * YES/NO outcomes only; voids and NO BET rows are excluded.
     * Home and the full scorecard both use this list.
     */
    fun settledScoredPicks(entries: List<PredictionLogEntry>): List<PredictionLogEntry> =
        entries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                ForecastUnits.isScoredPick(it)
        }

    fun honest(
        rows: List<PredictionLogEntry>,
        zoneId: ZoneId = ZoneId.of("America/New_York")
    ): Honest {
        val scored = rows.filter { ForecastUnits.isScoredPick(it) }
        if (scored.isEmpty()) {
            return Honest(0, null, null, null, null, null, false, emptyList())
        }
        val all = window(scored)
        val modelBriers = scored.map { ForecastUnits.brier(it) }
        val marketBriers = scored.map { ForecastUnits.marketBrier(it) }
        val sideBriers = scored.map { ForecastUnits.sideBrier(it) }
        val per = scored.groupBy { it.series.ifBlank { "unknown" } }
            .toSortedMap()
            .map { (series, group) ->
                SeriesStats(series = series, label = seriesLabel(series), stats = window(group))
            }
        return Honest(
            n = scored.size,
            modelBrier = modelBriers.average(),
            marketBrier = marketBriers.average(),
            hitRate = all.hitRate,
            avgEdgeWhenRight = all.avgEdgeWhenRight,
            avgEdgeWhenWrong = all.avgEdgeWhenWrong,
            enoughData = scored.size >= MIN_HONEST_SAMPLES,
            perAsset = per,
            perCoin = coinBreakdowns(scored),
            perTimeOfDay = timeOfDayBreakdowns(scored, zoneId),
            sideBrier = sideBriers.average(),
            hits = all.hits
        )
    }

    fun coinBreakdowns(rows: List<PredictionLogEntry>): List<Breakdown> {
        val groups = rows.groupBy { coinOf(it.series.ifBlank { it.ticker }) }
        return COIN_ORDER.map { coin ->
            breakdown(coin, coin, groups[coin].orEmpty())
        } + groups.keys.filter { it !in COIN_ORDER && it != "OTHER" }.sorted().map { coin ->
            breakdown(coin, coin, groups[coin].orEmpty())
        }
    }

    fun timeOfDayBreakdowns(
        rows: List<PredictionLogEntry>,
        zoneId: ZoneId = ZoneId.of("America/New_York")
    ): List<Breakdown> {
        val buckets = (0 until 6).map { i ->
            val start = i * 4
            val end = start + 4
            val key = "%02d-%02d".format(start, end)
            val label = "%d–%d ET".format(start, end)
            key to label
        }
        val groups = rows.groupBy { etBucketKey(settledAt(it), zoneId) }
        return buckets.map { (key, label) ->
            breakdown(key, label, groups[key].orEmpty())
        }
    }

    fun etBucketKey(epochMs: Long, zoneId: ZoneId = ZoneId.of("America/New_York")): String {
        val hour = Instant.ofEpochMilli(epochMs).atZone(zoneId).hour
        val start = (hour / 4) * 4
        return "%02d-%02d".format(start, start + 4)
    }

    fun coinOf(seriesOrTicker: String): String {
        val u = seriesOrTicker.uppercase()
        return when {
            u.contains("BTC") -> "BTC"
            u.contains("ETH") -> "ETH"
            u.contains("SOL") -> "SOL"
            else -> "OTHER"
        }
    }

    private fun breakdown(key: String, label: String, rows: List<PredictionLogEntry>): Breakdown {
        val scored = rows.filter { ForecastUnits.isScoredPick(it) }
        if (scored.isEmpty()) {
            return Breakdown(key, label, 0, null, null, null, null, false)
        }
        val hits = scored.count { ForecastUnits.hit(it) }
        val modelBrier = scored.map { ForecastUnits.brier(it) }.average()
        val marketBrier = scored.map { ForecastUnits.marketBrier(it) }.average()
        val pnl = scored.sumOf { e ->
            val won = ForecastUnits.hit(e)
            val stake = 1.0
            if (won) stake * kotlin.math.abs(e.edgePp ?: 0.0) / 100.0 else -stake * kotlin.math.abs(e.edgePp ?: 0.0) / 100.0
        }
        return Breakdown(
            key = key,
            label = label,
            n = scored.size,
            hitRate = hits.toDouble() / scored.size,
            modelBrier = modelBrier,
            marketBrier = marketBrier,
            pnlUsd = pnl,
            enoughData = scored.size >= MIN_BUCKET_SAMPLES
        )
    }

    fun window(rows: List<PredictionLogEntry>): WindowStats {
        val scored = rows.filter { ForecastUnits.isScoredPick(it) }
        if (scored.isEmpty()) return WindowStats()
        val hits = scored.count { ForecastUnits.hit(it) }
        val sideBriers = scored.map { ForecastUnits.sideBrier(it) }
        val yesBriers = scored.map { ForecastUnits.brier(it) }
        val rightEdges = scored.filter { ForecastUnits.hit(it) }
            .mapNotNull { it.edgePp }
        val wrongEdges = scored.filter { !ForecastUnits.hit(it) }
            .mapNotNull { it.edgePp }
        return WindowStats(
            hits = hits,
            total = scored.size,
            hitRate = hits.toDouble() / rows.size,
            brier = if (sideBriers.isNotEmpty()) sideBriers.average() else null,
            pUpBrier = if (yesBriers.isNotEmpty()) yesBriers.average() else null,
            avgEdgeWhenRight = rightEdges.takeIf { it.isNotEmpty() }?.average(),
            avgEdgeWhenWrong = wrongEdges.takeIf { it.isNotEmpty() }?.average()
        )
    }

    private fun sideHit(e: PredictionLogEntry): Boolean = ForecastUnits.hit(e)

    private fun brierOf(e: PredictionLogEntry): Double = ForecastUnits.sideBrier(e)

    private fun settledAt(e: PredictionLogEntry): Long = e.settledAtMs ?: e.timestampMs

    private fun startOfLocalDayMs(nowMs: Long, zoneId: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        return zoned.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun seriesLabel(series: String): String = when {
        series.contains("BTC") -> "Bitcoin"
        series.contains("ETH") -> "Ethereum"
        series.contains("SOL") -> "Solana"
        else -> series
    }
}
