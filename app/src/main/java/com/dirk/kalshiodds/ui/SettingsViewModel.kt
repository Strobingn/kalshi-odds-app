package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.service.BatteryExemption
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.config.SecureCredentialStore
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.PayoutGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: SignalSettings = SignalSettings(),
    val keyIdDraft: String = "",
    val pemDraft: String = "",
    val credentialMessage: String? = null,
    val extraRejected: String? = null,
    val bankrollDraft: String = "",
    val alertsPaused: Boolean = false,
    val pauseReason: String? = null,
    val pendingRaiseStake: Double? = null,
    val raiseDraft: String = "",
    val raiseError: String? = null,
    val batteryUnrestricted: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = KalshiOddsApp.from(application).container.preferences

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refreshBatteryStatus()
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
        _state.update {
            it.copy(batteryUnrestricted = BatteryExemption.isUnrestricted(getApplication()))
        }
    }

    fun setWatchBtc(v: Boolean) = viewModelScope.launch { prefs.updateWatchBtc(v) }
    fun setWatchEth(v: Boolean) = viewModelScope.launch { prefs.updateWatchEth(v) }
    fun setWatchSol(v: Boolean) = viewModelScope.launch { prefs.updateWatchSol(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { prefs.updateNotifications(v) }
    fun setLiveSignals(v: Boolean) = viewModelScope.launch { prefs.updateLiveSignals(v) }
    fun setSubscribeTrades(v: Boolean) = viewModelScope.launch { prefs.updateSubscribeTrades(v) }
    fun setThreshold(v: Double) = viewModelScope.launch { prefs.updateEdgeThresholdPp(v) }
    fun setMinConfidence(v: Double) = viewModelScope.launch { prefs.updateMinConfidence(v) }
    fun setMinLiquidity(v: Double) = viewModelScope.launch { prefs.updateMinLiquidity(v) }
    fun setMaxSpreadCents(v: Double) = viewModelScope.launch { prefs.updateMaxSpreadCents(v) }
    fun setHideWeak(v: Boolean) = viewModelScope.launch { prefs.updateHideWeak(v) }
    fun setBankroll(v: Double) = viewModelScope.launch { prefs.updateBankrollUsd(v) }
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
    fun setTicketRespectGates(v: Boolean) = viewModelScope.launch { prefs.updateTicketRespectGates(v) }

    /**
     * Lowering stake (or staying ≤ $5) writes immediately. Raising above the
     * $5 soft cap opens a typed-confirm dialog. Hard cap is $25.
     */
    fun requestTicketStake(raw: Double) {
        val clipped = PayoutGate.clipStake(raw)
        val current = _state.value.settings.ticketStakeUsd
        if (clipped <= current + 1e-9 || !PayoutGate.requiresRaiseConfirm(clipped)) {
            viewModelScope.launch { prefs.updateTicketStakeUsd(clipped) }
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

    fun saveCredentials() {
        val keyId = _state.value.keyIdDraft.trim()
        val pem = _state.value.pemDraft.trim()
        if (keyId.isBlank() || !SecureCredentialStore.looksLikePem(pem)) {
            _state.update { it.copy(credentialMessage = "Need Key ID + PEM private key (BEGIN/END PRIVATE KEY)") }
            return
        }
        prefs.saveCredentials(keyId, pem)
        _state.update { it.copy(pemDraft = "", credentialMessage = "Key stored on device (PEM never logged)") }
    }

    fun clearCredentials() {
        prefs.clearCredentials()
        _state.update { it.copy(keyIdDraft = "", pemDraft = "", credentialMessage = "Credentials cleared") }
    }
}
