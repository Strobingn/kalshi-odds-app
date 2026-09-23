package com.dirk.kalshiodds.signal.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.signalDataStore: DataStore<Preferences> by preferencesDataStore(name = "diphunter_signal_prefs")

data class SignalSettings(
    val watchBtc: Boolean = true,
    val watchEth: Boolean = true,
    val watchSol: Boolean = true,
    val extraTickers: List<String> = emptyList(),
    val extraTickersText: String = "",
    val edgeThresholdPp: Double = 5.0,
    val notificationsEnabled: Boolean = true,
    val liveSignalsEnabled: Boolean = false,
    val subscribeTrades: Boolean = true,
    val debounceMs: Long = 10_000L,
    val apiKeyId: String = "",
    val hasPrivateKey: Boolean = false
) {
    val credentialsConfigured: Boolean get() = apiKeyId.isNotBlank() && hasPrivateKey

    val watchedSeries: Set<String>
        get() = buildSet {
            if (watchBtc) add(KalshiApi.SERIES_BTC)
            if (watchEth) add(KalshiApi.SERIES_ETH)
            if (watchSol) add(KalshiApi.SERIES_SOL)
        }

    fun isWatchedTicker(ticker: String): Boolean {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return false
        val u = ticker.uppercase()
        if (extraTickers.any { it.equals(ticker, ignoreCase = true) }) return true
        if (watchBtc && (u.startsWith(KalshiApi.SERIES_BTC) || u.contains("BTC"))) return true
        if (watchEth && (u.startsWith(KalshiApi.SERIES_ETH) || (u.contains("ETH") && !u.contains("BTC")))) return true
        if (watchSol && (u.startsWith(KalshiApi.SERIES_SOL) || u.contains("SOL"))) return true
        return extraTickers.isNotEmpty() && extraTickers.any { u.startsWith(it.uppercase()) }
    }

    fun extraTickerList(): List<String> = CryptoMarkets.filterCrypto(extraTickers)
}

class SignalPreferences(
    private val context: Context,
    private val secrets: SecureCredentialStore = SecureCredentialStore(context),
    defaults: DefaultSignalConfig = DefaultSignalConfig.load(context)
) {
    private val app = context.applicationContext
    private val def = defaults

    /** Bumped when credentials change so [settings] re-emits. */
    private val secretRevision = MutableStateFlow(0)

    val settings: Flow<SignalSettings> = app.signalDataStore.data
        .map { it.toSettings() }
        .combine(secretRevision) { s, _ ->
            s.copy(
                apiKeyId = secrets.apiKeyId,
                hasPrivateKey = SecureCredentialStore.looksLikePem(secrets.privateKeyPem)
            )
        }

    suspend fun updateWatchBtc(value: Boolean) = edit { it[KEY_WATCH_BTC] = value }
    suspend fun updateWatchEth(value: Boolean) = edit { it[KEY_WATCH_ETH] = value }
    suspend fun updateWatchSol(value: Boolean) = edit { it[KEY_WATCH_SOL] = value }
    suspend fun updateExtraTickers(text: String) = edit { it[KEY_EXTRA] = text }
    suspend fun updateEdgeThresholdPp(value: Double) = edit {
        it[KEY_THRESHOLD] = value.coerceIn(0.5, 40.0)
    }
    suspend fun updateNotifications(value: Boolean) = edit { it[KEY_NOTIF] = value }
    suspend fun updateLiveSignals(value: Boolean) = edit { it[KEY_LIVE] = value }
    suspend fun updateSubscribeTrades(value: Boolean) = edit { it[KEY_TRADES] = value }

    fun saveCredentials(keyId: String, pem: String) {
        secrets.apiKeyId = keyId
        secrets.privateKeyPem = pem
        secretRevision.value += 1
    }

    fun clearCredentials() {
        secrets.clear()
        secretRevision.value += 1
    }

    fun credentialSnapshot(): Pair<String, String> = secrets.snapshot()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        app.signalDataStore.edit(block)
    }

    private fun Preferences.toSettings(): SignalSettings {
        val extraText = this[KEY_EXTRA] ?: def.extraTickers.joinToString(", ")
        return SignalSettings(
            watchBtc = this[KEY_WATCH_BTC] ?: def.watchBtc,
            watchEth = this[KEY_WATCH_ETH] ?: def.watchEth,
            watchSol = this[KEY_WATCH_SOL] ?: def.watchSol,
            extraTickers = CryptoMarkets.filterCrypto(parseTickerList(extraText)),
            extraTickersText = extraText,
            edgeThresholdPp = this[KEY_THRESHOLD] ?: def.edgeThresholdPp,
            notificationsEnabled = this[KEY_NOTIF] ?: def.notificationsEnabled,
            liveSignalsEnabled = this[KEY_LIVE] ?: def.liveSignalsEnabled,
            subscribeTrades = this[KEY_TRADES] ?: def.subscribeTrades,
            debounceMs = this[KEY_DEBOUNCE] ?: def.debounceMs,
            apiKeyId = secrets.apiKeyId,
            hasPrivateKey = SecureCredentialStore.looksLikePem(secrets.privateKeyPem)
        )
    }

    companion object {
        private val KEY_WATCH_BTC = booleanPreferencesKey("watch_btc")
        private val KEY_WATCH_ETH = booleanPreferencesKey("watch_eth")
        private val KEY_WATCH_SOL = booleanPreferencesKey("watch_sol")
        private val KEY_EXTRA = stringPreferencesKey("extra_tickers")
        private val KEY_THRESHOLD = doublePreferencesKey("edge_threshold_pp")
        private val KEY_NOTIF = booleanPreferencesKey("notifications_enabled")
        private val KEY_LIVE = booleanPreferencesKey("live_signals_enabled")
        private val KEY_TRADES = booleanPreferencesKey("subscribe_trades")
        private val KEY_DEBOUNCE = longPreferencesKey("debounce_ms")

        fun parseTickerList(text: String): List<String> =
            text.split(',', '\n', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
