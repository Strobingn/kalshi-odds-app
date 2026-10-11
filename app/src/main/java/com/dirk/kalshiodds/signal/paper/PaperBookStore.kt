package com.dirk.kalshiodds.signal.paper

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device paper ledger. Isolated from live Approve / Kalshi keys.
 */
class PaperBookStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val book: PaperBook = PaperBook(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): PaperBookState {
        if (prefs.getInt(VER, 0) < BANKROLL_VER) {
            prefs.edit().remove(KEY).putInt(VER, BANKROLL_VER).apply()
            return PaperBookState()
        }
        val raw = prefs.getString(KEY, null) ?: return PaperBookState()
        return runCatching { json.decodeFromString(PaperBookState.serializer(), raw) }
            .getOrElse { PaperBookState() }
    }

    private fun save(state: PaperBookState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(PaperBookState.serializer(), state)).apply()
        }
    }

    companion object {
        private const val PREFS = "diphunter_paper_book"
        private const val KEY = "state_json"
        private const val VER = "bankroll_ver"
        /** $20,000 start. Paper cash may go negative. */
        private const val BANKROLL_VER = 2
    }
}
