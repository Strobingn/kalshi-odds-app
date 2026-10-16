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
        // The $100 books cannot be rescaled into a $20k experiment.
        if (prefs.getInt(VER, 0) < BANKROLL_VER) {
            prefs.edit().remove(KEY).putInt(VER, BANKROLL_VER).apply()
            return ScalpLabState()
        }
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
        private const val VER = "bankroll_ver"
        /** $20,000 start, and a paper book may go negative. */
        private const val BANKROLL_VER = 3
    }
}
