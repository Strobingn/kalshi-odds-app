package com.dirk.kalshiodds.signal.v3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local-only V3 evidence store. Persistence is debounced on Dispatchers.IO so
 * polling does not serialize JSON on the UI thread. It intentionally does not
 * enqueue the Supabase worker: V3 must not contaminate the existing ledger.
 */
class V3ShadowStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pending: V3ShadowState? = null
    private var writer: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val book = V3ShadowBook(initial = load(), persist = ::save)

    private fun load(): V3ShadowState = prefs.getString(KEY, null)?.let { raw ->
        runCatching { json.decodeFromString(V3ShadowState.serializer(), raw) }.getOrNull()
    } ?: V3ShadowState()

    private fun save(state: V3ShadowState) {
        synchronized(lock) {
            pending = state
            if (writer?.isActive == true) return
            writer = scope.launch {
                while (true) {
                    delay(PERSIST_DEBOUNCE_MS)
                    val snapshot = synchronized(lock) { pending.also { pending = null } } ?: continue
                    runCatching {
                        prefs.edit().putString(KEY, json.encodeToString(V3ShadowState.serializer(), snapshot)).commit()
                    }
                    val stop = synchronized(lock) {
                        if (pending == null) {
                            writer = null
                            true
                        } else false
                    }
                    if (stop) return@launch
                }
            }
        }
    }

    private companion object {
        const val PREFS = "v3_shadow_experiment"
        const val KEY = "state_json"
        const val PERSIST_DEBOUNCE_MS = 1_000L
    }
}
