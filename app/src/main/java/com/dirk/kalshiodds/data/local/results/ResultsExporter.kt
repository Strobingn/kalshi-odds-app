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

    fun json(bundle: ResultsBundle): String {
        val root = org.json.JSONObject()
        root.put("format", "diphunter-results-v1")
        val snaps = org.json.JSONArray()
        for (r in bundle.snapshots) {
            snaps.put(
                org.json.JSONObject()
                    .put("kind", "snapshot")
                    .put("ticker", r.ticker)
                    .put("series", r.series)
                    .put("side", r.side)
                    .put("edge_pp", r.edgePp)
                    .put("fair_pp", r.fairPp)
                    .put("market_pp", r.marketPp)
                    .put("created_at_ms", r.createdAtMs)
            )
        }
        root.put("snapshots", snaps)
        val alerts = org.json.JSONArray()
        for (r in bundle.alerts) {
            alerts.put(
                org.json.JSONObject()
                    .put("kind", "alert")
                    .put("alert_id", r.alertId)
                    .put("ticker", r.ticker)
                    .put("series", r.series)
                    .put("side", r.side)
                    .put("edge_pp", r.edgePp)
                    .put("created_at_ms", r.createdAtMs)
                    .put("reason", r.reason)
            )
        }
        root.put("alerts", alerts)
        val cards = org.json.JSONArray()
        for (r in bundle.scorecards) {
            cards.put(
                org.json.JSONObject()
                    .put("kind", "scorecard")
                    .put("ticker", r.ticker)
                    .put("series", r.series)
                    .put("outcome", r.outcome)
                    .put("created_at_ms", r.createdAtMs)
            )
        }
        root.put("scorecards", cards)
        val tickets = org.json.JSONArray()
        for (r in bundle.tickets) {
            tickets.put(
                org.json.JSONObject()
                    .put("kind", "ticket")
                    .put("ticker", r.ticker)
                    .put("side", r.side)
                    .put("stake_usd", r.stakeUsd)
                    .put("approved", r.approved)
                    .put("result", r.result)
                    .put("created_at_ms", r.createdAtMs)
                    .put("client_order_id", r.clientOrderId)
            )
        }
        root.put("tickets", tickets)
        return root.toString()
    }

    fun jsonWithHistory(
        bundle: ResultsBundle,
        settings: List<com.dirk.kalshiodds.data.local.history.SettingsChange> = emptyList(),
        sessions: List<com.dirk.kalshiodds.data.local.history.HistorySession> = emptyList()
    ): String {
        val root = org.json.JSONObject(json(bundle))
        val sc = org.json.JSONArray()
        for (r in settings) {
            sc.put(
                org.json.JSONObject()
                    .put("kind", "settings_change")
                    .put("created_at_ms", r.createdAtMs)
                    .put("key", r.key)
                    .put("old_value", r.oldValue)
                    .put("new_value", r.newValue)
                    .put("snapshot_json", r.snapshotJson)
            )
        }
        root.put("settings_changes", sc)
        val sess = org.json.JSONArray()
        for (r in sessions) {
            sess.put(
                org.json.JSONObject()
                    .put("kind", "session")
                    .put("id", r.id)
                    .put("started_at_ms", r.startedAtMs)
                    .put("ended_at_ms", r.endedAtMs)
                    .put("markets", r.markets)
                    .put("signals", r.signals)
                    .put("bets", r.bets)
            )
        }
        root.put("sessions", sess)
        return root.toString()
    }

    fun csv(bundle: ResultsBundle): String = buildString {
        appendLine("# grok-bitcoin results export")
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
