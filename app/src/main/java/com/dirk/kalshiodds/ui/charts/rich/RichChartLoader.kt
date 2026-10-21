package com.dirk.kalshiodds.ui.charts.rich

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.signal.paper.PaperFill

/**
 * Turns stored rows into a capped chart pack. Callers do the read on [kotlinx.coroutines.Dispatchers.IO].
 * The scalper list is whatever the registry passed in — this file does not name strategies.
 */
object RichChartLoader {
    data class ScalpPack(
        val trades: List<RichTradeMath.Trade>,
        val capped: Boolean,
        val compare: List<RichTradeMath.NamedSeries>,
        val counts: Map<String, Int>
    )

    data class PaperPack(
        val trades: List<RichTradeMath.Trade>,
        val capped: Boolean
    )

    /** Query ids for [registryCodes], plus a legacy alias when the registry says one exists. */
    fun scalpQueryIds(registryCodes: List<String>, focusId: String?): List<String> {
        val source = if (focusId != null) listOf(focusId) else registryCodes
        return source.flatMap { KeptScalpers.storedIds(it) }.distinct()
    }

    fun perQueryLimit(idCount: Int, focus: Boolean, max: Int = RichTradeMath.MAX_ROWS): Int {
        if (focus) return max.coerceAtLeast(1)
        return (max / idCount.coerceAtLeast(1)).coerceAtLeast(1)
    }

    fun scalp(
        trades: List<RichTradeMath.Trade>,
        registry: List<Pair<String, String>>,
        focusId: String?,
        max: Int = RichTradeMath.MAX_ROWS
    ): ScalpPack {
        val ids = registry.map { it.first }.toHashSet()
        val allowed = if (focusId != null) trades.filter { it.scalperId == focusId } else trades.filter { it.scalperId in ids }
        val (kept, capped) = RichTradeMath.cap(allowed, max)
        val compare = if (focusId == null) RichTradeMath.compare(kept, registry) else emptyList()
        val counts = kept.filter { !it.open }.groupingBy { it.scalperId ?: "" }.eachCount()
        return ScalpPack(kept, capped, compare, counts)
    }

    fun paper(fills: List<PaperFill>, max: Int = RichTradeMath.MAX_ROWS): PaperPack {
        val (kept, capped) = RichTradeMath.cap(fills.mapNotNull { RichTradeMath.fromPaper(it) }, max)
        return PaperPack(kept, capped)
    }
}

/** Small process cache so a tab switch does not repeat the same capped read. */
object RichChartCache {
    private const val MAX = 4
    private val lock = Any()
    private val map = object : LinkedHashMap<String, Any>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > MAX
    }

    fun get(key: String): Any? = synchronized(lock) { map[key] }
    fun put(key: String, value: Any) = synchronized(lock) { map[key] = value }
}
