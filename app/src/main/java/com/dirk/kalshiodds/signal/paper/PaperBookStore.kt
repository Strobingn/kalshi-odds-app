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

    /** Every fill also goes to the permanent archive ([com.dirk.kalshiodds.signal.archive.BetArchiveStore]). */
    private val archive = com.dirk.kalshiodds.signal.archive.BetArchiveStore.of(context)

    val book: PaperBook = PaperBook(
        initial = load(),
        persist = {
            save(it)
            archive.paper(it)
        }
    ).also { archive.paper(it.snapshot()) }

    private fun load(): PaperBookState {
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
    }
}
