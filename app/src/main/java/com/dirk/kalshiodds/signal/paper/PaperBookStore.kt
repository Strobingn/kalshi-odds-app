package com.dirk.kalshiodds.signal.paper

import android.content.Context
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device paper ledger. Isolated from live Approve / Kalshi keys.
 */
class PaperBookStore(
    context: Context,
    private val sqlPersist: ((List<PaperFill>) -> Unit)? = null
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val book: PaperBook = PaperBook(
        initial = load(),
        persist = { save(it) }
    )

    /** 0.3.46 'Reset paper bankroll to $20,000' (Settings + Scalp Data). */
    fun resetTo20k() {
        book.reset(SignalConstants.PAPER_START_USD, "Reset paper bankroll to \$20,000 — prior run archived")
    }

    private fun load(): PaperBookState {
        val raw = prefs.getString(KEY, null) ?: return PaperBookState()
        val decoded = runCatching { json.decodeFromString(PaperBookState.serializer(), raw) }
            .getOrElse { return PaperBookState() }
        return PaperBookState.migrate(decoded)
    }

    private fun save(state: PaperBookState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(PaperBookState.serializer(), state)).apply()
        }
        runCatching {
            val mirror = (state.scorecardFills() + state.archivedFills())
                .groupBy { it.id }
                .map { (_, rows) -> rows.maxBy { it.syncAtMs() } }
            sqlPersist?.invoke(mirror)
        }
    }

    companion object {
        private const val PREFS = "diphunter_paper_book"
        private const val KEY = "state_json"

    }
}
