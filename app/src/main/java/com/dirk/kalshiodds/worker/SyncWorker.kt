package com.dirk.kalshiodds.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.data.local.results.ResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun halted(): Boolean = isStopped

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // CoroutineWorker.onStopped is final. A daemon polls isStopped and cancels the socket
        // so a blocked upload cannot keep SystemJobService from finishing onStopJob.
        val watch = Thread({
            while (!Thread.currentThread().isInterrupted) {
                if (isStopped) {
                    http.dispatcher.cancelAll()
                    break
                }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "sync-stop-watch").apply { isDaemon = true; start() }
        if (halted()) {
            watch.interrupt()
            return@withContext Result.retry()
        }
        try {
        val container = runCatching { KalshiOddsApp.from(applicationContext).container }
            .getOrElse { return@withContext Result.failure() }
        val hub = container.dataPrefs.hydrate()
        if (!hub.syncEnabled || !hub.supabaseConfigured) {
            return@withContext Result.success()
        }
        val sync = SupabaseSync(http)
        val settingsJson = mutableListOf<String>()
        val status = try {
            ensureActive()
            val store = container.resultsStore
            val archive = container.archive
            val startedMs = System.currentTimeMillis()
            val paper = container.paper.book.snapshot()
            val settings = container.preferences.hydrate()
            val picked = loadSnapshots(store)
            if (halted()) return@withContext Result.retry()
            // 0.3.67: unsynced closed rows from SQLite by index (synced, closed_at_ms) — no in-memory scan.
            val ackPrefs = applicationContext.getSharedPreferences(SCALP_PREFS, Context.MODE_PRIVATE)
            container.scalp.flushWrites()
            // 0.3.67 one-time: rows at/before the 0.3.65–0.3.66 ack watermark are already in Supabase.
            if (!ackPrefs.getBoolean(KEY_LEDGER_ACK_APPLIED, false)) {
                val ack = ackPrefs.getLong(KEY_SCALP_ACK, 0L)
                if (ack > 0L) (store as? SqliteResultsStore)?.markSyncedUpTo(ack)
                ackPrefs.edit().putBoolean(KEY_LEDGER_ACK_APPLIED, true).apply()
            }
            yield()
            val newest = container.scalp.unsyncedClosed(SCALP_NEWEST)
            if (halted()) return@withContext Result.retry()
            val result = sync.roundTrip(
                settings = hub,
                local = SupabaseSync.LocalBundle(
                    // 0.3.55: read a full sync interval; SnapshotUploadFilter picks the few worth uploading.
                    snapshots = picked,
                    alerts = store.recentAlerts(200),
                    tickets = store.recentTickets(200),
                    settingsChanges = archive.recentSettingsChanges(200),
                    paperFills = paper.fillsForSync(),
                    settingsSnapshot = SettingsRestore.snapshot(settings),
                    scalps = newest,
                    shadow = container.shadow.book.snapshot().tickets.filter { it.createdAtMs >= startedMs - 6 * 3_600_000L }
                ),
                store = store,
                lastPushMs = hub.lastSyncAtMs,
                onSettings = { json -> settingsJson += json },
                onPaper = { fill -> container.paper.book.upsertFromSync(fill) }
            )
            // 0.3.64: upload crash / non-fatal coroutine traces as kind "crash".
            if (!halted()) {
                runCatching {
                    val crashes = com.dirk.kalshiodds.crash.CrashLog.apply { attach(applicationContext) }.unsyncedRecords()
                    if (crashes.isNotEmpty()) {
                        val out = sync.pushBatches(hub, crashes)
                        if ("crash" !in out.failedKinds && out.errors.isEmpty()) com.dirk.kalshiodds.crash.CrashLog.markSynced(crashes.map { it.key })
                    }
                }
            }
            if (result.scalpsOk && newest.isNotEmpty() && !halted()) container.scalp.markSynced(newest.map { it.id })
            // 0.3.67: push remaining unsynced pages from SQLite; markSynced advances the index (never deletes rows).
            var chunks = 0
            var left = 0
            while (chunks < SCALP_CHUNKS) {
                ensureActive()
                if (halted()) break
                val chunk = container.scalp.unsyncedClosed(SCALP_BATCH)
                if (chunk.isEmpty()) break
                val recs = sync.pack(SupabaseSync.LocalBundle(scalps = chunk))
                val out = sync.pushBatches(hub, recs)
                if ("scalp" in out.failedKinds) { left = chunk.size; break }
                container.scalp.markSynced(chunk.map { it.id })
                chunk.maxOfOrNull { it.closedAtMs ?: 0L }?.let { ackPrefs.edit().putLong(KEY_SCALP_ACK, it).apply() }
                chunks++
                yield()
            }
            if (halted()) return@withContext Result.retry()
            if (left == 0) left = container.scalp.unsyncedClosed(1).size // 0 or 1 sentinel
            ackPrefs.edit().putInt(KEY_SCALP_UNSYNCED, if (left == 0 && chunks > 0) 0 else left).apply()
            if (result.acknowledgePaper) container.paper.book.acknowledgePaperSync(startedMs)
            // 0.3.56: only a successful upload advances the snapshot throttle.
            if (result.snapshotsOk) com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.commit(picked)
            settingsJson.lastOrNull()?.let { container.preferences.restoreSnapshot(it) }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            SupabaseSync.Status(ok = false, message = t.message ?: "sync failed")
        }
        if (halted()) return@withContext Result.retry()
        runCatching {
            // A failed paper or scalp batch must not move the watermark, or those rows are never retried.
            val kindsLanded = status.acknowledgePaper && status.scalpsOk
            val atMs = if (kindsLanded && (status.ok || status.advanced)) status.atMs else hub.lastSyncAtMs
            container.dataPrefs.updateSyncStatus(status.message, atMs)
        }
        if (status.ok) Result.success() else Result.retry()
        } finally {
            watch.interrupt()
        }
    }

    /** Page the newest snapshots. One page is in memory at a time; the filter keeps the upload set. */
    private fun loadSnapshots(store: ResultsStore): List<ScoredSnapshotRow> {
        val sqlite = store as? SqliteResultsStore
            ?: return com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.select(store.recentSnapshots(SNAP_PAGE))
        val floor = sqlite.snapshotSyncFloor(SNAP_LOOKBACK)
        var afterMs = floor?.first ?: -1L
        var afterId = (floor?.second ?: -1L) - 1L
        var pages = 0
        return com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.selectStreaming {
            if (halted() || pages >= SNAP_PAGES) return@selectStreaming null
            val page = sqlite.snapshotPageAfter(afterMs, afterId, SNAP_PAGE)
            pages++
            if (page.isEmpty()) {
                null
            } else {
                val tail = page.last()
                afterMs = tail.createdAtMs
                afterId = tail.id
                page
            }
        }
    }

    companion object {
        const val UNIQUE = "diphunter_cloud_sync"
        const val SCALP_PREFS = "scalp_sync"
        const val KEY_SCALP_ACK = "ack_closed_ms"
        const val KEY_SCALP_UNSYNCED = "unsynced"
        const val KEY_LEDGER_ACK_APPLIED = "ledger_ack_applied_0367"
        const val SCALP_BATCH = 500
        const val SCALP_CHUNKS = 12
        const val SCALP_NEWEST = 300
        const val SNAP_PAGE = 200
        /** Safety cap on pages. The floor query already starts near the newest [SNAP_LOOKBACK] rows. */
        const val SNAP_PAGES = 12
        const val SNAP_LOOKBACK = 1_600
        fun unsyncedScalps(context: Context): Int = context.getSharedPreferences(SCALP_PREFS, Context.MODE_PRIVATE).getInt(KEY_SCALP_UNSYNCED, 0)
        const val ONCE = "diphunter_cloud_sync_once"

        fun enqueuePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun enqueueOnce(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}
