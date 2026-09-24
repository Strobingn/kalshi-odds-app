package com.dirk.kalshiodds.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataHubStore: DataStore<Preferences> by preferencesDataStore(
    name = "diphunter_data_hub",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

data class DataHubSettings(
    val backfillDays: Int = 30,
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = ""
) {
    val supabaseConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") && supabaseAnonKey.length > 20
}

class DataPrefs(context: Context) {
    private val app = context.applicationContext

    val settings: Flow<DataHubSettings> = app.dataHubStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toSettings() }

    suspend fun hydrate(): DataHubSettings =
        runCatching { settings.first() }.getOrElse { DataHubSettings() }

    suspend fun updateBackfillDays(days: Int) {
        app.dataHubStore.edit { it[KEY_DAYS] = days.coerceIn(1, 180) }
    }

    suspend fun updateSupabase(url: String, anonKey: String) {
        app.dataHubStore.edit {
            it[KEY_SB_URL] = url.trim()
            it[KEY_SB_KEY] = anonKey.trim()
        }
    }

    private fun Preferences.toSettings() = DataHubSettings(
        backfillDays = this[KEY_DAYS] ?: 30,
        supabaseUrl = this[KEY_SB_URL].orEmpty(),
        supabaseAnonKey = this[KEY_SB_KEY].orEmpty()
    )

    companion object {
        private val KEY_DAYS = intPreferencesKey("backfill_days")
        private val KEY_SB_URL = stringPreferencesKey("supabase_url")
        private val KEY_SB_KEY = stringPreferencesKey("supabase_anon_key")
    }
}
