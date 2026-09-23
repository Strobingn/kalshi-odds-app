package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.CryptoMarkets

private val Context.marketDataStore: DataStore<Preferences> by preferencesDataStore(name = "kalshi_market_cache")

@Serializable
data class CachedMarketsPayload(
    val btc: List<MarketDto> = emptyList(),
    val eth: List<MarketDto> = emptyList(),
    val sol: List<MarketDto> = emptyList(),
    val extra: List<MarketDto> = emptyList(),
    val fetchedAtEpochMs: Long = 0L
)

class MarketCache(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val keyPayload = stringPreferencesKey("cached_markets_json")
    private val keyFetchedAt = longPreferencesKey("fetched_at_epoch_ms")

    val cachedFlow: Flow<CachedMarketsPayload?> = context.marketDataStore.data.map { prefs ->
        val raw = prefs[keyPayload] ?: return@map null
        runCatching { json.decodeFromString<CachedMarketsPayload>(raw) }.getOrNull()
            ?.cryptoOnly()
    }

    suspend fun read(): CachedMarketsPayload? = cachedFlow.first()

    suspend fun write(
        btc: List<MarketDto>,
        eth: List<MarketDto>,
        sol: List<MarketDto>,
        extra: List<MarketDto> = emptyList(),
        fetchedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val payload = CachedMarketsPayload(
            btc = btc.cryptoOnly(),
            eth = eth.cryptoOnly(),
            sol = sol.cryptoOnly(),
            extra = extra.cryptoOnly(),
            fetchedAtEpochMs = fetchedAtEpochMs
        )
        context.marketDataStore.edit { prefs ->
            prefs[keyPayload] = json.encodeToString(payload)
            prefs[keyFetchedAt] = fetchedAtEpochMs
        }
    }

    companion object {
        private fun List<MarketDto>.cryptoOnly(): List<MarketDto> =
            filter { CryptoMarkets.isCryptoTicker(it.ticker) }

        private fun CachedMarketsPayload.cryptoOnly(): CachedMarketsPayload = copy(
            btc = btc.cryptoOnly(),
            eth = eth.cryptoOnly(),
            sol = sol.cryptoOnly(),
            extra = extra.cryptoOnly()
        )
    }
}
