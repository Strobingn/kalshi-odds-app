package com.dirk.kalshiodds.signal.debug

/**
 * 0.3.43: in-memory ring buffer of every price input (WS ticker / trade / orderbook, REST) and what the
 * app did with it (applied, dropped as stale, sequence gap, REST suppressed by a fresher WS quote).
 * Viewable and exportable (CSV) from Data → Price debug log. Never contains credentials.
 */
object PriceDebugLog {
    const val CAPACITY = 3_000

    data class Entry(
        val wallMs: Long,
        val source: String,
        val ticker: String,
        val field: String,
        val value: Double?,
        val kalshiTsMs: Long?,
        val sid: Int?,
        val seq: Int?,
        val verdict: String
    )

    private val buf = ArrayDeque<Entry>(CAPACITY)

    @Volatile var enabled: Boolean = true

    @Synchronized
    fun record(
        source: String,
        ticker: String,
        field: String,
        value: Double?,
        verdict: String,
        kalshiTsMs: Long? = null,
        sid: Int? = null,
        seq: Int? = null,
        wallMs: Long = System.currentTimeMillis()
    ) {
        if (!enabled) return
        buf.addLast(Entry(wallMs, source, ticker, field, value, kalshiTsMs, sid, seq, verdict))
        while (buf.size > CAPACITY) buf.removeFirst()
    }

    @Synchronized fun snapshot(): List<Entry> = buf.toList()

    @Synchronized fun recent(n: Int): List<Entry> = buf.toList().takeLast(n)

    @Synchronized fun size(): Int = buf.size

    @Synchronized fun clear() = buf.clear()

    fun countByVerdict(): Map<String, Int> = snapshot().groupingBy { it.verdict }.eachCount()

    fun csv(entries: List<Entry> = snapshot()): String = buildString {
        append("wall_ms,source,ticker,field,value,kalshi_ts_ms,sid,seq,verdict\n")
        for (e in entries) {
            append(e.wallMs).append(',')
                .append(e.source).append(',')
                .append(e.ticker).append(',')
                .append(e.field).append(',')
                .append(e.value?.let { "%.4f".format(java.util.Locale.US, it) } ?: "").append(',')
                .append(e.kalshiTsMs ?: "").append(',')
                .append(e.sid ?: "").append(',')
                .append(e.seq ?: "").append(',')
                .append(e.verdict).append('\n')
        }
    }

    fun line(e: Entry): String {
        val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.wallMs))
        val v = e.value?.let { "%.4f".format(java.util.Locale.US, it) } ?: "—"
        val meta = listOfNotNull(e.kalshiTsMs?.let { "ts=$it" }, e.sid?.let { "sid=$it" }, e.seq?.let { "seq=$it" }).joinToString(" ")
        return "$t ${e.source} ${e.ticker} ${e.field}=$v $meta ${e.verdict}".trim()
    }

    const val APPLIED = "applied"
    const val STALE = "dropped-stale"
    const val DUPLICATE = "dropped-duplicate"
    const val GAP = "seq-gap-resync"
    const val NO_SNAPSHOT = "dropped-no-snapshot"
    const val REST_SUPPRESSED = "rest-suppressed-ws-fresh"
    const val OTHER_WINDOW = "series-mid-skipped-not-current-window"
}
