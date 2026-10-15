package com.dirk.kalshiodds.signal.external

/**
 * Spot features from Kalshi only: the CF Benchmarks index Kalshi streams on
 * the Live signals WebSocket ([CfIndexBook]), turned into the same
 * [AssetSpotFeatures] the Binance / Coinbase REST poll fills — 1- and
 * 5-minute returns and the EWMA σ of 1-minute log returns ([SpotVol], the
 * trainer's estimator) from 1-minute bars of the index. No funding rate
 * (Kalshi has none). Pure apart from reading [cf].
 */
object KalshiSpot {
    const val SOURCE = "kalshi-cf"
    private val ASSETS = listOf("BTC", "ETH", "SOL")

    /** One coin's features, or null while its index is stale or has no bars yet. */
    fun features(cf: CfIndexBook, asset: String, nowMs: Long): AssetSpotFeatures? {
        val live = cf.fresh(asset, nowMs) ?: return null
        val closes = cf.minuteCloses(asset, nowMs)
        val px = live.value
        val series = if (closes.size >= 3) closes else listOf(px)
        fun ret(back: Int): Double? {
            if (series.size < back + 1) return null
            val then = series[series.size - 1 - back]
            return if (then > 0) (px - then) / then else null
        }
        val vol = if (closes.size >= 3) SpotVol.trainerVolOf(closes) else null
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            spotReturn1m = ret(1),
            spotReturn5m = ret(5),
            realizedVol15m = vol,
            trainerVol1m = vol,
            trainerVolAtMs = if (vol != null) nowMs else 0L,
            fundingRate = null,
            lastPrice = px,
            source = SOURCE,
            fetchedAtMs = live.updatedMs
        )
    }

    /** All three coins; an empty (all null) snapshot when Kalshi's index is not arriving. */
    fun snapshot(cf: CfIndexBook, nowMs: Long): ExternalSnapshot {
        val f = ASSETS.associateWith { features(cf, it, nowMs) }
        return ExternalSnapshot(btc = f["BTC"], eth = f["ETH"], sol = f["SOL"], fetchedAtMs = nowMs)
    }
}
