package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.SeriesKind
import java.util.Calendar
import java.util.TimeZone

/**
 * One closed scalper round trip, flattened for aggregation. Built by pairing
 * the store's ENTER/EXIT [ScalpTradeRow]s (same `positionId`) or directly
 * from a closed [ScalpPosition] — either way this is the pure input the
 * stats math consumes, so tests never touch SQLite.
 *
 * Fees: [ScalpPositionStore] persists BOTH legs' taker fees (`fee_cents` on
 * the ENTER and EXIT trade rows, `entry_fee_cents` / `exit_fee_cents` on the
 * position row; the engine computes them with
 * `round(KalshiFee.total(contracts, price01) * 100)`). [pnlCents] is already
 * net of both fees (`exit proceeds − entry cost − fees`), so no fee
 * recomputation is needed here — [totalFeesCents] is a plain sum of what the
 * ledger stored.
 */
data class ClosedTrade(
    val positionId: String,
    val ticker: String,
    val strategy: ScalpStrategy,
    val entryPriceCents: Int,
    val exitPriceCents: Int,
    val contracts: Int,
    val entryTimeMs: Long,
    val exitTimeMs: Long,
    /** Realized net P&L cents: exit proceeds − entry cost − both-leg fees. */
    val pnlCents: Int,
    val entryFeeCents: Int,
    val exitFeeCents: Int,
    val reason: ExitReason
)

/** Mark-to-market view of the currently open position, if any. */
data class OpenPositionMark(
    val position: ScalpPosition,
    val markPriceCents: Int
) {
    /**
     * Unrealized cents before exit fees: (mark − entry) × contracts − the
     * sunk entry fee. The exit leg's fee is unknown until the fill, so it is
     * NOT estimated here (unlike closed trades, whose fees are persisted).
     */
    val unrealizedCents: Int
        get() = (markPriceCents - position.entryPriceCents) * position.contracts -
            position.entryFeeCents
}

data class BankrollPoint(val atMs: Long, val bankrollCents: Long)

data class StrategyStats(
    val strategy: ScalpStrategy,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val netCents: Long,
    /** Average net P&L cents per trade in this bucket. */
    val avgCents: Double
)

data class CoinStats(
    val coin: String,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val netCents: Long
)

data class HourStats(
    val hourUtc: Int,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val netCents: Long
)

/**
 * Aggregated scalper track record. Every money field is integer cents so no
 * float money leaks out; the UI formats to dollars at display time.
 */
data class ScalpStats(
    /** Sum of net P&L cents across all closed trades. */
    val totalNetCents: Long,
    /** Sum of the POSITIVE net P&L cents (wins only). */
    val grossWonCents: Long,
    /** Sum of the NEGATIVE net P&L cents (losses only) — always ≤ 0. */
    val grossLostCents: Long,
    val winCount: Int,
    val lossCount: Int,
    /** grossWon / winCount; 0.0 when no wins. */
    val avgWinCents: Double,
    /** grossLost / lossCount (negative); 0.0 when no losses. */
    val avgLossCents: Double,
    /** Largest single-trade net win; 0 when no wins. */
    val maxWinCents: Long,
    /** Largest single-trade net loss (most negative); 0 when no losses. */
    val maxLossCents: Long,
    /** Both legs' taker fees summed — read from stored row fees, not recomputed. */
    val totalFeesCents: Long,
    val tradeCount: Int,
    /** winCount / tradeCount; 0.0 when no trades. */
    val winRate: Double,
    /** Cumulative paper bankroll: starts at [ScalpStatsMath.PAPER_START_BANKROLL_CENTS]. */
    val bankroll: List<BankrollPoint>,
    val byStrategy: Map<ScalpStrategy, StrategyStats>,
    /** Keyed by coin ("BTC" / "ETH" / "SOL" / ticker prefix), net desc. */
    val byCoin: Map<String, CoinStats>,
    /** Keyed by UTC hour of entry (0–23), ascending. */
    val byHourUtc: Map<Int, HourStats>
) {
    val hasTrades: Boolean get() = tradeCount > 0
}

/**
 * Pure aggregation over [ClosedTrade]s — no Android, no I/O. Feed it the
 * paired trade rows from [ScalpPositionStore.recentTrades] via [fromLedgerRows],
 * or synthetic trades in tests.
 */
object ScalpStatsMath {

    /** Paper bankroll the scalper starts from: $100.00. */
    const val PAPER_START_BANKROLL_CENTS = 10_000L

    fun compute(
        trades: List<ClosedTrade>,
        open: OpenPositionMark? = null,
        nowMs: Long = trades.maxOfOrNull { it.exitTimeMs } ?: 0L
    ): ScalpStats {
        val ordered = trades.sortedBy { it.exitTimeMs }
        val wins = ordered.filter { it.pnlCents > 0 }
        val losses = ordered.filter { it.pnlCents < 0 }

        val totalNet = ordered.sumOf { it.pnlCents.toLong() }
        val grossWon = wins.sumOf { it.pnlCents.toLong() }
        val grossLost = losses.sumOf { it.pnlCents.toLong() }
        val totalFees = ordered.sumOf { (it.entryFeeCents + it.exitFeeCents).toLong() }
        val winCount = wins.size
        val lossCount = losses.size
        val tradeCount = ordered.size

        // Bankroll: seed with the $100 paper stake, then running net after
        // each exit. An open position appends a marked-to-market endpoint.
        val points = ArrayList<BankrollPoint>(tradeCount + 2)
        val startMs = ordered.firstOrNull()?.entryTimeMs ?: nowMs
        points += BankrollPoint(startMs, PAPER_START_BANKROLL_CENTS)
        var running = PAPER_START_BANKROLL_CENTS
        ordered.forEach { t ->
            running += t.pnlCents
            points += BankrollPoint(t.exitTimeMs, running)
        }
        val unrealized = open?.unrealizedCents?.toLong() ?: 0L
        if (open != null) {
            points += BankrollPoint(nowMs, running + unrealized)
        }

        return ScalpStats(
            totalNetCents = totalNet,
            grossWonCents = grossWon,
            grossLostCents = grossLost,
            winCount = winCount,
            lossCount = lossCount,
            avgWinCents = if (winCount > 0) grossWon.toDouble() / winCount else 0.0,
            avgLossCents = if (lossCount > 0) grossLost.toDouble() / lossCount else 0.0,
            maxWinCents = wins.maxOfOrNull { it.pnlCents }?.toLong() ?: 0L,
            maxLossCents = losses.minOfOrNull { it.pnlCents }?.toLong() ?: 0L,
            totalFeesCents = totalFees,
            tradeCount = tradeCount,
            winRate = if (tradeCount > 0) winCount.toDouble() / tradeCount else 0.0,
            bankroll = points,
            byStrategy = strategyBreakdown(ordered),
            byCoin = coinBreakdown(ordered),
            byHourUtc = hourBreakdown(ordered)
        )
    }

    /**
     * Pair the store's newest-first flat rows into [ClosedTrade]s by
     * `positionId` (ENTER row supplies entry price/fee/time, EXIT row the
     * exit price/fee/time, pnl and reason) and aggregate. Rows without a
     * matching EXIT (open positions, truncated cache) are skipped.
     */
    fun fromLedgerRows(
        rows: List<ScalpTradeRow>,
        open: OpenPositionMark? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ScalpStats = compute(pairRows(rows), open, nowMs)

    /** ENTER/EXIT pairing — exposed for tests. */
    fun pairRows(rows: List<ScalpTradeRow>): List<ClosedTrade> {
        val entries = HashMap<String, ScalpTradeRow>()
        val exits = LinkedHashMap<String, ScalpTradeRow>()
        rows.forEach { r ->
            when (r.action) {
                ScalpPositionStore.ACTION_ENTER -> entries[r.positionId] = r
                ScalpPositionStore.ACTION_EXIT -> exits[r.positionId] = r
            }
        }
        return exits.mapNotNull { (id, exit) ->
            val entry = entries[id] ?: return@mapNotNull null
            ClosedTrade(
                positionId = id,
                ticker = exit.ticker,
                strategy = ScalpStrategy.parse(exit.strategy),
                entryPriceCents = entry.priceCents,
                exitPriceCents = exit.priceCents,
                contracts = exit.contracts,
                entryTimeMs = entry.createdAtMs,
                exitTimeMs = exit.createdAtMs,
                pnlCents = exit.pnlCents ?: return@mapNotNull null,
                entryFeeCents = entry.feeCents,
                exitFeeCents = exit.feeCents,
                reason = runCatching { ExitReason.valueOf(exit.reason ?: "") }.getOrNull()
                    ?: ExitReason.TIMEOUT
            )
        }
    }

    private fun strategyBreakdown(trades: List<ClosedTrade>): Map<ScalpStrategy, StrategyStats> =
        trades.groupBy { it.strategy }
            .map { (strategy, list) ->
                val wins = list.count { it.pnlCents > 0 }
                val net = list.sumOf { it.pnlCents.toLong() }
                strategy to StrategyStats(
                    strategy = strategy,
                    tradeCount = list.size,
                    wins = wins,
                    losses = list.size - wins,
                    netCents = net,
                    avgCents = net.toDouble() / list.size
                )
            }
            .sortedByDescending { it.second.netCents }
            .toMap(LinkedHashMap())

    /** Coin key via the app's existing series→coin mapping in [CryptoMarkets]. */
    private fun coinBreakdown(trades: List<ClosedTrade>): Map<String, CoinStats> =
        trades.groupBy { coinOf(it.ticker) }
            .map { (coin, list) ->
                val wins = list.count { it.pnlCents > 0 }
                coin to CoinStats(
                    coin = coin,
                    tradeCount = list.size,
                    wins = wins,
                    losses = list.size - wins,
                    netCents = list.sumOf { it.pnlCents.toLong() }
                )
            }
            .sortedByDescending { it.second.netCents }
            .toMap(LinkedHashMap())

    fun coinOf(ticker: String): String = when (CryptoMarkets.kindFor(ticker)) {
        SeriesKind.BTC -> "BTC"
        SeriesKind.ETH -> "ETH"
        SeriesKind.SOL -> "SOL"
        SeriesKind.CRYPTO -> ticker.uppercase().substringBefore("-").take(6).ifBlank { "?" }
    }

    private fun hourBreakdown(trades: List<ClosedTrade>): Map<Int, HourStats> {
        val utc = TimeZone.getTimeZone("UTC")
        return trades.groupBy { trade ->
            Calendar.getInstance(utc).apply { timeInMillis = trade.entryTimeMs }
                .get(Calendar.HOUR_OF_DAY)
        }
            .toSortedMap()
            .mapValues { (hour, list) ->
                val wins = list.count { it.pnlCents > 0 }
                HourStats(
                    hourUtc = hour,
                    tradeCount = list.size,
                    wins = wins,
                    losses = list.size - wins,
                    netCents = list.sumOf { it.pnlCents.toLong() }
                )
            }
    }
}
