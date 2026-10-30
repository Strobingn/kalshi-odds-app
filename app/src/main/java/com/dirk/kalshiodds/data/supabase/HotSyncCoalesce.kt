package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.decision.ScalpGateLog

/**
 * Remote key rule for the two hot `diphunter_sync` kinds.
 *
 * `scalp_gate` used to insert one row per reason string (`reason.hashCode()`).
 * Gap and score text changes every tick, so a window produced hundreds of keys.
 * `model_decision` used `model:{decisionId}`, and the id includes the timestamp,
 * so each changed print was a new row (the phone queues up to 400 per sync).
 *
 * Uploads now upsert the latest payload:
 * - model: one row per twin, side, and 15-minute bucket
 * - gate: one row per window key and scalper code
 *
 * Local Room / SQLite and the in-memory gate log are not touched. Paper, scalp,
 * snapshot, alert, and the other kinds are not passed through here.
 */
object HotSyncCoalesce {
    const val WINDOW_MS = 900_000L
    /** Dominant block reasons kept inside the one gate payload. */
    const val GATE_REASONS_KEPT = 8
    const val REASON_CAP = 180

    data class ModelRow(val remoteKey: String, val row: ModelFilter.SyncRow)

    data class GateRow(
        val remoteKey: String,
        val windowKey: String,
        val code: String,
        val reason: String,
        val count: Int,
        /** Distinct reason strings folded into this one remote row. */
        val reasons: Int,
        val top: List<Pair<String, Int>>,
        val regime: String = "",
        val evidence: String = ""
    )

    fun windowBucket(atMs: Long): Long = atMs.floorDiv(WINDOW_MS)

    /** `mf:{twin}:{atMs}:{side}` → side. Anything else has no side. */
    fun sideOf(id: String): String {
        val parts = id.split(':')
        if (parts.size >= 4 && parts[0] == "mf" && parts[parts.size - 2].toLongOrNull() != null) {
            return parts.last().uppercase()
        }
        return ""
    }

    fun modelRemoteKey(row: ModelFilter.SyncRow): String =
        "model:${row.twinId}:${sideOf(row.id)}:${windowBucket(row.atMs)}"

    fun gateRemoteKey(windowKey: String, code: String): String = "scalp_gate:$windowKey:$code"

    /**
     * Newest [ModelFilter.SyncRow.atMs] wins. An equal timestamp keeps the last row.
     * A YES and a NO in the same bucket stay separate, and the next bucket does too.
     */
    fun coalesceModels(rows: List<ModelFilter.SyncRow>): List<ModelRow> {
        val byKey = LinkedHashMap<String, ModelFilter.SyncRow>()
        for (row in rows) {
            val key = modelRemoteKey(row)
            val prev = byKey[key]
            if (prev == null || row.atMs >= prev.atMs) byKey[key] = row
        }
        return byKey.map { (key, row) -> ModelRow(key, row) }
    }

    /**
     * One gate per window and scalper. The on-screen reason wins. Otherwise the
     * highest count wins, and a tie keeps the later row. [GateRow.top] keeps
     * that reason plus the most common other phrasings.
     */
    fun coalesceGates(rows: List<ScalpGateLog.SyncRow>): List<GateRow> {
        if (rows.isEmpty()) return emptyList()
        val groups = LinkedHashMap<String, ArrayList<ScalpGateLog.SyncRow>>()
        for (row in rows) {
            if (row.windowKey.isBlank() || row.code.isBlank()) continue
            groups.getOrPut(gateRemoteKey(row.windowKey, row.code)) { ArrayList() }.add(row)
        }
        return groups.map { (key, group) ->
            val chosen = chooseGate(group)
            val reason = chosen.reason.take(REASON_CAP)
            GateRow(
                remoteKey = key,
                windowKey = chosen.windowKey,
                code = chosen.code,
                reason = reason,
                count = chosen.count,
                reasons = group.map { it.reason.take(REASON_CAP) }.distinct().size,
                top = topReasons(group, chosen),
                regime = chosen.regime,
                evidence = chosen.evidence.take(REASON_CAP)
            )
        }
    }

    private fun chooseGate(group: List<ScalpGateLog.SyncRow>): ScalpGateLog.SyncRow {
        var best = group.first()
        for (i in 1 until group.size) {
            if (preferGate(group[i], best)) best = group[i]
        }
        return best
    }

    private fun preferGate(row: ScalpGateLog.SyncRow, best: ScalpGateLog.SyncRow): Boolean = when {
        row.latest != best.latest -> row.latest
        row.count != best.count -> row.count > best.count
        else -> true
    }

    private fun topReasons(group: List<ScalpGateLog.SyncRow>, chosen: ScalpGateLog.SyncRow): List<Pair<String, Int>> {
        val ranked = group.sortedWith(
            compareByDescending<ScalpGateLog.SyncRow> { it.count }.thenBy { it.reason }
        )
        val out = ArrayList<Pair<String, Int>>(GATE_REASONS_KEPT)
        val seen = HashSet<String>()
        fun add(reason: String, count: Int) {
            if (out.size >= GATE_REASONS_KEPT) return
            val text = reason.take(REASON_CAP)
            if (text.isBlank() || !seen.add(text)) return
            out += text to count
        }
        add(chosen.reason, chosen.count)
        for (row in ranked) add(row.reason, row.count)
        return out
    }
}
