package com.dirk.kalshiodds.signal.ws

/**
 * Documented way to change per-market WebSocket subscriptions without
 * dropping the socket.
 *
 * Official commands: `unsubscribe` (requires `sids`) then `subscribe`, or
 * `update_subscription` with exactly one `sid` and
 * `action` = `add_markets` | `delete_markets` | `get_snapshot`.
 *
 * https://docs.kalshi.com/websockets/websocket-connection
 * https://docs.kalshi.com/getting_started/quick_start_websockets
 */
object WsSubscriptionSwitch {
    const val LIFECYCLE_CHANNEL = "market_lifecycle_v2"

    data class Outbound(
        val cmd: String,
        val json: String,
        val marketTickers: List<String> = emptyList(),
        val sids: List<Int> = emptyList(),
        val droppedTickers: List<String> = emptyList()
    )

    fun replace(
        idStart: Int,
        channels: List<String>,
        previousTickers: List<String>,
        nextTickers: List<String>,
        sids: List<Int>
    ): List<Outbound> {
        if (previousTickers.toSet() == nextTickers.toSet()) return emptyList()
        val out = ArrayList<Outbound>(2)
        var id = idStart
        if (sids.isNotEmpty()) {
            out += Outbound(
                cmd = "unsubscribe",
                json = KalshiWsMessages.unsubscribe(id++, sids),
                sids = sids,
                droppedTickers = previousTickers
            )
        }
        out += Outbound(
            cmd = "subscribe",
            json = KalshiWsMessages.subscribe(id, channels, nextTickers.takeIf { it.isNotEmpty() }),
            marketTickers = nextTickers
        )
        return out
    }

    fun resubscribeOnReconnect(
        id: Int,
        channels: List<String>,
        currentTickers: List<String>
    ): Outbound = Outbound(
        cmd = "subscribe",
        json = KalshiWsMessages.subscribe(id, channels, currentTickers.takeIf { it.isNotEmpty() }),
        marketTickers = currentTickers
    )
}
