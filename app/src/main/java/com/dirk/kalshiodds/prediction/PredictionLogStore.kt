package com.dirk.kalshiodds.prediction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.predictionLogStore: DataStore<Preferences> by preferencesDataStore(name = "diphunter_prediction_log")

@Serializable
data class PredictionLogEntry(
    val ticker: String,
    val series: String,
    val predictedYes: Double,
    val predictedNo: Double,
    val marketMid: Double,
    val timestampMs: Long,
    val closeTimeMs: Long?,
    val outcome: String? = null, // "yes" | "no"
    val score: Int? = null, // 1 correct, 0 wrong
    val brier: Double? = null
)

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
        midMoveThreshold: Double = 0.02
    ) {
        context.predictionLogStore.edit { prefs ->
            val list = decode(prefs[key]).toMutableList()
            val existingIdx = list.indexOfLast { it.ticker == ticker && it.outcome == null }
            if (existingIdx >= 0) {
                val prev = list[existingIdx]
                val age = timestampMs - prev.timestampMs
                val moved = kotlin.math.abs(prev.marketMid - marketMid) > midMoveThreshold
                if (age < throttleMs && !moved) {
                    return@edit
                }
                list[existingIdx] = prev.copy(
                    predictedYes = predictedYes,
                    predictedNo = predictedNo,
                    marketMid = marketMid,
                    timestampMs = timestampMs,
                    closeTimeMs = closeTimeMs ?: prev.closeTimeMs
                )
            } else {
                list.add(
                    PredictionLogEntry(
                        ticker = ticker,
                        series = series,
                        predictedYes = predictedYes,
                        predictedNo = predictedNo,
                        marketMid = marketMid,
                        timestampMs = timestampMs,
                        closeTimeMs = closeTimeMs
                    )
                )
            }
            while (list.size > maxEntries) list.removeAt(0)
            prefs[key] = json.encodeToString(list)
        }
    }

    suspend fun applySettlement(ticker: String, result: String) {
        val normalized = result.lowercase()
        if (normalized != "yes" && normalized != "no") return
        context.predictionLogStore.edit { prefs ->
            val list = decode(prefs[key]).toMutableList()
            var changed = false
            for (i in list.indices) {
                val e = list[i]
                if (e.ticker == ticker && e.outcome == null) {
                    val predYes = e.predictedYes > 0.5
                    val actualYes = normalized == "yes"
                    val score = if (predYes == actualYes) 1 else 0
                    val y = if (actualYes) 1.0 else 0.0
                    val brier = (e.predictedYes - y) * (e.predictedYes - y)
                    list[i] = e.copy(outcome = normalized, score = score, brier = brier)
                    changed = true
                }
            }
            if (changed) prefs[key] = json.encodeToString(list)
        }
    }

    data class ScoreSummary(val correct: Int, val total: Int, val meanBrier: Double?)

    suspend fun scoreSummary(): ScoreSummary {
        val scored = readAll().filter { it.score != null }
        val correct = scored.count { it.score == 1 }
        val briers = scored.mapNotNull { it.brier }
        val meanBrier = if (briers.isNotEmpty()) briers.average() else null
        return ScoreSummary(correct, scored.size, meanBrier)
    }

    private fun decode(raw: String?): List<PredictionLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PredictionLogEntry>>(raw) }.getOrElse { emptyList() }
    }
}
