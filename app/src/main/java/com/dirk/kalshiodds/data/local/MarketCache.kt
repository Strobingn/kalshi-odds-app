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
                ?.bitcoinOnly()
        }

    override suspend fun read(): CachedMarketsPayload? {
        val prefs = context.marketDataStore.data.first()
        val raw = prefs[keyPayload] ?: return null
        val decoded = runCatching { json.decodeFromString<CachedMarketsPayload>(raw) }.getOrNull() ?: return null
        val cleaned = decoded.bitcoinOnly()
        if (decoded.hasRetiredSeries()) {
            write(cleaned.btc, emptyList(), emptyList(), cleaned.extra, cleaned.fetchedAtEpochMs)
        }
        return cleaned
    }

    override suspend fun write(
        btc: List<MarketDto>,
        eth: List<MarketDto>,
        sol: List<MarketDto>,
        extra: List<MarketDto>,
        fetchedAtEpochMs: Long
    ) {
        val payload = CachedMarketsPayload(
            btc = btc,
            eth = eth,
            sol = sol,
            extra = extra,
            fetchedAtEpochMs = fetchedAtEpochMs
        ).bitcoinOnly()
        context.marketDataStore.edit { prefs ->
            prefs[keyPayload] = json.encodeToString(payload)
            prefs[keyFetchedAt] = fetchedAtEpochMs
        }
    }

    companion object {
        private fun List<MarketDto>.cryptoOnly(): List<MarketDto> =
            filter { CryptoMarkets.isCryptoTicker(it.ticker) }

        private fun CachedMarketsPayload.cryptoOnly(): CachedMarketsPayload = bitcoinOnly()
    }
}

/** Drop ETH/SOL and any non-Bitcoin row. Used on cache read and on load. */
fun CachedMarketsPayload.bitcoinOnly(): CachedMarketsPayload {
    fun List<MarketDto>.btcOnly(): List<MarketDto> =
        filter { CryptoMarkets.isBtc15m("", it.ticker) }
    return copy(
        btc = btc.btcOnly(),
        eth = emptyList(),
        sol = emptyList(),
        extra = extra.btcOnly()
    )
}

fun CachedMarketsPayload.hasRetiredSeries(): Boolean =
    eth.isNotEmpty() || sol.isNotEmpty() ||
        btc.any { !CryptoMarkets.isBtc15m("", it.ticker) } ||
        extra.any { !CryptoMarkets.isBtc15m("", it.ticker) }
