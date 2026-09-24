package com.dirk.kalshiodds.signal.ml

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.heavyMlStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_heavy_ml",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Serializable
data class ExtendedAiPersisted(
    val rlLogits: List<Double> = emptyList(),
    val rlN: Int = 0,
    val metaW: List<Double> = emptyList(),
    val metaB: Double = 0.2,
    val metaN: Int = 0,
    val conformalScores: List<Double> = emptyList(),
    val conformalQ: Double = 0.5,
    val conformalAlpha: Double = ConformalSets.DEFAULT_ALPHA,
    val lastSettledAtMs: Long = 0L
)

@Serializable
data class HeavyMlPersisted(
    val stack: EnsembleStack.Weights = EnsembleStack.identity(),
    val regime: RegimeCalibrator.State = RegimeCalibrator.identity(),
    val yesW: List<Float> = emptyList(),
    val yesB: Float = 0f,
    val lastSettledAtMs: Long = 0L,
    val replay: List<ReplaySample> = emptyList(),
    val extended: ExtendedAiPersisted = ExtendedAiPersisted()
)

/**
 * Persists last-layer / stack / regime-cal / replay on-device.
 */
class HeavyMlStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val key = stringPreferencesKey("heavy_ml_json")

    suspend fun read(): HeavyMlPersisted {
        val raw = context.heavyMlStore.data.first()[key]
        if (raw.isNullOrBlank()) return HeavyMlPersisted()
        return runCatching { json.decodeFromString<HeavyMlPersisted>(raw) }
            .getOrElse { HeavyMlPersisted() }
    }

    suspend fun write(state: HeavyMlPersisted) {
        context.heavyMlStore.edit { it[key] = json.encodeToString(state) }
    }
}
