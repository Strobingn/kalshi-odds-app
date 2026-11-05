package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.arb.scan.ScanResult
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.signal.model.SignalAlert
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Pure numbers behind the charts on Signal history, Data and Arb Hunter (ThinScreenCharts.kt). Kept apart from the
 * composables so they can be unit-tested without a device.
 */
object ThinScreenChartData {

    private val ET: ZoneId = ZoneId.of("America/New_York")
    private const val HOUR_MS = 3_600_000L

    /** One column of the signals-per-hour chart: how many leaned UP and how many leaned DOWN in that hour. */
    data class HourBucket(val label: String, val up: Int, val down: Int)

    data class SignalStats(
        val total: Int,
        val up: Int,
        val down: Int,
        val passed: Int,
        /** Mean |fair − market| in percentage points; 0 with no signals. */
        val avgEdgePp: Double,
        /** Mean confidence (0–1) over signals that carry one; null when none do. */
        val avgConfidence: Double?,
        /** Oldest → newest, the last [HOURS] hours ending with the current one. */
        val hourly: List<HourBucket>,
        /** |edge| of each signal, oldest → newest (at most [EDGE_POINTS]). */
        val edgeSeries: List<Double>,
        /** Regime → share of signals, largest first (at most 5). Signals with no regime are left out. */
        val regimes: List<Pair<String, Double>>
    )

    const val HOURS = 12
    const val EDGE_POINTS = 60

    fun signalStats(alerts: List<SignalAlert>, nowMs: Long, zone: ZoneId = ET): SignalStats {
        val up = alerts.count { !it.predictedSide.equals("NO", ignoreCase = true) }
        val confs = alerts.mapNotNull { it.confidence }.filter { it.isFinite() }
        val currentHour = nowMs - Math.floorMod(nowMs, HOUR_MS)
        val firstHour = currentHour - (HOURS - 1) * HOUR_MS
        val ups = IntArray(HOURS)
        val downs = IntArray(HOURS)
        for (a in alerts) {
            if (a.createdAtMs < firstHour || a.createdAtMs >= currentHour + HOUR_MS) continue
            val i = ((a.createdAtMs - firstHour) / HOUR_MS).toInt()
            if (a.predictedSide.equals("NO", ignoreCase = true)) downs[i]++ else ups[i]++
        }
        val hourly = (0 until HOURS).map { i ->
            val h = Instant.ofEpochMilli(firstHour + i * HOUR_MS).atZone(zone).hour
            // Every third label, so twelve fit on a phone.
            HourBucket(if (i % 3 == 0) hourLabel(h) else "", ups[i], downs[i])
        }
        val edges = alerts.sortedBy { it.createdAtMs }
            .map { abs(it.deltaPp) }
            .filter { it.isFinite() }
            .takeLast(EDGE_POINTS)
        val withRegime = alerts.mapNotNull { it.regime?.takeIf { r -> r.isNotBlank() } }
        val regimes = if (withRegime.isEmpty()) emptyList() else withRegime.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value.toDouble() / withRegime.size }
        return SignalStats(
            total = alerts.size,
            up = up,
            down = alerts.size - up,
            passed = alerts.count { it.passedFilter },
            avgEdgePp = if (edges.isEmpty()) 0.0 else edges.average(),
            avgConfidence = if (confs.isEmpty()) null else confs.average(),
            hourly = hourly,
            edgeSeries = edges,
            regimes = regimes
        )
    }

    data class SettledStats(
        /** Oldest → newest settled results, true = YES (up). At most [STRIP] windows. */
        val strip: List<Boolean>,
        val yes: Int,
        val no: Int,
        /** Signed length of the run the newest window belongs to: +3 = three YES in a row, −2 = two NO. */
        val currentStreak: Int,
        val longestYes: Int,
        val longestNo: Int,
        /** Per ET hour of day (0–23): share that settled YES and how many windows closed in that hour. */
        val yesRateByHour: List<Pair<Double, Int>>
    )

    const val STRIP = 96

    /** [rows] in any order (the archive hands them newest first). Rows with no YES/NO result are skipped. */
    fun settledStats(rows: List<SettledWindowRow>, zone: ZoneId = ET): SettledStats {
        val known = rows.mapNotNull { r ->
            val yes = when (r.result.trim().lowercase()) {
                "yes" -> true
                "no" -> false
                else -> return@mapNotNull null
            }
            (r.closeMs ?: 0L) to yes
        }.sortedBy { it.first }
        val results = known.map { it.second }
        var longestYes = 0
        var longestNo = 0
        var run = 0
        var prev: Boolean? = null
        for (r in results) {
            run = if (r == prev) run + 1 else 1
            prev = r
            if (r) longestYes = maxOf(longestYes, run) else longestNo = maxOf(longestNo, run)
        }
        val current = when (prev) {
            null -> 0
            true -> run
            false -> -run
        }
        val yesByHour = IntArray(24)
        val nByHour = IntArray(24)
        for ((closeMs, yes) in known) {
            if (closeMs <= 0L) continue
            val h = Instant.ofEpochMilli(closeMs).atZone(zone).hour
            nByHour[h]++
            if (yes) yesByHour[h]++
        }
        return SettledStats(
            strip = results.takeLast(STRIP),
            yes = results.count { it },
            no = results.count { !it },
            currentStreak = current,
            longestYes = longestYes,
            longestNo = longestNo,
            yesRateByHour = (0 until 24).map { h ->
                (if (nByHour[h] == 0) 0.0 else yesByHour[h].toDouble() / nByHour[h]) to nByHour[h]
            }
        )
    }

    /** One bar of the arb "how close to free money" chart: cents per set after fees, positive = a real edge. */
    data class ArbGap(val label: String, val edgeCentsPerSet: Double, val profitable: Boolean)

    /** The real opportunities first, then the closest near misses, [limit] in all. */
    fun arbGaps(result: ScanResult, limit: Int = 8): List<ArbGap> =
        (result.opportunities + result.nearMisses.sortedBy { it.shortfallPerSetCents })
            .filter { it.shortfallPerSetCents.isFinite() }
            .take(limit)
            .map { o ->
                val name = o.structure.eventTitle.ifBlank { o.structure.eventTicker }
                ArbGap(name.take(28), -o.shortfallPerSetCents, o.isProfitable)
            }

    fun hourLabel(h: Int): String = when {
        h == 0 -> "12a"
        h < 12 -> "${h}a"
        h == 12 -> "12p"
        else -> "${h - 12}p"
    }
}
