package com.dirk.kalshiodds.decision

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** 0.3.67/0.3.68 incremental per-scalper totals (≈115 rows). Leaderboards / cards read only these. */
data class ScalpStat(
    val scalperId: String,
    val n: Int = 0,
    val wins: Int = 0,
    val netUsd: Double = 0.0,
    val feesUsd: Double = 0.0,
    val bestUsd: Double? = null,
    val worstUsd: Double? = null,
    val sumSq: Double = 0.0,
    val lastClosedAtMs: Long? = null,
    val openN: Int = 0,
    // 0.3.68
    val sumWin: Double = 0.0,
    /** net ≤ 0 (avg-loss bucket, as 0.3.52 cards). */
    val sumLoss: Double = 0.0,
    val nLoss: Int = 0,
    /** net < 0 strictly (gross loss / losses count). */
    val negSum: Double = 0.0,
    val negN: Int = 0,
    val costSum: Double = 0.0,
    val stopSavedSum: Double = 0.0,
    val stopSavedN: Int = 0,
    val noFills: Int = 0
) {
    val winRate: Double? get() = if (n == 0) null else wins.toDouble() / n
    val perTrade: Double? get() = if (n == 0) null else netUsd / n
    val avgWin: Double? get() = if (wins == 0) null else sumWin / wins
    val avgLoss: Double? get() = if (nLoss == 0) null else sumLoss / nLoss
    val roi: Double? get() = if (costSum > 0.0) netUsd / costSum else null
    /** Normal approx 95% CI of mean $/trade from sum / sumSq — no bootstrap. */
    fun perTradeCi(): Pair<Double, Double>? {
        if (n < 2) return perTrade?.let { it to it }
        val mean = netUsd / n
        val v = ((sumSq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0)
        val se = sqrt(v / n)
        return (mean - 1.96 * se) to (mean + 1.96 * se)
    }
}

data class ScalpDaily(
    val scalperId: String, val dayEt: String, val n: Int, val wins: Int, val netUsd: Double, val feesUsd: Double,
    val sumSq: Double = 0.0, val sumWin: Double = 0.0, val sumLoss: Double = 0.0, val nLoss: Int = 0,
    val negSum: Double = 0.0, val negN: Int = 0, val costSum: Double = 0.0, val best: Double? = null, val worst: Double? = null
) {
    fun asStat(openN: Int = 0) = ScalpStat(scalperId, n, wins, netUsd, feesUsd, best, worst, sumSq, null, openN,
        sumWin, sumLoss, nLoss, negSum, negN, costSum)
}

data class ScalpExitAgg(val scalperId: String, val reason: String, val n: Int)
data class ScalpWindowAgg(val windowKey: String, val scalperId: String, val n: Int, val netUsd: Double, val closeMs: Long?)

/** 0.3.68: everything one close contributes to the aggregate tables (pure; both stores apply it). */
data class ScalpCloseDelta(
    val sid: String, val net: Double, val fees: Double, val cost: Double, val win: Boolean,
    val stopSaved: Double?, val reason: String, val day: String, val windowKey: String, val windowCloseMs: Long?, val closedAtMs: Long
) {
    companion object {
        private val NOSTOP_RX = Regex("nostop=([+-]?[0-9]+\\.[0-9]+)")
        fun of(t: ScalpTrade): ScalpCloseDelta? {
            if (t.state != ScalpState.CLOSED) return null
            val net = t.netUsd?.takeIf { it.isFinite() } ?: return null
            val sid = ScalpModels.scalperIdOf(t) ?: return null
            val ns = NOSTOP_RX.find(t.note)?.groupValues?.get(1)?.toDoubleOrNull()
            return ScalpCloseDelta(sid, net, t.entryFeeUsd + t.exitFeeUsd, t.entryCostUsd, net > 0.0, ns?.let { net - it },
                ScalpModels.normExit(t.exitReason), ScalpModels.dayEt(t.closedAtMs ?: 0L), t.windowKey, ScalpTicker.closeMs(t.ticker), t.closedAtMs ?: 0L)
        }
    }
}

/**
 * 0.3.67 persistence: trades live in SQLite; the book keeps only open rows in RAM.
 * All methods are safe to call from a background thread (caller batches off Main).
 */
interface ScalpPersistence {
    fun loadScalps(): List<ScalpTrade>
    fun upsertScalp(trade: ScalpTrade)
    fun loadActiveScalps(): List<ScalpTrade> = loadScalps().filter { it.state != ScalpState.CLOSED && it.state != ScalpState.NO_FILL }
    fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) { upsertScalp(trade) }
    fun windowTrades(ticker: String, limit: Int = 2000): List<ScalpTrade> = loadScalps().filter { it.ticker.equals(ticker, true) && it.isPrimary }.sortedByDescending { it.signalAtMs }.take(limit.coerceIn(1, 5000))
    fun loadStats(): List<ScalpStat> = emptyList()
    fun loadDaily(dayEt: String): List<ScalpDaily> = emptyList()
    fun loadExits(): List<ScalpExitAgg> = emptyList()
    /** Newest [limitWindows] windows' per-scalper aggregates. */
    fun loadWindows(limitWindows: Int = 48): List<ScalpWindowAgg> = emptyList()
    fun pageTrades(scalperId: String, afterClosedAtMs: Long?, afterId: String?, limit: Int): List<ScalpTrade> = emptyList()
    fun unsyncedClosed(limit: Int): List<ScalpTrade> = emptyList()
    fun markSynced(ids: List<String>) {}
    fun migrateStatsChunk(limit: Int): Int = 0
    fun tradeCount(): Int = 0
    fun statsMigrated(): Boolean = true
}

class InMemoryScalpPersistence : ScalpPersistence {
    private val rows = LinkedHashMap<String, ScalpTrade>()
    private val stats = ConcurrentHashMap<String, ScalpStat>()
    private val daily = ConcurrentHashMap<String, ScalpDaily>()
    private val exits = ConcurrentHashMap<String, ScalpExitAgg>()
    private val windows = ConcurrentHashMap<String, ScalpWindowAgg>()

    override fun loadScalps(): List<ScalpTrade> = synchronized(rows) { rows.values.toList() }
    override fun upsertScalp(trade: ScalpTrade) { synchronized(rows) { rows[trade.id] = trade } }
    override fun loadActiveScalps(): List<ScalpTrade> = synchronized(rows) {
        rows.values.filter { it.state != ScalpState.CLOSED && it.state != ScalpState.NO_FILL }
    }
    override fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) {
        val before = synchronized(rows) { val p = rows[trade.id]; rows[trade.id] = trade; p } ?: prev
        val sid = ScalpModels.scalperIdOf(trade) ?: return
        val wasOpen = before != null && before.state != ScalpState.CLOSED && before.state != ScalpState.NO_FILL
        val nowOpen = trade.state != ScalpState.CLOSED && trade.state != ScalpState.NO_FILL
        var s = stats[sid] ?: ScalpStat(sid)
        if (nowOpen && !wasOpen) s = s.copy(openN = s.openN + 1)
        if (!nowOpen && wasOpen) s = s.copy(openN = (s.openN - 1).coerceAtLeast(0))
        if (trade.state == ScalpState.NO_FILL && before?.state != ScalpState.NO_FILL) s = s.copy(noFills = s.noFills + 1)
        val d = if (before?.state != ScalpState.CLOSED) ScalpCloseDelta.of(trade) else null
        if (d != null) {
            s = ScalpAggMath.applyStat(s, d)
            val dk = "${d.sid}|${d.day}"
            daily[dk] = ScalpAggMath.applyDaily(daily[dk] ?: ScalpDaily(d.sid, d.day, 0, 0, 0.0, 0.0), d)
            val ek = "${d.sid}|${d.reason}"
            exits[ek] = (exits[ek] ?: ScalpExitAgg(d.sid, d.reason, 0)).let { it.copy(n = it.n + 1) }
            val wk = "${d.windowKey}|${d.sid}"
            windows[wk] = (windows[wk] ?: ScalpWindowAgg(d.windowKey, d.sid, 0, 0.0, d.windowCloseMs)).let { it.copy(n = it.n + 1, netUsd = it.netUsd + d.net) }
        }
        stats[sid] = s
    }
    override fun loadStats(): List<ScalpStat> = stats.values.toList()
    override fun loadDaily(dayEt: String): List<ScalpDaily> = daily.values.filter { it.dayEt == dayEt }
    override fun loadExits(): List<ScalpExitAgg> = exits.values.toList()
    override fun loadWindows(limitWindows: Int): List<ScalpWindowAgg> {
        val keys = windows.values.map { it.windowKey to (it.closeMs ?: 0L) }.distinct().sortedByDescending { it.second }.take(limitWindows).map { it.first }.toSet()
        return windows.values.filter { it.windowKey in keys }
    }
    override fun pageTrades(scalperId: String, afterClosedAtMs: Long?, afterId: String?, limit: Int): List<ScalpTrade> =
        synchronized(rows) {
            rows.values.asSequence()
                .filter { ScalpModels.scalperIdOf(it) == scalperId && it.state == ScalpState.CLOSED }
                .sortedWith(compareByDescending<ScalpTrade> { it.closedAtMs ?: 0L }.thenByDescending { it.id })
                .filter { afterClosedAtMs == null || (it.closedAtMs ?: 0L) < afterClosedAtMs || ((it.closedAtMs ?: 0L) == afterClosedAtMs && it.id < (afterId ?: "")) }
                .take(limit).toList()
        }
    override fun unsyncedClosed(limit: Int): List<ScalpTrade> = synchronized(rows) {
        rows.values.filter { it.state == ScalpState.CLOSED && it.isPrimary }.sortedBy { it.closedAtMs }.take(limit)
    }
    override fun tradeCount(): Int = synchronized(rows) { rows.size }
    /** Test helper: bulk insert rows and build totals. */
    fun seed(list: List<ScalpTrade>) { list.forEach { upsertScalpAndStats(it, null) } }
}

object ScalpAggMath {
    fun applyStat(s: ScalpStat, d: ScalpCloseDelta): ScalpStat = s.copy(
        n = s.n + 1, wins = s.wins + if (d.win) 1 else 0, netUsd = s.netUsd + d.net, feesUsd = s.feesUsd + d.fees,
        bestUsd = maxOf(s.bestUsd ?: d.net, d.net), worstUsd = minOf(s.worstUsd ?: d.net, d.net), sumSq = s.sumSq + d.net * d.net,
        lastClosedAtMs = maxOf(s.lastClosedAtMs ?: 0L, d.closedAtMs),
        sumWin = s.sumWin + if (d.win) d.net else 0.0,
        sumLoss = s.sumLoss + if (!d.win) d.net else 0.0, nLoss = s.nLoss + if (!d.win) 1 else 0,
        negSum = s.negSum + if (d.net < 0) d.net else 0.0, negN = s.negN + if (d.net < 0) 1 else 0,
        costSum = s.costSum + d.cost,
        stopSavedSum = s.stopSavedSum + (d.stopSaved ?: 0.0), stopSavedN = s.stopSavedN + if (d.stopSaved != null) 1 else 0
    )
    fun applyDaily(x: ScalpDaily, d: ScalpCloseDelta): ScalpDaily = x.copy(
        n = x.n + 1, wins = x.wins + if (d.win) 1 else 0, netUsd = x.netUsd + d.net, feesUsd = x.feesUsd + d.fees,
        sumSq = x.sumSq + d.net * d.net, sumWin = x.sumWin + if (d.win) d.net else 0.0,
        sumLoss = x.sumLoss + if (!d.win) d.net else 0.0, nLoss = x.nLoss + if (!d.win) 1 else 0,
        negSum = x.negSum + if (d.net < 0) d.net else 0.0, negN = x.negN + if (d.net < 0) 1 else 0,
        costSum = x.costSum + d.cost, best = maxOf(x.best ?: d.net, d.net), worst = minOf(x.worst ?: d.net, d.net)
    )
}

