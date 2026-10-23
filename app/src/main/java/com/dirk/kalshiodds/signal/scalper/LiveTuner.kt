package com.dirk.kalshiodds.signal.scalper

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The live tuner: a built-in, on-phone tuner that needs no API key and no network. It reads every closed paper trade
 * and, each time a strategy has closed [EVAL_TRADES] more, looks at its last [WINDOW_TRADES]:
 *
 *  - made money and won at least half: loosen. Size × 1.25 (at least +1, up to [MAX_AUTO_CONTRACTS]), one more
 *    position a side, and its main trigger one step looser, so it trades more and bigger;
 *  - lost money: tighten. Size × 0.7 (at least 1), one fewer position a side, the trigger one step stricter.
 *
 * Every change goes through [SupervisorBounds.apply] (the same hard ranges as the Scalping AI) and is reported to
 * [onChange]. It runs between the cloud Scalping AI's calls, so the arena keeps adapting every few seconds. Paper only.
 *
 * Thread-safe: [onClosed] may be called from the trade path; [step] from one worker.
 */
class LiveTuner(
    private val tuning: ScalpTuning,
    private val onChange: (Change) -> Unit = {}
) {
    data class Change(val strategy: String, val param: String, val old: Double?, val new: Double, val reason: String)

    @Volatile
    var enabled: Boolean = true

    private val inbox = ConcurrentLinkedQueue<ClosedScalp>()
    private val recent = HashMap<String, ArrayDeque<Double>>()
    private val since = HashMap<String, Int>()

    /** Record one closed trade (cheap; the work is done in [step]). */
    fun onClosed(c: ClosedScalp) {
        inbox.add(c)
        while (inbox.size > 5_000) inbox.poll()
    }

    /** Learn the trades closed since the last call and make the changes they call for. */
    fun step(): List<Change> {
        val out = ArrayList<Change>()
        while (true) {
            val c = inbox.poll() ?: break
            val name = c.order.strategy.name
            val q = recent.getOrPut(name) { ArrayDeque() }
            q.addLast(c.pnlUsd)
            while (q.size > WINDOW_TRADES) q.removeFirst()
            since[name] = (since[name] ?: 0) + 1
            if (!enabled) continue
            if ((since[name] ?: 0) < EVAL_TRADES || q.size < EVAL_TRADES) continue
            since[name] = 0
            out += decide(c.order.strategy, q.toList())
        }
        for (ch in out) runCatching { onChange(ch) }
        return out
    }

    private fun decide(st: ScalpStrategy, pnl: List<Double>): List<Change> {
        if (st !in ScalpStrategy.ACTIVE) return emptyList()
        val net = pnl.sum()
        val wins = pnl.count { it > 0.0 }
        val wr = wins.toDouble() / pnl.size
        val tag = "last ${pnl.size}: ${if (net >= 0) "+" else "−"}\$${"%.2f".format(abs(net))}, $wins won"
        val tn = tuning.tuned(st)
        val asks = ArrayList<Triple<String, Double, String>>()
        when {
            net > 0.0 && wr >= 0.5 -> {
                val c = tn.contracts
                if (c < MAX_AUTO_CONTRACTS) asks += Triple("contracts", min(MAX_AUTO_CONTRACTS.toDouble(), max(c + 1.0, ceil(c * 1.25))), "winning ($tag): bigger")
                if (tn.maxPerSide < 50) asks += Triple("maxPerSide", tn.maxPerSide + 1.0, "winning ($tag): more at once")
                looser(st, tn, +1)?.let { asks += Triple("trigger", it, "winning ($tag): trigger looser") }
            }
            net < 0.0 -> {
                val c = tn.contracts
                if (c > 1) asks += Triple("contracts", max(1.0, floor(c * 0.7)), "losing ($tag): smaller")
                if (tn.maxPerSide > 1) asks += Triple("maxPerSide", tn.maxPerSide - 1.0, "losing ($tag): fewer at once")
                looser(st, tn, -1)?.let { asks += Triple("trigger", it, "losing ($tag): trigger stricter") }
            }
        }
        val done = ArrayList<Change>()
        for ((param, value, why) in asks) {
            val r = SupervisorBounds.apply(tuning, st.name, param, value)
            if (r is SupervisorBounds.Change.Applied) done += Change(st.name, param, r.old, r.new, why)
        }
        return done
    }

    /** The trigger one step looser ([dir] = +1) or stricter (−1), or null when this strategy's trigger has no clear direction. */
    private fun looser(st: ScalpStrategy, tn: Tuned, dir: Int): Double? {
        val spec = ScalpTriggers.of(st) ?: return null
        val lowerIsLooser = LOWER_IS_LOOSER(st) ?: return null
        val cur = tuning.knobs(st).trigger ?: spec.default.takeIf { it.isFinite() } ?: return null
        val step = if (spec.step > 0) spec.step else return null
        val next = cur + (if (lowerIsLooser) -dir else dir) * step
        if (next < spec.min - 1e-9 || next > spec.max + 1e-9) return null
        return (next / step).roundToInt() * step
    }

    companion object {
        const val EVAL_TRADES = 4
        const val WINDOW_TRADES = 8
        /** The tuner grows a strategy's size up to here on its own; you and the Scalping AI may set any size. */
        const val MAX_AUTO_CONTRACTS = 500

        /** True when a smaller trigger lets more trades through, false when a bigger one does, null when it is neither. */
        val LOWER_IS_LOOSER: (ScalpStrategy) -> Boolean? = { st ->
            when (st) {
                ScalpStrategy.BREAKOUT_50 -> null
                // More contracts allowed ahead, a later last minute: bigger is looser.
                ScalpStrategy.PENNY_SCALPER, ScalpStrategy.EARLY_FAVORITE -> false
                else -> true
            }
        }
    }
}

/**
 * When the Scalping AI should look again before its minute is up: a new window, a fast move in the market, or a run
 * of closed trades. At most once every [minSpacingMs].
 */
class LiveWindowWatch(private val minSpacingMs: Long = 20_000L) {
    private var lastTicker: String? = null

    /** A reason to ask the Scalping AI now, or null. [lastTickMs] is when it last ran. */
    fun check(nowMs: Long, ticker: String?, move10Cents: Double?, closesSinceTick: Int, lastTickMs: Long): String? {
        val newWindow = ticker != null && lastTicker != null && ticker != lastTicker
        if (ticker != null) lastTicker = ticker
        if (lastTickMs <= 0L || nowMs - lastTickMs < minSpacingMs) return null
        return when {
            newWindow -> "new window $ticker"
            move10Cents != null && abs(move10Cents) >= FAST_MOVE_CENTS -> "UP moved ${"%+.0f".format(move10Cents)}¢ in 10 s"
            closesSinceTick >= CLOSES -> "$closesSinceTick trades closed"
            else -> null
        }
    }

    companion object {
        const val FAST_MOVE_CENTS = 4.0
        const val CLOSES = 6
    }
}
