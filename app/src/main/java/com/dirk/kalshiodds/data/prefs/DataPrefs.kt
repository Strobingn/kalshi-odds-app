package com.dirk.kalshiodds.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    val supabaseAnonKey: String = "",
    val syncEnabled: Boolean = true,
    val lastSyncMessage: String = "",
    val lastSyncAtMs: Long = 0L,
    val firstRestoreDone: Boolean = false
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

    suspend fun updateSyncEnabled(enabled: Boolean) {
        app.dataHubStore.edit { it[KEY_SYNC] = enabled }
    }

    suspend fun updateSyncStatus(message: String, atMs: Long = System.currentTimeMillis()) {
        app.dataHubStore.edit {
            it[KEY_SYNC_MSG] = message
            it[KEY_SYNC_AT] = atMs
        }
    }

    suspend fun markFirstRestoreDone() {
        app.dataHubStore.edit { it[KEY_FIRST_RESTORE] = true }
    }

    private fun Preferences.toSettings() = DataHubSettings(
        backfillDays = this[KEY_DAYS] ?: 30,
        supabaseUrl = this[KEY_SB_URL].orEmpty(),
        supabaseAnonKey = this[KEY_SB_KEY].orEmpty(),
        syncEnabled = this[KEY_SYNC] ?: true,
        lastSyncMessage = this[KEY_SYNC_MSG].orEmpty(),
        lastSyncAtMs = this[KEY_SYNC_AT] ?: 0L,
        firstRestoreDone = this[KEY_FIRST_RESTORE] ?: false
    )

    companion object {
        private val KEY_DAYS = intPreferencesKey("backfill_days")
        private val KEY_SB_URL = stringPreferencesKey("supabase_url")
        private val KEY_SB_KEY = stringPreferencesKey("supabase_anon_key")
        private val KEY_SYNC = booleanPreferencesKey("cloud_sync_enabled")
        private val KEY_SYNC_MSG = stringPreferencesKey("cloud_sync_message")
        private val KEY_SYNC_AT = longPreferencesKey("cloud_sync_at_ms")
        private val KEY_FIRST_RESTORE = booleanPreferencesKey("first_restore_done")
    }
}
