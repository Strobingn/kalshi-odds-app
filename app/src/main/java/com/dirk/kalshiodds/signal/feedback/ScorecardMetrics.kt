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
        val brier: Double? = null,
        val avgEdgeWhenRight: Double? = null,
        val avgEdgeWhenWrong: Double? = null
    ) {
        val label: String
            get() = if (total <= 0) "—" else "$hits/$total"
    }

    data class SeriesStats(
        val series: String,
        val label: String,
        val stats: WindowStats
    )

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
        val policy: PolicyEval.Scorecard? = null
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
        val settled = entries.filter { it.outcome.equals("yes", true) || it.outcome.equals("no", true) }
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

    fun window(rows: List<PredictionLogEntry>): WindowStats {
        if (rows.isEmpty()) return WindowStats()
        val hits = rows.count { it.score == 1 || (it.score == null && sideHit(it)) }
        val briers = rows.map { it.brier ?: brierOf(it) }
        val rightEdges = rows.filter { it.score == 1 || (it.score == null && sideHit(it)) }
            .mapNotNull { it.edgePp }
        val wrongEdges = rows.filter { it.score == 0 || (it.score == null && !sideHit(it)) }
            .mapNotNull { it.edgePp }
        return WindowStats(
            hits = hits,
            total = rows.size,
            hitRate = hits.toDouble() / rows.size,
            brier = if (briers.isNotEmpty()) briers.average() else null,
            avgEdgeWhenRight = rightEdges.takeIf { it.isNotEmpty() }?.average(),
            avgEdgeWhenWrong = wrongEdges.takeIf { it.isNotEmpty() }?.average()
        )
    }

    private fun sideHit(e: PredictionLogEntry): Boolean {
        val predYes = when (e.predictedSide?.uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> e.predictedYes > 0.5
        }
        return predYes == e.outcome.equals("yes", true)
    }

    private fun brierOf(e: PredictionLogEntry): Double {
        val y = if (e.outcome.equals("yes", true)) 1.0 else 0.0
        val d = e.predictedYes - y
        return d * d
    }

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
