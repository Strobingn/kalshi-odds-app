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
    val days: Int = 30,
    val credPassphrase: String = "",
    val settingsPass: String = "",
    val githubTokenDraft: String = "",
    val groqKeyDraft: String = "",
    val mistralKeyDraft: String = "",
    val paperAiOn: Boolean = true,
    val supervisorOn: Boolean = false,
    val learningMode: Boolean = true,
    val syncLine: String? = null,
    val modelBusy: Boolean = false
)

class DataViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val archive = container.archive
    private val dataPrefs = container.dataPrefs
    private val modelStore = container.importedModel

    private val _state = MutableStateFlow(DataUiState(
        supervisorOn = container.extraSecrets.supervisorEnabled,
        paperAiOn = container.extraSecrets.paperAiEnabled,
        learningMode = container.learningMode
    ))
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    /** AI supervisor (signal/scalper/LlmSupervisor.kt): last tick, and its newest journal lines. */
    val supervisorStatus: StateFlow<String> = container.llmSupervisor.status
    val supervisorLog: StateFlow<List<String>> = container.llmSupervisor.log

    /** Paper AI (signal/scalper/PaperAi.kt): a separate AI that places one paper bet per window. */
    val paperAiStatus: StateFlow<String> = container.paperAi.status
    val paperAiLog: StateFlow<List<String>> = container.paperAi.log
    val paperAiRecord: StateFlow<String> = container.paperAi.record


    fun setPaperAiOn(on: Boolean) {
        container.extraSecrets.paperAiEnabled = on
        _state.update { it.copy(paperAiOn = on) }
    }

    fun setGroqKeyDraft(v: String) = _state.update { it.copy(groqKeyDraft = v) }

    fun setMistralKeyDraft(v: String) = _state.update { it.copy(mistralKeyDraft = v) }

    fun saveSupervisorKeys() {
        val g = _state.value.groqKeyDraft.trim()
        val m = _state.value.mistralKeyDraft.trim()
        if (g.isNotEmpty()) container.extraSecrets.groqApiKey = g
        if (m.isNotEmpty()) container.extraSecrets.mistralApiKey = m
        _state.update {
            it.copy(
                groqKeyDraft = "",
                mistralKeyDraft = "",
                message = if (g.isEmpty() && m.isEmpty()) "Nothing to save" else "AI keys stored (encrypted)"
            )
        }
    }

    fun setLearningMode(on: Boolean) {
        container.learningMode = on
        _state.update { it.copy(learningMode = on) }
    }

    fun setSupervisorOn(on: Boolean) {
        container.extraSecrets.supervisorEnabled = on
        _state.update { it.copy(supervisorOn = on) }
    }

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
                        modelNote = modelLabel(),
                        syncLine = syncLine(s)
                    )
                }
            }
        }
        viewModelScope.launch {
            dataPrefs.settings.collect { s ->
                _state.update { it.copy(settings = s, days = s.backfillDays, syncLine = syncLine(s)) }
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
                parsed.batch.settingsChanges.forEach { archive.insertSettingsChange(it) }
                parsed.batch.sessions.forEach { archive.insertSession(it) }
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

    fun setCredPassphrase(v: String) = _state.update { it.copy(credPassphrase = v) }

    /** Settings backup (1.20.7): every setting and key, encrypted with a passphrase, kept in Downloads/ClaudeBitcoin. */
    val settingsBackup: StateFlow<com.dirk.kalshiodds.data.backup.SettingsBackupManager.Status> = container.settingsBackup.status

    fun setSettingsPass(v: String) = _state.update { it.copy(settingsPass = v) }

    fun turnOnSettingsBackup() {
        val pass = _state.value.settingsPass
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { container.settingsBackup.enable(pass) }.fold(
                    { st -> st.error?.let { "Backup failed: $it" } ?: "Settings backed up to ${st.where}" },
                    { it.message ?: "Backup failed" }
                )
            }
            _state.update { it.copy(message = msg, settingsPass = "") }
        }
    }

    fun backupSettingsNow() {
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { container.settingsBackup.backupNow() }
            _state.update { it.copy(message = st.error?.let { e -> "Backup failed: $e" } ?: "Settings backed up to ${st.where}") }
        }
    }

    fun turnOffSettingsBackup() {
        container.settingsBackup.disable()
        _state.update { it.copy(message = "Automatic settings backup off (the file in Downloads stays)") }
    }

    /** Restore every setting and key from [uri] with the passphrase typed above, then restart the app. */
    fun restoreSettings(uri: Uri) {
        val pass = _state.value.settingsPass
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read that file")
                    container.settingsBackup.restore(bytes, pass)
                }
            }
            result.fold(
                { msg ->
                    _state.update { it.copy(message = msg, settingsPass = "") }
                    kotlinx.coroutines.delay(1_500L)
                    container.settingsBackup.restartApp()
                },
                { e ->
                    val msg = when (e) {
                        is com.dirk.kalshiodds.data.backup.SettingsBackup.WrongPassphrase -> "Wrong passphrase for that backup"
                        is com.dirk.kalshiodds.data.backup.SettingsBackup.BadFile -> "That is not a Claude Bitcoin settings backup"
                        else -> "Restore failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    _state.update { it.copy(message = msg) }
                }
            )
        }
    }

    fun setGithubTokenDraft(v: String) = _state.update { it.copy(githubTokenDraft = v) }

    fun saveGithubToken() {
        val token = _state.value.githubTokenDraft.trim()
        container.extraSecrets.githubToken = token
        _state.update {
            it.copy(message = if (token.isBlank()) "GitHub token cleared" else "GitHub token stored (encrypted, not the Kalshi key)")
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataPrefs.updateSyncEnabled(enabled)
            if (enabled) com.dirk.kalshiodds.worker.SyncWorker.enqueueOnce(getApplication())
            _state.update { it.copy(message = if (enabled) "Cloud sync on" else "Cloud sync off") }
        }
    }

    fun syncNow() {
        com.dirk.kalshiodds.worker.SyncWorker.enqueueOnce(getApplication())
        _state.update { it.copy(message = "Sync queued") }
    }

    fun getLatestModel() {
        viewModelScope.launch {
            _state.update { it.copy(modelBusy = true, message = "Fetching latest model…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val token = _state.value.githubTokenDraft.trim().ifBlank {
                        container.extraSecrets.githubToken
                    }
                    when (val out = com.dirk.kalshiodds.prediction.LatestModelClient().download(token)) {
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.Ready -> {
                            val d = out.fetch.decision
                            if (d.activate) {
                                modelStore.activate(out.fetch.model, out.fetch.manifest)
                                container.scoring.edgeModel = out.fetch.model
                            }
                            val m = out.fetch.manifest
                            buildString {
                                append(d.reason)
                                append(" · n=")
                                append(m.nSamples)
                                append(" · Brier ")
                                append("%.4f".format(m.modelBrier))
                                append(" vs mkt ")
                                append("%.4f".format(m.marketBrier))
                            }
                        }
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.NeedsAuth -> out.message
                        is com.dirk.kalshiodds.prediction.LatestModelClient.Outcome.Failed -> out.message
                    }
                }.getOrElse { it.message ?: "download failed" }
            }
            _state.update { it.copy(modelBusy = false, modelNote = result, message = result) }
        }
    }

    fun rollbackModel() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val prev = modelStore.rollback()
                if (prev != null) {
                    container.scoring.edgeModel = prev
                    "Rolled back to previous model v${prev.version}"
                } else {
                    "No previous model to roll back"
                }
            }
            _state.update { it.copy(modelNote = result, message = result) }
        }
    }

    /** Zip recorded market data for [days] (all when empty) into the picked document. */
    fun exportRecordings(uri: Uri, days: List<String>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("could not open the export file")
                    val n = container.recorder.export(days, out)
                    if (n == 0) "No recordings to export" else "Exported $n recording files"
                }.getOrElse { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
            }
            _state.update { it.copy(message = result) }
        }
    }

    fun backupCredentials(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pass = _state.value.credPassphrase
                    require(pass.length >= 6) { "Passphrase must be at least 6 characters" }
                    val (id, pem) = container.preferences.credentialSnapshot()
                    require(id.isNotBlank() && pem.isNotBlank()) { "No Kalshi key saved to back up" }
                    val (demoId, demoPem) = container.preferences.demoSnapshot()
                    val bytes = com.dirk.kalshiodds.signal.config.CredentialBackup.encrypt(
                        id, pem, pass.toCharArray(), demoId, demoPem
                    )
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("could not write backup")
                    "Credential backup written (passphrase-encrypted)"
                }.getOrElse { it.message ?: "backup failed" }
            }
            _state.update { it.copy(message = result) }
        }
    }

    fun restoreCredentials(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pass = _state.value.credPassphrase
                    require(pass.isNotBlank()) { "Enter the backup passphrase" }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: error("could not read backup")
                    val bundle = com.dirk.kalshiodds.signal.config.CredentialBackup.decryptAll(
                        bytes, pass.toCharArray()
                    )
                    container.preferences.saveCredentials(bundle.keyId, bundle.pem)
                    if (bundle.demoKeyId.isNotBlank() && bundle.demoPem.isNotBlank()) {
                        container.preferences.saveDemoCredentials(bundle.demoKeyId, bundle.demoPem)
                    }
                    "Kalshi key restored (${com.dirk.kalshiodds.signal.config.CredentialBackup.maskedKeyId(bundle.keyId)})"
                }.getOrElse { it.message ?: "restore failed" }
            }
            _state.update { it.copy(message = result) }
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
                    restore.batch.settingsChanges.forEach { archive.insertSettingsChange(it) }
                    restore.batch.sessions.forEach { archive.insertSession(it) }
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
        val man = modelStore.currentManifest()
        val extra = man?.let {
            " · n=${it.nSamples} · Brier ${"%.3f".format(it.modelBrier)} vs mkt ${"%.3f".format(it.marketBrier)}"
        }.orEmpty()
        return "Model ${m.kind} v${m.version} · blend ${"%.2f".format(m.blendWeight)}$extra"
    }

    private fun syncLine(s: DataHubSettings): String? {
        if (!s.supabaseConfigured) return "Supabase not configured — History stays on this phone."
        if (!s.syncEnabled) return "Cloud sync off"
        return s.lastSyncMessage.ifBlank { "Cloud sync ready — never uploads the Kalshi key." }
    }
}
