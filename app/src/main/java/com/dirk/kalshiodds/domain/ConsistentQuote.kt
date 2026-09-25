package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.model.MarketTick
import kotlin.math.abs

/**
 * Kalshi binary book identity
 * (https://docs.kalshi.com/getting_started/orderbook_responses,
 * https://docs.kalshi.com/websockets/market-ticker):
 *
 *     yes_bid = 1 − no_ask
 *     no_bid  = 1 − yes_ask
 *
 * Official ticker messages only carry `yes_bid_dollars` / `yes_ask_dollars`.
 * Complements must be derived from **that same update**. Mixing a fresh YES
 * print with a stale REST NO print produces impossible books
 * (UP bid 55¢ + DOWN ask 50¢ = 105).
 */
object ConsistentQuote {
    const val COMPLEMENT_EPS = 0.0015

    data class Snap(
        val yesBid: Double?,
        val yesAsk: Double?,
        val noBid: Double?,
        val noAsk: Double?
    ) {
        fun complementBroken(): Boolean {
            if (yesBid != null && noAsk != null && abs(yesBid + noAsk - 1.0) > COMPLEMENT_EPS) return true
            if (noBid != null && yesAsk != null && abs(noBid + yesAsk - 1.0) > COMPLEMENT_EPS) return true
            return false
        }
    }

    fun complement(price: Double?): Double? {
        val p = KalshiPrice.usable(price) ?: return null
        return KalshiPrice.usable(1.0 - p)
    }

    /**
     * Complete missing sides from fields that arrived together.
     * When YES is present it is the source of truth (ticker channel);
     * conflicting NO fields from another snapshot are dropped.
     */
    fun fromSameUpdate(
        yesBid: Double?,
        yesAsk: Double?,
        noBid: Double?,
        noAsk: Double?
    ): Snap? {
        val ybIn = KalshiPrice.usable(yesBid)
        val yaIn = KalshiPrice.usable(yesAsk)
        val nbIn = KalshiPrice.usable(noBid)
        val naIn = KalshiPrice.usable(noAsk)
        if (ybIn == null && yaIn == null && nbIn == null && naIn == null) return null

        val preferYes = ybIn != null || yaIn != null
        val yb: Double?
        val ya: Double?
        val nb: Double?
        val na: Double?
        if (preferYes) {
            yb = ybIn
            ya = yaIn
            na = complement(yb)
            nb = complement(ya)
        } else {
            nb = nbIn
            na = naIn
            ya = complement(nb)
            yb = complement(na)
        }
        val snap = Snap(yb, ya, nb, na)
        return if (snap.complementBroken()) null else snap
    }

    /**
     * Overlay a live tick onto a REST snapshot without mixing fields.
     * A usable tick replaces the whole book; otherwise the REST book is
     * repaired in place (never field-by-field).
     */
    fun overlay(rest: Snap, tick: Snap?): Snap {
        val fromTick = tick?.let {
            fromSameUpdate(it.yesBid, it.yesAsk, it.noBid, it.noAsk)
        }
        if (fromTick != null) return fromTick
        return fromSameUpdate(rest.yesBid, rest.yesAsk, rest.noBid, rest.noAsk) ?: rest
    }

    fun overlay(market: MarketUiModel, tick: MarketTick?): Snap =
        overlay(
            Snap(market.yesBid, market.yesAsk, market.noBid, market.noAsk),
            tick?.let { Snap(it.yesBid, it.yesAsk, it.noBid, it.noAsk) }
        )

    fun completeTick(tick: MarketTick): MarketTick {
        val snap = fromSameUpdate(tick.yesBid, tick.yesAsk, tick.noBid, tick.noAsk) ?: return tick
        return tick.copy(
            yesBid = snap.yesBid,
            yesAsk = snap.yesAsk,
            noBid = snap.noBid,
            noAsk = snap.noAsk
        )
    }
}
