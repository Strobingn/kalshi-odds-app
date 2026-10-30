package com.dirk.kalshiodds.decision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * On-device paper tuner. It runs after a fill and after a window, without an LLM call.
 * It loosens and enlarges only a scalper that is net-positive after fees over at least
 * [LOOSEN_MIN] recent closes. A loser gets tighter and a smaller clip.
 * It never loosens a loser, never benches, never pauses, and never moves a setting the owner just edited.
 * Steps scale with recent P&L. The buy band stays 15–55¢ and take-profit stays 25¢.
 * Hybrid Quant MTF, strong-move, and Fibonacci toggles are ModelFilter prefs. They are not fields here.
 * Until-minute and the stop still have floors. A 10¢ buy window is not a tighter setting.
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
            val ceil = ScalpFloors.ceilingCents(tape.code)
            val max0 = view.maxEntryCents ?: ceil
            val min0 = view.minEntryCents
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
                    "minEntryCents" -> {
                        lo = PaperFleetDefaults.BUY_MIN
                        hi = PaperFleetDefaults.BUY_MIN
                    }
                    "maxEntryCents" -> {
                        lo = PaperFleetDefaults.BUY_MAX
                        hi = maxOf(ceil, PaperFleetDefaults.BUY_MAX)
                    }
                    "contracts" -> { lo = PaperClip.MIN; hi = maxOf(ScalpVariants.clipOf(tape.code) ?: 100, 100) }
                    "untilMinute" -> { lo = AiLearnBounds.untilLo(view.untilMinute); hi = AiLearnBounds.UNTIL_CEILING }
                    "stopCents" -> { lo = AiLearnBounds.stopCentsLo(view.stopCents ?: 8); hi = 48 }
                    "stopLossPercent" -> { lo = AiLearnBounds.stopPctLo(view.stopLossPercent ?: 20); hi = 48 }
                    "takeProfitCents" -> {
                        lo = PaperFleetDefaults.PROFIT_CENTS
                        hi = PaperFleetDefaults.PROFIT_CENTS
                    }
                    "maxHoldSeconds" -> { lo = 0; hi = 900 }
                    "edgeCents" -> { lo = 0; hi = 30 }
                    else -> return
                }
                val clamped = next.coerceIn(lo, hi)
                if (old != null && clamped == old) return
                if (old == null && field == "maxEntryCents" && clamped == ceil && !winner) return
                out += Change(tape.code, field, old?.toString() ?: "default", clamped.toString(), why)
            }
            val clip0 = PaperClip.sized(view.contracts ?: ScalpParamSanity.builtinClip(tape.code))
            val nextMax = AiLearnBounds.nextMax(tape.code, max0, max0, min0, step, winner)
            if (winner) {
                put("maxEntryCents", view.maxEntryCents, nextMax)
                put("minEntryCents", min0, AiLearnBounds.nextMin(tape.code, min0, nextMax, step, true))
                put("contracts", view.contracts ?: clip0, clip0 + step)
                put("untilMinute", view.untilMinute, AiLearnBounds.nextUntil(view.untilMinute, true))
                put("takeProfitCents", view.takeProfitCents, (view.takeProfitCents ?: 0) + step)
                if ("edgeCents" in fields) put("edgeCents", view.edgeCents, (view.edgeCents ?: Research.defaultEdge(tape.code)) - step)
            } else {
                put("maxEntryCents", view.maxEntryCents ?: max0, nextMax)
                put("minEntryCents", min0, AiLearnBounds.nextMin(tape.code, min0, nextMax, step, false))
                put("contracts", view.contracts ?: clip0, clip0 - step)
                put("untilMinute", view.untilMinute, AiLearnBounds.nextUntil(view.untilMinute, false))
                if (view.stopCents != null || "stopCents" in fields) {
                    val curStop = view.stopCents ?: 8
                    put("stopCents", view.stopCents, AiLearnBounds.nextStopCents(curStop, false))
                }
                if ("stopLossPercent" in fields && view.stopCents == null) {
                    val curPct = view.stopLossPercent ?: 20
                    put("stopLossPercent", view.stopLossPercent, AiLearnBounds.nextStopPct(curPct, false))
                }
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
