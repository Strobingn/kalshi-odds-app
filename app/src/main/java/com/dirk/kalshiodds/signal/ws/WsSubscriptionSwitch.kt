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
    private const val CF_BENCHMARKS_CHANNEL = "cfbenchmarks_value"

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
            // CF Benchmarks uses a dedicated, index-id subscription. Combining it
            // with ticker channels makes Kalshi reject the command and can leave a
            // rollover with no active book subscription.
            json = KalshiWsMessages.subscribe(id, marketChannels(channels), nextTickers.takeIf { it.isNotEmpty() }),
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
        json = KalshiWsMessages.subscribe(id, marketChannels(channels), currentTickers.takeIf { it.isNotEmpty() }),
        marketTickers = currentTickers
    )

    private fun marketChannels(channels: List<String>): List<String> =
        channels.filterNot { it == CF_BENCHMARKS_CHANNEL }
}
