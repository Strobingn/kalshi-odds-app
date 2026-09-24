package com.dirk.kalshiodds.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.importing.ImportSummary
import com.dirk.kalshiodds.data.importing.ResultsImporter
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.local.archive.DataStats
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.data.supabase.SupabaseMirror
import com.dirk.kalshiodds.prediction.EdgeModel
import com.dirk.kalshiodds.worker.BackfillWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

data class DataUiState(
    val stats: DataStats = DataStats(),
    val settings: DataHubSettings = DataHubSettings(),
    val importSummary: ImportSummary? = null,
    val message: String? = null,
    val backfillMsg: String? = null,
    val backfillRunning: Boolean = false,
    val processed: Int = 0,
    val modelNote: String? = null,
    val supabaseUrlDraft: String = "",
    val supabaseKeyDraft: String = "",
    val days: Int = 30
)

class DataViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val archive = container.archive
    private val dataPrefs = container.dataPrefs
    private val modelStore = container.importedModel

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    init {
        refreshStats()
        viewModelScope.launch {
            runCatching { dataPrefs.hydrate() }.getOrNull()?.let { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        days = s.backfillDays,
                        supabaseUrlDraft = s.supabaseUrl,
                        supabaseKeyDraft = s.supabaseAnonKey,
                        modelNote = modelLabel()
                    )
                }
            }
        }
        viewModelScope.launch {
            dataPrefs.settings.collect { s ->
                _state.update { it.copy(settings = s, days = s.backfillDays) }
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkFlow(BackfillWorker.UNIQUE)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                    val msg = info?.progress?.getString(BackfillWorker.KEY_MSG)
                        ?: info?.outputData?.getString(BackfillWorker.KEY_MSG)
                    val processed = info?.progress?.getInt(BackfillWorker.KEY_PROCESSED, 0)
                        ?: info?.outputData?.getInt(BackfillWorker.KEY_PROCESSED, 0) ?: 0
                    _state.update {
                        it.copy(backfillRunning = running, backfillMsg = msg, processed = processed)
                    }
                    if (info?.state == WorkInfo.State.SUCCEEDED) refreshStats()
                }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { runCatching { archive.stats() }.getOrElse { DataStats() } }
            _state.update { it.copy(stats = stats, modelNote = modelLabel()) }
        }
    }

    fun setDays(days: Int) {
        _state.update { it.copy(days = days.coerceIn(1, 180)) }
        viewModelScope.launch { dataPrefs.updateBackfillDays(days.coerceIn(1, 180)) }
    }

    fun setSupabaseDrafts(url: String, key: String) {
        _state.update { it.copy(supabaseUrlDraft = url, supabaseKeyDraft = key) }
    }

    fun saveSupabase() {
        viewModelScope.launch {
            dataPrefs.updateSupabase(_state.value.supabaseUrlDraft, _state.value.supabaseKeyDraft)
            _state.update { it.copy(message = "Supabase settings saved on device") }
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val cr = getApplication<Application>().contentResolver
                    cr.openInputStream(uri)?.use { input ->
                        val seen = SeenKeys().apply {
                            archive.existingSnapshotKeys().forEach { snapshots.add(it) }
                            archive.existingAlertIds().forEach { alerts.add(it) }
                            archive.existingTicketKeys().forEach { tickets.add(it) }
                            archive.existingFillIds().forEach { fills.add(it) }
                        }
                        ResultsImporter.parse(InputStreamReader(input, Charsets.UTF_8), seen)
                    }
                } catch (t: Throwable) {
                    null
                }
            }
            if (parsed == null) {
                _state.update { it.copy(message = "Could not read that file") }
                return@launch
            }
            withContext(Dispatchers.IO) {
                val store = container.resultsStore
                if (parsed.batch.snapshots.isNotEmpty()) store.insertSnapshots(parsed.batch.snapshots)
                parsed.batch.alerts.forEach { store.insertAlert(it) }
                parsed.batch.scorecards.forEach { store.insertScorecard(it) }
                parsed.batch.tickets.forEach { store.insertTicket(it) }
                archive.insertFills(parsed.batch.fills)
            }
            _state.update {
                it.copy(
                    importSummary = parsed.summary,
                    message = parsed.summary.message + " · " + parsed.summary.dateRangeLabel
                )
            }
            refreshStats()
        }
    }

    fun importModelUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val raw = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching "empty file"
                    val model = modelStore.importJson(raw)
                    container.scoring.edgeModel = model
                    "Imported ${model.kind} v${model.version} · ${model.featureNames.size} features"
                }.getOrElse { it.message ?: "model import failed" }
            }
            _state.update { it.copy(modelNote = result, message = result) }
        }
    }

    fun startBackfill() {
        BackfillWorker.enqueue(getApplication(), _state.value.days, includeSpot = true)
        _state.update { it.copy(backfillRunning = true, backfillMsg = "Starting…", message = "Backfill queued") }
    }

    fun cancelBackfill() {
        BackfillWorker.cancel(getApplication())
        _state.update { it.copy(message = "Cancel requested") }
    }

    fun restoreSupabase() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val seen = SeenKeys()
                    val restore = SupabaseMirror().restore(_state.value.settings, seen)
                    val store = container.resultsStore
                    if (restore.batch.snapshots.isNotEmpty()) store.insertSnapshots(restore.batch.snapshots)
                    restore.batch.alerts.forEach { store.insertAlert(it) }
                    restore.batch.scorecards.forEach { store.insertScorecard(it) }
                    restore.batch.tickets.forEach { store.insertTicket(it) }
                    archive.upsertSettled(restore.settled)
                    restore.message
                }.getOrElse { it.message ?: "Supabase restore failed" }
            }
            _state.update { it.copy(message = result) }
            refreshStats()
        }
    }

    private fun modelLabel(): String? {
        val m: EdgeModel = modelStore.current() ?: return "No imported model yet"
        return "Model ${m.kind} v${m.version} · blend ${"%.2f".format(m.blendWeight)}"
    }
}
