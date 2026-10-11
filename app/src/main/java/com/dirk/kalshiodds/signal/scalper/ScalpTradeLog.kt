package com.dirk.kalshiodds.signal.scalper

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One closed paper scalp, as kept on disk and shown in the trade list. Never a real trade. */
data class ScalpTrade(
    val closedAtMs: Long,
    /** App start that made it (ids restart at 1 on every start). */
    val run: Long,
    val id: Long,
    val strategy: String,
    val ticker: String,
    val side: String,
    val contracts: Int,
    val entry: Double,
    val exit: Double,
    /** TARGET / STOP / TIMEOUT / SETTLED. */
    val kind: String,
    val entryFeeUsd: Double,
    val exitFeeUsd: Double,
    /** Net of both fees, for the whole order. */
    val pnlUsd: Double,
    val postedAtMs: Long,
    val filledAtMs: Long,
    val queueAhead: Double,
    val queueKnown: Boolean,
    val predictionCents: Double,
    val expectedCents: Double
) {
    val feesUsd: Double get() = entryFeeUsd + exitFeeUsd

    fun toCsv(): String = listOf(
        closedAtMs, run, id, strategy, ticker, side, contracts, num(entry), num(exit), kind,
        num(entryFeeUsd), num(exitFeeUsd), num(pnlUsd), postedAtMs, filledAtMs, num(queueAhead),
        if (queueKnown) 1 else 0, num(predictionCents), num(expectedCents)
    ).joinToString(",")

    companion object {
        const val HEADER =
            "closed_ms,run,id,strategy,ticker,side,contracts,entry,exit,kind,entry_fee,exit_fee,pnl," +
                "posted_ms,filled_ms,queue_ahead,queue_known,pred_cents,expected_cents"

        fun of(c: ClosedScalp, run: Long): ScalpTrade = ScalpTrade(
            closedAtMs = c.closedAtMs,
            run = run,
            id = c.order.id,
            strategy = c.order.strategy.name,
            ticker = c.order.ticker,
            side = c.order.side,
            contracts = c.order.contracts,
            entry = c.order.price,
            exit = c.exitPrice,
            kind = c.kind.name,
            entryFeeUsd = c.order.entryFeeUsd,
            exitFeeUsd = c.feeUsd,
            pnlUsd = c.pnlUsd,
            postedAtMs = c.order.postedAtMs,
            filledAtMs = c.filledAtMs,
            queueAhead = c.order.queueAhead,
            queueKnown = c.order.queueKnown,
            predictionCents = c.order.predictionCents,
            expectedCents = c.order.expectedCents
        )

        /** A row of a trade file, or null for the header / a damaged line. */
        fun parse(line: String): ScalpTrade? {
            val p = line.trim().split(',')
            if (p.size < 19) return null
            return runCatching {
                ScalpTrade(
                    closedAtMs = p[0].toLong(), run = p[1].toLong(), id = p[2].toLong(), strategy = p[3],
                    ticker = p[4], side = p[5], contracts = p[6].toInt(), entry = p[7].toDouble(),
                    exit = p[8].toDouble(), kind = p[9], entryFeeUsd = p[10].toDouble(),
                    exitFeeUsd = p[11].toDouble(), pnlUsd = p[12].toDouble(), postedAtMs = p[13].toLong(),
                    filledAtMs = p[14].toLong(), queueAhead = p[15].toDouble(), queueKnown = p[16] == "1",
                    predictionCents = p[17].toDouble(), expectedCents = p[18].toDouble()
                )
            }.getOrNull()
        }

        internal fun num(v: Double?): String {
            if (v == null || !v.isFinite()) return ""
            val s = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }
    }
}

/**
 * Every paper order the scalper sent, on disk, kept until deleted.
 *
 * Two files per UTC day under [dir] (the day the order resolved):
 *  - `scalps_YYYY-MM-DD.csv`: one row per **closed scalp** ([ScalpTrade.HEADER]).
 *    The results screen reads these.
 *  - `scalp_orders_YYYY-MM-DD.csv.gz`: one row per **order**, filled or not
 *    ([ORDER_HEADER]): the same columns plus the book the scalper saw, the
 *    price moves it reacted to and the model's inputs. Written for research;
 *    `kind` is `UNFILLED` for a bid that expired. Multi-member gzip: one
 *    member per flush.
 *
 * [add] only queues rows in memory (it runs on the trade-print path);
 * [flush] hands them to a single writer thread. A reset moves the files into
 * `archive/`, so nothing is thrown away. Oldest archived, then oldest live
 * days are deleted only when the folder passes [maxBytes].
 */
class ScalpTradeLog(
    val dir: File,
    /** Stamp of this app start, written on every row. */
    val run: Long = System.currentTimeMillis(),
    private val io: Executor = defaultExecutor(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    private val lock = Any()
    private val fileLock = Any()
    private var pendingTrades = ArrayList<ScalpTrade>()
    private var pendingOrders = ArrayList<Pair<Long, String>>()
    private var cacheKey: String? = null
    private var cacheRows: List<ScalpTrade> = emptyList()

    /** Queue rows for the next [flush]. No I/O. */
    fun add(trades: List<ScalpTrade>, orders: List<Pair<Long, String>>) {
        if (trades.isEmpty() && orders.isEmpty()) return
        synchronized(lock) {
            pendingTrades.addAll(trades)
            pendingOrders.addAll(orders)
        }
    }

    /** Write what is queued, on the writer thread. */
    fun flush() {
        runCatching { io.execute { flushNow() } }
    }

    /** Write what is queued, on this thread (export, reset, tests). */
    fun flushNow() {
        val trades: List<ScalpTrade>
        val orders: List<Pair<Long, String>>
        synchronized(lock) {
            if (pendingTrades.isEmpty() && pendingOrders.isEmpty()) return
            trades = pendingTrades
            orders = pendingOrders
            pendingTrades = ArrayList()
            pendingOrders = ArrayList()
        }
        synchronized(fileLock) {
            runCatching {
                dir.mkdirs()
                for ((day, rows) in trades.groupBy { utcDay(it.closedAtMs) }) {
                    val f = File(dir, tradeFile(day))
                    val sb = StringBuilder(rows.size * 140)
                    if (!f.exists() || f.length() == 0L) sb.append(ScalpTrade.HEADER).append('\n')
                    for (r in rows) sb.append(r.toCsv()).append('\n')
                    f.appendText(sb.toString(), Charsets.UTF_8)
                }
                for ((day, rows) in orders.groupBy { utcDay(it.first) }) {
                    val f = File(dir, orderFile(day))
                    val sb = StringBuilder(rows.size * 260)
                    if (!f.exists() || f.length() == 0L) sb.append(ORDER_HEADER).append('\n')
                    for (r in rows) sb.append(r.second).append('\n')
                    GZIPOutputStream(BufferedOutputStream(FileOutputStream(f, true))).use {
                        it.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                enforceCapLocked()
            }
        }
    }

    /** UTC days that have a trade file, oldest first. */
    fun days(): List<String> = synchronized(fileLock) { tradeDaysLocked() }

    /** Bytes on disk, archive included. */
    fun totalBytes(): Long = synchronized(fileLock) { allFilesLocked().sumOf { it.length() } }

    /**
     * Closed scalps, newest first: rows still queued, then the day files from
     * the newest back, skipping [offset] and returning at most [limit].
     */
    fun recent(limit: Int, offset: Int = 0): List<ScalpTrade> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<ScalpTrade>(limit)
        var skip = offset
        fun take(rows: List<ScalpTrade>): Boolean {
            for (r in rows) {
                if (skip > 0) { skip--; continue }
                out += r
                if (out.size >= limit) return true
            }
            return false
        }
        val queued = synchronized(lock) { pendingTrades.toList() }.asReversed()
        if (take(queued)) return out
        synchronized(fileLock) {
            for (day in tradeDaysLocked().asReversed()) {
                if (take(readDayLocked(day))) break
            }
        }
        return out
    }

    /** Zip every file (archive included) into [out]. Returns the number of files written. */
    fun zip(out: OutputStream): Int {
        flushNow()
        var n = 0
        synchronized(fileLock) {
            val files = allFilesLocked().sortedBy { it.path }
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                for (f in files) {
                    runCatching {
                        val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry("scalper/$rel").apply { time = f.lastModified() })
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        n += 1
                    }
                }
            }
        }
        return n
    }

    /** Reset: the live files move to `archive/<now>/` so the trade list starts empty and nothing is lost. */
    fun archive(nowMs: Long = System.currentTimeMillis()) {
        flushNow()
        synchronized(fileLock) {
            runCatching {
                val live = dir.listFiles()?.filter { it.isFile && isLogFile(it.name) }.orEmpty()
                if (live.isEmpty()) return
                val to = File(File(dir, ARCHIVE), nowMs.toString()).apply { mkdirs() }
                for (f in live) if (!f.renameTo(File(to, f.name))) f.delete()
                cacheKey = null
            }
        }
    }

    private fun readDayLocked(day: String): List<ScalpTrade> {
        val f = File(dir, tradeFile(day))
        val key = "${f.name}:${f.length()}"
        if (key == cacheKey) return cacheRows
        val rows = runCatching { f.readLines(Charsets.UTF_8).mapNotNull { ScalpTrade.parse(it) }.asReversed() }
            .getOrDefault(emptyList())
        // Only the newest day changes; older days are read once and reused while the list is paged.
        cacheKey = key
        cacheRows = rows
        return rows
    }

    private fun tradeDaysLocked(): List<String> =
        dir.listFiles()?.mapNotNull { TRADE_RE.matchEntire(it.name)?.groupValues?.get(1) }.orEmpty().sorted()

    private fun allFilesLocked(): List<File> =
        dir.walkTopDown().filter { it.isFile && isLogFile(it.name) }.toList()

    private fun enforceCapLocked() {
        var files = allFilesLocked()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val today = utcDay(System.currentTimeMillis())
        // Archived files first, then live days, oldest first; today's files are never deleted.
        val order = files.filter { !it.name.contains(today) }
            .sortedWith(compareBy<File> { if (it.path.contains("${File.separator}$ARCHIVE${File.separator}")) 0 else 1 }.thenBy { it.name })
        for (f in order) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        cacheKey = null
    }

    companion object {
        const val DIR_NAME = "scalper"
        const val ARCHIVE = "archive"
        const val DEFAULT_MAX_BYTES = 400L * 1024L * 1024L
        const val UNFILLED = "UNFILLED"

        /** [ScalpTrade.HEADER] with `resolved_ms` first, then what the scalper saw when it sent the order. */
        const val ORDER_HEADER =
            "resolved_ms,run,id,strategy,ticker,side,contracts,entry,exit,kind,entry_fee,exit_fee,pnl," +
                "posted_ms,filled_ms,queue_ahead,queue_known,pred_cents,expected_cents," +
                "bid,ask,bid_qty,ask_qty,move10,move30,features"

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        private val TRADE_RE = Regex("^scalps_(\\d{4}-\\d{2}-\\d{2})\\.csv$")
        private val ORDER_RE = Regex("^scalp_orders_(\\d{4}-\\d{2}-\\d{2})\\.csv\\.gz$")

        fun utcDay(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))
        fun tradeFile(day: String): String = "scalps_$day.csv"
        fun orderFile(day: String): String = "scalp_orders_$day.csv.gz"
        fun isLogFile(name: String): Boolean = TRADE_RE.matches(name) || ORDER_RE.matches(name)

        /** The order-file row for [order]: closed ([closed] set) or expired unfilled. */
        fun orderRow(order: ScalpOrder, closed: ClosedScalp?, resolvedAtMs: Long, run: Long): String {
            val n = ScalpTrade.Companion::num
            val c = order.context
            val head = listOf(
                resolvedAtMs, run, order.id, order.strategy.name, order.ticker, order.side, order.contracts,
                n(order.price), closed?.let { n(it.exitPrice) }.orEmpty(), closed?.kind?.name ?: UNFILLED,
                n(order.entryFeeUsd), n(closed?.feeUsd ?: 0.0), n(closed?.pnlUsd ?: 0.0), order.postedAtMs,
                closed?.filledAtMs?.toString().orEmpty(), n(order.queueAhead), if (order.queueKnown) 1 else 0,
                n(order.predictionCents), n(order.expectedCents)
            )
            val seen = listOf(
                n(c?.bid), n(c?.ask), n(c?.bidQty), n(c?.askQty), n(c?.move10), n(c?.move30),
                c?.features?.joinToString(";") { f -> if (f.isNaN()) "" else f.toString() }.orEmpty()
            )
            return (head + seen).joinToString(",")
        }

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "scalp-log").apply { isDaemon = true }
        }
    }
}
