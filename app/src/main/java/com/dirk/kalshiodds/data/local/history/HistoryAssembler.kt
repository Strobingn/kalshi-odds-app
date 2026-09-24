package com.dirk.kalshiodds.data.local.history

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill

/**
 * Pure History projections — filters, totals, and cumulative P&L
 * stay off the UI thread and off Paging 3.
 */
object HistoryAssembler {

    enum class SourceFilter { ALL, LIVE, PAPER }
    enum class CoinFilter { ALL, BTC, ETH, SOL }
    enum class DateFilter { ALL, D1, D7, D30 }

    data class Totals(
        val count: Int = 0,
        val stakeUsd: Double = 0.0,
        val pnlUsd: Double = 0.0,
        val wins: Int = 0,
        val losses: Int = 0,
        val open: Int = 0
    )

    data class SignalLine(
        val id: String,
        val createdAtMs: Long,
        val ticker: String,
        val side: String,
        val marketPp: Double,
        val fairPp: Double,
        val edgePp: Double,
        val settled: String?
    )

    fun sinceMs(filter: DateFilter, nowMs: Long): Long? = when (filter) {
        DateFilter.ALL -> null
        DateFilter.D1 -> nowMs - 86_400_000L
        DateFilter.D7 -> nowMs - 7L * 86_400_000L
        DateFilter.D30 -> nowMs - 30L * 86_400_000L
    }

    fun coinOf(ticker: String): String {
        val u = ticker.uppercase()
        return when {
            u.contains("BTC") -> "BTC"
            u.contains("ETH") -> "ETH"
            u.contains("SOL") -> "SOL"
            else -> "OTHER"
        }
    }

    fun paperResult(fill: PaperFill): String = when {
        fill.outcome.equals("sell", ignoreCase = true) -> "sold"
        fill.won == true -> "won"
        fill.won == false -> "lost"
        fill.settled -> "settled"
        else -> "open"
    }

    fun liveResult(row: TicketAttemptRow): String {
        val r = row.result.lowercase()
        return when {
            !row.approved -> "ticket"
            r.contains("sell") || r.contains("cancel") -> "sold"
            r.contains("win") || r == "won" -> "won"
            r.contains("loss") || r == "lost" -> "lost"
            r.contains("filled") || r.contains("submitted") || r.contains("resting") -> "open"
            else -> row.result.ifBlank { "open" }
        }
    }

    fun bets(
        tickets: List<TicketAttemptRow>,
        paper: PaperBookState,
        source: SourceFilter = SourceFilter.ALL,
        coin: CoinFilter = CoinFilter.ALL,
        sinceMs: Long? = null
    ): List<HistoryBet> {
        val live = tickets.filter { it.approved }.map { t ->
            HistoryBet(
                id = "live-${t.id}-${t.createdAtMs}-${t.ticker}",
                createdAtMs = t.createdAtMs,
                ticker = t.ticker,
                side = t.side,
                contracts = 0,
                price = 0.0,
                stakeUsd = t.stakeUsd,
                source = "live",
                result = liveResult(t),
                pnlUsd = null,
                live = true,
                winTargetUsd = parseWinTarget(t.note)
            )
        }
        val fills = (paper.fills + paper.archived.flatMap { it.fills }).map { f ->
            HistoryBet(
                id = "paper-${f.id}",
                createdAtMs = f.createdAtMs,
                ticker = f.ticker,
                side = f.side,
                contracts = f.contracts,
                price = f.limitPrice,
                stakeUsd = f.stakeUsd,
                source = "paper",
                result = paperResult(f),
                pnlUsd = f.pnlUsd,
                live = false,
                winTargetUsd = f.winTargetUsd
            )
        }
        return (live + fills)
            .asSequence()
            .filter { source == SourceFilter.ALL || (source == SourceFilter.LIVE) == it.live }
            .filter { coin == CoinFilter.ALL || coinOf(it.ticker) == coin.name }
            .filter { sinceMs == null || it.createdAtMs >= sinceMs }
            .sortedByDescending { it.createdAtMs }
            .distinctBy { it.id }
            .toList()
    }

    fun totals(bets: List<HistoryBet>): Totals {
        var stake = 0.0
        var pnl = 0.0
        var wins = 0
        var losses = 0
        var open = 0
        for (b in bets) {
            stake += b.stakeUsd
            pnl += b.pnlUsd ?: 0.0
            when (b.result.lowercase()) {
                "won" -> wins += 1
                "lost" -> losses += 1
                "open", "ticket" -> open += 1
            }
        }
        return Totals(
            count = bets.size,
            stakeUsd = stake,
            pnlUsd = pnl,
            wins = wins,
            losses = losses,
            open = open
        )
    }

    /** Oldest → newest running realized P&L (open bets add 0). */
    fun cumulativePnl(bets: List<HistoryBet>): List<Pair<Long, Double>> {
        var run = 0.0
        return bets.sortedBy { it.createdAtMs }.map { b ->
            run += b.pnlUsd ?: 0.0
            b.createdAtMs to run
        }
    }

    fun signals(
        snapshots: List<ScoredSnapshotRow>,
        settled: List<SettledWindowRow>
    ): List<SignalLine> {
        val byTicker = settled.associateBy { it.ticker.uppercase() }
        return snapshots.map { s ->
            SignalLine(
                id = "sig-${s.id}-${s.createdAtMs}-${s.ticker}",
                createdAtMs = s.createdAtMs,
                ticker = s.ticker,
                side = s.side,
                marketPp = s.marketPp,
                fairPp = s.fairPp,
                edgePp = s.edgePp,
                settled = byTicker[s.ticker.uppercase()]?.result
            )
        }
    }

    fun parseWinTarget(note: String?): Double? {
        if (note.isNullOrBlank()) return null
        val m = Regex("""win-?target[^0-9$]*\$?([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(note) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }
}
