package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.ResultsFileExport
import com.dirk.kalshiodds.signal.service.BatteryExemption
import com.dirk.kalshiodds.domain.CryptoMarkets
import android.net.Uri
import com.dirk.kalshiodds.signal.config.CredentialBackup
import com.dirk.kalshiodds.data.api.ConnectionTestResult
import com.dirk.kalshiodds.signal.config.CredentialWriteGuard
import com.dirk.kalshiodds.signal.config.PemNormalizer
import com.dirk.kalshiodds.signal.ws.KalshiWsAuth
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.trade.PayoutGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: SignalSettings = SignalSettings(),
    val keyIdDraft: String = "",
    val pemDraft: String = "",
    val demoKeyIdDraft: String = "",
    val demoPemDraft: String = "",
    val credentialMessage: String? = null,
    val extraRejected: String? = null,
    val bankrollDraft: String = "",
    val alertsPaused: Boolean = false,
    val pauseReason: String? = null,
    val pendingRaiseStake: Double? = null,
    val raiseDraft: String = "",
    val raiseError: String? = null,
    val batteryUnrestricted: Boolean = false,
    val exportMessage: String? = null,
    val mlGuardNote: String? = null,
    val credPassphrase: String = "",
    val connectionTestBusy: Boolean = false,
    val connectionTestMessage: String? = null,
    val lastOrderError: String? = null,
    val lastOrderErrorAtMs: Long = 0L
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val prefs = container.preferences

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refreshBatteryStatus()
        viewModelScope.launch {
            runCatching { prefs.hydrate() }.getOrNull()?.let { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        keyIdDraft = s.apiKeyId,
                        bankrollDraft = String.format(java.util.Locale.US, "%.0f", s.bankrollUsd),
                        credentialMessage = when {
                            prefs.needsReenterKey() ->
                                "Re-enter key — device Keystore was invalidated. Import keys backup in Settings, or paste again."
                            prefs.needsReenterDemoKey() ->
                                "Re-enter demo key — device Keystore was invalidated. Import keys backup in Settings, or paste again."
                            else -> it.credentialMessage
                        }
                    )
                }
            }
            refreshLastOrderError()
        }
        viewModelScope.launch {
            prefs.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        keyIdDraft = if (it.keyIdDraft.isBlank()) s.apiKeyId else it.keyIdDraft,
                        bankrollDraft = if (it.bankrollDraft.isBlank()) {
                            String.format(java.util.Locale.US, "%.0f", s.bankrollUsd)
                        } else {
                            it.bankrollDraft
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            KalshiOddsApp.from(getApplication()).container.guardrailStore.stateFlow.collect { g ->
                _state.update { it.copy(alertsPaused = g.paused, pauseReason = g.banner) }
            }
        }
    }

    fun setBankrollDraft(text: String) {
        _state.update { it.copy(bankrollDraft = text) }
        text.replace(",", "").toDoubleOrNull()?.let { setBankroll(it) }
    }

    fun refreshBatteryStatus() {
        val persistReason = runCatching { container.oomFlag.reason() }.getOrNull()
        val persistOn = runCatching { container.oomFlag.isDisabled() }.getOrElse { false }
        _state.update {
            it.copy(
                batteryUnrestricted = BatteryExemption.isUnrestricted(getApplication()),
                mlGuardNote = when {
                    HeavyMlGuard.lastReason != null ->
                        "Heavy ML auto-disabled: ${HeavyMlGuard.lastReason}. Scoring is the 0.2.x blend. Re-enable only if you accept the heap risk."
                    persistOn ->
                        "Heavy ML stays off after an out-of-memory crash${persistReason?.let { r -> " ($r)" } ?: ""}. Scoring is the 0.2.x blend."
                    else -> null
                }
            )
        }
    }

    fun setWatchBtc(v: Boolean) = viewModelScope.launch { prefs.updateWatchBtc(v) }
    fun setWatchEth(v: Boolean) = viewModelScope.launch { prefs.updateWatchEth(v) }
    fun setWatchSol(v: Boolean) = viewModelScope.launch { prefs.updateWatchSol(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { prefs.updateNotifications(v) }
    fun setAutoTuneOverride(v: Boolean) = viewModelScope.launch { prefs.updateAutoTuneOverride(v) }
    fun setAutoTuneEnabled(v: Boolean) = viewModelScope.launch { prefs.updateAutoTuneEnabled(v) }
    fun setOpportunityAlerts(v: Boolean) = viewModelScope.launch { prefs.updateOpportunityAlerts(v) }
    fun setOpportunityQuiet(v: Boolean) = viewModelScope.launch { prefs.updateOpportunityQuiet(v) }
    fun setLiveSignals(v: Boolean) = viewModelScope.launch { prefs.updateLiveSignals(v) }
    fun setSubscribeTrades(v: Boolean) = viewModelScope.launch { prefs.updateSubscribeTrades(v) }
    fun setThreshold(v: Double) = viewModelScope.launch { prefs.updateEdgeThresholdPp(v) }
    fun setMinConfidence(v: Double) = viewModelScope.launch { prefs.updateMinConfidence(v) }
    fun setMinLiquidity(v: Double) = viewModelScope.launch { prefs.updateMinLiquidity(v) }
    fun setMaxSpreadCents(v: Double) = viewModelScope.launch { prefs.updateMaxSpreadCents(v) }
    fun setHideWeak(v: Boolean) = viewModelScope.launch { prefs.updateHideWeak(v) }
    fun setBankroll(v: Double) = track("bankroll_usd", _state.value.settings.bankrollUsd, v) {
        prefs.updateBankrollUsd(v)
    }
    fun setUseKelly(v: Boolean) = viewModelScope.launch { prefs.updateUseKelly(v) }
    fun setKellyFraction(v: Double) = viewModelScope.launch { prefs.updateKellyFraction(v) }
    fun setFixedFraction(v: Boolean) = viewModelScope.launch { prefs.updateUseKelly(!v) }
    fun setFixedFractionValue(v: Double) = viewModelScope.launch { prefs.updateFixedFraction(v) }
    fun setMaxBankrollFraction(v: Double) = viewModelScope.launch { prefs.updateMaxBankrollFraction(v) }
    fun setFeeRate(v: Double) = viewModelScope.launch { prefs.updateFeeRate(v) }
    fun setRankByNetEv(v: Boolean) = viewModelScope.launch { prefs.updateRankByNetEv(v) }
    fun setAutoMute(v: Boolean) = viewModelScope.launch { prefs.updateAutoMute(v) }
    fun setMuteFloor(v: Double) = viewModelScope.launch { prefs.updateMuteHitRateFloor(v) }
    fun setStreakPauseN(v: Int) = viewModelScope.launch { prefs.updateStreakPauseN(v) }
    fun setDrawdownUsd(v: Double) = viewModelScope.launch { prefs.updateDrawdownUsd(v) }
    fun setResumeOnNewSession(v: Boolean) = viewModelScope.launch { prefs.updateResumeOnNewSession(v) }
    fun setTicketsEnabled(v: Boolean) = viewModelScope.launch { prefs.updateTicketsEnabled(v) }
    fun setHunterValueStake(v: Double) = track("hunter_value_stake", _state.value.settings.hunterValueStakeUsd, v) {
        prefs.updateHunterValueStakeUsd(v)
    }
    fun setHunterValuePayout(v: Double) = track("hunter_value_payout", _state.value.settings.hunterValuePayoutUsd, v) {
        prefs.updateHunterValuePayoutUsd(v)
    }
    fun setLongShotMaxAsk(v: Double) = track("long_shot_max_ask", _state.value.settings.longShotMaxAsk, v) {
        prefs.updateLongShotMaxAsk(v)
    }
    fun setWinTargetEnabled(v: Boolean) = track("win_target_enabled", _state.value.settings.winTargetEnabled, v) {
        prefs.updateWinTargetEnabled(v)
    }
    fun setWinTargetUsd(v: Double) = track("win_target_usd", _state.value.settings.winTargetUsd, v) {
        prefs.updateWinTargetUsd(v)
    }
    fun setWinTargetBankrollPct(v: Double) = track("win_target_bankroll_pct", _state.value.settings.winTargetBankrollPct, v) {
        prefs.updateWinTargetBankrollPct(v)
    }
    fun setWinTargetAbsCapUsd(v: Double?) = track("win_target_abs_cap", _state.value.settings.winTargetAbsCapUsd, v) {
        prefs.updateWinTargetAbsCapUsd(v)
    }
    fun setPaperTrading(v: Boolean) = track("paper_trading", _state.value.settings.paperTradingEnabled, v) {
        prefs.updatePaperTrading(v)
    }
    fun setKalshiDemo(v: Boolean) = viewModelScope.launch { prefs.updateKalshiDemo(v) }
    fun resetPaperBook() {
        val before = container.paper.book.snapshot().cashUsd
        container.paper.book.reset()
        track("paper_reset", before, 100.0) { }
        _state.update { it.copy(credentialMessage = "Paper book archived and reset to $100 — no Kalshi orders") }
    }

    private fun track(key: String, old: Any?, new: Any?, block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            withContext(Dispatchers.IO) {
                container.archive.insertSettingsChange(
                    SettingsChange(
                        createdAtMs = System.currentTimeMillis(),
                        key = key,
                        oldValue = old?.toString() ?: "",
                        newValue = new?.toString() ?: "",
                        snapshotJson = SettingsRestore.snapshot(_state.value.settings)
                    )
                )
            }
        }
    }
    fun setTicketRespectGates(v: Boolean) = viewModelScope.launch { prefs.updateTicketRespectGates(v) }
    fun setLightMode(on: Boolean) = viewModelScope.launch {
        if (on) {
            prefs.updateHeavyMl(false)
            prefs.updateExtendedAi(false)
        } else {
            clearOomLatch()
            prefs.updateHeavyMl(true)
        }
    }
    fun setHeavyMl(v: Boolean) = viewModelScope.launch {
        if (v) clearOomLatch()
        prefs.updateHeavyMl(v)
    }
    fun setSequenceModel(v: Boolean) = viewModelScope.launch { prefs.updateSequenceModel(v) }
    fun setGbm(v: Boolean) = viewModelScope.launch { prefs.updateGbm(v) }
    fun setUncertaintyGate(v: Boolean) = viewModelScope.launch { prefs.updateUncertaintyGate(v) }
    fun setMaxUncertainty(v: Double) = viewModelScope.launch { prefs.updateMaxUncertainty(v) }
    fun setContinualFineTune(v: Boolean) = viewModelScope.launch { prefs.updateContinualFineTune(v) }
    fun setPolicyEvalStakeUsd(v: Double) = viewModelScope.launch { prefs.updatePolicyEvalStakeUsd(v) }
    fun setExtendedAi(v: Boolean) = viewModelScope.launch {
        if (v) clearOomLatch()
        prefs.updateExtendedAi(v)
    }

    private fun clearOomLatch() {
        HeavyMlGuard.reset()
        runCatching { container.oomFlag.clear() }
        refreshBatteryStatus()
    }
    fun setRegimeClassifier(v: Boolean) = viewModelScope.launch { prefs.updateRegimeClassifier(v) }
    fun setAnomalyGate(v: Boolean) = viewModelScope.launch { prefs.updateAnomalyGate(v) }
    fun setSurvivalModel(v: Boolean) = viewModelScope.launch { prefs.updateSurvivalModel(v) }
    fun setRlSizer(v: Boolean) = viewModelScope.launch { prefs.updateRlSizer(v) }
    fun setNewsPulse(v: Boolean) = viewModelScope.launch { prefs.updateNewsPulse(v) }
    fun setRivalFlow(v: Boolean) = viewModelScope.launch { prefs.updateRivalFlow(v) }
    fun setBayesianMm(v: Boolean) = viewModelScope.launch { prefs.updateBayesianMm(v) }
    fun setConformal(v: Boolean) = viewModelScope.launch { prefs.updateConformal(v) }
    fun setMetaLabel(v: Boolean) = viewModelScope.launch { prefs.updateMetaLabel(v) }
    fun setPathSim(v: Boolean) = viewModelScope.launch { prefs.updatePathSim(v) }

    /**
     * Lowering stake (or staying ≤ $5) writes immediately. Raising above the
     * $5 soft cap opens a typed-confirm dialog. Hard cap is $25.
     */
    fun requestTicketStake(raw: Double) {
        val clipped = PayoutGate.clipStake(raw)
        val current = _state.value.settings.ticketStakeUsd
        if (clipped <= current + 1e-9 || !PayoutGate.requiresRaiseConfirm(clipped)) {
            track("ticket_stake_usd", current, clipped) { prefs.updateTicketStakeUsd(clipped) }
            _state.update { it.copy(pendingRaiseStake = null, raiseDraft = "", raiseError = null) }
            return
        }
        _state.update {
            it.copy(pendingRaiseStake = clipped, raiseDraft = "", raiseError = null)
        }
    }

    fun setRaiseDraft(text: String) = _state.update { it.copy(raiseDraft = text, raiseError = null) }

    fun confirmRaiseStake() {
        val pending = _state.value.pendingRaiseStake ?: return
        if (!PayoutGate.raiseConfirmMatches(_state.value.raiseDraft)) {
            _state.update {
                it.copy(raiseError = "Type ${SignalConstants.TICKET_RAISE_CONFIRM_PHRASE} to raise above $5")
            }
            return
        }
        viewModelScope.launch { prefs.updateTicketStakeUsd(pending) }
        _state.update { it.copy(pendingRaiseStake = null, raiseDraft = "", raiseError = null) }
    }

    fun cancelRaiseStake() {
        _state.update { it.copy(pendingRaiseStake = null, raiseDraft = "", raiseError = null) }
    }

    fun resumeAlerts() {
        viewModelScope.launch {
            KalshiOddsApp.from(getApplication()).container.support.resumeAlerts()
            _state.update { it.copy(credentialMessage = "Alerts resumed — streak guard cleared") }
        }
    }

    fun setExtraText(text: String) {
        val rejected = text.split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !CryptoMarkets.isCryptoTicker(it) }
        _state.update {
            it.copy(
                extraRejected = if (rejected.isEmpty()) null
                else "Dropped non-crypto: ${rejected.joinToString()}"
            )
        }
        viewModelScope.launch { prefs.updateExtraTickers(text) }
    }

    fun setKeyIdDraft(v: String) = _state.update { it.copy(keyIdDraft = v, credentialMessage = null) }
    fun setPemDraft(v: String) = _state.update { it.copy(pemDraft = v, credentialMessage = null) }

    fun refreshLastOrderError() {
        val snap = container.lastOrderError.snapshot()
        _state.update {
            it.copy(lastOrderError = snap?.first, lastOrderErrorAtMs = snap?.second ?: 0L)
        }
    }

    fun clearLastOrderError() {
        container.lastOrderError.clear()
        _state.update { it.copy(lastOrderError = null, lastOrderErrorAtMs = 0L) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(connectionTestBusy = true, connectionTestMessage = "Testing GET /portfolio/balance…") }
            val result = withContext(Dispatchers.IO) {
                runCatching { container.tradeClient.testConnection() }
                    .getOrElse { ConnectionTestResult.Fail(it.message ?: "test failed") }
            }
            val message = when (result) {
                is ConnectionTestResult.Ok -> result.rawSummary
                is ConnectionTestResult.Fail -> result.display
            }
            if (result is ConnectionTestResult.Fail) {
                container.lastOrderError.record(result.display)
            }
            _state.update {
                it.copy(
                    connectionTestBusy = false,
                    connectionTestMessage = message,
                    lastOrderError = container.lastOrderError.snapshot()?.first,
                    lastOrderErrorAtMs = container.lastOrderError.snapshot()?.second ?: 0L
                )
            }
        }
    }
    fun setDemoKeyIdDraft(v: String) = _state.update { it.copy(demoKeyIdDraft = v, credentialMessage = null) }
    fun setDemoPemDraft(v: String) = _state.update { it.copy(demoPemDraft = v, credentialMessage = null) }

    fun setCredPassphrase(v: String) = _state.update { it.copy(credPassphrase = v) }

    fun saveCredentials() {
        val keyId = _state.value.keyIdDraft.trim()
        val pem = PemNormalizer.normalize(_state.value.pemDraft)
        val reject = CredentialWriteGuard.rejectReason(keyId, pem)
        if (reject != null) {
            _state.update { it.copy(credentialMessage = reject) }
            return
        }
        val parseErr = runCatching { KalshiWsAuth.parsePrivateKey(pem) }.exceptionOrNull()
        if (parseErr != null) {
            _state.update {
                it.copy(
                    credentialMessage = "PEM would not parse — ${parseErr.message}. " +
                        "Kalshi RSA keys use BEGIN RSA PRIVATE KEY; openssl/Ed25519 use BEGIN PRIVATE KEY."
                )
            }
            return
        }
        prefs.saveCredentials(keyId, pem)
        _state.update { it.copy(pemDraft = "", credentialMessage = "Key stored on device (PEM never logged)") }
    }

    fun backupCredentials(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pass = _state.value.credPassphrase
                    require(pass.length >= 6) { "Passphrase must be at least 6 characters" }
                    val (id, pem) = prefs.credentialSnapshot()
                    require(id.isNotBlank() && pem.isNotBlank()) { "No Kalshi key saved to back up" }
                    val (demoId, demoPem) = prefs.demoSnapshot()
                    val bytes = CredentialBackup.encrypt(id, pem, pass.toCharArray(), demoId, demoPem)
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("could not write backup")
                    "Keys backup written (passphrase-encrypted). Keep it in Downloads or Drive."
                }.getOrElse { it.message ?: "backup failed" }
            }
            _state.update { it.copy(credentialMessage = result) }
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
                    val bundle = CredentialBackup.decryptAll(bytes, pass.toCharArray())
                    prefs.saveCredentials(bundle.keyId, bundle.pem)
                    if (bundle.demoKeyId.isNotBlank() && bundle.demoPem.isNotBlank()) {
                        prefs.saveDemoCredentials(bundle.demoKeyId, bundle.demoPem)
                    }
                    "Kalshi key restored (${CredentialBackup.maskedKeyId(bundle.keyId)})"
                }.getOrElse { e ->
                    when (e) {
                        is CredentialBackup.WrongPassphrase -> "Wrong passphrase — key not changed"
                        is CredentialBackup.BadFile -> "Not a DipHunter keys backup — key not changed"
                        else -> e.message ?: "restore failed"
                    }
                }
            }
            _state.update { it.copy(credentialMessage = result) }
        }
    }

    fun exportResults() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = KalshiOddsApp.from(getApplication()).container.resultsStore
                    val archive = KalshiOddsApp.from(getApplication()).container.archive
                    val json = ResultsExporter.jsonWithHistory(
                        store.exportBundle(),
                        settings = archive.recentSettingsChanges(400),
                        sessions = archive.recentSessions(200)
                    )
                    val csv = ResultsExporter.csv(store.exportBundle()) + "\n# history json follows\n" + json
                    ResultsFileExport.write(getApplication(), csv)
                }.getOrElse {
                    com.dirk.kalshiodds.data.local.results.ExportResult(
                        false,
                        null,
                        it.message ?: "Export failed"
                    )
                }
            }
            _state.update { it.copy(exportMessage = result.message) }
        }
    }

    fun clearCredentials() {
        prefs.clearCredentials()
        _state.update { it.copy(keyIdDraft = "", pemDraft = "", credentialMessage = "Credentials cleared") }
    }

    fun saveDemoCredentials() {
        val keyId = _state.value.demoKeyIdDraft.trim()
        val pem = PemNormalizer.normalize(_state.value.demoPemDraft)
        val reject = CredentialWriteGuard.rejectReason(keyId, pem, demo = true)
        if (reject != null) {
            _state.update { it.copy(credentialMessage = reject) }
            return
        }
        val parseErr = runCatching { KalshiWsAuth.parsePrivateKey(pem) }.exceptionOrNull()
        if (parseErr != null) {
            _state.update { it.copy(credentialMessage = "Demo PEM would not parse — ${parseErr.message}") }
            return
        }
        prefs.saveDemoCredentials(keyId, pem)
        _state.update { it.copy(demoPemDraft = "", credentialMessage = "Demo key stored separately (never the live Kalshi key)") }
    }

    fun clearDemoCredentials() {
        prefs.clearDemoCredentials()
        _state.update { it.copy(demoKeyIdDraft = "", demoPemDraft = "", credentialMessage = "Demo credentials cleared") }
    }
}
