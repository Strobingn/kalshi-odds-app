package com.dirk.kalshiodds.signal.scalp

import kotlin.math.max

/** Version 3 hypotheses. All targets are quote-price hypotheses, never settlement probabilities. */
object PaperScalpSignals {
    data class Signal(val side: String, val setup: String, val ask: Double, val targetBid: Double,
                      val risk: Double, val trail: Double, val rewardRisk: Double, val explanation: String)
    data class Scan(val signals: List<Signal>, val note: String)

    /** One observation per wall-clock second prevents quote-rate-dependent lookbacks. */
    fun seconds(history: List<PaperDepth>, q: PaperDepth): List<PaperDepth> = (history + q)
        .filter { it.ticker == q.ticker && it.atMs <= q.atMs && q.atMs - it.atMs <= 180_000 }
        .groupBy { it.atMs / 1000 }.values.map { bin -> bin.maxBy { it.atMs } }.sortedBy { it.atMs }
        .let { rows ->
            val gap = rows.zipWithNext().indexOfLast { (a, b) -> b.atMs - a.atMs > 5000 }
            if (gap < 0) rows else rows.drop(gap + 1)
        }

    fun forecastTarget(bid: Double, spread: Double, yesMove: Double, side: String): Double? {
        val signed = yesMove * if (side == "YES") 1 else -1
        return if (signed.isFinite() && signed > 0) bid + signed * .75 - spread / 2 else null
    }

    fun scan(q: PaperDepth, history: List<PaperDepth>): Scan {
        val rows = seconds(history, q)
        if (rows.size < 10 || q.atMs - rows.first().atMs < 10_000) return Scan(emptyList(), "Building 10 seconds of continuous book history")
        val signals = mutableListOf<Signal>()
        var structural = false
        var tooExpensive = false
        for (side in listOf("YES", "NO")) {
            val ask = q.ask(side) ?: continue
            val bid = q.bid(side)
            if (!PaperScalpPolicy.below50(ask) || bid <= 0 || ask < bid || ask - bid > .015 + 1e-9) continue
            fun at(back: Long) = rows.lastOrNull { it.atMs <= q.atMs - back }?.bid(side)?.takeIf { it > 0 }
            val b10 = at(10_000) ?: continue
            val b30 = at(30_000)
            val recent = rows.filter { q.atMs - it.atMs <= 60_000 }
            val prices = recent.map { it.bid(side) }.filter { it > 0 }
            val prior = recent.dropLast(1).map { it.bid(side) }.filter { it > 0 }
            if (prior.isEmpty()) continue
            val last = prior.last()
            val low = prices.min()
            val median = prices.sorted()[prices.size / 2]
            val support = q.bids(side).take(3).sumOf { it.second }
            val opposition = q.asks(side).take(3).sumOf { it.second }
            val pressure = (support - opposition) / (support + opposition).coerceAtLeast(1.0)
            val (risk, trail) = PaperScalpPolicy.distances(q, side, rows)
            val spread = ask - bid
            val hypotheses = mutableListOf<Triple<String, Double, String>>()
            // Recover toward an observed center, not the transient high or a settlement probability.
            if (b30 != null && median - low >= .06 && bid >= low + .015 && bid >= last && pressure >= -.1)
                hypotheses += Triple("cheap", median - .005, "Recover toward the observed 60s median")
            // A sweep needs both a reclaim and supporting executable depth.
            val preSweep = recent.filter { q.atMs - it.atMs in 10_000..30_000 }.map { it.bid(side) }.filter { it > 0 }.maxOrNull()
            if (preSweep != null && preSweep - low >= .08 && bid >= low + .02 && bid >= last && pressure >= .10)
                hypotheses += Triple("dip", preSweep - .01, "Sweep reclaimed 2¢ with supporting depth")
            // Fixed 10/30s horizons; never extrapolate the last three arbitrary WS packets.
            if (b30 != null && bid - b10 >= .03 && bid - b30 >= .06 && bid >= last && pressure >= .15) {
                val move = minOf(bid - b30, 2 * (bid - b10)).coerceAtMost(.20)
                hypotheses += Triple("momentum", bid + move * .75, "10s and 30s trend agree with depth")
            }
            // fc53 predicts a signed YES-mid change. Follow that sign and discount the target.
            val yesBid = q.bid("YES"); val yesAsk = q.ask("YES")
            if (yesBid > 0 && yesAsk != null) {
                val now = ShortHorizonForecaster.Sample(q.atMs, (yesBid + yesAsk) / 2,
                    q.bids("YES").take(3).sumOf { it.second }, q.bids("NO").take(3).sumOf { it.second })
                val samples = rows.dropLast(1).mapNotNull { h -> h.ask("YES")?.let {
                    ShortHorizonForecaster.Sample(h.atMs, (h.bid("YES") + it) / 2, 0.0, 0.0) } }
                val horizon = listOf(480, 300, 180, 120, 60).firstOrNull { it * 1000L <= q.closeMs - q.atMs }
                val predicted = horizon?.let { ShortHorizonForecaster.predict("BTC", it, ShortHorizonForecaster.features(now, samples, q.closeMs)) }
                val target = predicted?.let { forecastTarget(bid, spread, it, side) }
                if (target != null && pressure >= 0 && bid >= b10)
                    hypotheses += Triple("forecast-fade", target, "Follow fc53 ${horizon}s direction; target discounted 25%")
            }
            for ((setup, rawTarget, why) in hypotheses) {
                structural = true
                val target = rawTarget.coerceIn(.001, .999)
                val stop = max(.001, ask - risk)
                val reward = target - ask - PaperScalpPolicy.feePerContract(ask) - PaperScalpPolicy.feePerContract(target)
                val loss = ask - stop + PaperScalpPolicy.feePerContract(ask) + PaperScalpPolicy.feePerContract(stop)
                val rr = reward / loss.coerceAtLeast(.001)
                if (reward < .02 || rr < PaperScalpPolicy.MIN_REWARD_RISK) { tooExpensive = true; continue }
                signals += Signal(side, setup, ask, target, risk, trail, rr, why)
            }
        }
        return Scan(signals, when {
            signals.isNotEmpty() -> "${signals.size} signals cover fees and at least 1.25× planned stop risk"
            tooExpensive -> "Signals rejected: projected gain does not cover fees and planned stop risk"
            structural -> "No executable signal"
            else -> "Waiting for a confirmed recovery, trend or forecast below 50¢"
        })
    }
}
