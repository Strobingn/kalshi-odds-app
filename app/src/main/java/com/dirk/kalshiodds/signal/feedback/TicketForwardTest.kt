package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.results.TicketForwardRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlin.math.abs

/** Freezes the first executable-looking automatic suggestion. This never submits an order. */
object TicketForwardTest {
    fun capture(
        ticket: TradeTicket,
        book: BookLevelSnapshot?,
        modelSource: String,
        feeRate: Double,
        atMs: Long,
        buildCode: Int
    ): TicketForwardRow? {
        if (!ticket.canApprove || ticket.kind == TicketKind.MANUAL || ticket.kind == TicketKind.SELL ||
            ticket.ticker.isBlank() || ticket.side !in setOf("YES", "NO") ||
            atMs <= 0L || buildCode <= 0 || modelSource.isBlank() ||
            !feeRate.isFinite() || feeRate < 0.0 || ticket.contracts < 1) return null
        val modelSide = ticket.modelChance?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val yesBid = book?.yes?.filter { it.first.isFinite() && it.second.isFinite() && it.second > 0.0 }
            ?.maxByOrNull { it.first }
        val noBid = book?.no?.filter { it.first.isFinite() && it.second.isFinite() && it.second > 0.0 }
            ?.maxByOrNull { it.first }
        if (yesBid == null || noBid == null || yesBid.first + noBid.first > 1.0 + 1e-9) return null
        val marketYes = (yesBid.first + 1.0 - noBid.first) / 2.0
        if (!marketYes.isFinite() || marketYes !in 0.0..1.0) return null
        val opposite = if (ticket.side == "YES") noBid else yesBid
        val ask = KalshiPrice.impliedAskFromOppositeBid(opposite.first) ?: return null
        if (abs(ticket.limitPrice - ask) > 1e-6 || opposite.second + 1e-9 < ticket.contracts) return null
        val allIn = ticket.allInUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val fee = ticket.feeUsd?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        if (abs(allIn - LiveOrderSizer.allInUsd(ticket.contracts, ask, feeRate)) > 1e-6 ||
            abs(fee - LiveOrderSizer.feeUsd(ticket.contracts, ask, feeRate)) > 1e-6 ||
            allIn > LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-9) return null
        val expected = modelSide * ticket.contracts - allIn
        if (ticket.netEvUsd?.let { !it.isFinite() || abs(it - expected) > 1e-6 } != false) return null
        return TicketForwardRow(
            ticker = ticket.ticker,
            series = CryptoMarkets.inferSeries(ticket.ticker),
            capturedAtMs = atMs,
            buildCode = buildCode,
            kind = ticket.kind.name,
            modelSource = modelSource,
            side = ticket.side,
            modelYes = if (ticket.side == "YES") modelSide else 1.0 - modelSide,
            marketYes = marketYes,
            ask = ask,
            visibleContracts = opposite.second,
            contracts = ticket.contracts,
            allInUsd = allIn,
            feeUsd = fee,
            feeRate = feeRate,
            modeledNetUsd = expected
        )
    }

    data class Summary(
        val captured: Int = 0,
        val settled: Int = 0,
        val wins: Int = 0,
        val modelBrier: Double? = null,
        val marketBrier: Double? = null,
        val quotedProxyPnlUsd: Double? = null,
        val worstDrawdownUsd: Double? = null
    )

    fun summarize(rows: List<TicketForwardRow>): Summary {
        val settled = rows.mapNotNull { row ->
            val yes = when (row.outcome?.lowercase()) {
                "yes" -> 1.0
                "no" -> 0.0
                else -> return@mapNotNull null
            }
            row to yes
        }.sortedBy { it.first.capturedAtMs }
        var equity = 0.0
        var peak = 0.0
        var worst = 0.0
        for ((row, yes) in settled) {
            equity += proxyPnl(row, yes)
            peak = maxOf(peak, equity)
            worst = maxOf(worst, peak - equity)
        }
        return Summary(
            captured = rows.size,
            settled = settled.size,
            wins = settled.count { (row, yes) -> won(row, yes) },
            modelBrier = settled.takeIf { it.isNotEmpty() }?.map { (r, y) -> (r.modelYes - y) * (r.modelYes - y) }?.average(),
            marketBrier = settled.takeIf { it.isNotEmpty() }?.map { (r, y) -> (r.marketYes - y) * (r.marketYes - y) }?.average(),
            quotedProxyPnlUsd = settled.takeIf { it.isNotEmpty() }?.sumOf { (r, y) -> proxyPnl(r, y) },
            worstDrawdownUsd = settled.takeIf { it.isNotEmpty() }?.let { worst }
        )
    }

    private fun won(row: TicketForwardRow, yes: Double): Boolean =
        (row.side == "YES" && yes == 1.0) || (row.side == "NO" && yes == 0.0)

    private fun proxyPnl(row: TicketForwardRow, yes: Double): Double =
        (if (won(row, yes)) row.contracts.toDouble() else 0.0) - row.allInUsd

    fun csv(rows: List<TicketForwardRow>): String {
        val header = "ticker,series,captured_at_ms,build_code,kind,model_source,side,model_yes," +
            "market_yes,ask,visible_contracts,contracts,all_in_usd,fee_usd,fee_rate,modeled_net_usd," +
            "outcome,quoted_proxy_pnl_usd"
        fun number(v: Double): String = String.format(Locale.US, "%.6f", v)
        fun safe(raw: String?): String {
            val value = raw.orEmpty()
            val guarded = if (value.firstOrNull()?.let { it in "=+-@\t" } == true) "'$value" else value
            return "\"${guarded.replace("\"", "\"\"")}\""
        }
        return (listOf(header) + rows.sortedBy { it.capturedAtMs }.map { r ->
            val y = when (r.outcome?.lowercase()) { "yes" -> 1.0; "no" -> 0.0; else -> null }
            listOf(safe(r.ticker), safe(r.series), r.capturedAtMs.toString(), r.buildCode.toString(),
                safe(r.kind), safe(r.modelSource), safe(r.side), number(r.modelYes), number(r.marketYes),
                number(r.ask), number(r.visibleContracts), r.contracts.toString(), number(r.allInUsd),
                number(r.feeUsd), number(r.feeRate), number(r.modeledNetUsd), safe(r.outcome),
                y?.let { number(proxyPnl(r, it)) }.orEmpty()
            ).joinToString(",")
        }).joinToString("\n", postfix = "\n")
    }
}
