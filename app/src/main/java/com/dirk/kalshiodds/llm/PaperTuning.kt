package com.dirk.kalshiodds.llm

import android.content.Context
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperPositionManager
import com.dirk.kalshiodds.signal.paper.ScalpReview
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import org.json.JSONArray
import org.json.JSONObject

/** Only these paper exit knobs are writable; no order, stake, live, or credential settings. */
class PaperTuning(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("paper_tuning_v1", Context.MODE_PRIVATE)

    fun settings(version: String?): PaperPositionManager.ExitSettings {
        val raw = prefs.getString("track:$version", null) ?: return PaperPositionManager.ExitSettings()
        return runCatching {
            val j = JSONObject(raw)
            PaperPositionManager.ExitSettings(j.getDouble("trailing_retrace"), j.getDouble("fair_buffer"), j.getDouble("max_net_loss"))
        }.getOrDefault(PaperPositionManager.ExitSettings())
    }

    fun snapshot(fills: List<PaperFill>): String = buildString {
        ScalpSignal.Track.values().forEach { track ->
            val changed = prefs.getLong("changed:${track.formulaVersion}", 0L)
            val rows = fills.filter { it.strategyVersion == track.formulaVersion }
            val result = ScalpReview.assess(rows)
            val afterChange = ScalpReview.assess(rows.filter { it.createdAtMs >= changed })
            val s = settings(track.formulaVersion)
            appendLine("${track.formulaVersion}: open=${rows.count { !it.settled }}, settled=${result.settledFills}, days=${result.distinctDays}, pnl=${result.realizedPnlUsd}, decision=${result.decision}, since_change_settled=${afterChange.settledFills}, since_change_pnl=${afterChange.realizedPnlUsd}, trailing_retrace=${s.trailingRetrace}, fair_buffer=${s.fairValueBuffer}, max_net_loss=${s.maxNetLossPerContract}, changed_at=$changed")
        }
        val open = fills.filter { !it.settled && it.source.startsWith("AI ") }
        appendLine("OPEN PAPER POSITIONS: ${open.size}; each row is a position, not a live order.")
        open.take(100).forEach { f ->
            appendLine("${f.strategyVersion ?: "unknown"} ${f.ticker} ${f.side} contracts=${f.contracts} entry=${f.limitPrice} cost=${f.stakeUsd} fee=${f.feeUsd} high_bid=${f.highWaterMarkPrice}")
        }
        if (open.size > 100) appendLine("${open.size - 100} additional open positions omitted from prompt; track totals above include them.")
    }

    fun audit(): String = prefs.getString("audit", "No paper adjustments yet.") ?: "No paper adjustments yet."

    /** Parse an allowlisted structured response; settings only affect paper exits. */
    @Synchronized
    fun applyResponse(raw: String, fills: List<PaperFill>, automatic: Boolean): String {
        val json = runCatching { JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: return "No settings changed: response was not valid JSON."
        val changes = json.optJSONArray("changes") ?: JSONArray()
        val notes = mutableListOf<String>()
        for (i in 0 until minOf(changes.length(), 20)) {
            val change = changes.optJSONObject(i) ?: continue
            val version = change.optString("strategy")
            if (ScalpSignal.Track.values().none { it.formulaVersion == version }) continue
            val old = settings(version)
            fun value(name: String, previous: Double): Double? {
                if (!change.has(name)) return previous
                val n = change.optDouble(name, Double.NaN)
                return n.takeIf { it.isFinite() && it in 0.0..1.0 }
            }
            val trail = value("trailing_retrace", old.trailingRetrace) ?: continue
            val fair = value("fair_buffer", old.fairValueBuffer) ?: continue
            val loss = value("max_net_loss", old.maxNetLossPerContract) ?: continue
            val next = PaperPositionManager.ExitSettings(trail, fair, loss)
            if (next == old) continue
            val now = System.currentTimeMillis()
            val detail = "$now ${if (automatic) "AUTO" else "USER"} $version: $old -> $next"
            val prior = audit()
            val stored = JSONObject().put("trailing_retrace", trail).put("fair_buffer", fair).put("max_net_loss", loss)
            check(prefs.edit().putString("track:$version", stored.toString()).putLong("changed:$version", now)
                .putString("audit", (detail + "\n" + prior).take(16000)).commit())
            notes += detail
        }
        val message = json.optString("message").take(4000)
        return listOfNotNull(message.takeIf { it.isNotBlank() }, notes.joinToString("\n").takeIf { it.isNotBlank() }
            ?: "No supported paper settings changed.").joinToString("\n")
    }

    companion object {
        const val INSTRUCTIONS = "Return ONLY JSON: {\"message\":\"brief explanation\",\"changes\":[{\"strategy\":\"exact strategy version\",\"trailing_retrace\":0.005,\"fair_buffer\":0.005,\"max_net_loss\":0.01}]}. Values are dollars per contract from 0 to 1. Include only fields you want changed. These are paper exit settings only. No entry, cash, live, credential, or trading settings exist here. Never claim certainty or invent results. When the user asks for information rather than a settings change, return an empty changes array. Paper-only experimental adjustments need not wait for settled trades or losses. Treat small samples as tentative, not proof of underperformance or profit. An empty changes array means no change."
    }
}
