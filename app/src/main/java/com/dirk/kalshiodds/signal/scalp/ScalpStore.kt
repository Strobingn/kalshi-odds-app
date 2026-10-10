package com.dirk.kalshiodds.signal.scalp

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** On-device scalp lab. Isolated from live Approve and Kalshi keys. */
class ScalpStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val lab: ScalpLab = ScalpLab(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): ScalpLabState {
        val raw = prefs.getString(KEY, null) ?: return ScalpLabState()
        return runCatching { json.decodeFromString(ScalpLabState.serializer(), raw) }
            .getOrElse { ScalpLabState() }
    }

    private fun save(state: ScalpLabState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(ScalpLabState.serializer(), state)).apply()
        }
    }

    companion object {
        private const val PREFS = "grok_bitcoin_scalp_lab"
        private const val KEY = "state_json"
    }
}
