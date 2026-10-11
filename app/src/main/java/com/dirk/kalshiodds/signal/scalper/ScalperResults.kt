package com.dirk.kalshiodds.signal.scalper

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything the "Scalper results" screen shows, worked out from the paper
 * record. Pure and unit tested; the composables only lay it out.
 *
 * All dollars are **paper** dollars after fees. A win is a closed scalp that
 * netted more than $0 after fees, a loss one that netted less.
 */
object ScalperResults {

    /** The paper bankroll the chart starts from. The scalper has no real balance; this is a reference line. */
    const val START_BANKROLL_USD = 1_000.0

    data class Headline(
        val netUsd: Double,
        val bankrollUsd: Double,
        val wonUsd: Double,
        val lostUsd: Double,
        val wins: Int,
        val losses: Int,
        val flat: Int,
        val closed: Int,
        val avgWinUsd: Double?,
        val avgLossUsd: Double?,
        val biggestWinUsd: Double,
        val biggestLossUsd: Double,
        val feesUsd: Double,
        val winRate: Double?
    )

    data class Row(val label: String, val stats: ScalpStats)

    fun headline(state: ScalperState): Headline {
        val a = state.stats(ScalperState.ALL)
        return Headline(
            netUsd = a.pnlUsd,
            bankrollUsd = START_BANKROLL_USD + a.pnlUsd,
            wonUsd = a.wonUsd,
            lostUsd = a.lostUsd,
            wins = a.wins,
            losses = a.losses,
            flat = a.flat,
            closed = a.closed,
            avgWinUsd = a.avgWinUsd,
            avgLossUsd = a.avgLossUsd,
            biggestWinUsd = a.biggestWinUsd,
            biggestLossUsd = a.biggestLossUsd,
            feesUsd = a.feesUsd,
            winRate = a.winRate
        )
    }

    /** Strategies that have closed a scalp, best net first. */
    fun byStrategy(state: ScalperState): List<Row> =
        ScalpStrategy.values()
            .map { Row(it.label, state.stats(it.name)) }
            .filter { it.stats.closed > 0 }
            .sortedByDescending { it.stats.pnlUsd }

    fun byCoin(state: ScalperState): List<Row> =
        state.groups.filterKeys { it.startsWith(ScalperState.COIN_PREFIX) }
            .map { (k, v) -> Row(coinName(k.removePrefix(ScalperState.COIN_PREFIX)), v) }
            .filter { it.stats.closed > 0 }
            .sortedByDescending { it.stats.pnlUsd }

    /** Hours of the day (the phone's clock) that have a closed scalp, midnight first. */
    fun byHour(state: ScalperState): List<Row> =
        (0..23).mapNotNull { h ->
            val s = state.stats(ScalperState.hourKey(h))
            if (s.closed > 0) Row(hourLabel(h), s) else null
        }

    /** Bankroll over time: the start, then one point per time bucket. Empty until a scalp has closed. */
    fun bankroll(state: ScalperState): List<CurvePoint> {
        if (state.curve.isEmpty()) return emptyList()
        val start = state.startedAtMs?.coerceAtMost(state.curve.first().tMs) ?: state.curve.first().tMs
        return listOf(CurvePoint(start, START_BANKROLL_USD)) +
            state.curve.map { CurvePoint(it.tMs, START_BANKROLL_USD + it.pnlUsd) }
    }

    fun coinName(code: String): String = when (code.uppercase(Locale.US)) {
        "BTC" -> "Bitcoin"
        "ETH" -> "Ethereum"
        "SOL" -> "Solana"
        else -> code
    }

    /** `12 AM`, `1 PM`: the hour that starts then. */
    fun hourLabel(hour: Int): String {
        val h = ((hour % 24) + 24) % 24
        val twelve = if (h % 12 == 0) 12 else h % 12
        return "$twelve ${if (h < 12) "AM" else "PM"}"
    }

    /** `+$12.34` / `−$5.00`. */
    fun money(v: Double): String = ScalperSummary.money(v)

    /** `$1,012.34`, no sign. */
    fun plainMoney(v: Double): String = String.format(Locale.US, "$%,.2f", abs(v))

    /** `54¢`, or `54.5¢` when the price is between cents. */
    fun cents(price: Double): String {
        val c = price * 100.0
        return if (abs(c - c.roundToInt()) < 0.05) "${c.roundToInt()}¢" else String.format(Locale.US, "%.1f¢", c)
    }

    fun pct(rate: Double?): String = rate?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "–"

    /** `+$3.20 · 41 scalps · 61% won · +$0.08 each`. */
    fun rowDetail(s: ScalpStats): String {
        val each = s.perScalpUsd?.let { " · ${money(it)} each" }.orEmpty()
        return "${s.closed} scalp${if (s.closed == 1) "" else "s"} · ${pct(s.winRate)} won$each"
    }

    fun strategyLabel(name: String): String =
        ScalpStrategy.values().firstOrNull { it.name == name }?.label ?: name

    fun sideLabel(side: String): String = if (side.equals("NO", ignoreCase = true)) "DOWN" else "UP"

    fun kindLabel(kind: String): String = when (kind) {
        "TARGET" -> "target hit"
        "STOP" -> "stopped out"
        "TIMEOUT" -> "timed out"
        "SETTLED" -> "held to the close"
        else -> kind.lowercase(Locale.US)
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d h:mm:ss a", Locale.US)

    fun timeLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** `ML scalper · resting · UP`. */
    fun tradeTitle(t: ScalpTrade): String = "${strategyLabel(t.strategy)} · ${sideLabel(t.side)}"

    /** `Oct 9 1:42:07 PM · 10 × 54¢ → 55¢`: when it closed, the size, the entry and the exit. */
    fun tradePrices(t: ScalpTrade, zone: ZoneId = ZoneId.systemDefault()): String {
        val exit = if (t.kind == "SETTLED") (if (t.exit >= 0.5) "$1.00" else "$0.00") else cents(t.exit)
        return "${timeLabel(t.closedAtMs, zone)} · ${t.contracts} × ${cents(t.entry)} → $exit"
    }

    /** `target hit · held 12 s · fees $0.00`. */
    fun tradeDetail(t: ScalpTrade): String {
        val held = ((t.closedAtMs - t.filledAtMs).coerceAtLeast(0L) / 1000L)
        val heldLabel = if (held >= 120) "${held / 60} min" else "$held s"
        return "${kindLabel(t.kind)} · held $heldLabel · fees ${plainMoney(t.feesUsd)}"
    }

    /** `won $1.40 · lost $2.83`. */
    fun wonLost(s: ScalpStats): String = "won ${plainMoney(s.wonUsd)} · lost ${plainMoney(s.lostUsd)}"
}
