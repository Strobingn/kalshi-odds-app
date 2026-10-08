package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs

/**
 * Streamed spot for one asset at a point in time. [barStd1m] is in the
 * `AssetSpotFeatures.realizedVol15m` unit (std of 1-minute log returns);
 * null while the EWMA warms up.
 */
data class StreamSpot(
    val asset: String,
    val lastPrice: Double,
    val lastPrintMs: Long,
    val return1m: Double?,
    val return5m: Double?,
    /** Return over the trailing 15 minutes (the previous window when the
     * market just opened): the 15-minute sign-reversal feature. */
    val return15m: Double?,
    val barStd1m: Double?,
    val ewmaReturns: Int
)

/**
 * Thread-safe per-asset [SpotTape]s fed by [CoinbaseSpotStream] (OkHttp
 * reader thread) and read by scoring (tick thread). Every call holds the
 * lock for a few array reads, never for I/O.
 *
 * Freshness: an asset is live while it had a print or a heartbeat within
 * [staleMs] (5 s) and a print within [maxPrintAgeMs]. The ticker channel
 * only sends on trades; the heartbeat says "connected, nothing traded", so
 * a quiet SOL minute does not flip scoring back to 25 s-old REST spot.
 */
class SpotStreamBook(
    private val staleMs: Long = SignalConstants.SPOT_STREAM_STALE_MS,
    private val maxPrintAgeMs: Long = MAX_PRINT_AGE_MS,
    private val newTape: () -> SpotTape = { SpotTape() }
) {
    private val tapes = HashMap<String, SpotTape>()
    private val aliveMs = HashMap<String, Long>()

    /** Records a trade print. False when the tape rejected it (bad tick). */
    @Synchronized
    fun onPrint(asset: String, price: Double, tMs: Long): Boolean {
        val key = asset.uppercase()
        val ok = tapes.getOrPut(key) { newTape() }.add(tMs, price)
        if (ok) touchLocked(key, tMs)
        return ok
    }

    /** Heartbeat: connection is up for [asset] even without a trade. */
    @Synchronized
    fun touch(asset: String, tMs: Long) {
        val key = asset.uppercase()
        if (tapes[key]?.lastTimeMs == null) return
        touchLocked(key, tMs)
    }

    /** Reconnect: no EWMA return may span the outage. */
    @Synchronized
    fun markBreak() {
        tapes.values.forEach { it.breakChain() }
    }

    @Synchronized
    fun clear() {
        tapes.clear()
        aliveMs.clear()
    }

    @Synchronized
    fun lastPrintMs(asset: String): Long? = tapes[asset.uppercase()]?.lastTimeMs

    /** Fresh streamed spot for [asset] at [nowMs], or null (stale / none). */
    @Synchronized
    fun latest(asset: String, nowMs: Long): StreamSpot? {
        val key = asset.uppercase()
        val tape = tapes[key] ?: return null
        val price = tape.lastPrice ?: return null
        val printMs = tape.lastTimeMs ?: return null
        val alive = aliveMs[key] ?: printMs
        if (abs(nowMs - alive) > staleMs) return null
        if (nowMs - printMs > maxPrintAgeMs) return null
        return StreamSpot(
            asset = key,
            lastPrice = price,
            lastPrintMs = printMs,
            return1m = tape.returnOver(60_000L, nowMs),
            return5m = tape.returnOver(300_000L, nowMs),
            return15m = tape.returnOver(900_000L, nowMs),
            barStd1m = tape.barStd1m(),
            ewmaReturns = tape.returnCount
        )
    }

    /** Mean ln(price) of [asset] over [fromMs, toMs] from the tape, or null. */
    @Synchronized
    fun meanLogPrice(asset: String, fromMs: Long, toMs: Long): Double? =
        tapes[asset.uppercase()]?.meanLogPrice(fromMs, toMs)

    private fun touchLocked(key: String, tMs: Long) {
        val prev = aliveMs[key]
        if (prev == null || tMs > prev) aliveMs[key] = tMs
    }

    companion object {
        /** Heartbeats alone keep a price usable for at most this long. */
        const val MAX_PRINT_AGE_MS = 60_000L
    }
}

/**
 * Stream-over-REST merge for [AssetSpotFeatures]. Pure.
 *
 * When the stream is fresh, `lastPrice`, `spotReturn1m`, `spotReturn5m` and
 * `realizedVol15m` come from it; a field the stream cannot fill yet (5m
 * return in the first 5 minutes, σ while the EWMA warms up) falls back to
 * the REST value field by field and the source reads `coinbase-ws+<rest>`.
 * Funding always comes from REST. Stale or missing stream → REST unchanged.
 */
object SpotMerge {
    const val SOURCE = "coinbase-ws"

    fun merge(rest: AssetSpotFeatures?, stream: StreamSpot?): AssetSpotFeatures? {
        if (stream == null) return rest
        val usedRest = rest != null && (
            (stream.return1m == null && rest.spotReturn1m != null) ||
                (stream.return5m == null && rest.spotReturn5m != null) ||
                (stream.barStd1m == null && rest.realizedVol15m != null)
            )
        return AssetSpotFeatures(
            asset = stream.asset,
            spotReturn1m = stream.return1m ?: rest?.spotReturn1m,
            spotReturn5m = stream.return5m ?: rest?.spotReturn5m,
            prevWindowReturn = stream.return15m ?: rest?.prevWindowReturn,
            realizedVol15m = stream.barStd1m ?: rest?.realizedVol15m,
            fundingRate = rest?.fundingRate,
            lastPrice = stream.lastPrice,
            source = if (usedRest) "$SOURCE+${rest!!.source}" else SOURCE,
            fetchedAtMs = stream.lastPrintMs
        )
    }

    /** What scoring should use for [series] at [nowMs]. */
    fun forSeries(
        rest: ExternalSnapshot,
        stream: SpotStreamBook?,
        series: String,
        nowMs: Long
    ): AssetSpotFeatures? {
        val restFeat = rest.forSeries(series)
        if (stream == null) return restFeat
        val asset = ExternalSnapshot.assetOf(series) ?: return restFeat
        val live = runCatching { stream.latest(asset, nowMs) }.getOrNull()
        return merge(restFeat, live)
    }
}
