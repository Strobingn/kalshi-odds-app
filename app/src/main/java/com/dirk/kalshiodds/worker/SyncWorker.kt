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
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = runCatching { KalshiOddsApp.from(applicationContext).container }
            .getOrElse { return@withContext Result.failure() }
        val hub = container.dataPrefs.hydrate()
        if (!hub.syncEnabled || !hub.supabaseConfigured) {
            return@withContext Result.success()
        }
        val settingsJson = mutableListOf<String>()
        val status = runCatching {
            val store = container.resultsStore
            val archive = container.archive
            val startedMs = System.currentTimeMillis()
            val paper = container.paper.book.snapshot()
            val settings = container.preferences.hydrate()
            val picked = com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.select(store.recentSnapshots(4000))
            // 0.3.65 scalp backlog: every closed primary trade after the ack watermark, oldest first — retried until it lands.
            val ackPrefs = applicationContext.getSharedPreferences(SCALP_PREFS, Context.MODE_PRIVATE)
            val ackMs = ackPrefs.getLong(KEY_SCALP_ACK, 0L)
            val backlog = container.scalp.all.value.filter { it.isPrimary && it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED && (it.closedAtMs ?: 0L) > ackMs }
                .sortedBy { it.closedAtMs }
            // 0.3.66: the newest closes ride every run (idempotent upsert) so fresh trades are never stuck behind the backlog.
            val newest = container.scalp.all.value.asSequence().filter { it.isPrimary && it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED && it.closedAtMs != null }
                .sortedByDescending { it.closedAtMs }.take(SCALP_NEWEST).toList()
            val result = SupabaseSync().roundTrip(
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
            runCatching {
                val crashes = com.dirk.kalshiodds.crash.CrashLog.apply { attach(applicationContext) }.unsyncedRecords()
                if (crashes.isNotEmpty()) {
                    val out = SupabaseSync().pushBatches(hub, crashes)
                    if ("crash" !in out.failedKinds && out.errors.isEmpty()) com.dirk.kalshiodds.crash.CrashLog.markSynced(crashes.map { it.key })
                }
            }
            // 0.3.66 backlog drain: oldest first, 500 rows per POST, up to SCALP_CHUNKS POSTs per run (0.3.65 sent 500 per
            // 15-min run, slower than the scalpers close trades, so the backlog never caught up and nothing new appeared).
            var left = backlog
            var chunks = 0
            while (left.isNotEmpty() && chunks < SCALP_CHUNKS) {
                val chunk = left.take(SCALP_BATCH)
                val recs = SupabaseSync().pack(SupabaseSync.LocalBundle(scalps = chunk))
                val out = SupabaseSync().pushBatches(hub, recs)
                if ("scalp" in out.failedKinds) break
                val ack = chunk.maxOf { it.closedAtMs ?: 0L }
                ackPrefs.edit().putLong(KEY_SCALP_ACK, ack).apply()
                left = left.filter { (it.closedAtMs ?: 0L) > ack }
                chunks++
            }
            ackPrefs.edit().putInt(KEY_SCALP_UNSYNCED, left.size).apply()
            if (result.acknowledgePaper) container.paper.book.acknowledgePaperSync(startedMs)
            // 0.3.56: only a successful upload advances the snapshot throttle.
            if (result.snapshotsOk) com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.commit(picked)
            settingsJson.lastOrNull()?.let { container.preferences.restoreSnapshot(it) }
            result
        }.getOrElse { SupabaseSync.Status(ok = false, message = it.message ?: "sync failed") }
        runCatching {
            val atMs = if (status.ok || status.advanced) status.atMs else hub.lastSyncAtMs
            container.dataPrefs.updateSyncStatus(status.message, atMs)
        }
        if (status.ok) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE = "diphunter_cloud_sync"
        const val SCALP_PREFS = "scalp_sync"
        const val KEY_SCALP_ACK = "ack_closed_ms"
        const val KEY_SCALP_UNSYNCED = "unsynced"
        const val SCALP_BATCH = 500
        const val SCALP_CHUNKS = 12
        const val SCALP_NEWEST = 300
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
