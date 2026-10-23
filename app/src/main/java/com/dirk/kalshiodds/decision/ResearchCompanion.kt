package com.dirk.kalshiodds.decision

import java.util.Locale

/**
 * On-demand read of the model-filter log. Nothing here runs from a quote, a worker, or a timer.
 * The explanation is the feature ablation. An LLM, if the user asks for one, is called by the UI
 * with [Explain.prompt] and is not imported here.
 */
object ResearchCompanion {
    data class Driver(val name: String, val value: Double, val push: Double)

    data class Explain(
        val title: String,
        val score: Double,
        val scalar: Double,
        val entered: Boolean,
        val reason: String,
        val outcomeUsd: Double?,
        val drivers: List<Driver>,
        val prompt: String
    )

    data class Regime(val group: String, val name: String, val n: Int, val wins: Int, val netUsd: Double)

    data class Journal(val closed: Int, val regimes: List<Regime>, val suggestions: List<String>, val retrain: String)

    fun explainLatest(): Explain? {
        val row = ModelFilter.researchLog().lastOrNull() ?: return null
        return explain(row)
    }

    fun explain(row: ModelFilter.LogRow): Explain {
        val (weights, bias) = ModelFilter.weightCopy()
        return explain(row, weights, bias, QuantSession.telemetry().modelType)
    }

    fun explain(row: ModelFilter.LogRow, weights: DoubleArray, bias: Double, modelType: String): Explain {
        val push = QuantSession.attributeLogistic(weights, bias, row.features)
        val names = ModelFilter.FEATURE_NAMES
        val drivers = ArrayList<Driver>(push.size)
        for (i in push.indices) {
            val name = names.getOrElse(i) { "f$i" }
            val value = if (i < row.features.size) row.features[i] else 0.0
            drivers += Driver(name, value, push[i])
        }
        drivers.sortByDescending { kotlin.math.abs(it.push) }
        val top = drivers.take(5)
        val outcome = row.outcomeUsd?.let { money(it) } ?: "still open"
        val action = if (row.entered) "entered" else "skipped"
        val title = "${row.twinId} · $action · score ${fmt(row.score)}"
        val lines = top.joinToString("\n") { d ->
            "${d.name} ${signed(d.push)} (value ${fmt(d.value)})"
        }
        val prompt = "Paper $modelType filter. $title. Reason: ${row.reason}. Outcome: $outcome. " +
            "Top feature drivers, as the change in score if that feature is 0:\n$lines\n" +
            "Explain in two short paragraphs why this helped or hurt. No orders."
        return Explain(title, row.score, row.scalar, row.entered, row.reason, row.outcomeUsd, top, prompt)
    }

    fun journal(): Journal = journalOf(ModelFilter.researchLog(), ModelFilter.threshold())

    fun journalOf(rows: List<ModelFilter.LogRow>, threshold: Double, retrainNote: String = ""): Journal {
        val closed = rows.filter { it.outcomeUsd != null }
        if (closed.isEmpty()) {
            return Journal(0, emptyList(), listOf("No closed model trades yet. The log still has skips and open scores."), retrainNote)
        }
        val regimes = ArrayList<Regime>()
        regimes += group(closed, "Time left") { ModelFilter.bucketOf(it.features.getOrElse(0) { 0.0 } * 900.0) }
        regimes += group(closed, "Vol") { volRegime(it.features.getOrElse(7) { 0.0 }) }
        regimes += group(closed, "Score") { scoreBand(it.score) }
        return Journal(closed.size, regimes, suggestions(closed, regimes, threshold), retrainNote)
    }

    fun retrain(nowMs: Long): String = ModelFilter.retrainOffline(nowMs)

    fun volRegime(vol1m: Double): String = when {
        vol1m < 1.0 -> "quiet vol"
        vol1m < 5.0 -> "normal vol"
        else -> "hot vol"
    }

    fun scoreBand(score: Double): String = when {
        score < 0.45 -> "score < 0.45"
        score <= 0.65 -> "score 0.45–0.65"
        else -> "score > 0.65"
    }

    private fun group(rows: List<ModelFilter.LogRow>, group: String, key: (ModelFilter.LogRow) -> String): List<Regime> {
        val out = LinkedHashMap<String, Regime>()
        for (row in rows) {
            val name = key(row)
            val prev = out[name]
            val net = row.outcomeUsd ?: 0.0
            val win = if (net > 0.0) 1 else 0
            out[name] = if (prev == null) Regime(group, name, 1, win, net)
            else prev.copy(n = prev.n + 1, wins = prev.wins + win, netUsd = prev.netUsd + net)
        }
        return out.values.toList()
    }

    private fun suggestions(rows: List<ModelFilter.LogRow>, regimes: List<Regime>, threshold: Double): List<String> {
        val out = ArrayList<String>(3)
        val high = regimes.firstOrNull { it.group == "Score" && it.name == "score > 0.65" }
        if (high != null && high.n >= 4 && high.netUsd < 0.0) {
            val next = (threshold + 0.02).coerceAtMost(0.80)
            out += "Raise the threshold from ${fmt(threshold)} to ${fmt(next)}. High scores lost ${money(high.netUsd)} over ${high.n} closes."
        }
        val quiet = regimes.firstOrNull { it.name == "quiet vol" }
        val hot = regimes.firstOrNull { it.name == "hot vol" }
        if (quiet != null && hot != null && quiet.n >= 3 && hot.n >= 3 && quiet.netUsd > 0.0 && hot.netUsd < 0.0) {
            out += "Quiet vol made ${money(quiet.netUsd)}. Hot vol lost ${money(hot.netUsd)}. Keep size down when 1-minute vol is hot."
        }
        val losers = rows.filter { (it.outcomeUsd ?: 0.0) < 0.0 }
        val winners = rows.filter { (it.outcomeUsd ?: 0.0) > 0.0 }
        if (losers.size >= 3 && winners.size >= 3) {
            var worstName = ""
            var worstGap = 0.03
            val n = minOf(ModelFilter.FEATURES, rows.first().features.size)
            for (i in 0 until n) {
                val name = ModelFilter.FEATURE_NAMES[i]
                val win = winners.sumOf { it.features.getOrElse(i) { 0.0 } } / winners.size
                val lose = losers.sumOf { it.features.getOrElse(i) { 0.0 } } / losers.size
                val gap = lose - win
                if (gap > worstGap) {
                    worstGap = gap
                    worstName = name
                }
            }
            if (worstName.isNotEmpty()) {
                out += "Shrink the $worstName weight. Losers carried a higher $worstName (${fmt(worstGap)} above winners)."
            }
        }
        if (out.isEmpty()) out += "No tweak stands out yet. Retrain offline after more closes, or leave the threshold at ${fmt(threshold)}."
        return out
    }

    private fun money(v: Double) = String.format(Locale.US, "%+.2f", v)
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.2f", v)
}
