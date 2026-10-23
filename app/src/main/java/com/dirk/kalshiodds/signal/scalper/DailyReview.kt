package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.TakerCost
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One strategy's day. */
@Serializable
data class StrategyDay(val name: String, val trades: Int, val wins: Int, val netUsd: Double, val feesUsd: Double, val contracts: Int) {
    val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades
    val centsPerContract: Double get() = if (contracts == 0) 0.0 else netUsd / contracts * 100.0
}

/** A slice of the day (a minute of the window, a price band, an hour): its trades and net. */
@Serializable
data class Slice(val label: String, val trades: Int, val netUsd: Double)

/** The ML scalper's predicted cents against what its trades made, per prediction bin. */
@Serializable
data class CalibrationBin(val predictedCents: Double, val madeCents: Double, val trades: Int)

/** Everything one day's review is built on, and what its charts draw. Built by [DailyReview.stats]. */
@Serializable
data class DayStats(
    val day: String,
    val trades: Int,
    val wins: Int,
    val netUsd: Double,
    val feesUsd: Double,
    val strategies: List<StrategyDay>,
    val byMinute: List<Slice>,
    val byPrice: List<Slice>,
    val byHour: List<Slice>,
    val byExit: List<Slice>,
    /** Running net through the day, one point per trade (dollars). */
    val curve: List<Double>,
    val mlBins: List<CalibrationBin>,
    val best: String,
    val worst: String
) {
    val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades
}

/** One saved review: the AI's words and the numbers they were written from. */
@Serializable
data class SavedReview(
    val day: String,
    val createdMs: Long,
    /** "On-phone · qwen2.5-1.5b-instruct-q4_k_m.gguf" or "Groq gpt-oss-120b". */
    val source: String,
    val text: String,
    val stats: DayStats,
    /** True when written before the UTC day was over. */
    val partial: Boolean = false
)

/**
 * The daily review of the strategy arena: one UTC day of closed paper scalps summed up ([stats]), written up by an
 * LLM ([prompt]; the on-phone model first) and kept ([Store]). Runs by itself once a day for yesterday, or from the
 * Reviews tab for today so far. Pure apart from [Store].
 */
object DailyReview {

    val SYSTEM = """You review one day of PAPER scalping from a Bitcoin-only Kalshi 15-minute UP/DOWN app (many strategies trade side by side, after Kalshi fees).
Use only the numbers given; never invent any. Write four short paragraphs, under 220 words in all:
1. The day in one or two sentences (net, trades, win rate).
2. What made money and what lost, by strategy, minute of the window and price band.
3. The ML scalper: did its predicted cents match what its trades made (the bins)?
4. Two concrete things to test tomorrow, each a setting or a filter, each tied to a number above.
Plain words, no headings, no tables. Paper only: never advise real-money trades."""

    fun stats(day: String, trades: List<ScalpTrade>): DayStats {
        val ts = trades.withoutRetired().sortedBy { it.closedAtMs }
        val byName = ts.groupBy { it.strategy }.map { (name, list) ->
            StrategyDay(name, list.size, list.count { it.pnlUsd > 0 }, round2(list.sumOf { it.pnlUsd }), round2(list.sumOf { it.feesUsd }), list.sumOf { it.contracts })
        }.sortedByDescending { it.netUsd }
        fun slices(key: (ScalpTrade) -> String?, order: List<String>): List<Slice> {
            val g = ts.groupBy(key)
            return order.mapNotNull { k -> g[k]?.let { Slice(k, it.size, round2(it.sumOf { t -> t.pnlUsd })) } }
        }
        val minuteOrder = (0 until 15).map { "min ${it + 1}" }
        val byMinute = slices({ t ->
            val close = TakerCost.closeEpochMs(t.ticker) ?: return@slices null
            val at = if (t.filledAtMs > 0) t.filledAtMs else t.postedAtMs
            val s = ((at - (close - PrintGrid.SECONDS * 1000L)) / 1000L).toInt()
            if (s in 0 until PrintGrid.SECONDS) "min ${s / 60 + 1}" else null
        }, minuteOrder)
        val bands = listOf("1-20c", "20-40c", "40-60c", "60-80c", "80-99c")
        val byPrice = slices({ t ->
            val c = t.entry * 100.0
            when {
                c < 20 -> bands[0]; c < 40 -> bands[1]; c < 60 -> bands[2]; c < 80 -> bands[3]; else -> bands[4]
            }
        }, bands)
        val hours = (0 until 24).map { "%02d".format(Locale.US, it) }
        val byHour = slices({ t -> "%02d".format(Locale.US, java.time.Instant.ofEpochMilli(t.closedAtMs).atZone(java.time.ZoneOffset.UTC).hour) }, hours)
        val byExit = slices({ it.kind }, listOf("TARGET", "STOP", "TIMEOUT", "SETTLED"))
        var run = 0.0
        val curve = ts.map { run += it.pnlUsd; round2(run) }
        // The ML scalper's calibration is worth showing whenever it traded, retired from the arena's views or not.
        val ml = trades.filter { it.strategy == ScalpStrategy.ML_REST.name && it.contracts > 0 }
        val mlBins = if (ml.size < 10) emptyList() else {
            val sorted = ml.sortedBy { it.predictionCents }
            val n = minOf(5, sorted.size / 5).coerceAtLeast(1)
            sorted.chunked((sorted.size + n - 1) / n).map { b ->
                CalibrationBin(
                    predictedCents = round2(b.map { it.predictionCents }.average()),
                    madeCents = round2(b.sumOf { it.pnlUsd } / b.sumOf { it.contracts } * 100.0),
                    trades = b.size
                )
            }
        }
        fun describe(t: ScalpTrade?) = t?.let {
            "${ScalperResults.strategyLabel(it.strategy)} ${ScalperResults.sideLabel(it.side)} x${it.contracts} ${money(it.pnlUsd)}"
        }.orEmpty()
        return DayStats(
            day = day, trades = ts.size, wins = ts.count { it.pnlUsd > 0 }, netUsd = round2(ts.sumOf { it.pnlUsd }),
            feesUsd = round2(ts.sumOf { it.feesUsd }), strategies = byName, byMinute = byMinute, byPrice = byPrice,
            byHour = byHour, byExit = byExit, curve = curve, mlBins = mlBins,
            best = describe(ts.maxByOrNull { it.pnlUsd }), worst = describe(ts.minByOrNull { it.pnlUsd })
        )
    }

    /** The user message: the day's numbers, compact enough for a small on-phone model. */
    fun prompt(s: DayStats, partial: Boolean): String = buildString {
        appendLine("Day ${s.day} (UTC)${if (partial) ", so far" else ""}: ${s.trades} trades, ${pct(s.winRate)} won, net ${money(s.netUsd)} after ${money(s.feesUsd)} fees")
        appendLine("Best strategies: " + s.strategies.filter { it.netUsd > 0 }.take(5).joinToString("; ") { line(it) }.ifBlank { "none made money" })
        appendLine("Worst strategies: " + s.strategies.filter { it.netUsd < 0 }.sortedBy { it.netUsd }.take(5).joinToString("; ") { line(it) }.ifBlank { "none lost money" })
        appendLine("By minute of the window: " + s.byMinute.joinToString("; ") { "${it.label} ${money(it.netUsd)} (${it.trades})" })
        appendLine("By entry price: " + s.byPrice.joinToString("; ") { "${it.label} ${money(it.netUsd)} (${it.trades})" })
        appendLine("By exit: " + s.byExit.joinToString("; ") { "${it.label.lowercase()} ${money(it.netUsd)} (${it.trades})" })
        if (s.mlBins.isNotEmpty()) {
            appendLine("ML scalper, predicted vs made, cents a contract: " +
                s.mlBins.joinToString("; ") { "${fmt2(it.predictedCents)} -> ${fmt2(it.madeCents)} (${it.trades})" })
        } else {
            appendLine("ML scalper: fewer than 10 trades, no bins")
        }
        if (s.best.isNotBlank()) appendLine("Best trade: ${s.best}. Worst trade: ${s.worst}.")
    }.trim()

    private fun line(d: StrategyDay) =
        "${ScalperResults.strategyLabel(d.name)} ${money(d.netUsd)} (${d.trades} trades, ${pct(d.winRate)} won, ${fmt2(d.centsPerContract)}c/contract)"

    /** The UTC day before [nowMs]. */
    fun yesterday(nowMs: Long): String = ScalpTradeLog.utcDay(nowMs - 86_400_000L)

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun money(v: Double) = (if (v < 0) "-" else "+") + "$" + String.format(Locale.US, "%.2f", abs(v))
    private fun pct(p: Double) = "${Math.round(p * 100.0)}%"
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)

    /** The saved reviews, newest first, in one JSON file. Keeps [KEEP]. Thread-safe. */
    class Store(private val file: File) {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val ser = ListSerializer(SavedReview.serializer())
        private val lock = Any()

        fun all(): List<SavedReview> = synchronized(lock) {
            if (!file.isFile) emptyList() else runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
        }

        /** Saves [r], replacing an earlier review of the same day. */
        fun put(r: SavedReview) = synchronized(lock) {
            val next = (listOf(r) + all().filterNot { it.day == r.day }).sortedByDescending { it.day }.take(KEEP)
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ser, next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }

        /** True when [day] has a finished (not partial) review. */
        fun hasFinal(day: String): Boolean = all().any { it.day == day && !it.partial }

        companion object {
            const val KEEP = 90
        }
    }
}
