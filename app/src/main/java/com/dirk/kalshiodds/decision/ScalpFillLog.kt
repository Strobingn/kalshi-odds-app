package com.dirk.kalshiodds.decision

/** Append exact per-quote execution deltas before writes are coalesced. Stored in the existing trade note. */
object ScalpFillLog {
    enum class Kind { BUY, SELL, SETTLE }
    data class Fill(val atMs: Long, val qty: Int, val price: Double, val fee: Double, val kind: Kind, val aggregate: Boolean = false)
    private val token = Regex("(?:^|[ ;·])fills1=([^ ;·]+)")
    fun recorded(t: ScalpTrade): List<Fill> = token.find(t.note)?.groupValues?.get(1)?.split(',').orEmpty().mapNotNull { raw ->
        runCatching {
            val p = raw.split(':')
            Fill(p[0].toLong(), p[1].toInt(), p[2].toDouble(), p[3].toDouble(), Kind.valueOf(p[4])).also {
                require(it.atMs > 0 && it.qty > 0 && it.price.isFinite() && it.price in 0.0..1.0 && it.fee.isFinite() && it.fee >= 0)
            }
        }.getOrNull()
    }
    fun capture(t: ScalpTrade, prev: ScalpTrade?, nowMs: Long): ScalpTrade {
        if (prev == null) return t // imported history must never be relabelled as newly observed fills
        val fills = recorded(prev).toMutableList()
        if (prev.entryAtMs == null && t.entryAtMs != null && t.contracts > 0 && t.entryPrice != null)
            fills += Fill(t.entryAtMs, t.contracts, t.entryPrice, t.entryFeeUsd, Kind.BUY)
        val n = t.soldContracts - prev.soldContracts
        if (n > 0) fills += Fill(t.closedAtMs ?: nowMs, n, (t.proceedsUsd - prev.proceedsUsd) / n,
            (t.exitFeeUsd - prev.exitFeeUsd).coerceAtLeast(0.0), if (t.exitReason.orEmpty().contains("settl", true)) Kind.SETTLE else Kind.SELL)
        if (fills.isEmpty()) return t
        val encoded = fills.joinToString(",") { "${it.atMs}:${it.qty}:${it.price}:${it.fee}:${it.kind}" }
        val base = token.replace(t.note, "").trim().trimEnd(' ', '·', ';')
        return t.copy(note = (if (base.isEmpty()) "" else "$base · ") + "fills1=$encoded")
    }
    fun display(t: ScalpTrade): List<Fill> {
        val exact = recorded(t)
        val entry = if (exact.none { it.kind == Kind.BUY }) t.entryAtMs?.let { at ->
            t.entryPrice?.let { Fill(at, t.contracts, it, t.entryFeeUsd, Kind.BUY) } }?.takeIf { it.qty > 0 } else null
        val sold = exact.filter { it.kind != Kind.BUY }.sumOf { it.qty }
        // Legacy rows expose only an average exit and final close time. Say so instead of inventing a partial-fill path.
        val legacy = if (sold == 0 && t.soldContracts > 0 && t.closedAtMs != null)
            Fill(t.closedAtMs, t.soldContracts, t.proceedsUsd / t.soldContracts, t.exitFeeUsd,
                if (t.exitReason.orEmpty().contains("settl", true)) Kind.SETTLE else Kind.SELL, aggregate = true) else null
        return (listOfNotNull(entry) + exact + listOfNotNull(legacy)).sortedBy { it.atMs }
    }
}
