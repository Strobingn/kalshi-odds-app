package com.dirk.kalshiodds.data.local.results

import java.util.Locale

/**
 * Human-readable CSV / text for Settings → Export results.
 * Pure Kotlin so unit tests do not need Android.
 */
object ResultsExporter {
    const val SNAPSHOT_HEADER =
        "kind,ticker,series,side,edge_pp,fair_pp,market_pp,regime,uncertainty,confidence,tte,heavy_ml,created_at_ms,note"
    const val ALERT_HEADER =
        "kind,alert_id,ticker,series,side,edge_pp,fair_pp,market_pp,regime,created_at_ms,reason"
    const val SCORECARD_HEADER =
        "kind,ticker,series,outcome,score,brier,edge_pp,policy_roi,created_at_ms,note"
    const val TICKET_HEADER =
        "kind,ticker,side,stake_usd,approved,result,created_at_ms,client_order_id,note"

    fun csv(bundle: ResultsBundle): String = buildString {
        appendLine("# DipHunter results export")
        appendLine("# Approve-gated tickets only — never unsupervised bets.")
        appendLine()
        appendLine(SNAPSHOT_HEADER)
        for (r in bundle.snapshots) {
            appendLine(
                listOf(
                    "snapshot",
                    csv(r.ticker),
                    csv(r.series),
                    csv(r.side),
                    num(r.edgePp),
                    num(r.fairPp),
                    num(r.marketPp),
                    csv(r.regime),
                    num(r.uncertainty),
                    num(r.confidence),
                    csv(r.tte),
                    if (r.heavyMl) "1" else "0",
                    r.createdAtMs.toString(),
                    csv(r.note)
                ).joinToString(",")
            )
        }
        appendLine()
        appendLine(ALERT_HEADER)
        for (r in bundle.alerts) {
            appendLine(
                listOf(
                    "alert",
                    csv(r.alertId),
                    csv(r.ticker),
                    csv(r.series),
                    csv(r.side),
                    num(r.edgePp),
                    num(r.fairPp),
                    num(r.marketPp),
                    csv(r.regime),
                    r.createdAtMs.toString(),
                    csv(r.reason)
                ).joinToString(",")
            )
        }
        appendLine()
        appendLine(SCORECARD_HEADER)
        for (r in bundle.scorecards) {
            appendLine(
                listOf(
                    "scorecard",
                    csv(r.ticker),
                    csv(r.series),
                    csv(r.outcome),
                    r.score?.toString().orEmpty(),
                    num(r.brier),
                    num(r.edgePp),
                    num(r.policyRoi),
                    r.createdAtMs.toString(),
                    csv(r.note)
                ).joinToString(",")
            )
        }
        appendLine()
        appendLine(TICKET_HEADER)
        for (r in bundle.tickets) {
            appendLine(
                listOf(
                    "ticket",
                    csv(r.ticker),
                    csv(r.side),
                    num(r.stakeUsd),
                    if (r.approved) "1" else "0",
                    csv(r.result),
                    r.createdAtMs.toString(),
                    csv(r.clientOrderId),
                    csv(r.note)
                ).joinToString(",")
            )
        }
    }

    fun logLine(snapshot: ScoredSnapshotRow): String = String.format(
        Locale.US,
        "%d SNAP %s %s edge=%+.2f fair=%.1f mkt=%.1f unc=%s %s",
        snapshot.createdAtMs,
        snapshot.ticker,
        snapshot.side,
        snapshot.edgePp,
        snapshot.fairPp,
        snapshot.marketPp,
        snapshot.uncertainty?.let { String.format(Locale.US, "%.3f", it) } ?: "-",
        snapshot.regime ?: ""
    )

    fun logLine(alert: AlertRow): String = String.format(
        Locale.US,
        "%d ALERT %s %s edge=%+.2f %s",
        alert.createdAtMs,
        alert.ticker,
        alert.side,
        alert.edgePp,
        alert.reason.take(120)
    )

    fun logLine(ticket: TicketAttemptRow): String = String.format(
        Locale.US,
        "%d TICKET %s %s stake=$%.2f approved=%s %s",
        ticket.createdAtMs,
        ticket.ticker,
        ticket.side,
        ticket.stakeUsd,
        ticket.approved,
        ticket.result
    )

    fun csv(raw: String?): String {
        val s = raw.orEmpty()
        if (s.isEmpty()) return ""
        val escaped = s.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    fun num(v: Double?): String =
        if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.4f", v)
}
