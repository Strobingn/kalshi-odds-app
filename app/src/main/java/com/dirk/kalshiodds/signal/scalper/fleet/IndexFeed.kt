package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.external.CfIndexBook
import kotlin.math.sqrt

/**
 * The fleet's index inputs from a [CfIndexBook] (Kalshi's CF Benchmarks stream on the phone; the Coinbase stand-in
 * in the replay), plus the behavioural table. Venue data comes from [FleetHub] on the phone; the replay has none.
 */
open class IndexFeed(
    protected val cf: CfIndexBook,
    private val strikeOf: (String) -> Double?,
    val behaviour: BehaviourTable = BehaviourTable()
) : FleetFeed {

    override fun index(ticker: String, closeMs: Long, nowMs: Long): IndexView? {
        val spot = cf.fresh("BTC", nowMs)?.value ?: return null
        val idx = cf.series("BTC", nowMs, 600)
        val inFinal = ((nowMs - (closeMs - 60_000L)) / 1000L).toInt()
        val final = if (inFinal in 1..60) cf.series("BTC", nowMs, inFinal).filter { it.isFinite() }.toDoubleArray() else DoubleArray(0)
        val sigma = cf.std1m("BTC", nowMs)?.let { it / sqrt(60.0) }
        return IndexView(spot, strikeOf(ticker), sigma, idx, final)
    }

    override fun external(ticker: String, closeMs: Long, nowMs: Long): FleetExternal =
        FleetExternal(lastMacro = MacroCalendar.last(nowMs))

    override fun behavioural(yesPrice: Double): Double? = behaviour.epsilon(yesPrice)

    /** (ticker, minute) → YES mid seen with Bitcoin under the strike, waiting for the settlement. */
    private val pending = HashMap<String, MutableList<Double>>()
    private val seen = HashSet<String>()

    override fun onView(view: FleetView) {
        val minute = view.s / 60
        if (minute !in 1..12 || view.s % 60 > 5) return
        val mid = view.yesMid ?: return
        val spot = view.spot ?: return
        val k = view.strike ?: return
        if (mid < 0.10 || mid > 0.25 || spot >= k) return
        synchronized(pending) {
            if (!seen.add("${view.ticker}#$minute")) return
            pending.getOrPut(view.ticker) { ArrayList() } += mid
        }
    }

    override fun onSettle(ticker: String, result: String) {
        val mids = synchronized(pending) {
            seen.removeAll { it.startsWith("$ticker#") }
            pending.remove(ticker)
        } ?: return
        val won = result.equals("yes", true)
        if (!won && !result.equals("no", true)) return
        for (m in mids) behaviour.add(m, won)
        onBehaviourChanged()
    }

    protected open fun onBehaviourChanged() {}
}
