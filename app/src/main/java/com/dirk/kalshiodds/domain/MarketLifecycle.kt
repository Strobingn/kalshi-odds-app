package com.dirk.kalshiodds.domain

/**
 * Kalshi market tradability + rolling 15m window selection.
 *
 * Lifecycle (docs): filter `status=open` matches response `status=active`.
 * Past `close_time` the market is `closed` and new orders are rejected.
 * 15m crypto tickers look like `KXBTC15M-26SEP241645-45`
 * (series-window-strike). When that window expires the next live contract
 * is the soonest-closing open market in the same series.
 */
object MarketLifecycle {
    const val WINDOW_MS = 900_000L

    private val CLOSED_STATUSES = setOf(
        "closed", "determined", "disputed", "amended", "finalized", "settled"
    )
    private val NOT_TRADING_STATUSES = setOf(
        "inactive", "initialized", "unopened", "paused"
    )
    private val OPEN_STATUSES = setOf("active", "open")

    fun isClosed(market: MarketUiModel, nowMs: Long = System.currentTimeMillis()): Boolean {
        val status = market.status?.trim()?.lowercase()
        if (status != null && status in CLOSED_STATUSES) return true
        if (TimeLeft.isExpired(market.closeTimeEpochMs, nowMs)) return true
        return false
    }

    fun isTradable(market: MarketUiModel, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isClosed(market, nowMs)) return false
        val status = market.status?.trim()?.lowercase() ?: return true
        if (status in NOT_TRADING_STATUSES) return false
        if (status in OPEN_STATUSES) return true
        return !TimeLeft.isExpired(market.closeTimeEpochMs, nowMs)
    }

    fun tradable(markets: List<MarketUiModel>, nowMs: Long = System.currentTimeMillis()): List<MarketUiModel> =
        markets.filter { isTradable(it, nowMs) }

    /**
     * Current live window for [expiredTicker]'s series (same strike when
     * still listed, else the soonest-closing open contract).
     */
    fun liveSuccessor(
        expiredTicker: String,
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis()
    ): MarketUiModel? {
        val series = CryptoMarkets.inferSeries(expiredTicker)
        val strike = strikeSuffix(expiredTicker)
        val live = tradable(markets, nowMs).filter {
            CryptoMarkets.inferSeries(it.ticker) == series
        }
        if (live.isEmpty()) return null
        val sameStrike = strike?.let { s -> live.filter { strikeSuffix(it.ticker) == s } }.orEmpty()
        val pool = sameStrike.ifEmpty { live }
        return currentWindow(pool)
    }

    /** If [market] is still live, keep it; otherwise jump to the next window. */
    fun resolveLive(
        market: MarketUiModel,
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis()
    ): MarketUiModel {
        if (isTradable(market, nowMs)) return market
        return liveSuccessor(market.ticker, markets, nowMs) ?: market
    }

    /**
     * Market a Buy / Paper tap should act on. Prefers the series'
     * [currentOpenWindow], then [resolveLive] only if that contract is
     * actually the current window. Returns null when there is no open
     * window — callers show "Next window loading", never Window/Market closed.
     */
    fun resolveActionWindow(
        tapped: MarketUiModel,
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis()
    ): MarketUiModel? {
        val series = CryptoMarkets.inferSeries(tapped.ticker)
        val pool = markets.filter { CryptoMarkets.inferSeries(it.ticker) == series }
            .ifEmpty { markets }
        currentOpenWindow(pool, nowMs)?.let { return it }
        val resolved = resolveLive(tapped, markets, nowMs)
        return resolved.takeIf { isCurrentWindow(it, nowMs) }
    }

    fun currentWindow(markets: List<MarketUiModel>): MarketUiModel? {
        if (markets.isEmpty()) return null
        val soonest = markets.minOf { it.closeTimeEpochMs ?: Long.MAX_VALUE }
        val window = markets.filter {
            val close = it.closeTimeEpochMs ?: return@filter true
            close <= soonest + 60_000L
        }.ifEmpty { markets }
        return window.minByOrNull { m ->
            kotlin.math.abs((m.yesProbabilityPercent ?: m.aiYesPercent ?: 50.0) - 50.0)
        }
    }

    fun openTimeEpochMs(market: MarketUiModel): Long? =
        market.openTimeEpochMs ?: market.closeTimeEpochMs?.minus(WINDOW_MS)

    /**
     * The live 15m contract: [open_time, close_time). Closed and not-yet-open
     * listings (Kalshi often returns the next window as `status=active`) are
     * excluded even if they are still in the REST `status=open` set.
     */
    fun isCurrentWindow(market: MarketUiModel, nowMs: Long): Boolean {
        val close = market.closeTimeEpochMs ?: return false
        if (nowMs >= close) return false
        val status = market.status?.trim()?.lowercase()
        if (status != null && status in CLOSED_STATUSES) return false
        if (status != null && status in NOT_TRADING_STATUSES) return false
        val open = openTimeEpochMs(market) ?: return false
        return nowMs >= open
    }

    fun currentOpenWindow(markets: List<MarketUiModel>, nowMs: Long): MarketUiModel? =
        currentWindow(markets.filter { isCurrentWindow(it, nowMs) })

    fun featuredLive(
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis()
    ): MarketUiModel? {
        val live = tradable(markets, nowMs)
        if (live.isEmpty()) return null
        val btc = live.filter { it.seriesLabel.equals("Bitcoin", ignoreCase = true) }
        return currentWindow(btc.ifEmpty { live })
    }

    /** `KXBTC15M-26SEP241645-45` → `45`. */
    fun strikeSuffix(ticker: String): String? {
        val parts = ticker.split("-")
        return parts.lastOrNull()?.takeIf { parts.size >= 3 && it.isNotBlank() }
    }

    /** `KXBTC15M-26SEP241645-45` → `26SEP241645`. */
    fun windowKey(ticker: String): String? {
        val parts = ticker.split("-")
        return parts.getOrNull(1)?.takeIf { parts.size >= 3 && it.isNotBlank() }
    }
}
