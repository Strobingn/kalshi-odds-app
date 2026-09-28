package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperPickSource
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Everything-we-can-measure section of the Scorecard tab: paper account
 * analytics, extra breakdowns, signal-log calibration, and live engine
 * state. Pure (no Android) so it is unit-tested; the screen renders
 * [Section]s as label/value rows.
 */
object ScorecardDeepStats {

    enum class Tone { NEUTRAL, GOOD, BAD }

    data class Row(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL)

    data class Section(val title: String, val rows: List<Row>)

    /** Live engine / model state gathered by the view model. */
    data class Engine(
        val edgeModel: String? = null,
        val calibratorBuckets: List<String> = emptyList(),
        val adapterSamples: Int = 0,
        val adapterReady: Boolean = false,
        val adapterWeights: Map<String, Double> = emptyMap(),
        val spotSource: String? = null,
        val spotStreamConnected: Boolean = false,
        val sigmaAnnual: Double? = null,
        val blendWeights: String? = null,
        val kellyMultiplier: Double? = null
    )

    private val ET: ZoneId = ZoneId.of("America/New_York")

    fun compute(
        paper: PaperBookState,
        entries: List<PredictionLogEntry>,
        engine: Engine = Engine(),
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ET
    ): List<Section> {
        val fills = (paper.fills + paper.archived.flatMap { it.fills }).distinctBy { it.id }
        val settled = fills.filter { it.settled && it.pnlUsd != null && !it.outcome.equals("void", true) }
            .sortedBy { it.createdAtMs }
        return buildList {
            add(account(paper, fills, settled))
            add(risk(settled))
            add(breakdown("Paper by weekday (ET)", settled) { weekday(it.createdAtMs, zone) })
            add(breakdown("Paper by AI edge at entry", settled) { edgeBucket(it) })
            add(breakdown("Paper by stake size", settled) { stakeBucket(it.stakeUsd) })
            add(breakdown("Paper by source (incl. archived)", settled) {
                PaperPickSource.of(it)?.label ?: it.source.ifBlank { "unknown" }
            })
            add(aiVsActual(settled))
            add(signals(entries))
            add(reliability(entries))
            add(engine(engine))
        }.filter { it.rows.isNotEmpty() }
    }

    // --- paper account -----------------------------------------------------------

    private fun account(paper: PaperBookState, all: List<PaperFill>, settled: List<PaperFill>): Section {
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val staked = settled.sumOf { it.stakeUsd }
        val wins = settled.filter { it.won == true }
        val losses = settled.filter { it.won == false }
        val grossWin = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = -losses.sumOf { it.pnlUsd ?: 0.0 }
        val fees = all.sumOf { estFee(it) }
        val voids = all.count { it.outcome.equals("void", true) }
        val totalReturn = if (paper.startingUsd > 0) (paper.equityUsd - paper.startingUsd) / paper.startingUsd else null
        val stakes = all.map { it.stakeUsd }.sorted()
        return Section(
            "Paper account",
            listOf(
                Row("Equity (cash + open at cost)", usd(paper.equityUsd), tone(paper.equityUsd - paper.startingUsd)),
                Row("Cash / open stake", "${usd(paper.cashUsd)} / ${usd(paper.openStakeUsd)} (${paper.openCount} open)"),
                Row("Return on starting ${usd(paper.startingUsd)}", pct(totalReturn), tone(totalReturn ?: 0.0)),
                Row("Realized P&L (settled, incl. archived)", signedUsd(pnl), tone(pnl)),
                Row("Settled / wins / losses / void", "${settled.size} / ${wins.size} / ${losses.size} / $voids"),
                Row("Win rate", pct(ratio(wins.size, settled.size))),
                Row("ROI on staked", pct(if (staked > 0) pnl / staked else null), tone(pnl)),
                Row("Total staked (turnover)", usd(staked)),
                Row("Kalshi taker fees paid (est.)", usd(fees)),
                Row("Avg win / avg loss", "${usd(avg(wins.map { it.pnlUsd ?: 0.0 }))} / ${usd(avg(losses.map { -(it.pnlUsd ?: 0.0) }))}"),
                Row("Largest win / largest loss", "${signedUsd(wins.maxOfOrNull { it.pnlUsd ?: 0.0 })} / ${signedUsd(losses.minOfOrNull { it.pnlUsd ?: 0.0 })}"),
                Row("Profit factor (gross win ÷ gross loss)", if (grossLoss > 0) fmt2(grossWin / grossLoss) else "—", tone(grossWin - grossLoss)),
                Row("Expectancy per bet", signedUsd(if (settled.isNotEmpty()) pnl / settled.size else null), tone(pnl)),
                Row("Stake avg / median / max", "${usd(avg(stakes))} / ${usd(median(stakes))} / ${usd(stakes.maxOrNull())}"),
                Row("Avg entry price", cents(avg(all.map { it.limitPrice }))),
                Row("Fills in ledger / archived runs", "${paper.fills.size} / ${paper.archived.size}")
            )
        )
    }

    private fun risk(settled: List<PaperFill>): Section {
        if (settled.isEmpty()) return Section("Paper risk", emptyList())
        val pnls = settled.map { it.pnlUsd ?: 0.0 }
        val mean = pnls.average()
        val sd = if (pnls.size > 1) sqrt(pnls.sumOf { (it - mean) * (it - mean) } / (pnls.size - 1)) else null
        val se = sd?.div(sqrt(pnls.size.toDouble()))
        var eq = 0.0
        var peak = 0.0
        var maxDd = 0.0
        for (p in pnls) {
            eq += p
            peak = max(peak, eq)
            maxDd = minOf(maxDd, eq - peak)
        }
        val (bestStreak, worstStreak, current) = streaks(settled.map { it.won == true })
        return Section(
            "Paper risk & significance",
            listOf(
                Row("$/bet 95% CI", if (se != null) "${signedUsd(mean - 1.96 * se)} … ${signedUsd(mean + 1.96 * se)}" else "—",
                    when {
                        se == null -> Tone.NEUTRAL
                        mean - 1.96 * se > 0 -> Tone.GOOD
                        mean + 1.96 * se < 0 -> Tone.BAD
                        else -> Tone.NEUTRAL
                    }),
                Row("t-stat of $/bet (|t| > 2 ≈ real)", if (se != null && se > 0) fmt2(mean / se) else "—"),
                Row("Std dev per bet", usd(sd)),
                Row("Max drawdown (realized)", signedUsd(maxDd), if (maxDd < 0) Tone.BAD else Tone.NEUTRAL),
                Row("Longest win / loss streak", "$bestStreak / $worstStreak"),
                Row("Current streak", current)
            )
        )
    }

    private fun breakdown(title: String, settled: List<PaperFill>, key: (PaperFill) -> String): Section {
        val rows = settled.groupBy(key).entries
            .sortedByDescending { it.value.size }
            .map { (k, xs) ->
                val pnl = xs.sumOf { it.pnlUsd ?: 0.0 }
                val staked = xs.sumOf { it.stakeUsd }
                Row(
                    k,
                    "${xs.size} bets · win ${pct(ratio(xs.count { it.won == true }, xs.size))} · " +
                        "${signedUsd(pnl)} · ROI ${pct(if (staked > 0) pnl / staked else null)}",
                    tone(pnl)
                )
            }
        return Section(title, rows)
    }

    private fun aiVsActual(settled: List<PaperFill>): Section {
        val withAi = settled.filter { PaperFill.hasAiPct(it.aiPct) }
        if (withAi.isEmpty()) return Section("AI win % vs actual (paper)", emptyList())
        val buckets = withAi.groupBy { ((it.aiPct!! / 10.0).toInt().coerceIn(0, 9)) }
        val rows = buckets.entries.sortedBy { it.key }.map { (b, xs) ->
            val predicted = xs.mapNotNull { it.aiPct }.average()
            val actual = 100.0 * xs.count { it.won == true } / xs.size
            val market = xs.mapNotNull { it.marketPct }.takeIf { it.isNotEmpty() }?.average()
            Row(
                "AI ${b * 10}–${b * 10 + 10}%",
                "${xs.size} · AI ${fmt1(predicted)}% · won ${fmt1(actual)}%" +
                    (market?.let { " · paid ${fmt1(it)}¢" } ?: ""),
                tone(actual - predicted)
            )
        }
        val overallPred = withAi.mapNotNull { it.aiPct }.average()
        val overallAct = 100.0 * withAi.count { it.won == true } / withAi.size
        return Section(
            "AI win % vs actual (paper)",
            listOf(Row("All AI-sized fills", "${withAi.size} · AI ${fmt1(overallPred)}% · won ${fmt1(overallAct)}%", tone(overallAct - overallPred))) + rows
        )
    }

    // --- signal log ----------------------------------------------------------------

    private fun signals(entries: List<PredictionLogEntry>): Section {
        val settled = entries.filter { it.outcome == "yes" || it.outcome == "no" }
        if (settled.isEmpty()) return Section("Signal log (P(YES) vs outcome)", listOf(Row("Logged", "${entries.size} (none settled yet)")))
        fun stats(xs: List<PredictionLogEntry>, p: (PredictionLogEntry) -> Double): Pair<Double, Double> {
            var b = 0.0
            var l = 0.0
            for (e in xs) {
                val y = if (e.outcome == "yes") 1.0 else 0.0
                val q = p(e).coerceIn(1e-4, 1 - 1e-4)
                b += (q - y) * (q - y)
                l += -(y * ln(q) + (1 - y) * ln(1 - q))
            }
            return b / xs.size to l / xs.size
        }
        val rows = mutableListOf(
            Row("Logged / settled / open / void", "${entries.size} / ${settled.size} / ${entries.count { it.outcome == null }} / ${entries.count { it.outcome == "void" }}")
        )
        val (mb, ml) = stats(settled) { it.predictedYes }
        val (kb, kl) = stats(settled) { it.marketMid }
        rows += Row("Brier model / market", "${fmt4(mb)} / ${fmt4(kb)}", tone(kb - mb))
        rows += Row("Log-loss model / market", "${fmt4(ml)} / ${fmt4(kl)}", tone(kl - ml))
        val raw = settled.filter { it.rawFairYes != null }
        if (raw.isNotEmpty()) {
            val (rb, _) = stats(raw) { it.rawFairYes!! }
            val (rkb, _) = stats(raw) { it.marketMid }
            rows += Row("Raw blend Brier / market (n=${raw.size})", "${fmt4(rb)} / ${fmt4(rkb)}", tone(rkb - rb))
        }
        for ((bucket, xs) in settled.groupBy { it.tteBucket ?: "?" }.toSortedMap()) {
            val (b, _) = stats(xs) { it.predictedYes }
            val (k, _) = stats(xs) { it.marketMid }
            rows += Row("Brier $bucket (n=${xs.size}) model / market", "${fmt4(b)} / ${fmt4(k)}", tone(k - b))
        }
        val alerts = settled.filter { it.wouldAlert == true }
        if (alerts.isNotEmpty()) {
            rows += Row("Would-alert picks: side hit rate", "${alerts.count { ForecastUnits.hit(it) }}/${alerts.size} (${pct(ratio(alerts.count { ForecastUnits.hit(it) }, alerts.size))})")
        }
        val edges = settled.mapNotNull { it.edgePp }
        if (edges.isNotEmpty()) rows += Row("Mean |edge| at log time", "${fmt1(edges.map { abs(it) }.average())} pp")
        return Section("Signal log (P(YES) vs outcome)", rows)
    }

    private fun reliability(entries: List<PredictionLogEntry>): Section {
        val settled = entries.filter { it.outcome == "yes" || it.outcome == "no" }
        if (settled.size < 10) return Section("Reliability (model P(YES) bins)", emptyList())
        val rows = settled.groupBy { (it.predictedYes * 10).toInt().coerceIn(0, 9) }.entries.sortedBy { it.key }.map { (b, xs) ->
            val pred = 100.0 * xs.map { it.predictedYes }.average()
            val obs = 100.0 * xs.count { it.outcome == "yes" } / xs.size
            Row("${b * 10}–${b * 10 + 10}%", "n=${xs.size} · said ${fmt1(pred)}% · YES ${fmt1(obs)}%", if (abs(obs - pred) <= 10) Tone.NEUTRAL else Tone.BAD)
        }
        return Section("Reliability (model P(YES) bins)", rows)
    }

    // --- engine --------------------------------------------------------------------

    private fun engine(e: Engine): Section {
        val rows = mutableListOf<Row>()
        rows += Row("Edge model", e.edgeModel ?: "none active (blend only)")
        e.blendWeights?.let { rows += Row("Blend weights (early)", it) }
        rows += Row("Calibrator", e.calibratorBuckets.joinToString(" · ").ifBlank { "cold (identity)" })
        rows += Row(
            "Online adapter",
            "${e.adapterSamples}/${com.dirk.kalshiodds.signal.config.SignalConstants.MIN_ADAPTER_SAMPLES} settlements" +
                if (e.adapterReady) " · " + e.adapterWeights.entries.joinToString { "${it.key} ${fmt2(it.value)}" } else " (cold)"
        )
        rows += Row("Spot source", (e.spotSource ?: "none") + if (e.spotStreamConnected) " · live stream on" else " · stream off")
        e.sigmaAnnual?.let { rows += Row("BTC σ (EWMA, annualized)", pct(it)) }
        e.kellyMultiplier?.let { rows += Row("Paper sizing", "${fmt1(it)} × Kelly on equity, no stake cap") }
        return Section("Engine state", rows)
    }

    // --- helpers -------------------------------------------------------------------

    fun estFee(f: PaperFill): Double =
        if (f.contracts > 0 && f.limitPrice > 0.0 && f.limitPrice < 1.0) KalshiFee.total(f.contracts, f.limitPrice) else 0.0

    fun streaks(wins: List<Boolean>): Triple<Int, Int, String> {
        var best = 0
        var worst = 0
        var run = 0
        var last: Boolean? = null
        for (w in wins) {
            run = if (w == last) run + 1 else 1
            last = w
            if (w) best = max(best, run) else worst = max(worst, run)
        }
        val current = when (last) {
            null -> "—"
            true -> "$run win${if (run == 1) "" else "s"}"
            false -> "$run loss${if (run == 1) "" else "es"}"
        }
        return Triple(best, worst, current)
    }

    fun weekday(ms: Long, zone: ZoneId): String {
        val d: DayOfWeek = Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek
        return "${d.value} ${d.getDisplayName(TextStyle.SHORT, Locale.US)}"
    }

    fun edgeBucket(f: PaperFill): String {
        val ai = f.aiPct ?: return "no AI %"
        val mkt = f.marketPct ?: return "no market %"
        val e = ai - mkt
        return when {
            e < 0 -> "edge < 0"
            e < 3 -> "edge 0–3 pp"
            e < 7 -> "edge 3–7 pp"
            e < 15 -> "edge 7–15 pp"
            else -> "edge ≥ 15 pp"
        }
    }

    fun stakeBucket(usd: Double): String = when {
        usd < 2 -> "< $2"
        usd < 5 -> "$2–5"
        usd < 10 -> "$5–10"
        usd < 25 -> "$10–25"
        else -> "≥ $25"
    }

    private fun tone(v: Double): Tone = when {
        v > 1e-9 -> Tone.GOOD
        v < -1e-9 -> Tone.BAD
        else -> Tone.NEUTRAL
    }

    private fun ratio(a: Int, b: Int): Double? = if (b > 0) a.toDouble() / b else null
    private fun avg(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.average()
    private fun median(sorted: List<Double>): Double? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun usd(v: Double?): String = v?.let { String.format(Locale.US, "$%.2f", it) } ?: "—"
    private fun signedUsd(v: Double?): String = v?.let { String.format(Locale.US, "%s$%.2f", if (it < 0) "−" else "+", abs(it)) } ?: "—"
    private fun pct(v: Double?): String = v?.let { String.format(Locale.US, "%.1f%%", it * 100.0) } ?: "—"
    private fun cents(v: Double?): String = v?.let { String.format(Locale.US, "%.1f¢", it * 100.0) } ?: "—"
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)
}
