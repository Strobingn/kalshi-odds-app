package com.dirk.kalshiodds.signal.scalper.fleet

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Macro releases scalper 18 trades around (US Eastern time). Sources: BLS release calendars (bls.gov/schedule/2026,
 * 2027: CPI and the Employment Situation at 8:30 AM) and the Federal Reserve's FOMC calendar (statement at 2:00 PM on
 * the meeting's second day), read 2026-10-23.
 */
object MacroCalendar {
    private val ET: ZoneId = ZoneId.of("America/New_York")

    private fun at(date: String, hh: Int, mm: Int, name: String): Pair<Long, String> =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hh, mm), ET).toInstant().toEpochMilli() to name

    val EVENTS: List<Pair<Long, String>> by lazy {
        val cpi = listOf(
            "2026-11-10", "2026-12-10", "2027-01-13", "2027-02-11", "2027-03-10", "2027-04-13", "2027-05-12", "2027-06-10",
            "2027-07-14", "2027-08-11", "2027-09-14", "2027-10-14", "2027-11-10", "2027-12-10"
        ).map { at(it, 8, 30, "CPI") }
        val jobs = listOf(
            "2026-11-06", "2026-12-04", "2027-01-08", "2027-02-05", "2027-03-05", "2027-04-02", "2027-05-07", "2027-06-04",
            "2027-07-02", "2027-08-06", "2027-09-03", "2027-10-08", "2027-11-05", "2027-12-03"
        ).map { at(it, 8, 30, "Jobs report") }
        val fomc = listOf(
            "2026-10-28", "2026-12-09", "2027-01-27", "2027-03-17", "2027-04-28", "2027-06-09", "2027-07-28", "2027-09-15",
            "2027-10-27", "2027-12-08"
        ).map { at(it, 14, 0, "FOMC") }
        (cpi + jobs + fomc).sortedBy { it.first }
    }

    /** The latest release at or before [nowMs] within [withinMs], or null. */
    fun last(nowMs: Long, withinMs: Long = 15 * 60_000L): Pair<Long, String>? =
        EVENTS.lastOrNull { it.first <= nowMs }?.takeIf { nowMs - it.first <= withinMs }

    /** The next release after [nowMs]. */
    fun next(nowMs: Long): Pair<Long, String>? = EVENTS.firstOrNull { it.first > nowMs }
}

/**
 * Scalper 13's table: ε(P) = quoted YES − how often such YES won, for YES at 10–25¢ with Bitcoin under the strike.
 * Starts from the 10 recorded days (2026-10-03 → 10-12, once a minute in minutes 1–12, `ArenaReplay` data) and adds
 * every settled window the app sees. Thread-safe.
 */
class BehaviourTable(initial: List<DoubleArray>? = null) {
    /** Per 5¢ bucket (10–15, 15–20, 20–25¢): sum of quoted mids, YES wins, observations. */
    private val cells: List<DoubleArray> = initial?.map { it.copyOf() } ?: PRIOR.map { it.copyOf() }
    private val lock = Any()

    fun bucket(p: Double): Int? = if (p < 0.10 || p > 0.25) null else ((p - 0.10) / 0.05).toInt().coerceIn(0, 2)

    fun epsilon(p: Double): Double? = synchronized(lock) {
        val c = cells[bucket(p) ?: return null]
        if (c[2] < MIN_N) null else c[0] / c[2] - c[1] / c[2]
    }

    /** One observation: YES quoted at [p] (under the strike), and whether the window settled YES. */
    fun add(p: Double, won: Boolean) = synchronized(lock) {
        val c = cells[bucket(p) ?: return]
        c[0] += p; c[1] += if (won) 1.0 else 0.0; c[2] += 1.0
    }

    fun snapshot(): List<DoubleArray> = synchronized(lock) { cells.map { it.copyOf() } }

    companion object {
        const val MIN_N = 100.0
        /** From the recorded days: 10–15¢ mean 12.32¢, won 7.90% (n 443); 15–20¢ 17.35¢ vs 19.13% (413); 20–25¢ 22.70¢ vs 22.28% (561). */
        val PRIOR: List<DoubleArray> = listOf(
            doubleArrayOf(0.1232 * 443, 35.0, 443.0),
            doubleArrayOf(0.1735 * 413, 79.0, 413.0),
            doubleArrayOf(0.2270 * 561, 125.0, 561.0)
        )
    }
}

/** Hour-of-day volatility (scalper 6) and 15-minute returns (scalper 8), from candles. Pure. */
object FleetSeries {
    /**
     * From 5-minute closes (open time s, close) over several days: per UTC hour, σ of 5-minute log returns, divided by
     * σ over all hours (ω_h), and the all-hours σ per √second. Hours with under 24 returns are left out.
     */
    fun hourVol(closes: List<Pair<Long, Double>>): Pair<DoubleArray, Double>? {
        val ss = DoubleArray(24)
        val n = IntArray(24)
        var all = 0.0
        var nAll = 0
        for (i in 1 until closes.size) {
            val (t0, c0) = closes[i - 1]
            val (t1, c1) = closes[i]
            if (t1 - t0 != 300L || c0 <= 0 || c1 <= 0) continue
            val r = ln(c1 / c0)
            val h = ((t1 / 3600) % 24).toInt()
            ss[h] += r * r; n[h]++
            all += r * r; nAll++
        }
        if (nAll < 200) return null
        val sAll = sqrt(all / nAll)
        val omega = DoubleArray(24) { h -> if (n[h] < 24) Double.NaN else sqrt(ss[h] / n[h]) / sAll }
        return omega to sAll / sqrt(300.0)
    }

    /** 15-minute closes → simple returns, oldest first, keyed to the window's close (open time + 900 s). */
    fun windowReturns(closes: List<Pair<Long, Double>>): List<Pair<Long, Double>> =
        (1 until closes.size).mapNotNull { i ->
            val (t0, c0) = closes[i - 1]
            val (t1, c1) = closes[i]
            if (t1 - t0 != 900L || c0 <= 0) null else (t1 + 900L) to (c1 / c0 - 1)
        }

    /** Returns of [coin] aligned with BTC's window closes (both present), oldest first, last [n]. */
    fun aligned(btc: List<Pair<Long, Double>>, alt: List<Pair<Long, Double>>, n: Int = 40): Pair<DoubleArray, DoubleArray> {
        val byT = alt.toMap()
        val pairs = btc.mapNotNull { (t, r) -> byT[t]?.let { r to it } }.takeLast(n)
        return pairs.map { it.first }.toDoubleArray() to pairs.map { it.second }.toDoubleArray()
    }
}
