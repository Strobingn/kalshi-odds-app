package com.dirk.kalshiodds.signal.flip

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.lastminute.LastMinuteConstants
import com.dirk.kalshiodds.signal.lastminute.LastMinuteFired
import com.dirk.kalshiodds.signal.lastminute.LastMinuteMath
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Settlement flip check for every DipHunter pick path.
 *
 * KXBTC15M settles on the CF Benchmarks BRTI **average over the final
 * 60 seconds** (Kalshi Help — Crypto Markets; contract terms BTC.pdf).
 * Inside that minute, observed seconds are locked, so remaining variance
 * shrinks as `(τ/60)² · σ² · τ / 3`.
 *
 * Replaces the old 2–98% / ~4% probability floor: the losing side is
 * capped by [Verdict.flipProb]. A side is BET-eligible only when the
 * capped probability beats all-in cost (ask + Kalshi fee) by the existing
 * edge margin, [flipProb] ≥ [MIN_FLIP_PROB], and the ask is ≥ ~3¢ unless
 * the flip math clearly supports a cheaper print.
 *
 * Pure math — never places an order.
 */
object FlipCheck {

    /** Underdog must have at least this P(settlement flips). */
    const val MIN_FLIP_PROB = 0.03

    /** Asks below this need explicit flip support. */
    const val CHEAP_ASK = 0.03

    /**
     * Dollar-per-second floor. Typical 12 s BTC move ≈ $4
     * (`1.15 * sqrt(12) ≈ 4.0`).
     */
    const val SIGMA_FLOOR_USD_PER_SEC = 1.15

    /** Fat-tail scale on settlement SD. */
    const val FAT_TAIL = 2.5

    /** Same margin as [TicketBuilder.modelBeatsImplied]. */
    const val EDGE_MARGIN = 0.03

    /** Cheap ask is allowed when flip chance is clearly real. */
    const val CHEAP_FLIP_SUPPORT = 0.08

    const val FINAL_MINUTE_SEC = LastMinuteConstants.FINAL_MINUTE_SEC.toDouble()

    /** Robust-vol window: last 5–15 minutes of spot ticks. */
    const val VOL_WINDOW_MIN_MS = 5 * 60_000L
    const val VOL_WINDOW_MAX_MS = 15 * 60_000L

    data class Geometry(
        val spotUsd: Double,
        val targetUsd: Double,
        val secondsLeft: Double,
        val sigmaPerSecUsd: Double,
        val observedAvgUsd: Double? = null,
        val observedSeconds: Double = 0.0
    )

    data class Verdict(
        val distanceUsd: Double,
        val secondsLeft: Double,
        val sigmaPerSecUsd: Double,
        val typicalMoveUsd: Double,
        val settlementMeanUsd: Double,
        val settlementSdUsd: Double,
        val flipProb: Double,
        val leadingIsUp: Boolean,
        val cappedPUp: Double,
        val rawPUp: Double?,
        val noBetLine: String,
        val requiredMoveUsd: Double
    ) {
        val cappedPDown: Double get() = 1.0 - cappedPUp

        fun cappedSideProb(side: String): Double =
            if (side.equals("NO", true)) cappedPDown else cappedPUp

        fun isLeading(side: String): Boolean =
            if (side.equals("NO", true)) !leadingIsUp else leadingIsUp

        fun eligible(
            side: String,
            ask: Double?,
            modelProb: Double? = null,
            feeRate: Double = LastMinuteConstants.TAKER_RATE,
            edgeMargin: Double = EDGE_MARGIN,
            stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD
        ): Boolean = allowsSide(this, side, ask, modelProb, feeRate, edgeMargin, stakeUsd)
    }

    fun secondsLeft(closeTimeEpochMs: Long?, nowMs: Long): Double? {
        val close = closeTimeEpochMs ?: return null
        return ((close - nowMs) / 1000.0)
    }

    /**
     * Per-second dollar vol from last-minute log-vol: `σ_$ = spot · σ_s`.
     * Always at least [SIGMA_FLOOR_USD_PER_SEC].
     */
    fun sigmaFromLogVol(sigS: Double?, spotUsd: Double?): Double {
        val fromLog = if (sigS != null && sigS.isFinite() && sigS > 0.0 &&
            spotUsd != null && spotUsd.isFinite() && spotUsd > 0.0
        ) {
            spotUsd * sigS
        } else {
            Double.NaN
        }
        val raw = if (fromLog.isFinite() && fromLog > 0.0) fromLog else SIGMA_FLOOR_USD_PER_SEC
        return maxOf(raw, SIGMA_FLOOR_USD_PER_SEC)
    }

    /**
     * Robust realized $/s vol from recent BRTI-style spot ticks.
     * MAD × 1.4826 of per-second increments over the last 5–15 min.
     */
    fun sigmaFromSpotTicks(
        ticks: List<Pair<Long, Double>>,
        nowMs: Long
    ): Double {
        val lo = nowMs - VOL_WINDOW_MAX_MS
        val pts = ticks.filter { (t, p) ->
            t in lo..nowMs && p.isFinite() && p > 0.0
        }.sortedBy { it.first }
        if (pts.size < 8) return SIGMA_FLOOR_USD_PER_SEC
        val span = pts.last().first - pts.first().first
        if (span < VOL_WINDOW_MIN_MS / 3) {
            // still use what we have, then floor
        }
        val increments = ArrayList<Double>(pts.size)
        for (i in 1 until pts.size) {
            val dt = (pts[i].first - pts[i - 1].first) / 1000.0
            if (dt <= 0.25) continue
            val dp = pts[i].second - pts[i - 1].second
            if (!dp.isFinite()) continue
            increments.add(abs(dp / dt))
        }
        if (increments.size < 6) return SIGMA_FLOOR_USD_PER_SEC
        val mad = medianAbsoluteDeviation(increments) ?: return SIGMA_FLOOR_USD_PER_SEC
        val sigma = mad * 1.4826
        return maxOf(sigma, SIGMA_FLOOR_USD_PER_SEC)
    }

    fun typicalMoveUsd(sigmaPerSecUsd: Double, secondsLeft: Double): Double {
        val t = secondsLeft.coerceAtLeast(1.0)
        return maxOf(sigmaPerSecUsd, SIGMA_FLOOR_USD_PER_SEC) * sqrt(t)
    }

    /**
     * Variance of the official 60 s BRTI average remaining after [secondsLeft].
     * Matches `research/last_minute/fine_trades.py` (`τ ≥ 60`: `σ²(τ−40)`;
     * inside the minute: `(τ/60)² σ² max(τ,1) / 3`) in **dollars**.
     */
    fun settlementVarianceUsd(
        secondsLeft: Double,
        sigmaPerSecUsd: Double
    ): Double {
        val sig = maxOf(sigmaPerSecUsd, SIGMA_FLOOR_USD_PER_SEC)
        val tau = secondsLeft
        return if (tau >= FINAL_MINUTE_SEC) {
            sig * sig * (tau - 40.0).coerceAtLeast(1.0)
        } else {
            val t = maxOf(tau, 1.0)
            val frac = tau / FINAL_MINUTE_SEC
            frac * frac * sig * sig * t / 3.0
        }
    }

    fun settlementMeanUsd(geometry: Geometry): Double {
        val tau = geometry.secondsLeft
        if (tau >= FINAL_MINUTE_SEC) return geometry.spotUsd
        val nobs = (FINAL_MINUTE_SEC - tau).coerceAtLeast(0.0)
        val locked = geometry.observedAvgUsd?.takeIf { it.isFinite() && it > 0.0 }
            ?: geometry.spotUsd
        val remaining = tau.coerceAtLeast(0.0)
        return (nobs * locked + remaining * geometry.spotUsd) / FINAL_MINUTE_SEC
    }

    fun evaluate(geometry: Geometry, rawPUp: Double? = null): Verdict {
        val spot = geometry.spotUsd
        val target = geometry.targetUsd
        val tau = geometry.secondsLeft.coerceAtLeast(0.0)
        val sigma = maxOf(geometry.sigmaPerSecUsd, SIGMA_FLOOR_USD_PER_SEC)
        val distance = abs(spot - target)
        val mean = settlementMeanUsd(geometry)
        val sd = sqrt(settlementVarianceUsd(tau, sigma).coerceAtLeast(0.0))
        val sdFat = maxOf(sd * FAT_TAIL, 1e-9)
        val z = (target - mean) / sdFat
        val pSettleUp = LastMinuteMath.phi(-z)
        val leadingIsUp = mean >= target - 1e-12
        val flipProb = if (leadingIsUp) {
            (1.0 - pSettleUp).coerceIn(0.0, 1.0)
        } else {
            pSettleUp.coerceIn(0.0, 1.0)
        }
        val raw = rawPUp?.takeIf { it.isFinite() }
        val cappedPUp = capPUp(raw, flipProb, leadingIsUp)
        val typical = typicalMoveUsd(sigma, tau)
        val line = noBetLine(flipProb, distance, tau, typical)
        return Verdict(
            distanceUsd = distance,
            secondsLeft = tau,
            sigmaPerSecUsd = sigma,
            typicalMoveUsd = typical,
            settlementMeanUsd = mean,
            settlementSdUsd = sd,
            flipProb = flipProb,
            leadingIsUp = leadingIsUp,
            cappedPUp = cappedPUp,
            rawPUp = raw,
            noBetLine = line,
            requiredMoveUsd = distance
        )
    }

    fun capPUp(rawPUp: Double?, flipProb: Double, leadingIsUp: Boolean): Double {
        val raw = rawPUp?.takeIf { it.isFinite() }
        val losing = if (leadingIsUp) {
            raw?.let { (1.0 - it).coerceIn(0.0, 1.0) } ?: flipProb
        } else {
            raw?.coerceIn(0.0, 1.0) ?: flipProb
        }
        val cappedLosing = minOf(losing, flipProb.coerceIn(0.0, 1.0))
        return if (leadingIsUp) (1.0 - cappedLosing).coerceIn(0.0, 1.0)
        else cappedLosing.coerceIn(0.0, 1.0)
    }

    fun geometryFrom(
        spotUsd: Double?,
        targetUsd: Double?,
        secondsLeft: Double?,
        sigmaPerSecUsd: Double,
        observedAvgUsd: Double? = null,
        observedSeconds: Double? = null,
        x: Double? = null
    ): Geometry? {
        val strike = targetUsd?.takeIf { it.isFinite() && it > 0.0 }
        val spot = when {
            spotUsd != null && spotUsd.isFinite() && spotUsd > 0.0 -> spotUsd
            strike != null && x != null && x.isFinite() -> strike * exp(x)
            else -> null
        }
        val target = when {
            strike != null -> strike
            spot != null && x != null && x.isFinite() -> spot / exp(x)
            else -> null
        }
        if (spot == null || target == null || !spot.isFinite() || !target.isFinite()) return null
        if (spot <= 0.0 || target <= 0.0) return null
        val tau = secondsLeft?.takeIf { it.isFinite() } ?: return null
        val nobs = observedSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?: (FINAL_MINUTE_SEC - tau).coerceAtLeast(0.0).takeIf { tau < FINAL_MINUTE_SEC }
            ?: 0.0
        return Geometry(
            spotUsd = spot,
            targetUsd = target,
            secondsLeft = tau,
            sigmaPerSecUsd = sigmaPerSecUsd,
            observedAvgUsd = observedAvgUsd,
            observedSeconds = nobs
        )
    }

    fun geometryOf(
        market: MarketUiModel,
        nowMs: Long,
        sigmaPerSecUsd: Double? = null,
        observedAvgUsd: Double? = null
    ): Geometry? {
        val lm = market.lastMinute
        val tau = lm?.tauSec?.toDouble()
            ?: secondsLeft(market.closeTimeEpochMs, nowMs)
            ?: return null
        val sigma = sigmaPerSecUsd
            ?: sigmaFromLogVol(lm?.sigS, lm?.spotUsd ?: market.spotUsd)
        return geometryFrom(
            spotUsd = lm?.spotUsd ?: market.spotUsd,
            targetUsd = lm?.strikeUsd ?: market.floorStrike,
            secondsLeft = tau,
            sigmaPerSecUsd = sigma,
            observedAvgUsd = observedAvgUsd ?: observedSpotFromLog(lm?.obsMean, lm?.strikeUsd ?: market.floorStrike),
            x = lm?.x
        )
    }

    fun evaluateMarket(
        market: MarketUiModel,
        nowMs: Long,
        sigmaPerSecUsd: Double? = null,
        rawPUp: Double? = null
    ): Verdict? {
        val geo = geometryOf(market, nowMs, sigmaPerSecUsd) ?: return null
        val raw = rawPUp
            ?: market.lastMinute?.pUp
            ?: TicketBuilder.modelProb(market, "YES")
        return evaluate(geo, raw)
    }

    fun allowsSide(
        verdict: Verdict,
        side: String,
        ask: Double?,
        modelProb: Double? = null,
        feeRate: Double = LastMinuteConstants.TAKER_RATE,
        edgeMargin: Double = EDGE_MARGIN,
        stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD
    ): Boolean {
        val px = KalshiPrice.usable(ask) ?: return false
        val capped = verdict.cappedSideProb(side)
        val model = modelProb?.takeIf { it.isFinite() } ?: capped
        val p = minOf(model.coerceIn(0.0, 1.0), capped)
        if (!beatsAllIn(p, px, feeRate, edgeMargin, stakeUsd)) return false
        val cheap = px + 1e-12 < CHEAP_ASK
        val flipSupportsCheap = verdict.flipProb >= CHEAP_FLIP_SUPPORT - 1e-15 &&
            verdict.distanceUsd <= 2.0 * verdict.typicalMoveUsd + 1e-9
        if (cheap && !flipSupportsCheap) return false
        return if (verdict.isLeading(side)) {
            true
        } else {
            verdict.flipProb + 1e-15 >= MIN_FLIP_PROB
        }
    }

    fun beatsAllIn(
        prob: Double,
        ask: Double,
        feeRate: Double = LastMinuteConstants.TAKER_RATE,
        edgeMargin: Double = EDGE_MARGIN,
        stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD
    ): Boolean {
        val p = KalshiPrice.usable(ask) ?: return false
        if (!prob.isFinite()) return false
        val fee = KalshiFee.perContract(p, feeRate, stakeUsd)
        return prob > p + fee + edgeMargin
    }

    fun allowsMarketSide(
        market: MarketUiModel,
        side: String,
        ask: Double?,
        nowMs: Long,
        modelProb: Double? = null,
        sigmaPerSecUsd: Double? = null
    ): Boolean {
        val verdict = evaluateMarket(market, nowMs, sigmaPerSecUsd)
        if (verdict == null) {
            val px = KalshiPrice.usable(ask) ?: return false
            // No settlement geometry → never take a lottery-ticket print.
            if (px + 1e-12 < CHEAP_ASK) return false
            val model = modelProb ?: return true
            return beatsAllIn(model, px)
        }
        val model = modelProb ?: verdict.cappedSideProb(side)
        return allowsSide(verdict, side, ask, model)
    }

    fun allowsFired(
        fired: LastMinuteFired,
        spotUsd: Double? = null,
        strikeUsd: Double? = null,
        liveAsk: Double? = null
    ): Boolean {
        val strike = strikeUsd?.takeIf { it > 0.0 }
            ?: 80_000.0
        val spot = spotUsd?.takeIf { it > 0.0 } ?: strike * exp(fired.x)
        val target = strikeUsd?.takeIf { it > 0.0 } ?: spot / exp(fired.x)
        val geo = geometryFrom(
            spotUsd = spot,
            targetUsd = target,
            secondsLeft = fired.tauSec.toDouble(),
            sigmaPerSecUsd = sigmaFromLogVol(fired.sigS, spot),
            observedAvgUsd = observedSpotFromLog(fired.obsMean, target),
            x = fired.x
        ) ?: return false
        val verdict = evaluate(geo, if (fired.side.equals("NO", true)) 1.0 - fired.winChance else fired.winChance)
        val ask = liveAsk ?: fired.ask
        return allowsSide(verdict, fired.side, ask, fired.winChance)
    }

    fun allowsTicket(
        ticket: TradeTicket,
        market: MarketUiModel?,
        nowMs: Long,
        sigmaPerSecUsd: Double? = null
    ): Boolean {
        if (ticket.isSell) return true
        if (market == null) return true
        val ask = KalshiPrice.usable(ticket.limitPrice) ?: return false
        return allowsMarketSide(
            market = market,
            side = ticket.side,
            ask = ask,
            nowMs = nowMs,
            modelProb = ticket.modelChance,
            sigmaPerSecUsd = sigmaPerSecUsd
        )
    }

    fun noBetLine(
        flipProb: Double,
        requiredMoveUsd: Double,
        secondsLeft: Double,
        typicalMoveUsd: Double
    ): String {
        val pct = formatFlipPct(flipProb)
        val need = abs(requiredMoveUsd)
        val tau = secondsLeft.coerceAtLeast(0.0)
        val sec = if (abs(tau - tau.toInt().toDouble()) < 1e-6) {
            tau.toInt().toString()
        } else {
            String.format(Locale.US, "%.0f", tau)
        }
        return String.format(
            Locale.US,
            "Flip chance %s — needs a $%,.0f move in %ss (typical %ss move $%,.0f)",
            pct,
            need,
            sec,
            sec,
            typicalMoveUsd.coerceAtLeast(0.0)
        )
    }

    fun formatFlipPct(flipProb: Double): String {
        val pct = (flipProb.coerceIn(0.0, 1.0) * 100.0)
        return String.format(Locale.US, "%.1f%%", pct)
    }

    fun observedSpotFromLog(obsMean: Double?, strikeUsd: Double?): Double? {
        val x = obsMean?.takeIf { it.isFinite() } ?: return null
        val k = strikeUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return k * exp(x)
    }

    private fun medianAbsoluteDeviation(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = median(sorted) ?: return null
        val devs = sorted.map { abs(it - mid) }.sorted()
        return median(devs)
    }

    private fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
