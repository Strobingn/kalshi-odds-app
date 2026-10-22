package com.dirk.kalshiodds.decision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * On-device paper tuner. It runs after a fill and after a window, without an LLM call.
 * It loosens and enlarges only a scalper that is net-positive after fees over at least
 * [LOOSEN_MIN] recent closes. A loser gets tighter and a smaller clip.
 * It never loosens a loser, never benches, never pauses, and never moves a setting the owner just edited.
 * Steps scale with recent P&L. The strategy floor and the sane ask ceiling are hard stops.
 */
object PaperSettingTuner {
    /** Recent closes required before a positive net may loosen or enlarge a clip. */
    const val LOOSEN_MIN = 10
    data class Change(
        val scalper: String,
        val setting: String,
        val oldValue: String,
        val newValue: String,
        val reason: String
    )

    fun stepCents(netUsd: Double): Int {
        if (!netUsd.isFinite()) return 2
        return (2 + (abs(netUsd) * 2.0).roundToInt()).coerceIn(2, 8)
    }

    fun propose(
        controls: ScalpControls,
        tapes: List<PaperTape>,
        blocked: (String) -> Boolean = { false }
    ): List<Change> {
        val out = ArrayList<Change>()
        for (tape in tapes) {
            if (ScalpModels.modelByCode(tape.code) == null || tape.code == "v2-llm") continue
            val fields = fieldsFor(tape.code)
            if (fields.isEmpty()) continue
            val sample = tape.recentReal.filter { it.isFinite() }.takeLast(20)
            if (sample.isEmpty()) continue
            val net = sample.sum()
            if (!net.isFinite() || net == 0.0) continue
            val wins = sample.count { it > 0.0 }
            val wr = wins.toDouble() / sample.size
            val avg = net / sample.size
            val step = stepCents(avg)
            // A short hot streak is not a winner. Net has to be positive after fees, not just the win rate.
            val winner = sample.size >= LOOSEN_MIN && net > 0.0
            val loser = net < 0.0
            if (!winner && !loser) continue
            val view = controls.settings(tape.code)
            val floor = ScalpFloors.cents(tape.code)
            val ceil = ScalpFloors.ceilingCents(tape.code)
            val why = if (winner) {
                String.format(Locale.US, "winner net %+.2f win %.0f%% · loosen so it trades more", net, wr * 100.0)
            } else {
                String.format(Locale.US, "loser net %+.2f win %.0f%% · tighten, still trading", net, wr * 100.0)
            }
            fun put(field: String, old: Int?, next: Int) {
                if (field !in fields) return
                val key = "strategy.${tape.code}.$field"
                if (blocked(key) || blocked("strategy.${tape.code}.algo.$field")) return
                val lo: Int
                val hi: Int
                when (field) {
                    "minEntryCents" -> { lo = floor; hi = (ceil - 1).coerceAtLeast(floor) }
                    "maxEntryCents" -> { lo = ScalpFloors.minCeilingCents(tape.code); hi = ceil }
                    "contracts" -> { lo = 1; hi = 100 }
                    "untilMinute" -> { lo = 1; hi = 14 }
                    "stopCents", "stopLossPercent" -> { lo = 1; hi = 48 }
                    "takeProfitCents" -> { lo = 0; hi = 40 }
                    "maxHoldSeconds" -> { lo = 0; hi = 900 }
                    "edgeCents" -> { lo = 0; hi = 30 }
                    else -> return
                }
                val clamped = next.coerceIn(lo, hi)
                if (old != null && clamped == old) return
                if (old == null && field == "maxEntryCents" && clamped == ceil && !winner) return
                out += Change(tape.code, field, old?.toString() ?: "default", clamped.toString(), why)
            }
            val max0 = view.maxEntryCents ?: ceil
            val min0 = view.minEntryCents
            val clip0 = view.contracts ?: ScalpParamSanity.builtinClip(tape.code)
            if (winner) {
                put("maxEntryCents", view.maxEntryCents, max0 + step)
                put("minEntryCents", min0, min0 - step)
                put("contracts", view.contracts, clip0 + step)
                put("untilMinute", view.untilMinute, view.untilMinute + 1)
                put("takeProfitCents", view.takeProfitCents, (view.takeProfitCents ?: 0) + step)
                if ("edgeCents" in fields) put("edgeCents", view.edgeCents, (view.edgeCents ?: Research.defaultEdge(tape.code)) - step)
            } else {
                put("maxEntryCents", view.maxEntryCents ?: max0, max0 - step)
                put("minEntryCents", min0, min0 + 1)
                put("contracts", view.contracts, clip0 - step)
                put("untilMinute", view.untilMinute, view.untilMinute - 1)
                if (view.stopCents != null || "stopCents" in fields) put("stopCents", view.stopCents ?: 8, (view.stopCents ?: 8) - 1)
                if ("stopLossPercent" in fields && view.stopCents == null) put("stopLossPercent", view.stopLossPercent ?: 20, (view.stopLossPercent ?: 20) - 1)
                if ("edgeCents" in fields) put("edgeCents", view.edgeCents, (view.edgeCents ?: Research.defaultEdge(tape.code)) + step)
            }
        }
        return out.filter { it.setting != "bench" && !it.setting.contains("bench", ignoreCase = true) && it.setting != "enabled" }
    }

    /** Same families the settings card actually applies. Kept here so the tuner does not depend on the LLM tools. */
    internal fun fieldsFor(code: String): Set<String> {
        val m = ScalpModels.modelByCode(code) ?: return emptySet()
        if (m.code == "v2-llm") return emptySet()
        return when {
            ScalpModels.isResearch(m) -> setOf("enabled", "aiLearns", "contracts", "minEntryCents", "maxEntryCents", "edgeCents")
            ScalpModels.isV2(m) -> setOf(
                "enabled", "aiLearns", "contracts", "minEntryCents", "maxEntryCents", "stopCents", "stopLossPercent",
                "takeProfitCents", "maxHoldSeconds", "untilMinute", "cadenceSeconds", "maxOpenPerSide"
            )
            else -> setOf("aiLearns", "contracts", "minEntryCents", "maxEntryCents", "untilMinute", "stopLossPercent", "takeProfitCents", "maxHoldSeconds")
        }
    }
}
