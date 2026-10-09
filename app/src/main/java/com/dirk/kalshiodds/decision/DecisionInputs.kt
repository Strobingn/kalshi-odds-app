package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.ws.CfBenchmarks

/**
 * Builds [DecisionPipeline.Input] from app quotes. Pure: no network, no key.
 * CF Benchmarks is the primary settlement source; Coinbase spot is the
 * labelled fallback when the CF tick is missing or stale.
 */
object DecisionInputs {
    const val COINBASE_FRESH_MS = 90_000L

    data class Settlement(val source: String, val fresh: Boolean, val spot: Double?, val cf: DecisionPipeline.CfSnapshot?)

    fun settlement(
        series: String,
        nowMs: Long,
        cfTick: CfBenchmarks.Tick?,
        coinbaseSpot: Double?,
        coinbaseAtMs: Long?
    ): Settlement {
        if (cfTick != null && cfTick.fresh(nowMs)) {
            val fm = cfTick.finalMinuteAverage
            return Settlement(
                source = "CF ${cfTick.indexId}",
                fresh = true,
                spot = cfTick.value,
                cf = DecisionPipeline.CfSnapshot(
                    indexId = cfTick.indexId,
                    value = cfTick.value,
                    avg60s = cfTick.avg60s.value,
                    finalMinuteAvg = fm?.value,
                    finalMinuteCount = fm?.windowSize
                )
            )
        }
        val cbFresh = coinbaseSpot != null && coinbaseSpot > 0.0 && coinbaseAtMs != null &&
            nowMs - coinbaseAtMs in 0..COINBASE_FRESH_MS
        return if (coinbaseSpot != null && coinbaseSpot > 0.0) {
            Settlement("Coinbase fallback", cbFresh, coinbaseSpot, null)
        } else {
            Settlement("none", false, null, null)
        }
    }

    fun seriesOf(ticker: String): String = CryptoMarkets.inferSeries(ticker)

    fun build(
        ticker: String,
        nowMs: Long,
        closeTimeMs: Long?,
        rawModelYes: Double?,
        marketYes: Double?,
        yesAsk: Double?,
        noAsk: Double?,
        yesBid: Double?,
        noBid: Double?,
        yesDepth: Int?,
        noDepth: Int?,
        bookAgeMs: Long?,
        strike: Double?,
        volPerSec: Double?,
        settlement: Settlement,
        feeRate: Double,
        modelVersion: String,
        stakeBankrollUsd: Double? = null
    ): DecisionPipeline.Input = DecisionPipeline.Input(
        ticker = ticker.uppercase(),
        series = seriesOf(ticker),
        nowMs = nowMs,
        closeTimeMs = closeTimeMs,
        rawModelYes = rawModelYes,
        marketYes = marketYes,
        yesAsk = yesAsk,
        noAsk = noAsk,
        yesBid = yesBid,
        noBid = noBid,
        yesDepth = yesDepth,
        noDepth = noDepth,
        bookAgeMs = bookAgeMs,
        spot = settlement.spot,
        strike = strike,
        volPerSec = volPerSec,
        settlementSource = settlement.source,
        settlementFresh = settlement.fresh,
        cf = settlement.cf,
        feeRate = feeRate,
        modelVersion = modelVersion,
        stakeBankrollUsd = stakeBankrollUsd
    )

    /** Daily above/below: the raw model is the frozen v060 FLB curve on the mid. */
    fun dailyRaw(yesBid: Double?, yesAsk: Double?, closeTimeMs: Long?, nowMs: Long): Pair<Double, Double>? {
        val b = yesBid ?: return null
        val a = yesAsk ?: return null
        if (a <= b) return null
        val close = closeTimeMs ?: return null
        val tau = (close - nowMs) / 1000.0
        if (tau <= 0) return null
        val mid = (a + b) / 2.0
        return V060Rule.probability(mid, tau) to mid
    }
}
