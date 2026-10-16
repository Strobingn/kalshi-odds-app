package com.dirk.kalshiodds.signal.d3

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class D3PickRow(
    val id: String,
    val ticker: String,
    val eventTicker: String? = null,
    val strikeUsd: Double? = null,
    val subtitle: String? = null,
    val side: String,
    val bidPrice: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val createdAtMs: Long,
    val filled: Boolean = false,
    val filledAtMs: Long? = null,
    val cancelledAtMs: Long? = null,
    val cancelReason: String? = null,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null
) {
    fun toPick(): D3Pick = D3Pick(
        id = id,
        ticker = ticker,
        eventTicker = eventTicker,
        strikeUsd = strikeUsd,
        subtitle = subtitle,
        side = side,
        bidPrice = bidPrice,
        contracts = contracts,
        stakeUsd = stakeUsd,
        feeUsd = feeUsd,
        createdAtMs = createdAtMs,
        filled = filled,
        filledAtMs = filledAtMs,
        cancelledAtMs = cancelledAtMs,
        cancelReason = cancelReason,
        settled = settled,
        outcome = outcome,
        won = won,
        pnlUsd = pnlUsd
    )
}

/**
 * Persist D3 paper bids separately from the $100 AI paper book and the
 * last-minute store so existing scorecard sections stay intact.
 */
class D3Store(
    context: Context? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _state = MutableStateFlow(hydrate())
    val state: StateFlow<D3BookState> = _state.asStateFlow()

    fun snapshot(): D3BookState = _state.value

    fun openTickers(): Set<String> = _state.value.picks
        .filter { !it.settled && (it.filled || it.cancelledAtMs == null) }
        .map { it.ticker.uppercase() }
        .toSet()

    fun heldTickers(): Set<String> = _state.value.picks
        .filter { it.filled && !it.settled }
        .map { it.ticker.uppercase() }
        .toSet()

    fun takenToday(ticker: String, nowMs: Long = this.nowMs()): Boolean {
        val day = D3Window.dayKey(nowMs)
        return _state.value.picks.any {
            it.ticker.equals(ticker, true) && D3Window.dayKey(it.createdAtMs) == day
        }
    }

    fun recordResting(signal: D3Signal): D3Pick? {
        synchronized(lock) {
            val cur = _state.value
            val day = D3Window.dayKey(signal.qualifiedAtMs)
            if (cur.picks.any {
                    it.ticker.equals(signal.ticker, true) && D3Window.dayKey(it.createdAtMs) == day
                }
            ) {
                return null
            }
            val pick = D3Pick(
                id = idFactory(),
                ticker = signal.ticker,
                eventTicker = signal.eventTicker,
                strikeUsd = signal.strikeUsd,
                subtitle = signal.subtitle,
                side = signal.side,
                bidPrice = signal.bidPrice,
                contracts = signal.contracts,
                stakeUsd = signal.stakeUsd,
                feeUsd = signal.feeUsd,
                createdAtMs = if (signal.qualifiedAtMs > 0L) signal.qualifiedAtMs else nowMs()
            )
            publish(cur.copy(picks = (listOf(pick) + cur.picks).take(MAX)))
            return pick
        }
    }

    fun markFilled(ticker: String, atMs: Long = nowMs()): D3Pick? {
        var changed: D3Pick? = null
        synchronized(lock) {
            val next = _state.value.picks.map { pick ->
                if (pick.filled || pick.settled || !pick.ticker.equals(ticker, true)) return@map pick
                if (pick.cancelledAtMs != null) return@map pick
                pick.copy(filled = true, filledAtMs = atMs).also { changed = it }
            }
            if (changed != null) publish(_state.value.copy(picks = next))
        }
        return changed
    }

    fun cancelUnfilled(ticker: String, reason: String, atMs: Long = nowMs()): D3Pick? {
        var changed: D3Pick? = null
        synchronized(lock) {
            val next = _state.value.picks.map { pick ->
                if (pick.filled || pick.settled || pick.cancelledAtMs != null) return@map pick
                if (!pick.ticker.equals(ticker, true)) return@map pick
                pick.copy(cancelledAtMs = atMs, cancelReason = reason).also { changed = it }
            }
            if (changed != null) publish(_state.value.copy(picks = next))
        }
        return changed
    }

    fun settle(ticker: String, result: String): List<D3Pick> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val changed = mutableListOf<D3Pick>()
        synchronized(lock) {
            val next = _state.value.picks.map { pick ->
                if (pick.settled || !pick.ticker.equals(ticker, true)) return@map pick
                if (!pick.filled) {
                    return@map pick.copy(settled = true, outcome = outcome, won = null, pnlUsd = 0.0)
                        .also { changed += it }
                }
                val won = when (outcome) {
                    "void" -> null
                    "yes" -> pick.side.equals("YES", true)
                    else -> pick.side.equals("NO", true)
                }
                val pnl = when {
                    outcome == "void" -> 0.0
                    won == true -> pick.contracts - pick.stakeUsd
                    else -> -pick.stakeUsd
                }
                pick.copy(settled = true, outcome = outcome, won = won, pnlUsd = pnl).also { changed += it }
            }
            if (changed.isEmpty()) return emptyList()
            publish(_state.value.copy(picks = next))
        }
        return changed
    }

    fun today(nowMs: Long = this.nowMs()): List<D3Pick> {
        val day = D3Window.dayKey(nowMs)
        return _state.value.picks.filter { D3Window.dayKey(it.createdAtMs) == day }
    }

    private fun hydrate(): D3BookState {
        val raw = prefs?.getString(KEY, null) ?: return D3BookState()
        return runCatching {
            val rows = json.decodeFromString<List<D3PickRow>>(raw)
            D3BookState(picks = rows.map { it.toPick() })
        }.getOrElse { D3BookState() }
    }

    private fun publish(next: D3BookState) {
        _state.value = next
        val rows = next.picks.map {
            D3PickRow(
                id = it.id,
                ticker = it.ticker,
                eventTicker = it.eventTicker,
                strikeUsd = it.strikeUsd,
                subtitle = it.subtitle,
                side = it.side,
                bidPrice = it.bidPrice,
                contracts = it.contracts,
                stakeUsd = it.stakeUsd,
                feeUsd = it.feeUsd,
                createdAtMs = it.createdAtMs,
                filled = it.filled,
                filledAtMs = it.filledAtMs,
                cancelledAtMs = it.cancelledAtMs,
                cancelReason = it.cancelReason,
                settled = it.settled,
                outcome = it.outcome,
                won = it.won,
                pnlUsd = it.pnlUsd
            )
        }
        runCatching { prefs?.edit()?.putString(KEY, json.encodeToString(rows))?.apply() }
    }

    companion object {
        const val PREFS = "diphunter_d3"
        private const val KEY = "picks_json"
        private const val MAX = 800
    }
}
