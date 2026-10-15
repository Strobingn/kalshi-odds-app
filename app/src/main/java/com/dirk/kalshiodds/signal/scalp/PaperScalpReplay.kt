package com.dirk.kalshiodds.signal.scalp

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class PaperReplayTotals(val cash: Double, val equity: Double, val realized: Double,
    val fees: Double, val closed: Int, val wins: Int) {
    companion object { fun from(a: PaperScalpAccount) = PaperReplayTotals(a.cash, a.equity(), a.realized, a.fees, a.closed, a.wins) }
}
@Serializable data class PaperReplayEvent(val kind: String, val atMs: Long,
    val book: PaperDepth? = null, val ticker: String? = null, val yesWon: Boolean? = null, val paused: Boolean? = null,
    val checkpoint: PaperScalpState? = null, val history: List<PaperDepth> = emptyList(),
    val totals: Map<String, PaperReplayTotals> = emptyMap(), val schema: Int = 1, val policy: Int = PaperScalpPolicy.VERSION)
data class PaperReplayFrame(val before: PaperScalpState, val history: List<PaperDepth>, val event: PaperReplayEvent)

/** Reuses the production paper engine. Checkpoints include lookback books, pending orders and consumed depth. */
object PaperScalpReplay {
    data class Report(val events: Int, val checkedEvents: Int, val accounts: Map<String, PaperReplayTotals>)
    fun run(events: Sequence<PaperReplayEvent>): Report {
        var engine: AdaptivePaperScalp? = null
        var count = 0
        var checked = 0
        var id = 0
        for (event in events) {
            require(event.schema == 1 && event.policy == PaperScalpPolicy.VERSION) { "Unsupported replay schema/policy" }
            if (event.kind == "checkpoint") {
                engine = AdaptivePaperScalp(requireNotNull(event.checkpoint), idFactory = { "replay-${++id}" }, historySeed = event.history)
                continue
            }
            val lab = requireNotNull(engine) { "Replay needs a checkpoint before events" }
            when (event.kind) {
                "book" -> lab.onBook(requireNotNull(event.book), event.atMs)
                "settle" -> lab.settle(requireNotNull(event.ticker), requireNotNull(event.yesWon), event.atMs)
                "pause" -> lab.setPaused(requireNotNull(event.paused))
                "expire" -> lab.expirePending(event.atMs)
                else -> error("Unknown replay event ${event.kind}")
            }
            count++
            if (event.totals.isNotEmpty()) {
                val actual = lab.snapshot().accounts.associate { it.id to PaperReplayTotals.from(it) }
                event.totals.forEach { (key, expected) ->
                    val observed = requireNotNull(actual[key])
                    check(abs(expected.cash - observed.cash) < 1e-6 && abs(expected.equity - observed.equity) < 1e-6 &&
                        abs(expected.realized - observed.realized) < 1e-6 && abs(expected.fees - observed.fees) < 1e-6 &&
                        expected.closed == observed.closed && expected.wins == observed.wins) { "Replay mismatch at event $count / $key" }
                }
                checked++
            }
        }
        val last = requireNotNull(engine) { "No checkpoint in recording" }
        return Report(count, checked, last.snapshot().accounts.associate { it.id to PaperReplayTotals.from(it) })
    }
}
