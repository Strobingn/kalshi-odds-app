package com.dirk.kalshiodds.decision

/**
 * Which 15-minute books the scalper may trade.
 * Bitcoin, or a ticker that already has an open scalp so an older ETH/SOL
 * position can wind down. ETH and SOL quotes still feed the forecast recorder.
 */
object ScalpTradeGate {
    fun trades(ticker: String, openScalps: Set<String>): Boolean {
        val u = ticker.uppercase()
        if (ScalpParams.coinOf(u) == ShockFav.TRADE_COIN) return true
        return openScalps.any { it.equals(u, ignoreCase = true) }
    }
}
