package com.dirk.kalshiodds.signal.lastminute

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class LastMinutePickRow(
    val id: String,
    val ticker: String,
    val side: String,
    val entryAsk: Double,
    val contracts: Int,
    val stakeUsd: Double,
    val feeUsd: Double,
    val winChance: Double,
    val evPerDollar: Double,
    val depthLimited: Boolean = false,
    val createdAtMs: Long,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null
) {
    fun toPick(): LastMinutePick = LastMinutePick(
        id = id,
        ticker = ticker,
        side = side,
        entryAsk = entryAsk,
        contracts = contracts,
        stakeUsd = stakeUsd,
        feeUsd = feeUsd,
        winChance = winChance,
        evPerDollar = evPerDollar,
        depthLimited = depthLimited,
        createdAtMs = createdAtMs,
        settled = settled,
        outcome = outcome,
        won = won,
        pnlUsd = pnlUsd
    )
}

/**
 * Persist last-minute paper picks separately from the $100 AI paper book
 * so existing scorecard sections stay intact.
 */
class LastMinuteStore(
    context: Context? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _state = MutableStateFlow(hydrate())
    val state: StateFlow<LastMinuteBookState> = _state.asStateFlow()

    fun snapshot(): LastMinuteBookState = _state.value

    fun record(fired: LastMinuteFired): LastMinutePick? {
        synchronized(lock) {
            val cur = _state.value
            if (cur.picks.any { !it.settled && it.ticker.equals(fired.ticker, true) }) return null
            if (cur.picks.any { it.ticker.equals(fired.ticker, true) && it.createdAtMs == fired.firedAtMs }) {
                return null
            }
            val pick = LastMinutePick(
                id = idFactory(),
                ticker = fired.ticker,
                side = fired.side,
                entryAsk = fired.ask,
                contracts = fired.contracts,
                stakeUsd = fired.costUsd,
                feeUsd = fired.feeUsd,
                winChance = fired.winChance,
                evPerDollar = fired.evPerDollar,
                depthLimited = fired.depthLimited,
                createdAtMs = if (fired.firedAtMs > 0L) fired.firedAtMs else nowMs()
            )
            publish(cur.copy(picks = (listOf(pick) + cur.picks).take(MAX)))
            return pick
        }
    }

    fun settle(ticker: String, result: String): List<LastMinutePick> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val changed = mutableListOf<LastMinutePick>()
        synchronized(lock) {
            val cur = _state.value
            val next = cur.picks.map { pick ->
                if (pick.settled || !pick.ticker.equals(ticker, true)) return@map pick
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
            publish(cur.copy(picks = next))
        }
        return changed
    }

    fun recordOf(picks: List<LastMinutePick> = snapshot().picks): Triple<Int, Int, LastMinuteCopyStats> {
        val settled = picks.filter { it.settled && it.won != null }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val wonUsd = settled.filter { (it.pnlUsd ?: 0.0) > 0.0 }.sumOf { it.pnlUsd ?: 0.0 }
        val lostUsd = settled.filter { (it.pnlUsd ?: 0.0) <= 0.0 }.sumOf { -(it.pnlUsd ?: 0.0) }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val rate = if (settled.isEmpty()) null else wins.toDouble() / settled.size
        return Triple(wins, losses, LastMinuteCopyStats(rate, wonUsd, lostUsd, pnl, settled.size))
    }

    private fun hydrate(): LastMinuteBookState {
        val raw = prefs?.getString(KEY, null) ?: return LastMinuteBookState()
        return runCatching {
            val rows = json.decodeFromString<List<LastMinutePickRow>>(raw)
            LastMinuteBookState(picks = rows.map { it.toPick() })
        }.getOrElse { LastMinuteBookState() }
    }

    private fun publish(next: LastMinuteBookState) {
        _state.value = next
        val rows = next.picks.map {
            LastMinutePickRow(
                id = it.id,
                ticker = it.ticker,
                side = it.side,
                entryAsk = it.entryAsk,
                contracts = it.contracts,
                stakeUsd = it.stakeUsd,
                feeUsd = it.feeUsd,
                winChance = it.winChance,
                evPerDollar = it.evPerDollar,
                depthLimited = it.depthLimited,
                createdAtMs = it.createdAtMs,
                settled = it.settled,
                outcome = it.outcome,
                won = it.won,
                pnlUsd = it.pnlUsd
            )
        }
        runCatching { prefs?.edit()?.putString(KEY, json.encodeToString(rows))?.apply() }
    }

    companion object {
        private const val PREFS = "diphunter_last_minute"
        private const val KEY = "picks_json"
        private const val MAX = 400
    }
}

data class LastMinuteCopyStats(
    val winRate: Double?,
    val wonUsd: Double,
    val lostUsd: Double,
    val pnlUsd: Double,
    val settledCount: Int
)
