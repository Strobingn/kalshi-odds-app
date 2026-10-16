package com.dirk.kalshiodds.decision

import java.util.Locale

/** Exact sell batches persist in the existing trade note, including across sync/restart. */
object ScalpFills {
    data class Order(val atMs: Long, val price: Double)
    private val orderPattern = Regex("scalpOrder=([0-9]+),([0-9.]+)")
    fun orders(t: ScalpTrade): List<Order> = orderPattern.findAll(t.note).mapNotNull { m ->
        val at = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val px = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        if (px.isFinite() && px in 0.0..1.0) Order(at, px) else null
    }.toList()
    fun orderNote(t: ScalpTrade, prev: ScalpTrade?): String {
        if (t.state != ScalpState.PENDING_ENTRY) return t.note
        val limit = t.restingLimit ?: t.signalAsk.takeIf { t.strategyOrNull == ScalpStrategy.CF_REPRICE } ?: return t.note
        if (prev != null && (prev.restingLimit ?: prev.signalAsk.takeIf { prev.strategyOrNull == ScalpStrategy.CF_REPRICE }) == limit) return t.note
        return t.note + String.format(Locale.US, " · scalpOrder=%d,%.8f", t.restingSinceMs ?: t.signalAtMs, limit)
    }
    data class Sale(val atMs: Long, val contracts: Int, val price: Double, val fee: Double, val settlement: Boolean)
    private val pattern = Regex("scalpFill=([0-9]+),([0-9]+),([0-9.]+),([0-9.]+),([SX])")
    fun sell(t: ScalpTrade, atMs: Long, n: Int, proceeds: Double, fee: Double, settlement: Boolean = false): ScalpTrade {
        require(n in 1..t.remaining && proceeds.isFinite() && proceeds >= 0 && fee.isFinite() && fee >= 0)
        val token = String.format(Locale.US, "scalpFill=%d,%d,%.8f,%.8f,%s", atMs, n, proceeds / n, fee, if (settlement) "X" else "S")
        return t.copy(soldContracts = t.soldContracts + n, proceedsUsd = t.proceedsUsd + proceeds,
            exitFeeUsd = t.exitFeeUsd + fee, note = "${t.note} · $token")
    }
    fun executionNotes(note: String): String = (orderPattern.findAll(note).toList() + pattern.findAll(note).toList())
        .sortedBy { it.range.first }.joinToString(" · ") { it.value }
    fun restoreNote(note: String, execution: String): String {
        if (execution.isBlank()) return note.ifBlank { "restored from diphunter_sync" }
        val context = Regex("scalp(?:Fill|Order)=[^ ·]*").replace(note, "")
        return context + " · " + executionNotes(execution)
    }
    fun sales(t: ScalpTrade): List<Sale> = pattern.findAll(t.note).mapNotNull { m ->
        val at = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val px = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
        val fee = m.groupValues[4].toDoubleOrNull() ?: return@mapNotNull null
        if (n <= 0 || !px.isFinite() || px !in 0.0..1.0 || !fee.isFinite()) null else Sale(at, n, px, fee, m.groupValues[5] == "X")
    }.toList()
}
