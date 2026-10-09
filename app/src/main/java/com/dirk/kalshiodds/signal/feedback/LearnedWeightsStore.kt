package com.dirk.kalshiodds.signal.feedback

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.adapterDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_adapter",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Persists [OnlineAdapter.State] on-device. Never logs secrets.
 */
class LearnedWeightsStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val key = stringPreferencesKey("adapter_json")

    val stateFlow: Flow<OnlineAdapter.State> = context.adapterDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun read(): OnlineAdapter.State = stateFlow.first()

    suspend fun write(state: OnlineAdapter.State) {
        context.adapterDataStore.edit { it[key] = json.encodeToString(state) }
    }

    private fun decode(raw: String?): OnlineAdapter.State {
        if (raw.isNullOrBlank()) return OnlineAdapter.identity()
        return runCatching { json.decodeFromString<OnlineAdapter.State>(raw) }
            .getOrElse { OnlineAdapter.identity() }
    }
}
