package com.dirk.kalshiodds.signal.paper

import android.content.Context
import com.dirk.kalshiodds.AppIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** On-device shadow ledger. Never stores a Kalshi order id from a submit. */
class ShadowBookStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(AppIdentity.PREFS_SHADOW, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val book: ShadowBook = ShadowBook(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): ShadowBookState {
        val raw = prefs.getString(KEY, null) ?: return ShadowBookState()
        return runCatching { json.decodeFromString(ShadowBookState.serializer(), raw) }
            .getOrElse { ShadowBookState() }
    }

    private fun save(state: ShadowBookState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(ShadowBookState.serializer(), state)).apply()
        }
    }

    companion object {
        private const val KEY = "state_json"
    }
}
