package com.dirk.kalshiodds.signal.ws

/**
 * 0.3.43: Kalshi's orderbook `seq` is per subscription (`sid`), not per market — one sid carries the
 * snapshots and deltas of every subscribed ticker (https://docs.kalshi.com/websockets/orderbook-updates).
 * Pre-0.3.43 checked `seq == lastSeq + 1` per *ticker*, so every interleaved BTC/ETH/SOL delta looked like
 * a gap, the book was cleared and the next delta was applied onto an empty book → one-level books and
 * wild prices. This tracks seq per sid, drops duplicates/out-of-order, and reports real gaps so the
 * client can request fresh snapshots.
 */
class OrderbookSequencer {
    enum class Verdict { APPLY, STALE, GAP }

    private val lastBySid = HashMap<Int, Int>()

    @Synchronized
    fun accept(sid: Int?, seq: Int?): Verdict {
        if (sid == null || seq == null) return Verdict.APPLY
        val last = lastBySid[sid]
        return when {
            last == null -> { lastBySid[sid] = seq; Verdict.APPLY }
            seq <= last -> Verdict.STALE
            seq == last + 1 -> { lastBySid[sid] = seq; Verdict.APPLY }
            else -> { lastBySid[sid] = seq; Verdict.GAP }
        }
    }

    @Synchronized fun reset() = lastBySid.clear()

    @Synchronized fun forget(sids: Collection<Int>) { sids.forEach { lastBySid.remove(it) } }

    @Synchronized fun last(sid: Int): Int? = lastBySid[sid]
}
