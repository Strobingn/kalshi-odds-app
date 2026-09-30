package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.paper.PaperKellySizer
import kotlin.math.floor

/**
 * Pure D3 favourite-side maker rule. One evaluation, no I/O, no orders.
 */
object D3Strategy {

    data class Inputs(
        val quote: D3Quote,
        val nowMs: Long,
        val alreadyTakenToday: Boolean = false,
        val book: BookLevelSnapshot? = null,
        val bankrollUsd: Double = 0.0,
        val kellyFraction: Double = D3Constants.HALF_KELLY,
        val schedule: D3Fees.Schedule = D3Fees.fromSeries("quadratic", 1.0, 0.0),
        val liveCapUsd: Double = D3Constants.LIVE_ALL_IN_CAP_USD
    )

    fun favourite(quote: D3Quote): Pair<String, Double>? {
        val yesAsk = KalshiPrice.usable(quote.yesAsk)
        val noAsk = KalshiPrice.usable(quote.noAsk)
            ?: KalshiPrice.impliedAskFromOppositeBid(quote.yesBid)
        val yes = yesAsk ?: KalshiPrice.impliedAskFromOppositeBid(quote.noBid)
        if (yes == null && noAsk == null) return null
        return if ((yes ?: -1.0) >= (noAsk ?: -1.0)) {
            val ask = yes ?: return null
            "YES" to ask
        } else {
            val ask = noAsk ?: return null
            "NO" to ask
        }
    }

    fun inBand(ask: Double): Boolean =
        ask + 1e-12 >= D3Constants.FAV_ASK_MIN && ask - 1e-12 <= D3Constants.FAV_ASK_MAX

    fun bestBid(quote: D3Quote, side: String): Double? {
        return if (side.equals("NO", true)) {
            KalshiPrice.usable(quote.noBid)
                ?: KalshiPrice.usable(quote.yesAsk)?.let { KalshiPrice.usable(1.0 - it) }
        } else {
            KalshiPrice.usable(quote.yesBid)
                ?: KalshiPrice.usable(quote.noAsk)?.let { KalshiPrice.usable(1.0 - it) }
        }
    }

    /**
     * Resting bid on the favourite side: best bid + 1¢ when the spread
     * is ≥2¢, otherwise the best bid. Never crosses the ask (post-only).
     */
    fun bidPrice(favBid: Double, favAsk: Double): Double? {
        val bid = KalshiPrice.usable(favBid) ?: return null
        val ask = KalshiPrice.usable(favAsk) ?: return null
        val spread = (ask - bid).coerceAtLeast(0.0)
        val raw = if (spread + 1e-12 >= D3Constants.IMPROVE_SPREAD) {
            bid + D3Constants.TICK
        } else {
            bid
        }
        val clipped = KalshiPrice.usable(raw) ?: return null
        // Post-only: never rest at or through the ask.
        return if (clipped + 1e-12 >= ask) KalshiPrice.usable(bid) else clipped
    }

    fun evaluate(input: Inputs): D3Signal? {
        if (input.alreadyTakenToday) return null
        if (!D3Window.closeIsFivePmEt(input.quote.closeTimeEpochMs)) return null
        if (D3Window.phase(input.nowMs, input.quote.closeTimeEpochMs) != D3Phase.ACTIVE) return null
        val (side, ask) = favourite(input.quote) ?: return null
        if (!inBand(ask)) return null
        val bid = bestBid(input.quote, side) ?: return null
        val limit = bidPrice(bid, ask) ?: return null
        val display = if (side.equals("NO", true)) "DOWN" else "UP"
        val ahead = D3Book.sizeAhead(side, limit, input.quote, input.book)
        val depth = D3Book.depthCap(side, input.quote, input.book)
        val depthOrBank = depth ?: floor(
            (input.bankrollUsd.takeIf { it > 0.0 } ?: input.liveCapUsd) / limit
        ).toInt().coerceAtLeast(1)
        val paper = PaperKellySizer.size(
            winProb = D3Constants.HISTORICAL_WIN_RATE,
            ask = limit,
            bankrollUsd = input.bankrollUsd.takeIf { it > 0.0 } ?: input.liveCapUsd,
            kellyFraction = input.kellyFraction,
            feeRate = input.schedule.makerFeeRate,
            depthContracts = depthOrBank
        )
        val live = liveSize(limit, input.liveCapUsd, input.schedule, depth)
        val contracts = if (paper.ok) paper.contracts else live.contracts
        val fee = if (paper.ok) paper.feeUsd else live.feeUsd
        val allIn = if (paper.ok) paper.allInUsd else live.allInUsd
        if (contracts <= 0) return null
        return D3Signal(
            ticker = input.quote.ticker,
            eventTicker = input.quote.eventTicker,
            strikeUsd = input.quote.strikeUsd,
            subtitle = input.quote.subtitle,
            side = if (side.equals("NO", true)) "NO" else "YES",
            displaySide = display,
            favAsk = ask,
            favBid = bid,
            spread = (ask - bid).coerceAtLeast(0.0),
            bidPrice = limit,
            sizeAhead = ahead,
            depthContracts = depth,
            contracts = contracts,
            stakeUsd = allIn,
            feeUsd = fee,
            allInUsd = allIn,
            winRate = D3Constants.HISTORICAL_WIN_RATE,
            qualifiedAtMs = input.nowMs
        )
    }

    fun liveSize(
        bidPrice: Double,
        capUsd: Double,
        schedule: D3Fees.Schedule,
        depthContracts: Int?
    ): PaperKellySizer.Result {
        val px = KalshiPrice.usable(bidPrice)
            ?: return PaperKellySizer.Result(skip = true, reason = "unusable bid")
        val n = PaperKellySizer.maxContracts(
            ask = px,
            capUsd = capUsd,
            feeRate = schedule.makerFeeRate,
            depthContracts = depthContracts ?: floor(capUsd / px).toInt().coerceAtLeast(1)
        )
        if (n < 1) {
            return PaperKellySizer.Result(skip = true, reason = "cannot size under cap", ask = px)
        }
        val allIn = D3Fees.allInUsd(n, px, schedule)
        val fee = D3Fees.makerFeeUsd(n, px, schedule)
        return PaperKellySizer.Result(
            skip = false,
            kellyF = 0.0,
            kellyFraction = D3Constants.HALF_KELLY,
            contracts = n,
            stakeUsd = allIn,
            feeUsd = fee,
            allInUsd = allIn,
            ask = px,
            costPerContract = if (n > 0) allIn / n else px
        )
    }

    fun stillInBand(quote: D3Quote): Boolean {
        val fav = favourite(quote) ?: return false
        return inBand(fav.second)
    }
}
