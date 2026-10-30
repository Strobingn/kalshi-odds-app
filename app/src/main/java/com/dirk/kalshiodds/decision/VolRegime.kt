package com.dirk.kalshiodds.decision

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Volatility regime for one coin in one 15-minute window.
 * Realized vol is the sample standard deviation of close-to-close log returns
 * on completed 1-minute bars. The tag compares that vol with the median of
 * earlier windows. With no history yet, the comparison uses [PRIOR_BASELINE].
 * Paper only. One tag per coin per window, not a row per tick.
 */
object VolRegime {
    const val CALM = "calm"
    const val NORMAL = "normal"
    const val CHOPPY = "choppy"
    const val UNKNOWN = "unknown"
    const val MARK = "volRegime="
    const val BUCKET_MARK = "scoreBucket="
    /** Calm when realized vol is under 75% of the baseline. */
    const val CALM_RATIO = 0.75
    /** Choppy when realized vol is over 135% of the baseline. */
    const val CHOPPY_RATIO = 1.35
    /** Cold-start baseline: 8 bps per completed minute. */
    const val PRIOR_BASELINE = 0.0008
    private const val MAX_WINDOWS = 16
    /** One close per minute. Three closed minutes are required, and the book ticks much faster than that. */
    private const val MAX_SPOTS = 40

    private val lock = Any()
    private val spots = HashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private val rvByWindow = LinkedHashMap<String, Double>()
    private val tags = HashMap<String, String>()
    /** Expiry stamp → coin|stamp last written. Gate rows pass the stamp alone. */
    private val latestForStamp = HashMap<String, String>()

    fun reset() = synchronized(lock) {
        spots.clear()
        rvByWindow.clear()
        tags.clear()
        latestForStamp.clear()
    }

    /**
     * BTC and ETH that close on the same stamp must not share a spot series.
     * A ticker becomes `COIN|stamp`. A stamp or an already qualified key is unchanged.
     */
    fun qualify(tickerOrKey: String): String {
        val key = tickerOrKey.trim().uppercase()
        if (key.isBlank() || key.contains('|') || !key.contains('-')) return key
        val coin = ScalpParams.coinOf(key)
        val window = key.substringAfter('-').substringBefore('-')
        return if (window.isBlank()) coin else "$coin|$window"
    }

    private fun stampOf(key: String): String = if (key.contains('|')) key.substringAfter('|') else key

    /** Sample stdev of close-to-close log returns. Null until two returns exist. */
    fun realizedVol(bars: List<HybridQuantGates.MinuteBar>): Double? {
        if (bars.size < 3) return null
        val rets = ArrayList<Double>(bars.size)
        for (i in 1 until bars.size) {
            val a = bars[i - 1].close
            val b = bars[i].close
            if (a <= 0.0 || b <= 0.0 || !a.isFinite() || !b.isFinite()) continue
            val r = ln(b / a)
            if (r.isFinite()) rets += r
        }
        if (rets.size < 2) return null
        val mean = rets.sum() / rets.size
        var acc = 0.0
        for (r in rets) {
            val d = r - mean
            acc += d * d
        }
        val v = acc / (rets.size - 1)
        if (!v.isFinite() || v < 0.0) return null
        return sqrt(v)
    }

    fun tag(rv: Double, baseline: Double): String {
        if (!rv.isFinite() || rv < 0.0 || !baseline.isFinite() || baseline <= 1e-12) return UNKNOWN
        val ratio = rv / baseline
        return when {
            ratio < CALM_RATIO -> CALM
            ratio > CHOPPY_RATIO -> CHOPPY
            else -> NORMAL
        }
    }

    fun current(windowKey: String): String = synchronized(lock) {
        val key = qualify(windowKey)
        tags[key]?.let { return@synchronized it }
        // A bare expiry stamp is what gate rows pass. A ticker that has no tag yet stays unknown.
        if (key.contains('|') || windowKey.contains('-')) return@synchronized UNKNOWN
        val latest = latestForStamp[stampOf(key)] ?: return@synchronized UNKNOWN
        tags[latest] ?: UNKNOWN
    }

    /** One spot on this coin's window. A bar that is still open does not change the tag. */
    fun noteSpot(windowKey: String, atMs: Long, spot: Double?): String {
        val key = qualify(windowKey)
        if (key.isBlank() || spot == null || !spot.isFinite() || spot <= 0.0) {
            if (key.isNotBlank()) synchronized(lock) { latestForStamp[stampOf(key)] = key }
            return current(key)
        }
        val bars = synchronized(lock) {
            val row = spots.getOrPut(key) { ArrayDeque() }
            val last = row.lastOrNull()
            val minute = atMs / HybridQuantGates.MINUTE_MS
            if (last != null && last.first / HybridQuantGates.MINUTE_MS == minute) {
                row.removeLast()
                row.addLast(atMs to spot)
            } else {
                row.addLast(atMs to spot)
                while (row.size > MAX_SPOTS) row.removeFirst()
            }
            if (spots.size > MAX_WINDOWS) {
                val drop = spots.keys.firstOrNull { it != key }
                if (drop != null) spots.remove(drop)
            }
            latestForStamp[stampOf(key)] = key
            HybridQuantGates.completedMinuteBars(row.toList(), atMs)
        }
        return observeBars(key, bars)
    }

    fun observeBars(windowKey: String, bars: List<HybridQuantGates.MinuteBar>): String = synchronized(lock) {
        val key = qualify(windowKey)
        if (key.isBlank()) return@synchronized UNKNOWN
        latestForStamp[stampOf(key)] = key
        val rv = realizedVol(bars)
        if (rv == null) return@synchronized tags[key] ?: UNKNOWN
        val base = baselineLocked(key) ?: PRIOR_BASELINE
        val next = tag(rv, base)
        tags[key] = next
        rvByWindow[key] = rv
        while (rvByWindow.size > MAX_WINDOWS) {
            val eldest = rvByWindow.keys.first()
            rvByWindow.remove(eldest)
        }
        next
    }

    fun stamp(note: String, regime: String): String {
        if (regime != CALM && regime != NORMAL && regime != CHOPPY) return note
        if (note.contains(MARK)) return note
        return if (note.isBlank()) "$MARK$regime" else "$note · $MARK$regime"
    }

    fun stampBucket(note: String, bucket: String): String {
        if (bucket.isBlank() || note.contains(BUCKET_MARK)) return note
        return if (note.isBlank()) "$BUCKET_MARK$bucket" else "$note · $BUCKET_MARK$bucket"
    }

    fun regimeOf(note: String): String? =
        Regex("volRegime=(calm|normal|choppy)").find(note)?.groupValues?.getOrNull(1)

    fun bucketOf(note: String): String? =
        Regex("scoreBucket=([A-Za-z0-9]+)").find(note)?.groupValues?.getOrNull(1)

    private fun baselineLocked(windowKey: String): Double? {
        val prior = rvByWindow.filterKeys { it != windowKey }.values.toList()
        if (prior.size < 3) return null
        val sorted = prior.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

/**
 * Per-scalper paper P&L by volatility regime, plus similar-setup history
 * (same side, score bucket, and regime). Ids are recorded once.
 * Closed trades keep the tag in the existing note. This ledger is the
 * in-memory rollup of those notes, not a new per-tick table.
 */
object VolRegimeLedger {
    data class Bucket(val regime: String, val n: Int, val wins: Int, val netUsd: Double) {
        val winRate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
        val avgPnl: Double get() = if (n == 0) 0.0 else netUsd / n
    }

    data class Similar(val n: Int, val wins: Int, val winRate: Double, val avgPnl: Double)

    private data class Setup(val side: String, val bucket: String, val regime: String, val net: Double)

    private val lock = Any()
    private val seen = HashSet<String>()
    private val byScalper = HashMap<String, LinkedHashMap<String, Bucket>>()
    private val setups = HashMap<String, ArrayList<Setup>>()

    fun reset() = synchronized(lock) {
        seen.clear()
        byScalper.clear()
        setups.clear()
    }

    fun record(scalperId: String, regime: String, netUsd: Double, tradeId: String) {
        if (scalperId.isBlank() || tradeId.isBlank() || !netUsd.isFinite()) return
        if (regime != VolRegime.CALM && regime != VolRegime.NORMAL && regime != VolRegime.CHOPPY) return
        synchronized(lock) {
            if (!seen.add(tradeId)) return
            val row = byScalper.getOrPut(scalperId) { LinkedHashMap() }
            val prev = row[regime]
            val win = if (netUsd > 0.0) 1 else 0
            row[regime] = if (prev == null) Bucket(regime, 1, win, netUsd)
            else prev.copy(n = prev.n + 1, wins = prev.wins + win, netUsd = prev.netUsd + netUsd)
        }
    }

    fun addSetup(tradeId: String, scalperId: String, side: String, bucket: String, regime: String, netUsd: Double) {
        if (scalperId.isBlank() || bucket.isBlank() || side.isBlank() || !netUsd.isFinite()) return
        if (regime != VolRegime.CALM && regime != VolRegime.NORMAL && regime != VolRegime.CHOPPY) return
        synchronized(lock) {
            if (!seen.add("setup:$tradeId")) return
            setups.getOrPut(scalperId) { ArrayList() }.add(Setup(side.uppercase(), bucket, regime, netUsd))
        }
    }

    fun similar(scalperId: String, side: String, bucket: String, regime: String): Similar = synchronized(lock) {
        val rows = setups[scalperId].orEmpty().filter {
            it.side == side.uppercase() && it.bucket == bucket && it.regime == regime
        }
        val n = rows.size
        val wins = rows.count { it.net > 0.0 }
        val avg = if (n == 0) 0.0 else rows.sumOf { it.net } / n
        val rate = if (n == 0) 0.0 else wins.toDouble() / n
        Similar(n, wins, rate, avg)
    }

    fun ingest(trades: List<ScalpTrade>) {
        for (t in trades) {
            if (t.state != ScalpState.CLOSED) continue
            val net = t.netUsd ?: continue
            val regime = VolRegime.regimeOf(t.note) ?: continue
            val id = ScalpModels.scalperIdOf(t) ?: t.variantId
            record(id, regime, net, t.id)
            val bucket = VolRegime.bucketOf(t.note) ?: continue
            addSetup(t.id, id, t.side, bucket, regime, net)
        }
    }

    fun breakdown(scalperId: String): List<Bucket> = synchronized(lock) {
        byScalper[scalperId]?.values?.toList().orEmpty()
    }

    fun lineFor(scalperId: String): String {
        val rows = breakdown(scalperId)
        if (rows.isEmpty()) return ""
        return rows.joinToString(" · ") { b ->
            val pct = if (b.n == 0) 0.0 else 100.0 * b.wins / b.n
            String.format(Locale.US, "%s %d/%d %.0f%% %+.2f", b.regime, b.wins, b.n, pct, b.netUsd)
        }
    }

    fun lines(): List<String> = synchronized(lock) {
        byScalper.keys.sorted().mapNotNull { id ->
            val text = lineFor(id)
            if (text.isBlank()) null else "$id · $text"
        }
    }
}
