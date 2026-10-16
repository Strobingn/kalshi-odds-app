package com.dirk.kalshiodds.signal.scalper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/**
 * The two contract facts the edge report could not confirm, checked against
 * Kalshi itself (Arena → settings → Contract checks):
 *
 *  1. **Maker fee.** `GET /series/KXBTC15M` → `fee_type`. `quadratic` means
 *     resting fills are free; `quadratic_with_maker_fees` means they pay
 *     0.0175 × C × P × (1 − P), which would wipe out the resting scalper's
 *     +0.35¢ an order. The arena charges it on resting fills when Kalshi says so.
 *  2. **The strike and the settlement.** For every settled window still inside
 *     the index history, the market's `floor_strike` is compared with three
 *     readings of Kalshi's own CF Benchmarks index: the average of the minute
 *     before the open, the average of the minute after it, and the price at
 *     the open; and `expiration_value` with the average of the last minute.
 *     The reading that keeps matching is the one the late strategies must use.
 *
 * Pure apart from the [persist] callback. Paper only: it never orders.
 */
@Serializable
data class FeeCheck(
    val series: String,
    val feeType: String?,
    val feeMultiplier: Double?,
    val checkedAtMs: Long,
    /** Why the last call failed, when it did (the previous answer is kept). */
    val error: String? = null
) {
    /** True when resting fills pay a fee; null before Kalshi answered. */
    val makerFees: Boolean? get() = feeType?.let { it.lowercase(Locale.US).contains("maker") }
}

@Serializable
data class WindowCheck(
    val ticker: String,
    val closeMs: Long,
    val strike: Double?,
    /** Index average over the 60 s before the open, the 60 s after it, and its price at the open. */
    val beforeOpen: Double?,
    val afterOpen: Double?,
    val atOpen: Double?,
    /** Index average over the window's last 60 s, and Kalshi's settlement value. */
    val lastMinute: Double?,
    val settleValue: Double?,
    /** Seconds of index history inside the last minute (60 = complete). */
    val lastMinuteSeconds: Int = 0
)

@Serializable
data class ContractCheckState(
    val fee: FeeCheck? = null,
    /** Newest first, at most [ContractChecks.MAX_WINDOWS]. */
    val windows: List<WindowCheck> = emptyList()
)

object ContractChecks {
    const val MAX_WINDOWS = 200

    /** Within this many basis points counts as a match (one BTC basis point is about $8). */
    const val MATCH_BP = 0.5

    /** Fewest complete windows before a reading is named the strike. */
    const val MIN_WINDOWS = 5

    enum class Reading(val label: String) {
        BEFORE_OPEN("average of the minute before the open"),
        AFTER_OPEN("average of the minute after the open"),
        AT_OPEN("the price at the open")
    }

    data class Fit(val reading: Reading, val windows: Int, val meanAbsBp: Double, val matches: Int)

    fun bp(a: Double, b: Double): Double = (a - b) / b * 10_000.0

    private fun value(w: WindowCheck, r: Reading): Double? = when (r) {
        Reading.BEFORE_OPEN -> w.beforeOpen
        Reading.AFTER_OPEN -> w.afterOpen
        Reading.AT_OPEN -> w.atOpen
    }

    /** How well each reading matches the strike, best first; readings with no window are left out. */
    fun strikeFits(windows: List<WindowCheck>): List<Fit> = Reading.values().mapNotNull { r ->
        val errs = windows.mapNotNull { w ->
            val k = w.strike?.takeIf { it > 0.0 } ?: return@mapNotNull null
            value(w, r)?.takeIf { it > 0.0 }?.let { abs(bp(it, k)) }
        }
        if (errs.isEmpty()) null else Fit(r, errs.size, errs.average(), errs.count { it <= MATCH_BP })
    }.sortedBy { it.meanAbsBp }

    /** The last minute's index average against Kalshi's settlement value, windows with a complete minute only. */
    fun settleFit(windows: List<WindowCheck>): Triple<Int, Double, Int>? {
        val errs = windows.mapNotNull { w ->
            val v = w.settleValue?.takeIf { it > 0.0 } ?: return@mapNotNull null
            if (w.lastMinuteSeconds < 55) return@mapNotNull null
            w.lastMinute?.let { abs(bp(it, v)) }
        }
        return if (errs.isEmpty()) null else Triple(errs.size, errs.average(), errs.count { it <= MATCH_BP })
    }

    /** The card's lines, plain words. */
    fun lines(state: ContractCheckState): List<String> {
        val out = ArrayList<String>()
        val f = state.fee
        out += when {
            f == null -> "Maker fee: not checked yet (needs the app online)."
            f.feeType == null -> "Maker fee: Kalshi did not answer (${f.error ?: "no reply"}); still treated as free."
            f.makerFees == true -> "Maker fee: ${f.series} is fee_type ${f.feeType} — resting fills pay " +
                "0.0175 × C × P × (1 − P). The arena now charges it on every resting fill."
            else -> "Maker fee: ${f.series} is fee_type ${f.feeType}, multiplier ${fmt(f.feeMultiplier ?: 1.0)} — " +
                "resting fills are free, takers pay 0.07 × C × P × (1 − P)."
        }
        val fits = strikeFits(state.windows)
        if (fits.isEmpty()) {
            out += "Strike: no settled window with index history yet (Live signals must be on through a whole window)."
        } else {
            val best = fits.first()
            val named = best.windows >= MIN_WINDOWS && best.matches * 10 >= best.windows * 8
            out += if (named) {
                "Strike: it is the ${best.reading.label} — within ${fmt(MATCH_BP)} bp on ${best.matches} of ${best.windows} windows " +
                    "(off by ${fmt(best.meanAbsBp)} bp on average)."
            } else {
                "Strike: not settled yet — closest is the ${best.reading.label}, off by ${fmt(best.meanAbsBp)} bp on average " +
                    "over ${best.windows} windows (${best.matches} within ${fmt(MATCH_BP)} bp)."
            }
            for (fit in fits.drop(1)) {
                out += "  · ${fit.reading.label}: off by ${fmt(fit.meanAbsBp)} bp on average (${fit.matches} of ${fit.windows} within)."
            }
        }
        val sf = settleFit(state.windows)
        out += if (sf == null) {
            "Settlement: no settled window with a complete last minute of the index yet."
        } else {
            "Settlement: Kalshi's value matched the index's last-minute average within ${fmt(MATCH_BP)} bp on " +
                "${sf.third} of ${sf.first} windows (off by ${fmt(sf.second)} bp on average)."
        }
        return out
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}

/** The checks' record, kept on the phone through [persist]. Thread-safe. */
class ContractCheckBook(
    initial: ContractCheckState = ContractCheckState(),
    private val persist: (ContractCheckState) -> Unit = {}
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ContractCheckState> = _state.asStateFlow()

    fun snapshot(): ContractCheckState = _state.value

    fun has(ticker: String): Boolean = _state.value.windows.any { it.ticker == ticker }

    @Synchronized
    fun setFee(fee: FeeCheck) {
        val prev = _state.value.fee
        // A failed call keeps the last answer Kalshi gave.
        val next = if (fee.feeType == null && prev?.feeType != null) prev.copy(checkedAtMs = fee.checkedAtMs, error = fee.error) else fee
        update(_state.value.copy(fee = next))
    }

    @Synchronized
    fun add(w: WindowCheck) {
        val cur = _state.value
        if (cur.windows.any { it.ticker == w.ticker }) return
        update(cur.copy(windows = (listOf(w) + cur.windows).sortedByDescending { it.closeMs }.take(ContractChecks.MAX_WINDOWS)))
    }

    private fun update(next: ContractCheckState) {
        _state.value = next
        runCatching { persist(next) }
    }
}
