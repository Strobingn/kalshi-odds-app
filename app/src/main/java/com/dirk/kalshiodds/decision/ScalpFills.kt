package com.dirk.kalshiodds.decision

import java.util.Locale

/** Exact sell batches persist in the existing trade note, including across sync/restart. */
object ScalpFills {
    data class Sale(val atMs: Long, val contracts: Int, val price: Double, val fee: Double, val settlement: Boolean)
    private val pattern = Regex("scalpFill=([0-9]+),([0-9]+),([0-9.]+),([0-9.]+),([SX])")
    fun sell(t: ScalpTrade, atMs: Long, n: Int, proceeds: Double, fee: Double, settlement: Boolean = false): ScalpTrade {
        require(n in 1..t.remaining && proceeds.isFinite() && proceeds >= 0 && fee.isFinite() && fee >= 0)
        val token = String.format(Locale.US, "scalpFill=%d,%d,%.8f,%.8f,%s", atMs, n, proceeds / n, fee, if (settlement) "X" else "S")
        return t.copy(soldContracts = t.soldContracts + n, proceedsUsd = t.proceedsUsd + proceeds,
            exitFeeUsd = t.exitFeeUsd + fee, note = "${t.note} · $token")
    }
    fun sales(t: ScalpTrade): List<Sale> = pattern.findAll(t.note).mapNotNull { m ->
        val at = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val px = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
        val fee = m.groupValues[4].toDoubleOrNull() ?: return@mapNotNull null
        if (n <= 0 || !px.isFinite() || px !in 0.0..1.0 || !fee.isFinite()) null else Sale(at, n, px, fee, m.groupValues[5] == "X")
    }.toList()
}
