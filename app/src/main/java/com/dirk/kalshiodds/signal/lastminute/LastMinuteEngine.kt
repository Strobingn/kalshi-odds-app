package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.flip.FlipCheck
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Per-window last-minute state: final-minute log samples, completed-minute
 * vol, and the one-signal latch. Pure evaluate + bookkeeping — the
 * ViewModel logs paper picks and posts the heads-up.
 */
class LastMinuteEngine(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    data class WindowState(
        val ticker: String,
        val samples: ArrayDeque<Double> = ArrayDeque(),
        val lastSampleEpochSec: Long = 0L,
        val fired: LastMinuteFired? = null,
        val closedNoPlay: Boolean = false
    )

    private val windows = ConcurrentHashMap<String, WindowState>()
    private val minuteLogCloses = ArrayDeque<Double>()
    private val spotTicks = ArrayDeque<Pair<Long, Double>>()
    @Volatile var lastQuote: BrtiQuote? = null
        private set

    @Synchronized
    fun replaceMinuteCloses(logCloses: List<Double>) {
        minuteLogCloses.clear()
        minuteLogCloses.addAll(logCloses.filter { it.isFinite() })
        while (minuteLogCloses.size > LastMinuteConstants.VOL_MINUTES + 5) {
            minuteLogCloses.removeFirst()
        }
    }

    @Synchronized
    fun noteCompletedMinute(logClose: Double) {
        if (!logClose.isFinite()) return
        if (minuteLogCloses.lastOrNull() == logClose) return
        minuteLogCloses.addLast(logClose)
        while (minuteLogCloses.size > LastMinuteConstants.VOL_MINUTES + 5) {
            minuteLogCloses.removeFirst()
        }
    }

    fun noteSpot(quote: BrtiQuote) {
        lastQuote = quote
        val px = quote.price
        val t = quote.fetchedAtMs.takeIf { it > 0L } ?: nowMs()
        if (px.isFinite() && px > 0.0) {
            synchronized(this) {
                val last = spotTicks.lastOrNull()
                if (last == null || last.first != t) {
                    spotTicks.addLast(t to px)
                } else {
                    spotTicks.removeLast()
                    spotTicks.addLast(t to px)
                }
                val cutoff = t - FlipCheck.VOL_WINDOW_MAX_MS
                while (spotTicks.isNotEmpty() && spotTicks.first().first < cutoff) {
                    spotTicks.removeFirst()
                }
            }
        }
    }

    fun snapshotOf(ticker: String): WindowState? = windows[ticker.uppercase()]

    fun forgetStale(liveTickers: Set<String>) {
        val keep = liveTickers.map { it.uppercase() }.toSet()
        windows.keys.toList().forEach { if (it !in keep) windows.remove(it) }
    }

    fun sigS(): Double? = LastMinuteMath.perSecondVol(minuteLogCloses.toList())

    fun sigmaUsdPerSec(spotUsd: Double?): Double {
        val fromTicks = synchronized(this) { FlipCheck.sigmaFromSpotTicks(spotTicks.toList(), nowMs()) }
        val fromLog = FlipCheck.sigmaFromLogVol(sigS(), spotUsd)
        return maxOf(fromTicks, fromLog, FlipCheck.SIGMA_FLOOR_USD_PER_SEC)
    }

    fun liveAsks(market: MarketUiModel): Pair<Double?, Double?> {
        val tile = MarketQuoteView.of(market)
        val up = tile.yesAsk
            ?: KalshiPrice.usable(market.yesAsk)
            ?: KalshiPrice.impliedAskFromOppositeBid(market.noBid)
        val down = tile.noAsk
            ?: KalshiPrice.usable(market.noAsk)
            ?: KalshiPrice.impliedAskFromOppositeBid(market.yesBid)
        return up to down
    }

    fun tick(
        market: MarketUiModel,
        book: BookLevelSnapshot? = null,
        stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD,
        fallbackSpot: Double? = market.spotUsd,
        fallbackSource: String? = market.spotLabel
    ): LastMinuteSnapshot {
        val ticker = market.ticker.uppercase()
        val now = nowMs()
        val close = market.closeTimeEpochMs
        val strike = market.floorStrike
        val quote = lastQuote
        val spot = quote?.price?.takeIf { it > 0.0 } ?: fallbackSpot
        val source = when {
            quote != null -> quote.source
            fallbackSpot != null -> "fallback: ${fallbackSource ?: "existing spot"}"
            else -> "none"
        }
        val tauSec = if (close != null) {
            floor((close - now) / 1000.0).toInt()
        } else {
            900
        }
        val closed = close != null && (now >= close || !MarketLifecycle.isTradable(market, now))
        val key = ticker
        val prev = windows[key] ?: WindowState(ticker)
        val (upAsk, downAsk) = liveAsks(market)
        if (prev.fired != null) {
            val snap = LastMinuteStrategy.evaluate(
                LastMinuteStrategy.Inputs(
                    ticker = ticker,
                    tauSec = tauSec,
                    x = prev.fired.x,
                    obsMean = prev.fired.obsMean,
                    sigS = prev.fired.sigS,
                    upAsk = upAsk,
                    downAsk = downAsk,
                    book = book,
                    upQuotedSize = market.yesAskSize,
                    downQuotedSize = null,
                    stakeUsd = stakeUsd,
                    alreadyFired = true,
                    nowMs = now,
                    windowClosed = closed,
                    spotUsd = spot,
                    strikeUsd = strike,
                    spotSource = source
                )
            )
            val raw = LastMinuteMath.fairP(prev.fired.x, prev.fired.tauSec.toDouble(), prev.fired.obsMean, prev.fired.sigS)
            return snap.copy(
                fired = prev.fired,
                pUp = snap.pUp ?: snap.flip?.cappedPUp ?: raw
            )
        }
        if (closed || tauSec <= 0) {
            val next = prev.copy(closedNoPlay = true)
            windows[key] = next
            return LastMinuteSnapshot(
                phase = LastMinutePhase.NO_PLAY,
                tauSec = 0,
                startsInMs = null,
                spotUsd = spot,
                strikeUsd = strike,
                spotSource = source
            )
        }
        val x = if (spot != null && strike != null) LastMinuteMath.logSpotOverStrike(spot, strike) else null
        val sig = sigS()
        if (tauSec > LastMinuteConstants.FINAL_MINUTE_SEC) {
            windows[key] = WindowState(ticker)
            return LastMinuteSnapshot(
                phase = LastMinutePhase.WAITING,
                tauSec = tauSec,
                startsInMs = (tauSec - LastMinuteConstants.FINAL_MINUTE_SEC).toLong() * 1000L,
                spotUsd = spot,
                strikeUsd = strike,
                spotSource = source,
                x = x,
                obsMean = 0.0,
                sigS = sig
            )
        }
        if (x == null || sig == null) {
            return LastMinuteSnapshot(
                phase = LastMinutePhase.LIVE,
                tauSec = tauSec,
                startsInMs = null,
                spotUsd = spot,
                strikeUsd = strike,
                spotSource = source,
                x = x,
                obsMean = 0.0,
                sigS = sig
            )
        }
        val epochSec = now / 1000L
        val samples = ArrayDeque(prev.samples)
        if (epochSec != prev.lastSampleEpochSec) {
            samples.addLast(x)
            while (samples.size > LastMinuteConstants.FINAL_MINUTE_SEC) samples.removeFirst()
        }
        val nobs = (LastMinuteConstants.FINAL_MINUTE_SEC - tauSec).coerceAtLeast(0)
        val obsMean = if (tauSec >= LastMinuteConstants.FINAL_MINUTE_SEC) {
            0.0
        } else if (samples.isEmpty()) {
            0.0
        } else {
            samples.takeLast(nobs.coerceAtLeast(1)).average()
        }
        val eval = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = ticker,
                tauSec = tauSec,
                x = x,
                obsMean = obsMean,
                sigS = sig,
                upAsk = upAsk,
                downAsk = downAsk,
                book = book,
                upQuotedSize = market.yesAskSize,
                downQuotedSize = null,
                stakeUsd = stakeUsd.coerceIn(1.0, LastMinuteConstants.MAX_STAKE_USD),
                alreadyFired = false,
                nowMs = now,
                windowClosed = false,
                spotUsd = spot,
                strikeUsd = strike,
                spotSource = source
            )
        )
        windows[key] = WindowState(
            ticker = ticker,
            samples = samples,
            lastSampleEpochSec = epochSec,
            fired = eval.fired,
            closedNoPlay = false
        )
        return eval
    }
}
