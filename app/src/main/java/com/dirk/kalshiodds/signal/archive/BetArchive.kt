package com.dirk.kalshiodds.signal.archive

import com.dirk.kalshiodds.signal.latefav.LateFavoriteEntry
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import java.util.Locale

/**
 * One bet the phone's own rules took on paper (clear lead, the other
 * trackers, the paper book), kept for good in [BetArchiveStore]. The
 * trackers trim their own lists and a reset empties them; this never does.
 * Paper only: nothing here is an order.
 */
data class BetRow(
    /** Unique per bet: the source, ticker and entry time, or the paper fill's id. */
    val id: String,
    val source: String,
    val ticker: String,
    val side: String,
    val price: Double,
    val contracts: Int,
    /** All-in: contracts × price plus the taker fee. */
    val costUsd: Double,
    val feeUsd: Double,
    val placedAtMs: Long,
    val secondsLeft: Long? = null,
    /** "yes" | "no" | "void" once settled. */
    val result: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null,
    val settledAtMs: Long? = null,
    val note: String = ""
) {
    val settled: Boolean get() = result != null || pnlUsd != null
}

object BetRows {
    const val CLEAR_LEAD = "Clear lead"
    const val LATE_FAVORITE = "Late favorite"
    const val FLOW_FADE = "Flow fade"
    const val CENT_BETTER = "1¢ better"
    const val PAPER_BOOK = "Paper book"

    fun ofTracker(source: String, e: LateFavoriteEntry): BetRow = BetRow(
        id = "$source|${e.ticker.uppercase(Locale.US)}|${e.entryAtMs}",
        source = source,
        ticker = e.ticker,
        side = e.side.uppercase(Locale.US),
        price = e.ask,
        contracts = e.contracts,
        costUsd = e.costUsd,
        feeUsd = e.feeUsd,
        placedAtMs = e.entryAtMs,
        secondsLeft = e.tteSeconds,
        result = e.result,
        won = e.won,
        pnlUsd = e.pnlUsd,
        settledAtMs = e.settledAtMs
    )

    fun ofTracker(source: String, state: LateFavoriteState): List<BetRow> = state.entries.map { ofTracker(source, it) }

    fun ofPaper(f: PaperFill): BetRow = BetRow(
        id = "paper|${f.id}",
        source = PAPER_BOOK,
        ticker = f.ticker,
        side = f.displaySide,
        price = f.limitPrice,
        contracts = f.contracts,
        costUsd = f.stakeUsd + f.feeUsd,
        feeUsd = f.feeUsd,
        placedAtMs = f.createdAtMs,
        result = f.outcome?.lowercase(Locale.US),
        won = f.won,
        pnlUsd = f.pnlUsd,
        note = "${f.source} · ${f.note}"
    )

    /** Every fill the paper book still holds, the reset runs it archived included. */
    fun ofPaper(state: PaperBookState): List<BetRow> =
        (state.fills + state.archived.flatMap { it.fills }).distinctBy { it.id }.map { ofPaper(it) }
}

/**
 * Remembers what was last written per bet so a save writes only the bets
 * that are new or changed (a settlement). Pure; [write] is the storage.
 */
class BetArchiveDiff(private val write: (List<BetRow>) -> Unit) {
    private val seen = HashMap<String, BetRow>()

    @Synchronized
    fun offer(rows: List<BetRow>) {
        val changed = rows.filter { seen[it.id] != it }
        if (changed.isEmpty()) return
        write(changed)
        for (r in changed) seen[r.id] = r
    }
}

/** CSV of the archive (Settings / Scorecard → Export results). */
object BetArchiveCsv {
    const val HEADER =
        "kind,id,source,ticker,side,price,contracts,cost_usd,fee_usd,placed_at_ms,seconds_left,result,won,pnl_usd,settled_at_ms,note"

    fun csv(rows: List<BetRow>): String = buildString {
        append(HEADER).append('\n')
        for (r in rows) {
            append("bet,").append(q(r.id)).append(',').append(q(r.source)).append(',').append(q(r.ticker)).append(',')
            append(r.side).append(',').append(String.format(Locale.US, "%.4f", r.price)).append(',').append(r.contracts).append(',')
            append(String.format(Locale.US, "%.4f", r.costUsd)).append(',').append(String.format(Locale.US, "%.4f", r.feeUsd)).append(',')
            append(r.placedAtMs).append(',').append(r.secondsLeft ?: "").append(',').append(r.result ?: "").append(',')
            append(r.won ?: "").append(',').append(r.pnlUsd?.let { String.format(Locale.US, "%.4f", it) } ?: "").append(',')
            append(r.settledAtMs ?: "").append(',').append(q(r.note)).append('\n')
        }
    }

    private fun q(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** Totals and chart series for the archive card. Pure. */
object BetArchiveSummary {
    data class Source(val name: String, val bets: Int, val settled: Int, val wins: Int, val pnlUsd: Double, val costUsd: Double) {
        val winRate: Double? get() = if (settled > 0) wins.toDouble() / settled else null
        val perBetUsd: Double? get() = if (settled > 0) pnlUsd / settled else null
    }

    fun bySource(rows: List<BetRow>): List<Source> =
        rows.groupBy { it.source }.map { (name, list) ->
            val done = list.filter { it.pnlUsd != null && it.result != "void" }
            Source(name, list.size, done.size, done.count { it.won == true }, done.sumOf { it.pnlUsd!! }, done.sumOf { it.costUsd })
        }.sortedByDescending { it.bets }

    /** Running paper P&L over settled bets in placement order: (time, total so far). */
    fun cumulative(rows: List<BetRow>, source: String? = null): List<Pair<Long, Double>> {
        var total = 0.0
        return rows.filter { (source == null || it.source == source) && it.pnlUsd != null }
            .sortedBy { it.placedAtMs }
            .map { total += it.pnlUsd!!; it.placedAtMs to total }
    }

    /** Settled paper P&L per UTC day (by placement), oldest first, the last [days] days that had a bet. */
    fun dailyPnl(rows: List<BetRow>, days: Int = 14): List<Pair<String, Double>> {
        val byDay = rows.filter { it.pnlUsd != null }.groupBy {
            java.time.Instant.ofEpochMilli(it.placedAtMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        }.mapValues { (_, l) -> l.sumOf { it.pnlUsd!! } }
        return byDay.entries.sortedBy { it.key }.takeLast(days).map { it.key to it.value }
    }

    /** A tracker's running paper P&L over its settled entries, in settlement order: (time, total so far). */
    fun trackerCurve(entries: List<LateFavoriteEntry>): List<Pair<Long, Double>> {
        var total = 0.0
        return entries.filter { it.pnlUsd != null && it.result != "void" }
            .sortedBy { it.settledAtMs ?: it.entryAtMs }
            .map { total += it.pnlUsd!!; (it.settledAtMs ?: it.entryAtMs) to total }
    }

    /** Bets placed per UTC day, oldest first, the last [days] days. */
    fun perDay(rows: List<BetRow>, days: Int = 14): List<Pair<String, Int>> {
        val byDay = rows.groupingBy {
            java.time.Instant.ofEpochMilli(it.placedAtMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        }.eachCount()
        return byDay.entries.sortedBy { it.key }.takeLast(days).map { it.key to it.value }
    }
}
