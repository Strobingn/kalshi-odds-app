package com.dirk.kalshiodds.prediction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.predictionLogStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_prediction_log",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Serializable
data class CalSample(
    val rawYes: Double,
    val tteSeconds: Long,
    val bucket: String
)

@Serializable
data class PredictionLogEntry(
    val ticker: String,
    val series: String,
    val predictedYes: Double,
    val predictedNo: Double,
    val marketMid: Double,
    val timestampMs: Long,
    val closeTimeMs: Long?,
    val outcome: String? = null, // "yes" | "no" | "void"
    val score: Int? = null, // 1 correct, 0 wrong; null for void
    val brier: Double? = null,
    /** YES or NO stance at signal time. */
    val predictedSide: String? = null,
    val edgePp: Double? = null,
    val confidence: Double? = null,
    val regime: String? = null,
    val tteBucket: String? = null,
    val fairValuePp: Double? = null,
    val calibrated: Boolean? = null,
    val settledAtMs: Long? = null,
    /** Blend-channel deviations (featureFair − mid) in pp, for on-device learning. */
    val featureDevs: Map<String, Double> = emptyMap(),
    val uncertainty: Double? = null,
    val timeToMoveSec: Double? = null,
    val midVolPp: Double? = null,
    val pFill: Double? = null,
    val wouldAlert: Boolean? = null,
    val mlpYes: Double? = null,
    val cnnYes: Double? = null,
    val gbmYes: Double? = null,
    /** Entry ask (0–1) for the picked side at signal time. */
    val entryAsk: Double? = null,
    val contracts: Int? = null,
    val stakeUsd: Double? = null,
    val feeUsd: Double? = null,
    /** Raw uncalibrated blend (0–1). Calibrator fits on this, not [predictedYes]. */
    val rawPredictedYes: Double? = null,
    val displayedYes: Double? = null,
    val tteSeconds: Long? = null,
    val calSamples: List<CalSample> = emptyList()
)

@Serializable
data class SignalSnapshot(
    val predictedSide: String? = null,
    val edgePp: Double? = null,
    val confidence: Double? = null,
    val regime: String? = null,
    val tteBucket: String? = null,
    val fairValuePp: Double? = null,
    val calibrated: Boolean? = null,
    val featureDevs: Map<String, Double> = emptyMap(),
    val uncertainty: Double? = null,
    val timeToMoveSec: Double? = null,
    val midVolPp: Double? = null,
    val pFill: Double? = null,
    val wouldAlert: Boolean? = null,
    val mlpYes: Double? = null,
    val cnnYes: Double? = null,
    val gbmYes: Double? = null,
    val entryAsk: Double? = null,
    val contracts: Int? = null,
    val stakeUsd: Double? = null,
    val feeUsd: Double? = null,
    val rawPredictedYes: Double? = null,
    val displayedYes: Double? = null,
    val tteSeconds: Long? = null
)

/**
 * How an open prediction row is refreshed.
 *
 * The first captured entry ask / contracts / stake / fee stick
 * (`prev ?: snapshot`). If the logged side changes, the price is
 * replaced together with the side — a missing ask on the new side
 * becomes null, never the other side's penny price.
 *
 * Open rows whose close time is more than [STALE_OPEN_GRACE_MS] ago
 * are evicted so they stop occupying the 400-row log. They are not
 * given a made-up settlement.
 */
internal object OpenPredictionMerge {
    const val STALE_OPEN_GRACE_MS = 30L * 60L * 1000L

    data class Frozen(
        val predictedSide: String?,
        val entryAsk: Double?,
        val contracts: Int?,
        val stakeUsd: Double?,
        val feeUsd: Double?
    )

    fun sideChanged(prevSide: String?, nextSide: String?): Boolean {
        if (prevSide.isNullOrBlank() || nextSide.isNullOrBlank()) return false
        val prev = com.dirk.kalshiodds.signal.model.SignalStance.normalizeSide(prevSide)
        val next = com.dirk.kalshiodds.signal.model.SignalStance.normalizeSide(nextSide)
        if (prev == null || next == null) return !prevSide.equals(nextSide, ignoreCase = true)
        return prev != next
    }

    fun freeze(
        prevSide: String?,
        prevAsk: Double?,
        prevContracts: Int?,
        prevStake: Double?,
        prevFee: Double?,
        snapSide: String?,
        snapAsk: Double?,
        snapContracts: Int?,
        snapStake: Double?,
        snapFee: Double?
    ): Frozen {
        return if (sideChanged(prevSide, snapSide)) {
            Frozen(
                predictedSide = snapSide,
                entryAsk = snapAsk,
                contracts = snapContracts,
                stakeUsd = snapStake,
                feeUsd = snapFee
            )
        } else {
            Frozen(
                predictedSide = snapSide ?: prevSide,
                entryAsk = prevAsk ?: snapAsk,
                contracts = prevContracts ?: snapContracts,
                stakeUsd = prevStake ?: snapStake,
                feeUsd = prevFee ?: snapFee
            )
        }
    }

    fun isStaleOpen(outcome: String?, closeTimeMs: Long?, nowMs: Long, graceMs: Long = STALE_OPEN_GRACE_MS): Boolean {
        if (outcome != null) return false
        val close = closeTimeMs ?: return false
        if (close <= 0L) return false
        return nowMs >= close + graceMs
    }
}

class PredictionLogStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val key = stringPreferencesKey("predictions_json")
    private val maxEntries = 400

    val entriesFlow: Flow<List<PredictionLogEntry>> = context.predictionLogStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun readAll(): List<PredictionLogEntry> = entriesFlow.first()

    suspend fun upsertOpenPrediction(
        ticker: String,
        series: String,
        predictedYes: Double,
        predictedNo: Double,
        marketMid: Double,
        timestampMs: Long,
        closeTimeMs: Long?,
        throttleMs: Long = 30_000L,
        midMoveThreshold: Double = 0.02,
        snapshot: SignalSnapshot? = null
    ) {
        context.predictionLogStore.edit { prefs ->
            val raw = decode(prefs[key])
            val swept = raw.filterNot {
                OpenPredictionMerge.isStaleOpen(it.outcome, it.closeTimeMs, timestampMs)
            }
            val sweptAny = swept.size != raw.size
            val list = swept.toMutableList()
            if (OpenPredictionMerge.isStaleOpen(null, closeTimeMs, timestampMs)) {
                if (sweptAny) prefs[key] = json.encodeToString(list)
                return@edit
            }
            val existingIdx = list.indexOfLast { it.ticker == ticker && it.outcome == null }
            val rawYes = snapshot?.rawPredictedYes
            val tteSec = snapshot?.tteSeconds
            val bucket = com.dirk.kalshiodds.signal.feedback.Calibrator.TteBucket.of(tteSec)
            if (existingIdx >= 0) {
                val prev = list[existingIdx]
                val age = timestampMs - prev.timestampMs
                val moved = kotlin.math.abs(prev.marketMid - marketMid) > midMoveThreshold
                val samples = prev.calSamples.toMutableList()
                if (rawYes != null && rawYes.isFinite() && tteSec != null) {
                    if (samples.none { it.bucket == bucket.key }) {
                        samples.add(CalSample(rawYes, tteSec, bucket.key))
                    }
                }
                // Do not freeze at LATE — keep the first sample per TTE bucket
                // and still refresh the displayed row so scorecard is current.
                if (age < throttleMs && !moved && samples.size == prev.calSamples.size) {
                    if (sweptAny) prefs[key] = json.encodeToString(list)
                    return@edit
                }
                val frozen = OpenPredictionMerge.freeze(
                    prevSide = prev.predictedSide,
                    prevAsk = prev.entryAsk,
                    prevContracts = prev.contracts,
                    prevStake = prev.stakeUsd,
                    prevFee = prev.feeUsd,
                    snapSide = snapshot?.predictedSide,
                    snapAsk = snapshot?.entryAsk,
                    snapContracts = snapshot?.contracts,
                    snapStake = snapshot?.stakeUsd,
                    snapFee = snapshot?.feeUsd
                )
                list[existingIdx] = prev.copy(
                    predictedYes = predictedYes,
                    predictedNo = predictedNo,
                    marketMid = marketMid,
                    timestampMs = timestampMs,
                    closeTimeMs = closeTimeMs ?: prev.closeTimeMs,
                    predictedSide = frozen.predictedSide,
                    edgePp = snapshot?.edgePp ?: prev.edgePp,
                    confidence = snapshot?.confidence ?: prev.confidence,
                    regime = snapshot?.regime ?: prev.regime,
                    tteBucket = snapshot?.tteBucket ?: prev.tteBucket,
                    fairValuePp = snapshot?.fairValuePp ?: prev.fairValuePp,
                    calibrated = snapshot?.calibrated ?: prev.calibrated,
                    featureDevs = snapshot?.featureDevs?.takeIf { it.isNotEmpty() } ?: prev.featureDevs,
                    uncertainty = snapshot?.uncertainty ?: prev.uncertainty,
                    timeToMoveSec = snapshot?.timeToMoveSec ?: prev.timeToMoveSec,
                    midVolPp = snapshot?.midVolPp ?: prev.midVolPp,
                    pFill = snapshot?.pFill ?: prev.pFill,
                    wouldAlert = snapshot?.wouldAlert ?: prev.wouldAlert,
                    mlpYes = snapshot?.mlpYes ?: prev.mlpYes,
                    cnnYes = snapshot?.cnnYes ?: prev.cnnYes,
                    gbmYes = snapshot?.gbmYes ?: prev.gbmYes,
                    entryAsk = frozen.entryAsk,
                    contracts = frozen.contracts,
                    stakeUsd = frozen.stakeUsd,
                    feeUsd = frozen.feeUsd,
                    rawPredictedYes = rawYes ?: prev.rawPredictedYes,
                    displayedYes = snapshot?.displayedYes ?: prev.displayedYes,
                    tteSeconds = tteSec ?: prev.tteSeconds,
                    calSamples = samples
                )
            } else {
                val first = if (rawYes != null && rawYes.isFinite() && tteSec != null) {
                    listOf(CalSample(rawYes, tteSec, bucket.key))
                } else {
                    emptyList()
                }
                list.add(
                    PredictionLogEntry(
                        ticker = ticker,
                        series = series,
                        predictedYes = predictedYes,
                        predictedNo = predictedNo,
                        marketMid = marketMid,
                        timestampMs = timestampMs,
                        closeTimeMs = closeTimeMs,
                        predictedSide = snapshot?.predictedSide,
                        edgePp = snapshot?.edgePp,
                        confidence = snapshot?.confidence,
                        regime = snapshot?.regime,
                        tteBucket = snapshot?.tteBucket,
                        fairValuePp = snapshot?.fairValuePp,
                        calibrated = snapshot?.calibrated,
                        featureDevs = snapshot?.featureDevs ?: emptyMap(),
                        uncertainty = snapshot?.uncertainty,
                        timeToMoveSec = snapshot?.timeToMoveSec,
                        midVolPp = snapshot?.midVolPp,
                        pFill = snapshot?.pFill,
                        wouldAlert = snapshot?.wouldAlert,
                        mlpYes = snapshot?.mlpYes,
                        cnnYes = snapshot?.cnnYes,
                        gbmYes = snapshot?.gbmYes,
                        entryAsk = snapshot?.entryAsk,
                        contracts = snapshot?.contracts,
                        stakeUsd = snapshot?.stakeUsd,
                        feeUsd = snapshot?.feeUsd,
                        rawPredictedYes = rawYes,
                        displayedYes = snapshot?.displayedYes,
                        tteSeconds = tteSec,
                        calSamples = first
                    )
                )
            }
            while (list.size > maxEntries) list.removeAt(0)
            prefs[key] = json.encodeToString(list)
        }
    }

    suspend fun applySettlement(ticker: String, result: String, settledAtMs: Long = System.currentTimeMillis()) {
        val normalized = result.lowercase().trim()
        if (normalized != "yes" && normalized != "no" && normalized != "void") return
        context.predictionLogStore.edit { prefs ->
            val list = decode(prefs[key]).toMutableList()
            var changed = false
            for (i in list.indices) {
                val e = list[i]
                if (e.ticker == ticker && e.outcome == null) {
                    if (normalized == "void") {
                        list[i] = e.copy(outcome = "void", score = null, brier = null, settledAtMs = settledAtMs)
                    } else {
                        val next = e.copy(outcome = normalized, settledAtMs = settledAtMs)
                        val hit = com.dirk.kalshiodds.signal.feedback.ForecastUnits.hit(next)
                        list[i] = next.copy(
                            score = if (hit) 1 else 0,
                            brier = com.dirk.kalshiodds.signal.feedback.ForecastUnits.sideBrier(next)
                        )
                    }
                    changed = true
                }
            }
            if (changed) prefs[key] = json.encodeToString(list)
        }
    }

    data class ScoreSummary(val correct: Int, val total: Int, val meanBrier: Double?)

    suspend fun scoreSummary(): ScoreSummary {
        // Recompute from predictedSide / predictedYes / outcome. Ignore stored
        // score and brier columns so existing logs are rescored on read.
        val scored = readAll().filter { it.outcome != null && !it.outcome.equals("void", true) }
        val stats = com.dirk.kalshiodds.signal.feedback.ScorecardMetrics.window(scored)
        return ScoreSummary(stats.hits, stats.total, stats.brier)
    }

    private fun decode(raw: String?): List<PredictionLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PredictionLogEntry>>(raw) }.getOrElse { emptyList() }
    }
}
