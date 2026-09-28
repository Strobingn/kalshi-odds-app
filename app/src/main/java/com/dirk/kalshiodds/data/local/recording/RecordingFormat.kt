package com.dirk.kalshiodds.data.local.recording

import com.dirk.kalshiodds.signal.engine.TopOfBook
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * On-disk format of the second-by-second market-data recordings under
 * `filesDir/recordings/`. `tools/research/recordings.py` parses exactly
 * this, so change both together.
 *
 * One file per kind per UTC day (the day of the row's `ts_ms`; for settle
 * rows the day of `close_ms`, falling back to the day it was appended):
 * - `spot_YYYY-MM-DD.csv.gz`   `ts_ms,product,price`
 * - `book_YYYY-MM-DD.csv.gz`   `ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty`
 * - `trades_YYYY-MM-DD.csv.gz` `ts_ms,ticker,yes_price,count,taker_side`
 * - `settle_YYYY-MM-DD.csv`    `ticker,close_ms,strike,result` (plain text)
 *
 * Comma-separated, header row first, empty field = missing. `ts_ms` is the
 * device wall clock (epoch ms) when the row's data was received. Prices are
 * dollars (0–1 for Kalshi, USD for spot), quantities are contracts.
 *
 * The `.csv.gz` files are **multi-member gzip**: every app start / day
 * rollover / 10-minute rotation appends a new member, and the open member
 * is sync-flushed every few seconds, so after a crash the last member may
 * have no gzip trailer. Readers must concatenate members and tolerate a
 * truncated one (Python's `gzip` handles the first; `recordings.py` both).
 */
object RecordingFormat {
    const val KIND_SPOT = "spot"
    const val KIND_BOOK = "book"
    const val KIND_TRADES = "trades"
    const val KIND_SETTLE = "settle"

    const val SPOT_HEADER = "ts_ms,product,price"
    const val BOOK_HEADER =
        "ts_ms,ticker,strike,close_ms,yes_bid,yes_bid_qty,yes_ask,yes_ask_qty,no_bid,no_bid_qty,no_ask,no_ask_qty"
    const val TRADES_HEADER = "ts_ms,ticker,yes_price,count,taker_side"
    const val SETTLE_HEADER = "ticker,close_ms,strike,result"

    val GZ_KINDS: List<String> = listOf(KIND_SPOT, KIND_BOOK, KIND_TRADES)
    val ALL_KINDS: List<String> = GZ_KINDS + KIND_SETTLE

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val FILE_RE = Regex("^(spot|book|trades|settle)_(\\d{4}-\\d{2}-\\d{2})\\.csv(\\.gz)?$")

    fun utcDay(epochMs: Long): String = DAY.format(Instant.ofEpochMilli(epochMs))

    fun header(kind: String): String = when (kind) {
        KIND_SPOT -> SPOT_HEADER
        KIND_BOOK -> BOOK_HEADER
        KIND_TRADES -> TRADES_HEADER
        KIND_SETTLE -> SETTLE_HEADER
        else -> error("unknown kind $kind")
    }

    fun fileName(kind: String, day: String): String =
        if (kind == KIND_SETTLE) "${kind}_$day.csv" else "${kind}_$day.csv.gz"

    /** `(kind, day)` for a recording file name, null for anything else. */
    fun parseFileName(name: String): Pair<String, String>? {
        val m = FILE_RE.matchEntire(name) ?: return null
        val kind = m.groupValues[1]
        val gz = m.groupValues[3].isNotEmpty()
        if ((kind == KIND_SETTLE) == gz) return null
        return kind to m.groupValues[2]
    }

    /** Plain decimal, at most 6 places, no trailing zeros; null / NaN → empty. */
    fun num(x: Double?): String {
        if (x == null || !x.isFinite()) return ""
        return BigDecimal.valueOf(x).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun field(s: String?): String =
        s?.trim().orEmpty().replace(",", "").replace("\n", "").replace("\r", "")

    fun spotRow(tsMs: Long, product: String, price: Double): String =
        "$tsMs,${field(product)},${num(price)}"

    fun bookRow(tsMs: Long, ticker: String, strike: Double?, closeMs: Long?, top: TopOfBook): String =
        buildString {
            append(tsMs).append(',').append(field(ticker)).append(',')
            append(num(strike)).append(',').append(closeMs?.toString().orEmpty())
            for (v in listOf(
                top.yesBid, top.yesBidQty, top.yesAsk, top.yesAskQty,
                top.noBid, top.noBidQty, top.noAsk, top.noAskQty
            )) {
                append(',').append(num(v))
            }
        }

    fun tradeRow(tsMs: Long, ticker: String, yesPrice: Double?, count: Double?, takerSide: String?): String =
        "$tsMs,${field(ticker)},${num(yesPrice)},${num(count)},${takerSideField(takerSide)}"

    fun settleRow(ticker: String, closeMs: Long?, strike: Double?, result: String): String =
        "${field(ticker)},${closeMs?.toString().orEmpty()},${num(strike)},${field(result).lowercase()}"

    /** Kalshi trade `taker_side` → `yes` / `no` / empty. */
    fun takerSideField(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "yes" -> "yes"
        "no" -> "no"
        else -> ""
    }
}
