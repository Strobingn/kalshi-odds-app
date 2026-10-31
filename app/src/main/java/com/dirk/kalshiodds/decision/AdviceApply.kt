package com.dirk.kalshiodds.decision

/**
 * What "Apply advice" is allowed to do with Hybrid Quant text.
 * The button used to ignore the paragraph and turn on a shared late-window gate.
 * Protected lines stay as the owner set them. The score ceiling drop stays.
 */
object AdviceApply {
    const val PROTECTED = "Not applied (protected): "

    data class Line(val text: String, val protected: Boolean)

    private val TIME_LEFT = Regex("""time[-\s]?left""", RegexOption.IGNORE_CASE)
    private val WEIGHT = Regex("""weight|down-?scale|recalibrat|influence|shrink""", RegexOption.IGNORE_CASE)
    private val HYBRID_HIT = Regex(
        """(?:remov\w+|penali[sz]\w+|disabl\w+|turn\s+off|gate\s+off).{0,80}hybrid[-\s]?gate|hybrid[-\s]?gate.{0,80}(?:remov\w+|penali[sz]\w+|disabl\w+|\boff\b)""",
        RegexOption.IGNORE_CASE
    )
    private val FIB_HIT = Regex(
        """fib[_\-\s]?barrier|(?:remov\w+|penali[sz]\w+|switch|mode).{0,40}fib|\bfib\s+(?:off|block)\b""",
        RegexOption.IGNORE_CASE
    )
    private val MTF_HIT = Regex(
        """mtf(?:\s+agree)?.{0,40}(?:\bon\b|\boff\b|enable|disable|switch)|(?:enable|disable|switch).{0,40}mtf""",
        RegexOption.IGNORE_CASE
    )
    private val STRONG_HIT = Regex(
        """strong[-\s]?move.{0,40}(?:\bon\b|\boff\b|enable|disable|switch)|(?:enable|disable|switch).{0,40}strong[-\s]?move""",
        RegexOption.IGNORE_CASE
    )
    private val LATE_ONLY = Regex(
        """short[-\s]window|late[-\s]window[-\s]only|only the time-left|time left under\s*5|under five minutes""",
        RegexOption.IGNORE_CASE
    )
    private val SHRINK_DEFAULTS = Regex(
        """(?:tighten|shrink|narrow|lower|cut).{0,48}(?:buy|take-?profit|contracts|open-at-once|open at once)|(?:buy band|take-?profit|open-at-once|open at once).{0,48}(?:tighten|shrink|narrow|lower)""",
        RegexOption.IGNORE_CASE
    )

    /** Stable protected lines for this paragraph. Order does not follow the prose. */
    fun classify(text: String): List<Line> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Line>(8)
        if (TIME_LEFT.containsMatchIn(text) && WEIGHT.containsMatchIn(text)) {
            out += Line("down-scale or recalibrate the time-left weight", true)
        }
        if (HYBRID_HIT.containsMatchIn(text)) out += Line("remove or penalize the hybrid-gate flag", true)
        if (FIB_HIT.containsMatchIn(text)) out += Line("remove or penalize the fib-barrier flag", true)
        if (Regex("""fib\s+(?:off|block)|switch.{0,20}fib|fib mode""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            out += Line("switch Fibonacci mode", true)
        }
        if (MTF_HIT.containsMatchIn(text)) out += Line("switch MTF agree", true)
        if (STRONG_HIT.containsMatchIn(text)) out += Line("switch strong-move", true)
        if (LATE_ONLY.containsMatchIn(text)) out += Line("late-window-only on the shared Hybrid Quant gate", true)
        if (SHRINK_DEFAULTS.containsMatchIn(text)) {
            out += Line("shrink the buy band, take-profit, contract size, or open-at-once", true)
        }
        return out
    }

    fun note(text: String, floor: Double, ceiling: Double): String {
        val kept = "Kept score ceiling ${fmt(ceiling)}. Floor stays at ${fmt(floor)} (nudge to ${fmt(floor + 0.02)} not applied)."
        val blocked = classify(text).filter { it.protected }.map { PROTECTED + it.text }
        return (listOf(kept) + blocked).joinToString("\n")
    }

    /** AI-touched rows only. Hand bands at least 20¢ wide stay. */
    fun repairControls(c: ScalpControls): ScalpControls = AiLearnBounds.repairAll(c).normalized()

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
