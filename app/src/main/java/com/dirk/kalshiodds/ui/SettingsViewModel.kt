package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.config.SecureCredentialStore
import com.dirk.kalshiodds.signal.config.SignalSettings
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
    val extraRejected: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = KalshiOddsApp.from(application).container.preferences

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        keyIdDraft = if (it.keyIdDraft.isBlank()) s.apiKeyId else it.keyIdDraft
                    )
                }
            }
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
