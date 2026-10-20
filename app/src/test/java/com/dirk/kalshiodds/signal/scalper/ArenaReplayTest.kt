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

    /** One paper scalper fed by the replay, with the trades it closed. */
    private class Run(val name: String, val strategy: ScalpStrategy?, val scalper: PaperScalper, val trades: ArrayList<ScalpTrade>)

    private class Replayed(val days: List<String>, val prints: Long, val settled: Int, val hasModel: Boolean)

    private var clock = 0L

    /**
     * Feeds every recorded day in [dir] to the scalpers [make] builds, the way the app does. [make] gets the
     * model, the shared book map and the fair-price function, and returns the runs to feed.
     */
    private fun replay(
        dir: File,
        make: (model: ScalperModel?, books: HashMap<String, TopOfBook>, fair: (String, Long) -> Double?) -> List<Run>
    ): Pair<Replayed, List<Run>> {
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
        val fairFn: (String, Long) -> Double? = { t, now -> fair(t, now) }
        val runs = make(model, books, fairFn)
        val scalpers = runs.map { it.scalper }
        fun settleUpTo(now: Long) {
            while (nextSettle < pending.size && pending[nextSettle].second.first + 2_000L <= now) {
                val (tk, cr) = pending[nextSettle++]
                for (sc in scalpers) sc.settle(tk, cr.second, cr.first + 2_000L)
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
                        for (sc in scalpers) sc.onClock(tk, tB)
                        j++
                    }
                    else -> {
                        clock = tT
                        settleUpTo(tT)
                        val tk = tickers[r.tradeTicker[i]]
                        val side = if (r.tradeYes[i]) "yes" else "no"
                        for (sc in scalpers) sc.onTrade(tk, side, r.tradePrice[i], r.tradeCount[i], tT)
                        printsSeen++
                        i++
                    }
                }
            }
        }
        settleUpTo(Long.MAX_VALUE)
        return Replayed(days, printsSeen, results.size, model != null) to runs
    }

    private fun run(
        name: String, strategy: ScalpStrategy?, strategies: List<ScalpStrategy>, model: ScalperModel?,
        books: HashMap<String, TopOfBook>, fair: (String, Long) -> Double?,
        knobs: Map<String, ScalpKnobs> = emptyMap(), oneSidedPrints: Boolean = false
    ): Run {
        val trades = ArrayList<ScalpTrade>()
        val ledger = ScalperLedger(nowMs = { clock }, record = { t, _ -> trades.addAll(t) }, hourOf = { 0 })
        val scalper = PaperScalper(
            model = model, ledger = ledger, topOfBook = { books[it] }, bookLevels = { null },
            closeMs = { closes[it] ?: TakerCost.closeEpochMs(it) },
            engine = ScalperEngine(tuning = ScalpTuning(knobs)), fairYes = fair, strategies = strategies,
            oneSidedPrints = oneSidedPrints
        )
        return Run(name, strategy, scalper, trades)
    }

    @Test
    fun replayRecordedDays() {
        val dirName = System.getenv("ARENA_REPLAY_DIR")
        Assume.assumeTrue("set ARENA_REPLAY_DIR to replay the recordings", !dirName.isNullOrBlank())
        Assume.assumeTrue("ARENA_SWEEP runs the sweep instead", System.getenv("ARENA_SWEEP").isNullOrBlank())
        val outFile = File(System.getenv("ARENA_REPLAY_OUT") ?: "build/arena_replay.md")
        val (info, runs) = replay(File(dirName!!)) { model, books, fair ->
            listOf(run("all", null, ScalpStrategy.values().toList(), model, books, fair))
        }
        outFile.parentFile?.mkdirs()
        outFile.writeText(report(runs.single().trades, info.days, info.prints, info.settled, info.hasModel))
        println(outFile.readText())
    }

    /** One small change to one strategy's settings, run on its own. */
    private class Variant(val strategy: ScalpStrategy, val name: String, val knobs: ScalpKnobs = ScalpKnobs(), val oneSided: Boolean = false)

    /**
     * The tweaks tried on the strategies still running, written down before the sweep ran: each loosens one
     * setting one small step (or two together), so the strategy takes more trades. "defaults" is the baseline.
     */
    private fun variants(): List<Variant> {
        val out = ArrayList<Variant>()
        val cb = ScalpStrategy.LATE_COMEBACK
        out += Variant(cb, "defaults")
        out += Variant(cb, "rise 3¢", ScalpKnobs(trigger = 3.0))
        out += Variant(cb, "rise 5¢", ScalpKnobs(trigger = 5.0))
        out += Variant(cb, "from 15¢", ScalpKnobs(entryMinCents = 15.0))
        out += Variant(cb, "up to 45¢", ScalpKnobs(entryMaxCents = 45.0))
        out += Variant(cb, "from minute 9", ScalpKnobs(fromMinute = 9))
        out += Variant(cb, "from minute 8", ScalpKnobs(fromMinute = 8))
        out += Variant(cb, "2 at once, 60 s apart", ScalpKnobs(maxPerSide = 2, cooldownSeconds = 60))
        out += Variant(cb, "rise 3¢ + 15–45¢", ScalpKnobs(trigger = 3.0, entryMinCents = 15.0, entryMaxCents = 45.0))
        out += Variant(cb, "minute 9 + 15–45¢", ScalpKnobs(fromMinute = 9, entryMinCents = 15.0, entryMaxCents = 45.0))
        for (st in listOf(
            ScalpStrategy.KASHI_FAV_BASE, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
            ScalpStrategy.KASHI_FAV_LAST30, ScalpStrategy.KASHI_FAV_NIGHT
        )) {
            val f = st.fav!!
            val gap = Math.round(f.gap * 100).toDouble()
            out += Variant(st, "defaults")
            out += Variant(st, "edge ${(gap - 1).toInt()}¢", ScalpKnobs(edgeCents = gap - 1))
            out += Variant(st, "edge ${(gap - 2).toInt()}¢", ScalpKnobs(edgeCents = gap - 2))
            out += Variant(st, "fair ${Math.round(f.fairMin * 100) - 2}%", ScalpKnobs(trigger = f.fairMin * 100 - 2))
            out += Variant(st, "from ${f.tteMax * 3 / 2} s left", ScalpKnobs(secondsLeftMax = f.tteMax * 3 / 2))
            out += Variant(st, "from ${f.tteMax * 2} s left", ScalpKnobs(secondsLeftMax = f.tteMax * 2))
            out += Variant(st, "until ${f.tteMin / 2} s left", ScalpKnobs(secondsLeftMin = f.tteMin / 2))
            out += Variant(st, "2 at once, 15 s apart", ScalpKnobs(maxPerSide = 2, cooldownSeconds = 15))
            out += Variant(st, "one-sided prints ok", oneSided = true)
            out += Variant(
                st, "edge ${(gap - 1).toInt()}¢ + from ${f.tteMax * 3 / 2} s",
                ScalpKnobs(edgeCents = gap - 1, secondsLeftMax = f.tteMax * 3 / 2)
            )
        }
        val le = ScalpStrategy.LATE_EDGE
        out += Variant(le, "defaults")
        out += Variant(le, "edge 1¢", ScalpKnobs(trigger = 1.0))
        out += Variant(le, "edge 0¢", ScalpKnobs(trigger = 0.0))
        out += Variant(le, "edge 3¢", ScalpKnobs(trigger = 3.0))
        out += Variant(le, "from 60 s left", ScalpKnobs(secondsLeftMax = 60))
        out += Variant(le, "from 90 s left", ScalpKnobs(secondsLeftMax = 90))
        out += Variant(le, "one-sided prints ok", oneSided = true)
        out += Variant(le, "edge 1¢ + from 60 s", ScalpKnobs(trigger = 1.0, secondsLeftMax = 60))
        return out
    }

    /**
     * Every tweak in [variants] on every recorded day, each strategy on its own. A tweak counts as better
     * (rule fixed before the run) when it takes at least 10% more trades, nets at least the defaults' dollars,
     * and makes money in both the first 60% and the last 40% of the days.
     */
    @Test
    fun sweepTweaks() {
        val dirName = System.getenv("ARENA_REPLAY_DIR")
        Assume.assumeTrue("set ARENA_REPLAY_DIR and ARENA_SWEEP to sweep", !dirName.isNullOrBlank() && !System.getenv("ARENA_SWEEP").isNullOrBlank())
        val outFile = File(System.getenv("ARENA_REPLAY_OUT") ?: "build/arena_sweep.md")
        val vs = variants()
        val (info, runs) = replay(File(dirName!!)) { model, books, fair ->
            vs.map { v ->
                run(v.name, v.strategy, listOf(v.strategy), model, books, fair, mapOf(v.strategy.name to v.knobs), v.oneSided)
            }
        }
        val dayOf = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
        val split = info.days.getOrNull((info.days.size * 0.6).toInt()) ?: ""
        val sb = StringBuilder()
        sb.append("# Arena sweep: small tweaks to the strategies still running\n\n")
        sb.append("Recorded days: ${info.days.first()} → ${info.days.last()} (${info.days.size}) · ${info.prints} BTC prints · ")
        sb.append("${info.settled} settled windows. Each row is one strategy alone with one change. ")
        sb.append("Better = 10%+ more trades, net at least the defaults', up in both the first 60% and last 40% of days (split $split).\n\n")
        sb.append("| Strategy | Tweak | Trades | Net $ | ¢/contract | Win % | Days up | First | Last | $/day 95% CI | Better |\n")
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|\n")
        val lines = ArrayList<String>()
        for ((st, group) in runs.groupBy { it.strategy!! }) {
            val base = group.first { it.name == "defaults" }.trades
            val baseNet = base.sumOf { it.pnlUsd }
            val scored = group.map { r ->
                val t = r.trades
                val net = t.sumOf { it.pnlUsd }
                val contracts = t.sumOf { it.contracts.toLong() }
                val first = t.filter { dayOf(it.closedAtMs) < split }.sumOf { it.pnlUsd }
                val last = t.filter { dayOf(it.closedAtMs) >= split }.sumOf { it.pnlUsd }
                val byDay = info.days.map { d -> t.filter { dayOf(it.closedAtMs) == d }.sumOf { it.pnlUsd } }
                val rnd = Random(7)
                val boots = DoubleArray(2_000) { byDay.indices.sumOf { byDay[rnd.nextInt(byDay.size)] } / byDay.size }.also { it.sort() }
                val better = r.name != "defaults" && t.size >= base.size * 1.1 && net >= baseNet - 1e-9 && first > 0 && last > 0
                sb.append(
                    String.format(
                        Locale.US, "| %s | %s | %d | %+.2f | %+.2f | %.0f%% | %d / %d | %+.2f | %+.2f | [%+.2f, %+.2f] | %s |\n",
                        st.name, r.name, t.size, net, if (contracts > 0) net * 100.0 / contracts else 0.0,
                        if (t.isEmpty()) 0.0 else t.count { it.pnlUsd > 0 } * 100.0 / t.size,
                        byDay.count { it > 0 }, byDay.count { it != 0.0 }, first, last,
                        boots[(0.025 * boots.size).toInt()], boots[(0.975 * boots.size).toInt().coerceAtMost(boots.size - 1)],
                        if (better) "yes" else ""
                    )
                )
                Triple(r.name, net, better)
            }
            val pick = scored.filter { it.third }.maxByOrNull { it.second }
            lines += "${st.name} ${pick?.first ?: "keep defaults"}"
        }
        sb.append("\nSWEEP_PICKS\n")
        lines.forEach { sb.append(it).append('\n') }
        outFile.parentFile?.mkdirs()
        outFile.writeText(sb.toString())
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
