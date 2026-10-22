package com.dirk.kalshiodds.ui.charts.rich

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.domain.EtTime
import com.dirk.kalshiodds.signal.paper.PaperFill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Paper-only chart math. Every figure is a reduction of stored trades.
 * Fees match Kalshi's taker schedule: ceil(0.07·C·P·(1−P)) to the cent,
 * the same round-up [ScalpRule.orderFee] stores on a scalp fill.
 */
object RichTradeMath {
    const val MAX_ROWS = 4_000
    const val ROLL_20 = 20
    const val ROLL_50 = 50
    val ET: ZoneId = EtTime.ZONE

    private val DAY = DateTimeFormatter.ofPattern("M/d", Locale.US)
    private val FEE_NOTE = Regex("""fee\s+\$(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    enum class ExitBucket(val label: String) {
        TP("TP"),
        FAIR("Fair"),
        STOP("Stop"),
        SETTLE("Settle"),
        TIME("Time"),
        OTHER("Other")
    }

    data class Trade(
        val id: String,
        val closedAtMs: Long,
        val entryAtMs: Long,
        val netUsd: Double,
        val feeUsd: Double,
        val sideUp: Boolean,
        val entryPrice: Double?,
        val contracts: Int,
        val secondsLeft: Double?,
        val holdMs: Long?,
        val exit: ExitBucket,
        /** Picked-side probability, 0–1. Null when the fill did not store one. */
        val predicted: Double?,
        /** Model confidence 0–1. Falls back to [predicted] when the fill has only an AI %. */
        val confidence: Double?,
        val won: Boolean,
        val stakeUsd: Double,
        val scalperId: String?,
        val open: Boolean = false,
        /** 15-minute market id. Empty on rows that are not a paper fill. */
        val ticker: String = ""
    )

    data class Sample(val tMs: Long, val value: Double)

    /** [up] and [down] are dollars or counts, depending on the chart. */
    data class Split(val label: String, val n: Int, val up: Double, val down: Double) {
        val total: Double get() = up + down
    }

    data class NamedSeries(val name: String, val points: List<Sample>)

    data class CalPoint(val label: String, val predicted: Double, val actual: Double, val n: Int)

    data class Summary(
        val n: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val scratches: Int = 0,
        val netUsd: Double = 0.0,
        val feesUsd: Double = 0.0,
        val grossUsd: Double = 0.0,
        val bestUsd: Double? = null,
        val worstUsd: Double? = null,
        val longestWinStreak: Int = 0,
        val longestLossStreak: Int = 0,
        val profitFactor: Double? = null,
        val expectancyUsd: Double? = null,
        val avgWinUsd: Double? = null,
        val avgLossUsd: Double? = null,
        val sharpe: Double? = null,
        val tradesPerDay: Double? = null,
        val winRate: Double? = null
    )

    data class Report(
        val summary: Summary = Summary(),
        val equity: List<Sample> = emptyList(),
        val equityUp: List<Sample> = emptyList(),
        val equityDown: List<Sample> = emptyList(),
        val drawdown: List<Sample> = emptyList(),
        val grossSeries: List<Sample> = emptyList(),
        val feeSeries: List<Sample> = emptyList(),
        val pnlHistogram: List<Split> = emptyList(),
        val rollingWin20: List<Sample> = emptyList(),
        val rollingWin50: List<Sample> = emptyList(),
        val rollingNet20: List<Sample> = emptyList(),
        val rollingNet50: List<Sample> = emptyList(),
        val byEntry: List<Split> = emptyList(),
        val byTimeLeft: List<Split> = emptyList(),
        val byHour: List<Split> = emptyList(),
        val hold: List<Split> = emptyList(),
        val exits: List<Split> = emptyList(),
        val bySide: List<Split> = emptyList(),
        val daily: List<Split> = emptyList(),
        val calibration: List<CalPoint> = emptyList(),
        val brier: List<Sample> = emptyList(),
        val byConfidence: List<Split> = emptyList(),
        val exposure: List<Sample> = emptyList(),
        val sizeBins: List<Split> = emptyList(),
        val openCount: Int = 0,
        /** One row per 15-minute ticker. UP and DOWN dollars both sit in the split, so the total is the window. */
        val byWindow: List<Split> = emptyList()
    )

    fun countLabel(n: Int): String = if (n <= 0) "No trades yet" else "n=$n"

    /** ceil(0.07·C·P·(1−P)) to the cent, in dollars. Zero at 0¢, 100¢, or an empty clip. */
    fun kalshiCeilFee(contracts: Int, price: Double): Double = ScalpRule.orderFee(contracts, price)

    fun exitBucket(reason: String?): ExitBucket {
        val raw = reason?.trim().orEmpty()
        if (raw.isEmpty()) return ExitBucket.OTHER
        val last = raw.substringAfterLast(" → ").lowercase(Locale.US)
        return when {
            last.contains("settl") -> ExitBucket.SETTLE
            last.contains("fair-value") || last.contains("beats fair") || last.contains("crossed fair") ||
                last.contains("above fair") -> ExitBucket.FAIR
            last.contains("time") || last.contains("hold") || last.contains("s left") ||
                last.contains("minute") || last.contains("before close") -> ExitBucket.TIME
            last.contains("stop") -> ExitBucket.STOP
            last.contains("profit") || last.contains("take-profit") || last.contains("tp") -> ExitBucket.TP
            else -> ExitBucket.OTHER
        }
    }

    /** 5¢ bands. 40¢ is the 40–45¢ band. 100¢ stays in 95–100¢. */
    fun entryBand(price: Double): String? {
        if (!price.isFinite() || price !in 0.0..1.0) return null
        val cents = floor(price * 100.0 + 1e-6).toInt().coerceIn(0, 100)
        val lo = if (cents == 100) 95 else (cents / 5) * 5
        return "$lo–${lo + 5}¢"
    }

    fun timeLeftBand(seconds: Double?): String {
        val s = seconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return "Unknown"
        return when {
            s >= 12 * 60 -> "12–15 min"
            s >= 10 * 60 -> "10–12 min"
            s >= 8 * 60 -> "8–10 min"
            s >= 5 * 60 -> "5–8 min"
            s >= 3 * 60 -> "3–5 min"
            s >= 60 -> "1–3 min"
            else -> "<1 min"
        }
    }

    fun fromScalp(t: ScalpTrade): Trade? {
        if (!t.isPrimary || t.state != ScalpState.CLOSED) return null
        val net = t.netUsd?.takeIf { it.isFinite() } ?: return null
        val closedAt = t.closedAtMs ?: return null
        val entryAt = t.entryAtMs ?: t.signalAtMs
        val price = t.entryPrice?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val fee = listOf(t.entryFeeUsd, t.exitFeeUsd).sumOf { v -> if (v.isFinite() && v > 0.0) v else 0.0 }
        val left = ScalpTicker.closeMs(t.ticker)?.let { (it - entryAt) / 1000.0 }?.takeIf { it.isFinite() }
        val hold = (t.holdMs ?: (closedAt - entryAt)).takeIf { it >= 0L }
        val contracts = t.contracts.coerceAtLeast(0)
        return Trade(
            id = t.id,
            closedAtMs = closedAt,
            entryAtMs = entryAt,
            netUsd = net,
            feeUsd = fee,
            sideUp = isUp(t.side),
            entryPrice = price,
            contracts = contracts,
            secondsLeft = left,
            holdMs = hold,
            exit = exitBucket(t.exitReason),
            predicted = null,
            confidence = null,
            won = net > 0.0,
            stakeUsd = (price ?: 0.0) * contracts,
            scalperId = ScalpModels.scalperIdOf(t)?.let(ScalpModels::canonicalScalperId),
            open = false
        )
    }

    fun fromPaper(f: PaperFill): Trade? {
        if (f.outcome?.equals("void", ignoreCase = true) == true) return null
        val price = f.limitPrice.takeIf { it.isFinite() && it in 0.0..1.0 }
        val contracts = f.contracts.coerceAtLeast(0)
        val stake = when {
            f.stakeUsd.isFinite() && f.stakeUsd > 0.0 -> f.stakeUsd
            price != null && contracts > 0 -> price * contracts
            else -> 0.0
        }
        val predicted = unitProb(f.aiPct) ?: unitProb(f.aiConfidence)
        val confidence = unitProb(f.aiConfidence) ?: predicted
        val entryAt = f.createdAtMs
        val up = isUp(f.side)
        if (!f.settled) {
            return Trade(
                id = f.id,
                closedAtMs = entryAt,
                entryAtMs = entryAt,
                netUsd = 0.0,
                feeUsd = 0.0,
                sideUp = up,
                entryPrice = price,
                contracts = contracts,
                secondsLeft = null,
                holdMs = null,
                exit = ExitBucket.OTHER,
                predicted = predicted,
                confidence = confidence,
                won = false,
            stakeUsd = stake,
            scalperId = null,
            open = true,
            ticker = f.ticker
        )
    }
        val net = f.pnlUsd?.takeIf { it.isFinite() } ?: return null
        val closedAt = f.syncAtMs().coerceAtLeast(entryAt)
        return Trade(
            id = f.id,
            closedAtMs = closedAt,
            entryAtMs = entryAt,
            netUsd = net,
            feeUsd = paperFee(f, contracts, price),
            sideUp = up,
            entryPrice = price,
            contracts = contracts,
            secondsLeft = null,
            holdMs = (closedAt - entryAt).takeIf { it >= 0L },
            exit = ExitBucket.OTHER,
            predicted = predicted,
            confidence = confidence,
            won = f.won ?: (net > 0.0),
            stakeUsd = stake,
            scalperId = null,
            open = false,
            ticker = f.ticker
        )
    }

    /** Stored "fee $x" in the note wins. Otherwise the ceil formula on the stored clip. */
    fun paperFee(fill: PaperFill, contracts: Int = fill.contracts, price: Double? = fill.limitPrice): Double {
        val noted = FEE_NOTE.find(fill.note)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (noted != null && noted.isFinite() && noted >= 0.0) return noted
        val px = price?.takeIf { it.isFinite() } ?: return 0.0
        return kalshiCeilFee(contracts, px)
    }

    fun dedupe(trades: List<Trade>): List<Trade> =
        trades.groupBy { it.id }.values.map { rows ->
            rows.maxWith(compareBy<Trade>({ if (it.open) 0 else 1 }, { it.closedAtMs }, { it.entryAtMs }))
        }

    /** Keeps every open row, then the newest closed rows, until [max]. */
    fun cap(trades: List<Trade>, max: Int = MAX_ROWS): Pair<List<Trade>, Boolean> {
        val unique = dedupe(trades)
        val limit = max.coerceAtLeast(1)
        if (unique.size <= limit) return unique to false
        val open = unique.filter { it.open }.sortedByDescending { it.entryAtMs }
        val closed = unique.filter { !it.open }.sortedWith(compareByDescending<Trade> { it.closedAtMs }.thenByDescending { it.id })
        val keepOpen = open.take(limit)
        return (keepOpen + closed.take(limit - keepOpen.size)) to true
    }

    fun report(trades: List<Trade>, nowMs: Long): Report {
        val unique = dedupe(trades)
        val closed = unique.filter { !it.open && it.netUsd.isFinite() }
            .sortedWith(compareBy<Trade> { it.closedAtMs }.thenBy { it.id })
        val summary = summarize(closed)
        val openCount = unique.count { it.open }
        if (closed.isEmpty() && openCount == 0) return Report(summary = summary)
        val equity = equity(closed)
        val drawdown = drawdown(equity)
        return Report(
            summary = summary,
            equity = equity,
            equityUp = sideEquity(closed, up = true, span = equity),
            equityDown = sideEquity(closed, up = false, span = equity),
            drawdown = drawdown,
            grossSeries = anchored(closed) { it.netUsd + it.feeUsd },
            feeSeries = anchored(closed) { it.feeUsd },
            pnlHistogram = pnlHistogram(closed),
            rollingWin20 = rolling(closed, ROLL_20) { slice -> slice.count { it.netUsd > 0.0 }.toDouble() / ROLL_20 },
            rollingWin50 = rolling(closed, ROLL_50) { slice -> slice.count { it.netUsd > 0.0 }.toDouble() / ROLL_50 },
            rollingNet20 = rolling(closed, ROLL_20) { slice -> slice.sumOf { it.netUsd } / ROLL_20 },
            rollingNet50 = rolling(closed, ROLL_50) { slice -> slice.sumOf { it.netUsd } / ROLL_50 },
            byEntry = bandSplits(closed, ENTRY_ORDER) { entryBand(it.entryPrice ?: Double.NaN) },
            byTimeLeft = bandSplits(closed, TIME_ORDER) { timeLeftBand(it.secondsLeft) },
            byHour = byHour(closed),
            hold = countSplits(closed, HOLD_ORDER) { holdBand(it.holdMs) },
            exits = bandSplits(closed, EXIT_ORDER) { it.exit.label },
            bySide = sideSplit(closed),
            daily = daily(closed),
            calibration = calibration(closed),
            brier = brier(closed),
            byConfidence = bandSplits(closed, CONF_ORDER) { confidenceBand(it.confidence) },
            exposure = exposure(unique, nowMs),
            sizeBins = countSplits(unique, SIZE_ORDER) { sizeBand(it.contracts) },
            openCount = openCount,
            byWindow = windowSplits(unique)
        )
    }

    /** Settled P&L of every fill in a 15-minute ticker. UP and DOWN both count. */
    fun windowSplits(trades: List<Trade>): List<Split> {
        val closed = dedupe(trades).filter { !it.open && it.ticker.isNotBlank() }
        return closed.groupBy { it.ticker.uppercase(Locale.US) }
            .map { (ticker, rows) ->
                var up = 0.0
                var down = 0.0
                for (t in rows) if (t.sideUp) up += t.netUsd else down += t.netUsd
                Split(com.dirk.kalshiodds.ui.WindowLabel.of(ticker), rows.size, up, down)
            }
            .sortedByDescending { kotlin.math.abs(it.total) }
    }

    /**
     * One equity line per registry id that actually closed a trade, in registry order.
     * Ids the registry does not return are left off, including ones present in [trades].
     */
    fun compare(trades: List<Trade>, registry: List<Pair<String, String>>): List<NamedSeries> {
        val closed = dedupe(trades).filter { !it.open }.groupBy { it.scalperId }
        val duplicateLabels = registry.map { it.second }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        val out = ArrayList<NamedSeries>()
        for ((id, label) in registry) {
            val rows = closed[id].orEmpty().sortedWith(compareBy<Trade> { it.closedAtMs }.thenBy { it.id })
            if (rows.isEmpty()) continue
            val name = if (label in duplicateLabels) "$label ($id)" else label
            out += NamedSeries(name, equity(rows))
        }
        return out
    }

    fun thin(points: List<Sample>, maxPoints: Int): List<Sample> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val last = points.lastIndex
        val out = ArrayList<Sample>(maxPoints)
        var prev = -1
        for (i in 0 until maxPoints) {
            val idx = (i.toLong() * last / (maxPoints - 1)).toInt()
            if (idx == prev) continue
            out += points[idx]
            prev = idx
        }
        if (out.last().tMs != points.last().tMs || out.last().value != points.last().value) {
            out[out.lastIndex] = points.last()
        }
        return out
    }

    private fun summarize(closed: List<Trade>): Summary {
        if (closed.isEmpty()) return Summary()
        var wins = 0
        var losses = 0
        var scratches = 0
        var net = 0.0
        var fees = 0.0
        var grossWin = 0.0
        var grossLoss = 0.0
        var best = closed.first().netUsd
        var worst = closed.first().netUsd
        var winStreak = 0
        var lossStreak = 0
        var bestWin = 0
        var bestLoss = 0
        val nets = DoubleArray(closed.size)
        for (i in closed.indices) {
            val v = closed[i].netUsd
            nets[i] = v
            net += v
            fees += closed[i].feeUsd
            if (v > best) best = v
            if (v < worst) worst = v
            when {
                v > 0.0 -> {
                    wins++
                    grossWin += v
                    winStreak++
                    lossStreak = 0
                    if (winStreak > bestWin) bestWin = winStreak
                }
                v < 0.0 -> {
                    losses++
                    grossLoss += -v
                    lossStreak++
                    winStreak = 0
                    if (lossStreak > bestLoss) bestLoss = lossStreak
                }
                else -> {
                    scratches++
                    winStreak = 0
                    lossStreak = 0
                }
            }
        }
        val n = closed.size
        val firstDay = Instant.ofEpochMilli(closed.first().closedAtMs).atZone(ET).toLocalDate()
        val lastDay = Instant.ofEpochMilli(closed.last().closedAtMs).atZone(ET).toLocalDate()
        val span = ChronoUnit.DAYS.between(firstDay, lastDay) + 1
        return Summary(
            n = n,
            wins = wins,
            losses = losses,
            scratches = scratches,
            netUsd = net,
            feesUsd = fees,
            grossUsd = net + fees,
            bestUsd = best,
            worstUsd = worst,
            longestWinStreak = bestWin,
            longestLossStreak = bestLoss,
            profitFactor = profitFactor(grossWin, grossLoss),
            expectancyUsd = net / n,
            avgWinUsd = if (wins == 0) null else grossWin / wins,
            avgLossUsd = if (losses == 0) null else -grossLoss / losses,
            sharpe = sharpe(nets),
            tradesPerDay = n.toDouble() / span.coerceAtLeast(1),
            winRate = wins.toDouble() / n
        )
    }

    private fun profitFactor(grossWin: Double, grossLossAbs: Double): Double? {
        if (grossWin == 0.0 && grossLossAbs == 0.0) return null
        if (grossLossAbs < 1e-12) return if (grossWin > 0.0) Double.POSITIVE_INFINITY else 0.0
        return grossWin / grossLossAbs
    }

    private fun sharpe(nets: DoubleArray): Double? {
        if (nets.size < 2) return null
        var sum = 0.0
        for (v in nets) sum += v
        val mean = sum / nets.size
        var ss = 0.0
        for (v in nets) {
            val d = v - mean
            ss += d * d
        }
        val sd = sqrt(ss / (nets.size - 1))
        if (sd < 1e-12) return null
        return mean / sd
    }

    private fun equity(closed: List<Trade>): List<Sample> = anchored(closed) { it.netUsd }

    private fun anchored(closed: List<Trade>, value: (Trade) -> Double): List<Sample> {
        val pts = cumulative(closed, value)
        if (pts.isEmpty()) return emptyList()
        return listOf(Sample(pts.first().tMs - 1, 0.0)) + pts
    }

    private fun sideEquity(closed: List<Trade>, up: Boolean, span: List<Sample>): List<Sample> {
        if (span.isEmpty()) return emptyList()
        val mine = closed.filter { it.sideUp == up }
        if (mine.isEmpty()) return listOf(span.first(), Sample(span.last().tMs, 0.0))
        return equity(mine)
    }

    private fun cumulative(closed: List<Trade>, value: (Trade) -> Double): List<Sample> {
        if (closed.isEmpty()) return emptyList()
        var acc = 0.0
        return closed.map { t ->
            val v = value(t)
            if (v.isFinite()) acc += v
            Sample(t.closedAtMs, acc)
        }
    }

    private fun drawdown(equity: List<Sample>): List<Sample> {
        if (equity.isEmpty()) return emptyList()
        var peak = equity.first().value
        return equity.map { p ->
            if (p.value > peak) peak = p.value
            Sample(p.tMs, p.value - peak)
        }
    }

    private fun rolling(closed: List<Trade>, window: Int, value: (List<Trade>) -> Double): List<Sample> {
        if (window < 1 || closed.size < window) return emptyList()
        val out = ArrayList<Sample>(closed.size - window + 1)
        for (i in window - 1 until closed.size) {
            val slice = closed.subList(i - window + 1, i + 1)
            out += Sample(closed[i].closedAtMs, value(slice))
        }
        return out
    }

    private fun pnlHistogram(closed: List<Trade>): List<Split> {
        if (closed.isEmpty()) return emptyList()
        val up = IntArray(PNL_LABELS.size)
        val down = IntArray(PNL_LABELS.size)
        for (t in closed) {
            var i = PNL_UPPERS.size
            for (e in PNL_UPPERS.indices) if (t.netUsd < PNL_UPPERS[e]) { i = e; break }
            if (t.sideUp) up[i]++ else down[i]++
        }
        return PNL_LABELS.mapIndexed { i, label -> Split(label, up[i] + down[i], up[i].toDouble(), down[i].toDouble()) }
    }

    private fun byHour(closed: List<Trade>): List<Split> {
        val up = DoubleArray(24)
        val down = DoubleArray(24)
        val n = IntArray(24)
        for (t in closed) {
            val h = Instant.ofEpochMilli(t.entryAtMs).atZone(ET).hour
            n[h]++
            if (t.sideUp) up[h] += t.netUsd else down[h] += t.netUsd
        }
        return (0 until 24).filter { n[it] > 0 }.map { h -> Split(EtTime.hour(h), n[h], up[h], down[h]) }
    }

    private fun daily(closed: List<Trade>): List<Split> {
        if (closed.isEmpty()) return emptyList()
        val order = ArrayList<String>()
        val acc = LinkedHashMap<String, Acc>()
        for (t in closed) {
            val day = DAY.format(Instant.ofEpochMilli(t.closedAtMs).atZone(ET))
            if (day !in acc) order += day
            acc.getOrPut(day) { Acc() }.add(t)
        }
        return order.map { label -> acc.getValue(label).split(label) }
    }

    private fun calibration(closed: List<Trade>): List<CalPoint> {
        val acc = Array(10) { CalAcc() }
        for (t in closed) {
            val p = t.predicted?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: continue
            val idx = ((p * 100.0).toInt().coerceIn(0, 100) / 10).coerceAtMost(9)
            acc[idx].add(p, t.won)
        }
        return acc.mapIndexedNotNull { i, bucket ->
            if (bucket.n == 0) null else CalPoint(
                "${i * 10}–${i * 10 + 10}%",
                bucket.predictedSum / bucket.n,
                bucket.wins.toDouble() / bucket.n,
                bucket.n
            )
        }
    }

    private fun brier(closed: List<Trade>): List<Sample> {
        var sum = 0.0
        var k = 0
        val out = ArrayList<Sample>()
        for (t in closed) {
            val p = t.predicted?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: continue
            val outcome = if (t.won) 1.0 else 0.0
            sum += (p - outcome) * (p - outcome)
            k++
            out += Sample(t.closedAtMs, sum / k)
        }
        return out
    }

    private fun exposure(trades: List<Trade>, nowMs: Long): List<Sample> {
        val events = ArrayList<Event>(trades.size * 2)
        for (t in trades) {
            val stake = t.stakeUsd.takeIf { it.isFinite() && it > 0.0 } ?: continue
            events += Event(t.entryAtMs, stake)
            if (!t.open) events += Event(maxOf(t.closedAtMs, t.entryAtMs), -stake)
        }
        if (events.isEmpty()) return emptyList()
        events.sortWith(compareBy<Event> { it.tMs }.thenBy { if (it.delta >= 0.0) 0 else 1 })
        var acc = 0.0
        val pts = events.map { e ->
            acc += e.delta
            Sample(e.tMs, acc)
        }.toMutableList()
        if (trades.any { it.open && it.stakeUsd > 0.0 }) {
            val tip = maxOf(nowMs, pts.last().tMs)
            if (tip != pts.last().tMs) pts += Sample(tip, acc)
        }
        return pts
    }

    private fun sideSplit(closed: List<Trade>): List<Split> {
        if (closed.isEmpty()) return emptyList()
        var up = 0.0
        var down = 0.0
        for (t in closed) if (t.sideUp) up += t.netUsd else down += t.netUsd
        return listOf(Split("UP / DOWN", closed.size, up, down))
    }

    private fun countSplits(trades: List<Trade>, order: List<String>, labelOf: (Trade) -> String?): List<Split> {
        if (trades.isEmpty()) return emptyList()
        val up = HashMap<String, Int>()
        val down = HashMap<String, Int>()
        for (t in trades) {
            val label = labelOf(t) ?: continue
            if (t.sideUp) up[label] = (up[label] ?: 0) + 1 else down[label] = (down[label] ?: 0) + 1
        }
        val seen = LinkedHashSet<String>()
        seen += order
        seen += up.keys
        seen += down.keys
        return seen.mapNotNull { label ->
            val u = up[label] ?: 0
            val d = down[label] ?: 0
            if (u + d == 0) null else Split(label, u + d, u.toDouble(), d.toDouble())
        }
    }

    private fun bandSplits(trades: List<Trade>, order: List<String>, labelOf: (Trade) -> String?): List<Split> {
        if (trades.isEmpty()) return emptyList()
        val acc = LinkedHashMap<String, Acc>()
        for (t in trades) {
            val label = labelOf(t) ?: continue
            acc.getOrPut(label) { Acc() }.add(t)
        }
        if (acc.isEmpty()) return emptyList()
        val ranked = order.filter { it in acc } + acc.keys.filter { it !in order }
        return ranked.map { acc.getValue(it).split(it) }
    }

    private fun holdBand(holdMs: Long?): String {
        val ms = holdMs?.takeIf { it >= 0L }?.toDouble() ?: return "Unknown"
        return when {
            ms < 30_000.0 -> "<30s"
            ms < 120_000.0 -> "30s–2m"
            ms < 300_000.0 -> "2–5m"
            ms < 600_000.0 -> "5–10m"
            else -> "≥10m"
        }
    }

    private fun confidenceBand(confidence: Double?): String? {
        val c = confidence?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val idx = ((c * 100.0).toInt().coerceIn(0, 100) / 10).coerceAtMost(9)
        return "${idx * 10}–${idx * 10 + 10}%"
    }

    private fun sizeBand(contracts: Int): String? = when {
        contracts <= 0 -> null
        contracts == 1 -> "1"
        contracts <= 5 -> "2–5"
        contracts <= 10 -> "6–10"
        contracts <= 25 -> "11–25"
        contracts <= 50 -> "26–50"
        else -> "≥51"
    }

    private fun unitProb(raw: Double?): Double? {
        val v = raw?.takeIf { it.isFinite() } ?: return null
        val p = if (v > 1.0 + 1e-9) v / 100.0 else v
        return p.takeIf { it in 0.0..1.0 }
    }

    private fun isUp(side: String): Boolean = side.equals("YES", true) || side.equals("UP", true)

    private data class Event(val tMs: Long, val delta: Double)

    private class Acc {
        var n = 0
        var up = 0.0
        var down = 0.0
        fun add(t: Trade) {
            n++
            if (t.sideUp) up += t.netUsd else down += t.netUsd
        }
        fun split(label: String) = Split(label, n, up, down)
    }

    private class CalAcc {
        var n = 0
        var wins = 0
        var predictedSum = 0.0
        fun add(p: Double, won: Boolean) {
            n++
            predictedSum += p
            if (won) wins++
        }
    }

    private val PNL_UPPERS = doubleArrayOf(-2.0, -1.0, -0.25, 0.25, 1.0, 2.0)
    private val PNL_LABELS = listOf("< −$2", "−$2 to −$1", "−$1 to −25¢", "±25¢", "25¢ to $1", "$1 to $2", "≥ $2")
    private val ENTRY_ORDER = (0..19).map { "${it * 5}–${it * 5 + 5}¢" }
    private val TIME_ORDER = listOf("12–15 min", "10–12 min", "8–10 min", "5–8 min", "3–5 min", "1–3 min", "<1 min", "Unknown")
    private val HOLD_ORDER = listOf("<30s", "30s–2m", "2–5m", "5–10m", "≥10m", "Unknown")
    private val EXIT_ORDER = ExitBucket.entries.map { it.label }
    private val CONF_ORDER = (0 until 10).map { "${it * 10}–${it * 10 + 10}%" }
    private val SIZE_ORDER = listOf("1", "2–5", "6–10", "11–25", "26–50", "≥51")
}
