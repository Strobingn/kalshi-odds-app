package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.MarketsResponse
import kotlinx.coroutines.CancellationException

/**
 * Live contracts for a series.
 *
 * Docs say `GET /markets?status=open` matches response `status=active`.
 * That filter still works for the daily series. For KXBTC15M / KXETH15M /
 * KXSOL15M it currently returns an empty page while the contract is active.
 * `GET /events?status=open` still names that window, and
 * `GET /markets?event_ticker=` (no status) returns the contract.
 */
object OpenMarketListing {
    fun isFifteenMinute(series: String): Boolean =
        series.trim().uppercase().endsWith("15M")

    suspend fun fetch(api: KalshiApi, series: String, limit: Int): MarketsResponse {
        val open = api.getMarkets(seriesTicker = series, status = "open", limit = limit)
        if (open.markets.isNotEmpty() || !isFifteenMinute(series)) return open
        val event = try {
            api.getEvents(seriesTicker = series, status = "open", limit = 5)
                .events.firstOrNull { !it.eventTicker.isNullOrBlank() }
                ?.eventTicker
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return open
        val listed = try {
            api.getMarketsForEvent(seriesTicker = series, eventTicker = event, limit = limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return open
        }
        return if (listed.markets.isEmpty()) open else listed
    }
}
