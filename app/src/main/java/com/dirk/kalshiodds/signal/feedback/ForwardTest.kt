package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.data.local.results.ForwardTestRow
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import java.util.Locale

/** Frozen, advisory-only observation. A visible order is never a confirmed fill. */
object ForwardTest {
    fun capture(
        ticker: String,
        series: String,
        atMs: Long,
        modelYes: Double,
        marketYes: Double,
        side: String,
        book: BookLevelSnapshot?,
        feeRate: Double
    ): ForwardTestRow? {
        if (ticker.isBlank() || series.isBlank() || atMs <= 0 ||
            !modelYes.isFinite() || modelYes !in 0.0..1.0 ||
            !marketYes.isFinite() || marketYes !in 0.0..1.0 ||
            !feeRate.isFinite() || feeRate < 0.0 || side !in setOf("YES", "NO")) return null
        val bestYes = book?.yes?.filter { it.first.isFinite() && it.second.isFinite() && it.second > 0.0 }
            ?.maxByOrNull { it.first }
        val bestNo = book?.no?.filter { it.first.isFinite() && it.second.isFinite() && it.second > 0.0 }
            ?.maxByOrNull { it.first }
        val crossed = bestYes != null && bestNo != null && bestYes.first + bestNo.first > 1.0 + 1e-9
        val opposite = if (side == "YES") bestNo else bestYes
        val ask = if (crossed) null else opposite?.first?.let { KalshiPrice.usable(1.0 - it) }
        val clip = ask?.let { LiveOrderSizer.size(it, capUsd = 5.0, feeRate = feeRate) }?.takeIf { it.ok }
        val available = opposite?.second?.takeIf { it.isFinite() && it > 0.0 }
        return ForwardTestRow(
            ticker = ticker,
            series = series,
            capturedAtMs = atMs,
            modelYes = modelYes,
            marketYes = marketYes,
            side = side,
            bookAsk = ask,
            sizeAtAsk = available,
            contracts = clip?.count,
            allInUsd = clip?.allInUsd,
            feeUsd = clip?.feeUsd,
            quoteQualified = clip != null && available != null && available + 1e-9 >= clip.count
        )
    }

    data class Summary(
        val captured: Int = 0,
        val settled: Int = 0,
        val quoted: Int = 0,
        val modelBrier: Double? = null,
        val marketBrier: Double? = null,
        /** Assumes the visible quote was fillable; no order was placed. */
        val quotedProxyPnlUsd: Double? = null
    )

    fun summarize(rows: List<ForwardTestRow>): Summary {
        val settled = rows.mapNotNull { snap ->
            val yes = when (snap.outcome?.lowercase()) {
                "yes" -> 1.0
                "no" -> 0.0
                else -> return@mapNotNull null
            }
            snap to yes
        }
        val quoted = settled.filter { (snap, _) ->
            snap.quoteQualified && snap.contracts != null && snap.allInUsd != null
        }
        return Summary(
            captured = rows.size,
            settled = settled.size,
            quoted = quoted.size,
            modelBrier = settled.takeIf { it.isNotEmpty() }?.map { (snap, yes) ->
                (snap.modelYes - yes) * (snap.modelYes - yes)
            }?.average(),
            marketBrier = settled.takeIf { it.isNotEmpty() }?.map { (snap, yes) ->
                (snap.marketYes - yes) * (snap.marketYes - yes)
            }?.average(),
            quotedProxyPnlUsd = quoted.takeIf { it.isNotEmpty() }?.sumOf { (snap, yes) ->
                val wins = (snap.side == "YES" && yes == 1.0) || (snap.side == "NO" && yes == 0.0)
                (if (wins) snap.contracts!!.toDouble() else 0.0) - snap.allInUsd!!
            }
        )
    }

    fun csv(rows: List<ForwardTestRow>): String {
        val header = "ticker,series,captured_at_ms,model_yes,market_yes,side,book_ask," +
            "size_at_ask,contracts,all_in_usd,fee_usd,quote_qualified,outcome,quoted_proxy_pnl_usd"
        fun number(v: Double?): String = v?.let { String.format(Locale.US, "%.6f", it) } ?: ""
        fun safe(v: String?): String {
            val raw = v.orEmpty()
            val guarded = if (raw.firstOrNull()?.let { it in "=+-@\t" } == true) "'$raw" else raw
            return "\"${guarded.replace("\"", "\"\"")}\""
        }
        return (listOf(header) + rows.sortedBy { it.capturedAtMs }.map { r ->
            val won = (r.outcome.equals("yes", true) && r.side == "YES") ||
                (r.outcome.equals("no", true) && r.side == "NO")
            val resolved = r.outcome.equals("yes", true) || r.outcome.equals("no", true)
            val pnl = if (resolved && r.quoteQualified && r.contracts != null && r.allInUsd != null)
                (if (won) r.contracts.toDouble() else 0.0) - r.allInUsd else null
            listOf(safe(r.ticker), safe(r.series), r.capturedAtMs.toString(), number(r.modelYes),
                number(r.marketYes), safe(r.side), number(r.bookAsk), number(r.sizeAtAsk),
                r.contracts?.toString().orEmpty(), number(r.allInUsd), number(r.feeUsd),
                r.quoteQualified.toString(), safe(r.outcome), number(pnl)).joinToString(",")
        }).joinToString("\n", postfix = "\n")
    }
}
