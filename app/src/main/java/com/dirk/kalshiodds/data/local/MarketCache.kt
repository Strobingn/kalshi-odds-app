package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.CryptoMarkets

private val Context.marketDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kalshi_market_cache",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Serializable
data class CachedMarketsPayload(
    val btc: List<MarketDto> = emptyList(),
    val eth: List<MarketDto> = emptyList(),
    val sol: List<MarketDto> = emptyList(),
    val extra: List<MarketDto> = emptyList(),
    val fetchedAtEpochMs: Long = 0L
)

interface MarketSnapshotCache {
    val cachedFlow: Flow<CachedMarketsPayload?>
    suspend fun read(): CachedMarketsPayload?
    suspend fun write(
        btc: List<MarketDto>,
        eth: List<MarketDto>,
        sol: List<MarketDto>,
        extra: List<MarketDto> = emptyList(),
        fetchedAtEpochMs: Long = System.currentTimeMillis()
    )
}

class MarketCache(private val context: Context) : MarketSnapshotCache {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val keyPayload = stringPreferencesKey("cached_markets_json")
    private val keyFetchedAt = longPreferencesKey("fetched_at_epoch_ms")

    override val cachedFlow: Flow<CachedMarketsPayload?> = context.marketDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[keyPayload] ?: return@map null
            runCatching { json.decodeFromString<CachedMarketsPayload>(raw) }.getOrNull()
                ?.cryptoOnly()
        }

    override suspend fun read(): CachedMarketsPayload? = cachedFlow.first()

    override suspend fun write(
        btc: List<MarketDto>,
        eth: List<MarketDto>,
        sol: List<MarketDto>,
        extra: List<MarketDto>,
        fetchedAtEpochMs: Long
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
