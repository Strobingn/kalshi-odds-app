package com.dirk.kalshiodds.signal.latefav

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * On-device JSON store for the late-favorite paper tracker (same pattern as
 * [com.dirk.kalshiodds.signal.paper.PaperBookStore]). Isolated from live
 * Approve / Kalshi keys.
 */
class LateFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val ledger: LateFavoriteLedger = LateFavoriteLedger(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): LateFavoriteState {
        val raw = prefs.getString(KEY, null) ?: return LateFavoriteState()
        return runCatching { json.decodeFromString(LateFavoriteState.serializer(), raw) }
            .getOrElse { LateFavoriteState() }
    }

    private fun save(state: LateFavoriteState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(LateFavoriteState.serializer(), state)).apply()
        }
    }

    companion object {
        private const val PREFS = "diphunter_late_favorite"
        private const val KEY = "state_json"
    }
}
