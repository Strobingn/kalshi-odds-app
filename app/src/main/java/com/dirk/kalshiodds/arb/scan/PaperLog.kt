package com.dirk.kalshiodds.arb.scan

import kotlinx.serialization.Serializable

/**
 * One opportunity's life: when we first/last saw it and how big it got.
 * Paper only; nothing is ever traded.
 */
@Serializable
data class PaperEntry(
    val key: String,
    val eventTicker: String,
    val eventTitle: String,
    val type: String,
    val legs: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val peakSets: Long,
    val peakProfitCents: Long,
    val sightings: Int,
    /** Seen in the most recent scan. */
    val open: Boolean
) {
    val durationMs: Long get() = (lastSeenMs - firstSeenMs).coerceAtLeast(0L)
}

object PaperLog {

    const val MAX_ENTRIES = 500

    data class Merge(val entries: List<PaperEntry>, val newlyOpened: List<Opportunity>)

    /**
     * Folds one scan's [opportunities] into [entries]: extends open ones,
     * opens new ones (or re-opens a closed key as a fresh sighting run), and
     * closes whatever did not show up. Newest first, capped at [MAX_ENTRIES].
     */
    fun merge(entries: List<PaperEntry>, opportunities: List<Opportunity>, nowMs: Long): Merge {
        val seen = opportunities.associateBy { it.key }
        val newly = ArrayList<Opportunity>()
        val out = ArrayList<PaperEntry>(entries.size + seen.size)
        val stillOpen = HashSet<String>()
        for (e in entries) {
            val o = if (e.open) seen[e.key] else null
            if (o != null) {
                stillOpen += e.key
                out += e.copy(
                    lastSeenMs = nowMs,
                    peakSets = maxOf(e.peakSets, o.sets),
                    peakProfitCents = maxOf(e.peakProfitCents, o.profitCents),
                    sightings = e.sightings + 1
                )
            } else {
                out += if (e.open) e.copy(open = false) else e
            }
        }
        val fresh = ArrayList<PaperEntry>()
        for (o in opportunities) {
            if (o.key in stillOpen) continue
            newly += o
            fresh += PaperEntry(
                key = o.key,
                eventTicker = o.structure.eventTicker,
                eventTitle = o.structure.eventTitle,
                type = o.structure.type.label,
                legs = o.structure.legs.joinToString(" + ") { "${it.side} ${it.label}" },
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                peakSets = o.sets,
                peakProfitCents = o.profitCents,
                sightings = 1,
                open = true
            )
        }
        val merged = (fresh + out).sortedByDescending { it.lastSeenMs }.take(MAX_ENTRIES)
        return Merge(merged, newly)
    }
}
