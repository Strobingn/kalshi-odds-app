package com.dirk.kalshiodds.signal.paper

import android.content.Context
import com.dirk.kalshiodds.worker.PaperScalpSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device paper ledger. Isolated from live Approve / Kalshi keys.
 */
class PaperBookStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /**
     * A scalp fill can be marked repeatedly as its executable high-water bid
     * improves. Serializing a large, unrestricted paper ledger from that hot
     * path can stall the UI or exhaust a small device heap. Keep the entire
     * ledger, but coalesce persistence onto one IO writer.
     */
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceLock = Any()
    private var pendingState: PaperBookState? = null
    private var persistenceJob: Job? = null
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val book: PaperBook = PaperBook(
        initial = load(),
        persist = { save(it) }
    )

    private fun load(): PaperBookState {
        val raw = prefs.getString(KEY, null) ?: return PaperBookState()
        return runCatching { json.decodeFromString(PaperBookState.serializer(), raw) }
            .getOrElse { PaperBookState() }
    }

    private fun save(state: PaperBookState) {
        synchronized(persistenceLock) {
            pendingState = state
            if (persistenceJob?.isActive == true) return
            persistenceJob = persistenceScope.launchWriter()
        }
    }

    private fun CoroutineScope.launchWriter(): Job = launch {
        while (true) {
            // Let a burst of parallel strategies and quote updates collapse
            // into one immutable snapshot. This is persistence throttling,
            // not a limit on entries, stake, or retained paper evidence.
            delay(PERSIST_DEBOUNCE_MS)
            val next = synchronized(persistenceLock) {
                pendingState.also { pendingState = null }
            } ?: continue

            runCatching {
                // commit() runs only on Dispatchers.IO here. It makes the
                // latest coalesced snapshot durable before a sync is queued;
                // apply() on every price tick was the UI-thread pressure path.
                prefs.edit()
                    .putString(KEY, json.encodeToString(PaperBookState.serializer(), next))
                    .commit()
            }

            // KEEP coalesces network work. The worker never sends a Kalshi
            // order and cannot access Kalshi credentials.
            runCatching { PaperScalpSyncWorker.enqueueOnce(app) }

            val shouldStop = synchronized(persistenceLock) {
                if (pendingState == null) {
                    persistenceJob = null
                    true
                } else {
                    false
                }
            }
            if (shouldStop) return@launch
        }
    }

    companion object {
        private const val PREFS = "diphunter_paper_book"
        private const val KEY = "state_json"
        private const val PERSIST_DEBOUNCE_MS = 750L
    }
}
