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
            val paper = container.paper.book.snapshot()
            val settings = container.preferences.hydrate()
            val result = SupabaseSync().roundTrip(
                settings = hub,
                local = SupabaseSync.LocalBundle(
                    snapshots = store.recentSnapshots(400),
                    alerts = store.recentAlerts(200),
                    tickets = store.recentTickets(200),
                    settingsChanges = archive.recentSettingsChanges(200),
                    paperFills = paper.fills,
                    settingsSnapshot = SettingsRestore.snapshot(settings)
                ),
                store = store,
                lastPushMs = hub.lastSyncAtMs,
                onSettings = { json -> settingsJson += json },
                onPaper = { fill ->
                    val cur = container.paper.book.snapshot()
                    if (cur.fills.none { it.id == fill.id }) {
                        container.paper.book.hydrate(cur.copy(fills = (listOf(fill) + cur.fills).take(40)))
                    }
                }
            )
            settingsJson.lastOrNull()?.let { container.preferences.restoreSnapshot(it) }
            result
        }.getOrElse { SupabaseSync.Status(ok = false, message = it.message ?: "sync failed") }
        val stamp = SupabaseSync.nextSyncCursor(status.ok, status.atMs, hub.lastSyncAtMs)
        runCatching { container.dataPrefs.updateSyncStatus(status.message, stamp) }
        if (status.ok) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE = "diphunter_cloud_sync"
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
