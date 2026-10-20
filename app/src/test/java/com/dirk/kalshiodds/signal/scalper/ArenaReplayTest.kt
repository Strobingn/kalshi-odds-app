package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.external.CfIndexBook
import com.dirk.kalshiodds.signal.trade.TakerCost
import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assume
import org.junit.Test
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Replays the cloud recorder's days (the `scalper-data` release: every
 * KXBTC15M public trade with its taker side, the top of the book with sizes,
 * Coinbase BTC, and every settlement) through the app's own [PaperScalper],
 * every strategy on its default settings, and writes each strategy's paper
 * result by day. Runs only when `ARENA_REPLAY_DIR` points at the files
 * (`.github/workflows/arena-replay.yml`); a normal unit-test run skips it.
 *
 * Feeding mirrors the app: a trade print → `onTrade`, a book update →
 * `onClock`, a settled window → `settle`. The late favourites' fair price
 * comes from Coinbase BTC fed into a [CfIndexBook] (the recordings have no
 * CF Benchmarks index), so those strategies are an approximation.
 */
class ArenaReplayTest {

    private class DayRows(
        val tradeTs: LongArray, val tradeTicker: IntArray, val tradePrice: DoubleArray,
        val tradeCount: DoubleArray, val tradeYes: BooleanArray,
        val bookTs: LongArray, val bookTicker: IntArray, val book: Array<TopOfBook>,
        val spotTs: LongArray, val spotPx: DoubleArray
    )

    private val tickers = ArrayList<String>()
    private val tickerId = HashMap<String, Int>()
    private val strikes = HashMap<String, Double>()
    private val closes = HashMap<String, Long>()

    private fun id(t: String): Int = tickerId.getOrPut(t) { tickers.add(t); tickers.size - 1 }

    /** Lines of a multi-member gzip, stopping cleanly at a truncated tail. */
    private fun gzLines(f: File, each: (String) -> Unit) {
        if (!f.isFile) return
        try {
            BufferedReader(InputStreamReader(GZIPInputStream(f.inputStream().buffered(1 shl 16), 1 shl 16))).use { r ->
                while (true) each(r.readLine() ?: break)
            }
        } catch (_: EOFException) {
        } catch (_: java.util.zip.ZipException) {
        }
    }

    private fun d(s: String): Double? = s.toDoubleOrNull()

    /** Sorts row indices by time (stable): packs (ts − base) and the row index into one long. */
    private fun order(ts: LongArray, n: Int): IntArray {
        if (n == 0) return IntArray(0)
        val base = (0 until n).minOf { ts[it] }
        val keys = LongArray(n) { ((ts[it] - base) shl 23) or it.toLong() }
        keys.sort()
        return IntArray(n) { (keys[it] and ((1L shl 23) - 1)).toInt() }
    }

    private fun loadDay(dir: File, day: String): DayRows {
        val tTs = ArrayList<Long>(); val tTk = ArrayList<Int>(); val tPx = ArrayList<Double>()
        val tCt = ArrayList<Double>(); val tYes = ArrayList<Boolean>()
        gzLines(File(dir, "trades_$day.csv.gz")) { line ->
            val p = line.split(',')
            if (p.size < 5 || !p[1].startsWith("KXBTC15M")) return@gzLines
            val ts = p[0].toLongOrNull() ?: return@gzLines
            val px = d(p[2]) ?: return@gzLines
            val ct = d(p[3]) ?: return@gzLines
            val side = p[4].trim().lowercase(Locale.US)
            if (side != "yes" && side != "no") return@gzLines
            tTs += ts; tTk += id(p[1]); tPx += px; tCt += ct; tYes += side == "yes"
        }
        val bTs = ArrayList<Long>(); val bTk = ArrayList<Int>(); val bk = ArrayList<TopOfBook>()
        gzLines(File(dir, "book_$day.csv.gz")) { line ->
            val p = line.split(',')
            if (p.size < 12 || !p[1].startsWith("KXBTC15M")) return@gzLines
            val ts = p[0].toLongOrNull() ?: return@gzLines
            d(p[2])?.let { strikes[p[1]] = it }
            p[3].toLongOrNull()?.let { closes[p[1]] = it }
            bTs += ts; bTk += id(p[1])
            bk += TopOfBook(d(p[4]), d(p[5]), d(p[6]), d(p[7]), d(p[8]), d(p[9]), d(p[10]), d(p[11]))
        }
        val sTs = ArrayList<Long>(); val sPx = ArrayList<Double>()
        gzLines(File(dir, "spot_$day.csv.gz")) { line ->
            val p = line.split(',')
            if (p.size < 3 || p[1] != "BTC-USD") return@gzLines
            val ts = p[0].toLongOrNull() ?: return@gzLines
            sTs += ts; sPx += d(p[2]) ?: return@gzLines
        }
        val nT = tTs.size
        val oT = order(tTs.toLongArray(), nT)
        val oB = order(bTs.toLongArray(), bTs.size)
        val oS = order(sTs.toLongArray(), sTs.size)
        return DayRows(
            LongArray(nT) { tTs[oT[it]] }, IntArray(nT) { tTk[oT[it]] }, DoubleArray(nT) { tPx[oT[it]] },
            DoubleArray(nT) { tCt[oT[it]] }, BooleanArray(nT) { tYes[oT[it]] },
            LongArray(bTs.size) { bTs[oB[it]] }, IntArray(bTs.size) { bTk[oB[it]] }, Array(bTs.size) { bk[oB[it]] },
            LongArray(sTs.size) { sTs[oS[it]] }, DoubleArray(sTs.size) { sPx[oS[it]] }
        )
    }

    @Test
    fun replayRecordedDays() {
        val dirName = System.getenv("ARENA_REPLAY_DIR")
        Assume.assumeTrue("set ARENA_REPLAY_DIR to replay the recordings", !dirName.isNullOrBlank())
        val dir = File(dirName!!)
        val outFile = File(System.getenv("ARENA_REPLAY_OUT") ?: "build/arena_replay.md")
        val dayRe = Regex("""^trades_(\d{4}-\d{2}-\d{2})\.csv\.gz$""")
        val days = dir.listFiles().orEmpty().mapNotNull { dayRe.find(it.name)?.groupValues?.get(1) }.sorted()
        require(days.isNotEmpty()) { "no trades_*.csv.gz in $dir" }

        // Every settlement, from every day's file.
        val results = HashMap<String, Pair<Long, String>>()
        dir.listFiles().orEmpty().filter { it.name.startsWith("settle_") && it.name.endsWith(".csv") }.forEach { f ->
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size >= 4 && p[0].startsWith("KXBTC15M")) {
                    val close = p[1].toLongOrNull() ?: return@forEachLine
                    val r = p[3].trim().lowercase(Locale.US)
                    if (r == "yes" || r == "no") results[p[0]] = close to r
                }
            }
        }
        val pending = results.entries.sortedBy { it.value.first }.map { it.key to it.value }
        var nextSettle = 0

        val model = listOf("src/main/assets/scalper_model.json", "app/src/main/assets/scalper_model.json")
            .map { File(it) }.firstOrNull { it.isFile }?.let { ScalperModel.parse(it.readText()) }
        var clock = 0L
        val trades = ArrayList<ScalpTrade>()
        val ledger = ScalperLedger(nowMs = { clock }, record = { t, _ -> trades.addAll(t) }, hourOf = { 0 })
        val books = HashMap<String, TopOfBook>()
        val cf = CfIndexBook()
        fun fair(ticker: String, now: Long): Double? {
            val close = closes[ticker] ?: TakerCost.closeEpochMs(ticker) ?: return null
            val spot = cf.fresh("BTC", now)?.value ?: return null
            val observed = if (close - now < 60_000L) cf.meanLog("BTC", close - 60_000L, now) else null
            return ScalpFair.fairYes(
                spot = spot, strike = strikes[ticker], tteSeconds = (close - now) / 1000.0,
                std1m = cf.std1m("BTC", now), coin = "BTC", observedMeanLog = observed
            )
        }
        val scalper = PaperScalper(
            model = model, ledger = ledger, topOfBook = { books[it] }, bookLevels = { null },
            closeMs = { closes[it] ?: TakerCost.closeEpochMs(it) },
            engine = ScalperEngine(), fairYes = { t, now -> fair(t, now) }, strategies = ScalpStrategy.values().toList()
        )
        fun settleUpTo(now: Long) {
            while (nextSettle < pending.size && pending[nextSettle].second.first + 2_000L <= now) {
                val (tk, cr) = pending[nextSettle++]
                scalper.settle(tk, cr.second, cr.first + 2_000L)
            }
        }

        var printsSeen = 0L
        for (day in days) {
            val r = loadDay(dir, day)
            var i = 0; var j = 0; var k = 0
            val nT = r.tradeTs.size; val nB = r.bookTs.size; val nS = r.spotTs.size
            while (i < nT || j < nB || k < nS) {
                val tT = if (i < nT) r.tradeTs[i] else Long.MAX_VALUE
                val tB = if (j < nB) r.bookTs[j] else Long.MAX_VALUE
                val tS = if (k < nS) r.spotTs[k] else Long.MAX_VALUE
                when {
                    tS <= tT && tS <= tB -> {
                        clock = tS
                        cf.onUpdate(KalshiWsMessages.Parsed.IndexValue("BRTI", r.spotPx[k], tS, tS, null, null, null, null), tS)
                        k++
                    }
                    tB <= tT -> {
                        clock = tB
                        settleUpTo(tB)
                        val tk = tickers[r.bookTicker[j]]
                        books[tk] = r.book[j]
                        scalper.onClock(tk, tB)
                        j++
                    }
                    else -> {
                        clock = tT
                        settleUpTo(tT)
                        scalper.onTrade(
                            tickers[r.tradeTicker[i]], if (r.tradeYes[i]) "yes" else "no",
                            r.tradePrice[i], r.tradeCount[i], tT
                        )
                        printsSeen++
                        i++
                    }
                }
            }
        }
        settleUpTo(Long.MAX_VALUE)
        outFile.parentFile?.mkdirs()
        outFile.writeText(report(trades, days, printsSeen, results.size, model != null))
        println(outFile.readText())
    }

    private fun report(trades: List<ScalpTrade>, days: List<String>, prints: Long, settled: Int, hasModel: Boolean): String {
        val dayOf = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
        val replayDays = trades.map { dayOf(it.closedAtMs) }.toSortedSet().toList()
        val split = replayDays.getOrNull((replayDays.size * 0.6).toInt()) ?: ""
        data class Row(
            val st: ScalpStrategy, val n: Int, val contracts: Long, val net: Double, val winRate: Double,
            val daysActive: Int, val daysUp: Int, val lo: Double, val hi: Double, val first: Double, val last: Double
        )
        val rows = ScalpStrategy.values().map { st ->
            val mine = trades.filter { it.strategy == st.name }
            val byDay = replayDays.associateWith { d -> mine.filter { dayOf(it.closedAtMs) == d }.sumOf { it.pnlUsd } }
            val active = replayDays.filter { d -> mine.any { dayOf(it.closedAtMs) == d } }
            // Day-block bootstrap of the mean dollars a day (over every replayed day, idle days count 0).
            val vals = replayDays.map { byDay[it] ?: 0.0 }
            val rnd = Random(7)
            val boots = DoubleArray(2_000) { vals.indices.sumOf { vals[rnd.nextInt(vals.size)] } / vals.size.coerceAtLeast(1) }
            boots.sort()
            Row(
                st, mine.size, mine.sumOf { it.contracts.toLong() }, mine.sumOf { it.pnlUsd },
                if (mine.isEmpty()) 0.0 else mine.count { it.pnlUsd > 0 }.toDouble() / mine.size,
                active.size, active.count { (byDay[it] ?: 0.0) > 0 },
                if (vals.isEmpty()) 0.0 else boots[(0.025 * boots.size).toInt()],
                if (vals.isEmpty()) 0.0 else boots[(0.975 * boots.size).toInt().coerceAtMost(boots.size - 1)],
                mine.filter { dayOf(it.closedAtMs) < split }.sumOf { it.pnlUsd },
                mine.filter { dayOf(it.closedAtMs) >= split }.sumOf { it.pnlUsd }
            )
        }.sortedByDescending { it.net }
        val sb = StringBuilder()
        sb.append("# Arena replay: every strategy on the recorded days\n\n")
        sb.append("Recorded days: ${days.first()} → ${days.last()} (${days.size} files, ${replayDays.size} days with closed trades) · ")
        sb.append("$prints BTC prints · $settled settled windows · ML model ${if (hasModel) "loaded" else "missing"}.\n\n")
        sb.append("Every strategy on its defaults, through the app's own PaperScalper / ScalperEngine. Paper dollars after fees. ")
        sb.append("CI = 95% day-block bootstrap of dollars a day. 'First / last' splits the days 60/40 at $split.\n\n")
        sb.append("| Strategy | Trades | Contracts | Net $ | ¢/contract | Win % | Days up / active | $/day 95% CI | First | Last |\n")
        sb.append("|---|---:|---:|---:|---:|---:|---:|---|---:|---:|\n")
        for (r in rows) {
            val cpc = if (r.contracts > 0) r.net * 100.0 / r.contracts else 0.0
            sb.append(
                String.format(
                    Locale.US, "| %s | %d | %d | %+.2f | %+.2f | %.0f%% | %d / %d | [%+.2f, %+.2f] | %+.2f | %+.2f |\n",
                    r.st.name, r.n, r.contracts, r.net, cpc, r.winRate * 100, r.daysUp, r.daysActive, r.lo, r.hi, r.first, r.last
                )
            )
        }
        sb.append("\nVERDICT_LINES\n")
        for (r in rows) {
            val verdict = when {
                r.n == 0 -> "NO_TRADES"
                r.net > 0 && r.first > 0 && r.last > 0 -> "MADE_MONEY_BOTH_HALVES"
                r.net > 0 -> "MADE_MONEY"
                else -> "LOST"
            }
            sb.append("${r.st.name} $verdict\n")
        }
        return sb.toString()
    }
}
