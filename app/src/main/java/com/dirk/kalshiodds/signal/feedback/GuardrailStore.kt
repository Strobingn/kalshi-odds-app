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

private val Context.guardrailDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_guardrails",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Persists streak / drawdown pause state across process death. */
class GuardrailStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val key = stringPreferencesKey("guardrail_json")

    val stateFlow: Flow<Guardrails.State> = context.guardrailDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun read(): Guardrails.State = stateFlow.first()

    suspend fun write(state: Guardrails.State) {
        context.guardrailDataStore.edit { it[key] = json.encodeToString(state) }
    }

    private fun decode(raw: String?): Guardrails.State {
        if (raw.isNullOrBlank()) return Guardrails.identity()
        return runCatching { json.decodeFromString<Guardrails.State>(raw) }
            .getOrElse { Guardrails.identity() }
    }
}
