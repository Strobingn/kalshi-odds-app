package com.dirk.kalshiarb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiarb.ArbNotifier
import com.dirk.kalshiarb.data.ArbSettings
import com.dirk.kalshiarb.data.PaperLogStore
import com.dirk.kalshiarb.data.ScanReport
import com.dirk.kalshiarb.data.ScanRunner
import com.dirk.kalshiarb.scan.PaperEntry
import com.dirk.kalshiarb.scan.PaperLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArbUiState(
    val scanning: Boolean = false,
    val progress: String = "",
    val report: ScanReport? = null,
    val error: String? = null,
    val log: List<PaperEntry> = emptyList(),
    val autoScan: Boolean = false,
    val notify: Boolean = true,
    val lightTheme: Boolean = false
)

class ArbViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ArbSettings(app)
    private val logStore = PaperLogStore(app)
    private val runner = ScanRunner()
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(
        ArbUiState(
            autoScan = settings.autoScan,
            notify = settings.notify,
            lightTheme = settings.lightTheme
        )
    )
    val state: StateFlow<ArbUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val log = logStore.load()
            _state.update { it.copy(log = log) }
        }
    }

    /** Starts a scan unless one is already running. */
    fun scanNow() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null, progress = "Starting…") }
            try {
                val report = runner.scan(progress = { p -> _state.update { it.copy(progress = p) } })
                val merge = PaperLog.merge(_state.value.log, report.result.opportunities, report.finishedMs)
                logStore.save(merge.entries)
                _state.update {
                    it.copy(scanning = false, progress = "", report = report, log = merge.entries)
                }
                if (_state.value.notify) ArbNotifier.notifyNew(getApplication<Application>(),merge.newlyOpened)
            } catch (e: CancellationException) {
                _state.update { it.copy(scanning = false, progress = "") }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(scanning = false, progress = "", error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun setAutoScan(on: Boolean) {
        settings.autoScan = on
        _state.update { it.copy(autoScan = on) }
    }

    fun setNotify(on: Boolean) {
        settings.notify = on
        _state.update { it.copy(notify = on) }
    }

    fun setLightTheme(on: Boolean) {
        settings.lightTheme = on
        _state.update { it.copy(lightTheme = on) }
    }

    fun clearLog() {
        viewModelScope.launch {
            logStore.clear()
            _state.update { it.copy(log = emptyList()) }
        }
    }
}
