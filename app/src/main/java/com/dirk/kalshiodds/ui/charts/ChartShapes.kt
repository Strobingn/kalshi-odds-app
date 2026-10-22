package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.decision.ScalpFills
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Pure chart inputs. Every value comes from a recorded trade, window, bid, or note.
 * Empty output means the screen should say "no data yet" — nothing here is filled in.
 */
object ChartShapes {
    val ET: ZoneId = ChartSeries.ET

    data class Sample(val tMs: Long, val value: Double)
    data class Bin(val label: String, val count: Int)
    data class HourCell(val hour: Int, val net: Double, val trades: Int)
    data class SideCount(val label: String, val up: Int, val down: Int)
    /**
     * One row on the shared YES price axis.
     * [cents] is the YES price (0.1¢ precision). UP bids sit at their own price.
     * A DOWN bid at q is drawn at 100¢ − q, the YES ask that bid implies.
     */
    data class DepthRung(val cents: Double, val upSize: Double, val downSize: Double) {
        fun label(): String = ChartShapes.centLabel(cents)
    }
    data class FlowNode(val title: String, val detail: String)
    data class TweakMark(val label: String, val note: String, val fromCents: Int?, val toCents: Int?)
    data class WindowEvent(val atMs: Long, val kind: String, val up: Boolean, val price: Double, val label: String)
    data class NamedSeries(val name: String, val points: List<Sample>)
    data class AiMarket(val tMs: Long, val aiUp: Double?, val marketUp: Double?)
    data class TradeRollup(
        val equity: List<Sample>,
        val fees: List<Sample>,
        val drawdown: List<Sample>,
        val entryBins: List<Bin>,
        val holdBins: List<Bin>,
        val perHour: List<Bin>,
        val hourPnl: List<HourCell>,
        val sides: List<SideCount>
    )

    private val ENTRY_UPPERS = doubleArrayOf(0.20, 0.40, 0.60, 0.80)
    private val ENTRY_LABELS = listOf("<20¢", "20–40¢", "40–60¢", "60–80¢", "≥80¢")
    private val HOLD_UPPERS = doubleArrayOf(30_000.0, 120_000.0, 300_000.0, 600_000.0)
    private val HOLD_LABELS = listOf("<30s", "30s–2m", "2–5m", "5–10m", "≥10m")
    private val MOVED = Regex("""(\d+)\s*¢\s*(?:→|->|to)\s*(\d+)\s*¢""")

    fun indexAt(x: Float, width: Float, n: Int): Int {
        if (n <= 1 || width <= 0f) return 0
        return ((x / width).coerceIn(0f, 1f) * (n - 1)).toInt().coerceIn(0, n - 1)
    }

    fun hourLabel(hour: Int): String = com.dirk.kalshiodds.domain.EtTime.hourTick(hour)

    fun cumulative(events: List<Pair<Long, Double>>): List<Sample> {
        val clean = events.filter { it.second.isFinite() }.sortedBy { it.first }
        if (clean.isEmpty()) return emptyList()
        var acc = 0.0
        return clean.map { (t, v) -> acc += v; Sample(t, acc) }
    }

    /** Peak-to-current, zero or negative. Same timestamps as [equity]. */
    fun drawdown(equity: List<Sample>): List<Sample> {
        val pts = equity.filter { it.value.isFinite() }
        if (pts.isEmpty()) return emptyList()
        var peak = pts.first().value
        return pts.map { p ->
            if (p.value > peak) peak = p.value
            Sample(p.tMs, p.value - peak)
        }
    }

    fun spotSamples(points: List<BidPoint>): List<Sample> = points.mapNotNull { p ->
        p.spotUsd?.takeIf { it.isFinite() && it > 0.0 }?.let { Sample(p.tMs, it) }
    }

    fun bidSamples(points: List<BidPoint>, up: Boolean): List<Sample> = points.mapNotNull { p ->
        val c = if (up) p.upBidCents else p.downBidCents
        c?.takeIf { it.isFinite() && it in 0f..100f }?.let { Sample(p.tMs, it.toDouble()) }
    }

    /** AI fair on the same timestamps as the market prints. Missing fair is skipped, not invented. */
    fun aiSamples(points: List<BidPoint>): List<Sample> = points.mapNotNull { p ->
        p.aiYesPp?.takeIf { it.isFinite() && it in 0f..100f }?.let { Sample(p.tMs, it.toDouble()) }
    }

    /**
     * Lag of the AI print against the market print it sits on.
     * Fair is stored on the bid row, so the lag is zero when both exist.
     */
    fun aiLagMs(points: List<BidPoint>): Long? {
        val paired = points.filter { it.aiYesPp != null && (it.upBidCents != null || it.downBidCents != null) }
        if (paired.isEmpty()) return null
        return 0L
    }

    fun latestBidCents(points: List<BidPoint>, up: Boolean): Double? = bidSamples(points, up).lastOrNull()?.value

    /**
     * One combined equity line, then one line per model that actually booked a finite net.
     * Each line starts at $0 one window before the first recorded close.
     */
    fun equityFromWindows(
        windows: List<ScalpModels.WindowRow>,
        nowMs: Long,
        models: List<ScalpModels.Model>
    ): List<NamedSeries> {
        if (models.isEmpty()) return emptyList()
        val wanted = models.toSet()
        val recorded = windows.filter { w ->
            val close = w.closeMs
            close != null && close <= nowMs && w.netByModel.keys.any { it in wanted }
        }.sortedBy { it.closeMs }
        if (recorded.isEmpty()) return emptyList()
        val t0 = recorded.first().closeMs!! - 900_000L
        fun line(name: String, step: (ScalpModels.WindowRow) -> Double): NamedSeries {
            var acc = 0.0
            val pts = ArrayList<Sample>(recorded.size + 1)
            pts += Sample(t0, 0.0)
            for (w in recorded) {
                val v = step(w)
                if (v.isFinite()) acc += v
                pts += Sample(w.closeMs!!, acc)
            }
            return NamedSeries(name, pts)
        }
        val out = ArrayList<NamedSeries>(models.size + 1)
        out += line("Combined") { w ->
            w.netByModel.filter { it.key in wanted && it.value.isFinite() }.values.sum()
        }
        for (m in models) {
            if (recorded.none { it.netByModel[m]?.isFinite() == true }) continue
            out += line(m.label) { w -> w.netByModel[m]?.takeIf { it.isFinite() } ?: 0.0 }
        }
        return out
    }

    fun windowHourPnl(
        windows: List<ScalpModels.WindowRow>,
        nowMs: Long,
        models: List<ScalpModels.Model>,
        zone: ZoneId = ET
    ): List<HourCell> {
        val wanted = models.toSet()
        val events = windows.mapNotNull { w ->
            val close = w.closeMs ?: return@mapNotNull null
            if (close > nowMs || w.netByModel.keys.none { it in wanted }) return@mapNotNull null
            close to w.netByModel.filter { it.key in wanted && it.value.isFinite() }.values.sum()
        }
        return pnlByHour(events, zone)
    }

    /** One count per recorded round trip, stamped at that window's close. */
    fun windowTradesPerHour(
        windows: List<ScalpModels.WindowRow>,
        nowMs: Long,
        models: List<ScalpModels.Model>,
        zone: ZoneId = ET
    ): List<Bin> {
        val wanted = models.toSet()
        val times = ArrayList<Long>()
        for (w in windows) {
            val close = w.closeMs ?: continue
            if (close > nowMs) continue
            val trips = w.tripsByModel.filter { it.key in wanted }.values.sum()
            repeat(trips.coerceAtLeast(0)) { times += close }
        }
        return tradesPerHour(times, zone)
    }

    fun histogram(values: List<Double>, uppersExclusive: DoubleArray, labels: List<String>): List<Bin> {
        require(labels.size == uppersExclusive.size + 1)
        val clean = values.filter { it.isFinite() }
        if (clean.isEmpty()) return emptyList()
        val counts = IntArray(labels.size)
        for (v in clean) {
            var i = uppersExclusive.size
            for (e in uppersExclusive.indices) {
                if (v < uppersExclusive[e]) { i = e; break }
            }
            counts[i]++
        }
        return labels.mapIndexed { i, label -> Bin(label, counts[i]) }
    }

    fun entryPriceBins(prices: List<Double>): List<Bin> =
        histogram(prices.filter { it.isFinite() && it in 0.0..1.0 }, ENTRY_UPPERS, ENTRY_LABELS)

    fun holdBins(holdMs: List<Double>): List<Bin> =
        histogram(holdMs.filter { it.isFinite() && it >= 0.0 }, HOLD_UPPERS, HOLD_LABELS)

    fun tradesPerHour(times: List<Long>, zone: ZoneId = ET): List<Bin> {
        if (times.isEmpty()) return emptyList()
        val counts = IntArray(24)
        for (t in times) counts[Instant.ofEpochMilli(t).atZone(zone).hour]++
        return (0 until 24).map { Bin(hourLabel(it), counts[it]) }
    }

    fun pnlByHour(events: List<Pair<Long, Double>>, zone: ZoneId = ET): List<HourCell> {
        val clean = events.filter { it.second.isFinite() }
        if (clean.isEmpty()) return emptyList()
        val nets = DoubleArray(24)
        val ns = IntArray(24)
        for ((t, v) in clean) {
            val h = Instant.ofEpochMilli(t).atZone(zone).hour
            nets[h] += v
            ns[h]++
        }
        return (0 until 24).map { HourCell(it, nets[it], ns[it]) }
    }

    fun sideCounts(groups: List<Pair<String, List<String>>>): List<SideCount> = groups.mapNotNull { (label, sides) ->
        var up = 0
        var down = 0
        for (s in sides) when {
            s.equals("YES", true) || s.equals("UP", true) -> up++
            s.equals("NO", true) || s.equals("DOWN", true) -> down++
        }
        if (up + down == 0) null else SideCount(label, up, down)
    }

    /**
     * Book price → tenth-of-a-cent index, 1..999 (0.1¢..99.9¢).
     *
     * 0.038 dollars, 3.8 cents, and the same tick written either way land on 38.
     * 38 itself is 38¢ (a cent price), not 3.8¢. Deci-cent integers 101–999
     * follow Kalshi: 380 → 38.0¢. Nothing below half a tenth rounds down to 0.
     */
    fun levelDeci(price: Double): Int? {
        if (!price.isFinite() || price <= 0.0) return null
        val cents = when {
            price <= 1.0 + 1e-9 -> price * 100.0
            price <= 100.0 + 1e-6 -> price
            price < 1000.0 -> price / 10.0
            else -> return null
        }
        if (!cents.isFinite()) return null
        val deci = round(cents * 10.0).toInt()
        return deci.takeIf { it in 1..999 }
    }

    /** "3.8¢", "0.4¢", "74¢". Whole cents drop the decimal. Never "0¢" for 0.4¢. */
    fun centLabel(cents: Double): String {
        if (!cents.isFinite()) return "—"
        val deci = round(cents * 10.0).toInt()
        val whole = deci / 10
        val frac = abs(deci % 10)
        return if (frac == 0) "$whole¢" else "$whole.$frac¢"
    }

    /** YES price opposite a DOWN bid (or the reverse), still on the 0.1¢ grid. */
    fun complementCents(cents: Double): Double {
        val deci = round(cents * 10.0).toInt()
        return (1000 - deci) / 10.0
    }

    /**
     * Ladder rows around the inside market.
     *
     * Prices stay on 0.1¢ when the book has a sub-cent tick, and on whole cents
     * when every level is a whole cent. UP and DOWN share the YES axis: a DOWN
     * bid at 96.1¢ is the row at 3.9¢. The window is the touch, not the eight
     * highest whole cents of each side, so a 4¢ book does not jump from 89¢ to 0¢.
     */
    fun depthRungs(
        yes: List<Pair<Double, Double>>,
        no: List<Pair<Double, Double>>,
        maxLevels: Int = 8
    ): List<DepthRung> {
        val up = aggregateDeci(yes)
        val downNative = aggregateDeci(no)
        if (up.isEmpty() && downNative.isEmpty()) return emptyList()
        val down = HashMap<Int, Double>()
        for ((deci, size) in downNative) {
            val yesAsk = 1000 - deci
            if (yesAsk !in 1..999) continue
            down[yesAsk] = (down[yesAsk] ?: 0.0) + size
        }
        val occupied = up.keys + down.keys
        val step = if (occupied.all { it % 10 == 0 }) 10 else 1
        val bestBid = up.keys.maxOrNull()
        val bestAsk = down.keys.minOrNull()
        val touch = when {
            bestBid != null && bestAsk != null -> (bestBid + bestAsk) / 2.0
            bestBid != null -> bestBid.toDouble()
            else -> bestAsk!!.toDouble()
        }
        fun dist(deci: Int) = abs(deci - touch)
        val keep = LinkedHashSet<Int>()
        val perSide = maxLevels.coerceAtLeast(1)
        up.keys.sortedBy { dist(it) }.take(perSide).forEach { keep.add(it) }
        down.keys.sortedBy { dist(it) }.take(perSide).forEach { keep.add(it) }
        val active = keep.toMutableSet()
        while (active.size > 1 && spanRows(active, step) > DEPTH_MAX_ROWS) {
            active.remove(farthest(active, up, down, ::dist))
        }
        if (active.isEmpty()) return emptyList()
        val lo = (active.min() / step) * step
        val hi = ((active.max() + step - 1) / step) * step
        val rows = ArrayList<DepthRung>((hi - lo) / step + 1)
        var cursor = hi
        while (cursor >= lo) {
            rows += DepthRung(cursor / 10.0, up[cursor] ?: 0.0, down[cursor] ?: 0.0)
            cursor -= step
        }
        while (rows.size > 1 && rows.first().upSize <= 0.0 && rows.first().downSize <= 0.0) rows.removeAt(0)
        while (rows.size > 1 && rows.last().upSize <= 0.0 && rows.last().downSize <= 0.0) rows.removeAt(rows.lastIndex)
        return rows
    }

    /** Largest size on either side. Bar widths share this so a 10k UP bid cannot hide DOWN. */
    fun depthMaxSize(rungs: List<DepthRung>): Double =
        rungs.maxOfOrNull { max(it.upSize, it.downSize) }?.coerceAtLeast(1.0) ?: 1.0

    fun depthBarFraction(size: Double, rungs: List<DepthRung>): Double {
        if (!size.isFinite() || size <= 0.0) return 0.0
        return (size / depthMaxSize(rungs)).coerceIn(0.0, 1.0)
    }

    /** Drag or tap y → row. The top of the ladder is the highest YES price. */
    fun depthRowAt(y: Float, height: Float, rows: Int): Int {
        if (rows <= 0 || height <= 0f) return 0
        return ((y / height) * rows).toInt().coerceIn(0, rows - 1)
    }

    fun depthReadout(rung: DepthRung): String {
        val parts = ArrayList<String>(3)
        parts += rung.label()
        if (rung.upSize > 0.0) parts += "UP ${rung.upSize.roundToInt()}"
        if (rung.downSize > 0.0) parts += "DOWN ${rung.downSize.roundToInt()} (${centLabel(complementCents(rung.cents))} bid)"
        return parts.joinToString(" · ")
    }

    private const val DEPTH_MAX_ROWS = 16

    private fun aggregateDeci(levels: List<Pair<Double, Double>>): Map<Int, Double> {
        val acc = HashMap<Int, Double>()
        for ((price, size) in levels) {
            if (!size.isFinite() || size <= 0.0) continue
            val deci = levelDeci(price) ?: continue
            acc[deci] = (acc[deci] ?: 0.0) + size
        }
        return acc
    }

    private fun spanRows(keys: Set<Int>, step: Int): Int {
        if (keys.isEmpty()) return 0
        val lo = (keys.min() / step) * step
        val hi = ((keys.max() + step - 1) / step) * step
        return (hi - lo) / step + 1
    }

    private fun farthest(
        keys: Set<Int>,
        up: Map<Int, Double>,
        down: Map<Int, Double>,
        dist: (Int) -> Double
    ): Int {
        var victim = keys.first()
        var victimDist = dist(victim)
        var victimSize = (up[victim] ?: 0.0) + (down[victim] ?: 0.0)
        for (deci in keys) {
            val d = dist(deci)
            val size = (up[deci] ?: 0.0) + (down[deci] ?: 0.0)
            if (d > victimDist + 1e-9 || (abs(d - victimDist) <= 1e-9 && size < victimSize)) {
                victim = deci
                victimDist = d
                victimSize = size
            }
        }
        return victim
    }

    fun tweakMark(label: String, note: String): TweakMark? {
        val n = note.trim()
        if (n.isEmpty()) return null
        val m = MOVED.find(n)
        return TweakMark(label, n, m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull())
    }

    fun flowCounts(signaled: Int, entered: Int, exited: Int): List<FlowNode> {
        if (signaled <= 0 && entered <= 0 && exited <= 0) return emptyList()
        return listOf(
            FlowNode("Signal", "$signaled observed"),
            FlowNode("Entry", "$entered filled"),
            FlowNode("Exit", "$exited closed")
        )
    }

    fun flowPath(
        signalAsk: Double?,
        entry: Double?,
        exitPrice: Double?,
        exitReason: String?,
        contracts: Int
    ): List<FlowNode> {
        val nodes = ArrayList<FlowNode>(3)
        if (signalAsk != null && signalAsk.isFinite() && signalAsk in 0.0..1.0) nodes += FlowNode("Signal", cents(signalAsk))
        if (entry != null && entry.isFinite() && entry in 0.0..1.0 && contracts > 0) {
            nodes += FlowNode("Entry", "$contracts ct · ${cents(entry)}")
        }
        if (exitPrice != null && exitPrice.isFinite() && exitPrice in 0.0..1.0) {
            val why = exitReason?.trim()?.takeIf { it.isNotEmpty() }
            nodes += FlowNode("Exit", cents(exitPrice) + (why?.let { " · $it" } ?: ""))
        }
        return nodes
    }

    fun cents(price: Double): String {
        val c = price * 100.0
        return if (abs(c - round(c)) < 0.05) "${round(c).toInt()}¢" else String.format(Locale.US, "%.1f¢", c)
    }

    /** Stored percent or fraction → 0–100. Null when it is not a probability. */
    fun scalePct(raw: Double?): Double? {
        val v = raw?.takeIf { it.isFinite() } ?: return null
        val pct = if (v <= 1.0 + 1e-9) v * 100.0 else v
        return pct.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    /** Picked-side probability → UP chance. */
    fun aiUp(sideYes: Boolean, storedPct: Double?): Double? {
        val p = scalePct(storedPct) ?: return null
        return if (sideYes) p else 100.0 - p
    }

    /**
     * Market percent → UP chance. [entryAsk] (0–1, the side that was bought) tells a side
     * price apart from a YES mid. With no ask, only a YES pick is unambiguous.
     */
    fun marketUp(sideYes: Boolean, marketPct: Double?, entryAsk: Double?): Double? {
        val m = scalePct(marketPct) ?: return null
        val ask = entryAsk?.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100.0)
        if (ask == null) return if (sideYes) m else null
        val asSide = abs(m - ask) <= abs(m - (100.0 - ask))
        val sidePct = if (asSide) m else 100.0 - m
        return if (sideYes) sidePct else 100.0 - sidePct
    }

    fun aiMarket(tMs: Long, sideYes: Boolean, aiPct: Double?, marketPct: Double?, entryAsk: Double?): AiMarket? {
        val ai = aiUp(sideYes, aiPct)
        val mkt = marketUp(sideYes, marketPct, entryAsk)
        if (ai == null && mkt == null) return null
        return AiMarket(tMs, ai, mkt)
    }

    fun windowEvents(startMs: Long, closeMs: Long, trades: List<ScalpTrade>): List<WindowEvent> {
        if (closeMs <= startMs) return emptyList()
        val out = ArrayList<WindowEvent>()
        for (t in trades) {
            val up = !t.side.equals("NO", true)
            if (t.signalAtMs in startMs..closeMs && t.signalAsk.isFinite() && t.signalAsk in 0.0..1.0) {
                out += WindowEvent(t.signalAtMs, "signal", up, t.signalAsk, "Signal")
            }
            val entryAt = t.entryAtMs
            val entryPx = t.entryPrice
            if (entryAt != null && entryPx != null && entryAt in startMs..closeMs && entryPx.isFinite() && entryPx in 0.0..1.0) {
                out += WindowEvent(entryAt, "entry", up, entryPx, "Entry")
            }
            val sales = ScalpFills.sales(t)
            if (sales.isEmpty() && t.soldContracts > 0 && t.closedAtMs != null) {
                val px = t.proceedsUsd / t.soldContracts
                val at = minOf(t.closedAtMs, closeMs)
                if (at in startMs..closeMs && px.isFinite() && px in 0.0..1.0) {
                    out += WindowEvent(at, "exit", up, px, "Exit")
                }
            }
            for (f in sales) {
                val at = if (f.settlement) minOf(f.atMs, closeMs) else f.atMs
                if (at in startMs..closeMs && f.price.isFinite() && f.price in 0.0..1.0) {
                    out += WindowEvent(at, if (f.settlement) "settle" else "exit", up, f.price, if (f.settlement) "Settle" else "Exit")
                }
            }
        }
        return out.sortedBy { it.atMs }
    }

    fun rollup(trades: List<ScalpTrade>): TradeRollup {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
            .sortedBy { it.closedAtMs ?: Long.MAX_VALUE }
        val equity = cumulative(closed.map { (it.closedAtMs ?: it.signalAtMs) to it.netUsd!! })
        val fees = if (closed.isEmpty()) emptyList() else cumulative(
            closed.map { (it.closedAtMs ?: it.signalAtMs) to (it.entryFeeUsd + it.exitFeeUsd) }.filter { it.second.isFinite() }
        )
        val filled = trades.filter { it.state != ScalpState.NO_FILL && it.contracts > 0 && it.entryPrice != null }
        val groups = filled.groupBy { it.algoLabel }.entries
            .sortedBy { it.key }
            .map { (label, rows) -> label to rows.map { it.side } }
        return TradeRollup(
            equity = equity,
            fees = fees,
            drawdown = drawdown(equity),
            entryBins = entryPriceBins(filled.mapNotNull { it.entryPrice }),
            holdBins = holdBins(trades.mapNotNull { it.holdMs?.takeIf { h -> h >= 0L }?.toDouble() }),
            perHour = tradesPerHour(filled.mapNotNull { it.entryAtMs }),
            hourPnl = pnlByHour(closed.map { (it.closedAtMs ?: it.signalAtMs) to it.netUsd!! }),
            sides = sideCounts(groups)
        )
    }
}
