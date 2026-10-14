package com.dirk.kalshiodds.decision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** 0.3.67 incremental per-scalper totals (≈115 rows). Leaderboards read only these. */
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
    val openN: Int = 0
) {
    val winRate: Double? get() = if (n == 0) null else wins.toDouble() / n
    val perTrade: Double? get() = if (n == 0) null else netUsd / n
    /** Normal approx 95% CI of mean $/trade from sum / sumSq — no bootstrap. */
    fun perTradeCi(): Pair<Double, Double>? {
        if (n < 2) return perTrade?.let { it to it }
        val mean = netUsd / n
        val var_ = ((sumSq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0)
        val se = sqrt(var_ / n)
        return (mean - 1.96 * se) to (mean + 1.96 * se)
    }
}

data class ScalpDaily(val scalperId: String, val dayEt: String, val n: Int, val wins: Int, val netUsd: Double, val feesUsd: Double)

/**
 * 0.3.67 persistence: trades live in SQLite; the book keeps only open rows in RAM.
 * All methods are safe to call from a background thread (caller batches off Main).
 */
interface ScalpPersistence {
    fun loadScalps(): List<ScalpTrade>
    fun upsertScalp(trade: ScalpTrade)
    /** Active (non-closed / non-nofill) only — what the in-memory book holds. */
    fun loadActiveScalps(): List<ScalpTrade> = loadScalps().filter {
        it.state != ScalpState.CLOSED && it.state != ScalpState.NO_FILL
    }
    fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) { upsertScalp(trade) }
    fun loadStats(): List<ScalpStat> = emptyList()
    fun loadDaily(dayEt: String): List<ScalpDaily> = emptyList()
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
    @Volatile private var migrated = true

    override fun loadScalps(): List<ScalpTrade> = synchronized(rows) { rows.values.toList() }
    override fun upsertScalp(trade: ScalpTrade) { synchronized(rows) { rows[trade.id] = trade } }
    override fun loadActiveScalps(): List<ScalpTrade> = synchronized(rows) {
        rows.values.filter { it.state != ScalpState.CLOSED && it.state != ScalpState.NO_FILL }
    }
    override fun upsertScalpAndStats(trade: ScalpTrade, prev: ScalpTrade?) {
        synchronized(rows) { rows[trade.id] = trade }
        val sid = ScalpModels.scalperIdOf(trade) ?: return
        val wasClosed = prev?.state == ScalpState.CLOSED
        val nowClosed = trade.state == ScalpState.CLOSED && trade.netUsd != null
        val wasOpen = prev != null && prev.state != ScalpState.CLOSED && prev.state != ScalpState.NO_FILL
        val nowOpen = trade.state != ScalpState.CLOSED && trade.state != ScalpState.NO_FILL
        var s = stats[sid] ?: ScalpStat(sid)
        if (nowOpen && !wasOpen) s = s.copy(openN = s.openN + 1)
        if (!nowOpen && wasOpen) s = s.copy(openN = (s.openN - 1).coerceAtLeast(0))
        if (nowClosed && !wasClosed) {
            val net = trade.netUsd!!
            val fees = trade.entryFeeUsd + trade.exitFeeUsd
            s = s.copy(
                n = s.n + 1,
                wins = s.wins + if (net > 0) 1 else 0,
                netUsd = s.netUsd + net,
                feesUsd = s.feesUsd + fees,
                bestUsd = maxOf(s.bestUsd ?: net, net),
                worstUsd = minOf(s.worstUsd ?: net, net),
                sumSq = s.sumSq + net * net,
                lastClosedAtMs = trade.closedAtMs ?: s.lastClosedAtMs
            )
            val day = ScalpModels.dayEt(trade.closedAtMs ?: 0L)
            val key = "$sid|$day"
            val d = daily[key] ?: ScalpDaily(sid, day, 0, 0, 0.0, 0.0)
            daily[key] = d.copy(n = d.n + 1, wins = d.wins + if (net > 0) 1 else 0, netUsd = d.netUsd + net, feesUsd = d.feesUsd + fees)
        }
        stats[sid] = s
    }
    override fun loadStats(): List<ScalpStat> = stats.values.toList()
    override fun loadDaily(dayEt: String): List<ScalpDaily> = daily.values.filter { it.dayEt == dayEt }
    override fun pageTrades(scalperId: String, afterClosedAtMs: Long?, afterId: String?, limit: Int): List<ScalpTrade> =
        synchronized(rows) {
            rows.values.asSequence()
                .filter { ScalpModels.scalperIdOf(it) == scalperId && it.state == ScalpState.CLOSED }
                .sortedWith(compareByDescending<ScalpTrade> { it.closedAtMs ?: 0L }.thenByDescending { it.id })
                .filter {
                    if (afterClosedAtMs == null) true
                    else {
                        val c = it.closedAtMs ?: 0L
                        c < afterClosedAtMs || (c == afterClosedAtMs && it.id < (afterId ?: ""))
                    }
                }
                .take(limit).toList()
        }
    override fun unsyncedClosed(limit: Int): List<ScalpTrade> = synchronized(rows) {
        rows.values.filter { it.state == ScalpState.CLOSED && it.isPrimary }.sortedBy { it.closedAtMs }.take(limit)
    }
    override fun markSynced(ids: List<String>) {}
    override fun tradeCount(): Int = synchronized(rows) { rows.size }
    override fun statsMigrated(): Boolean = migrated
    /** Test helper: bulk insert rows and rebuild totals. */
    fun seed(list: List<ScalpTrade>) {
        list.forEach { upsertScalpAndStats(it, null) }
    }
}
