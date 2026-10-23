package com.dirk.kalshiodds.signal.scalper.fleet

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Reads the public feeds the fleet polls. Pure; tested on saved responses. */
object FleetParsers {

    private fun JSONObject.dbl(key: String): Double? = optString(key, "").toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A price the book really shows: 0 or 1 means nobody is there. */
    private fun quote(v: Double?): Double? = v?.takeIf { it > 0.0 && it < 1.0 }

    /** Kalshi `GET /markets` (15-minute alts or hourly KXBTCD) → quotes. */
    fun kalshiMarkets(body: String): List<VenueQuote> {
        val arr = JSONObject(body).optJSONArray("markets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val ticker = m.optString("ticker").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VenueQuote(
                id = ticker,
                yesAsk = quote(m.dbl("yes_ask_dollars")), yesBid = quote(m.dbl("yes_bid_dollars")),
                noAsk = quote(m.dbl("no_ask_dollars")), noBid = quote(m.dbl("no_bid_dollars")),
                strike = m.optDouble("floor_strike").takeIf { it.isFinite() },
                closeMs = m.optString("close_time").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                askSizeYes = m.dbl("yes_ask_size_fp"), askSizeNo = m.dbl("no_ask_size_fp")
            )
        }
    }

    /** Kalshi `GET /markets/{ticker}` → "yes" / "no" once settled, else null. */
    fun kalshiResult(body: String): String? {
        val m = JSONObject(body).optJSONObject("market") ?: return null
        val r = m.optString("result").lowercase()
        return r.takeIf { it == "yes" || it == "no" }
    }

    /** `GET /margin/markets/KXBTCPERP` → perp quote (dollars per contract of `contract_size` BTC). */
    fun perpMarket(body: String, nowMs: Long, takerFeeRate: Double = PERP_TAKER_FEE): PerpQuote? {
        val m = JSONObject(body).optJSONObject("market") ?: return null
        val bid = m.dbl("bid")?.takeIf { it > 0 } ?: return null
        val ask = m.dbl("ask")?.takeIf { it > 0 && it >= bid } ?: return null
        val size = m.dbl("contract_size")?.takeIf { it > 0 } ?: return null
        return PerpQuote(bid, ask, size, takerFeeRate, nowMs)
    }

    /** Polymarket gamma `GET /events?slug=` → (up token, down token, fee rate), or null. */
    fun polyEvent(body: String): Triple<String, String, Double>? {
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val m = arr.getJSONObject(0).optJSONArray("markets")?.optJSONObject(0) ?: return null
        val outcomes = JSONArray(m.optString("outcomes", "[]"))
        val tokens = JSONArray(m.optString("clobTokenIds", "[]"))
        if (outcomes.length() != 2 || tokens.length() != 2) return null
        val up = (0..1).firstOrNull { outcomes.getString(it).equals("Up", true) } ?: return null
        val rate = m.optJSONObject("feeSchedule")?.optDouble("rate")?.takeIf { it.isFinite() } ?: 0.07
        return Triple(tokens.getString(up), tokens.getString(1 - up), rate)
    }

    /** Polymarket gamma event → "yes" (Up) / "no" (Down) once resolved, else null. */
    fun polyResult(body: String): String? {
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val m = arr.getJSONObject(0).optJSONArray("markets")?.optJSONObject(0) ?: return null
        if (!m.optBoolean("closed", false)) return null
        val outcomes = JSONArray(m.optString("outcomes", "[]"))
        val prices = JSONArray(m.optString("outcomePrices", "[]"))
        if (outcomes.length() != 2 || prices.length() != 2) return null
        for (i in 0..1) if (prices.getString(i).toDoubleOrNull() == 1.0) return if (outcomes.getString(i).equals("Up", true)) "yes" else "no"
        return null
    }

    /** Polymarket CLOB `GET /book` → (best ask, its size). */
    fun polyBestAsk(body: String): Pair<Double, Double>? {
        val asks = JSONObject(body).optJSONArray("asks") ?: return null
        var best: Pair<Double, Double>? = null
        for (i in 0 until asks.length()) {
            val a = asks.getJSONObject(i)
            val p = a.dbl("price") ?: continue
            val s = a.dbl("size") ?: continue
            if (s > 0 && (best == null || p < best.first)) best = p to s
        }
        return best
    }

    /** Coinbase `GET /products/BTC-USD/book?level=2` → (bids, asks) as (price, BTC). */
    fun coinbaseBook(body: String): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val o = JSONObject(body)
        fun side(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { r -> r.getString(0).toDouble() to r.getString(1).toDouble() } } }.orEmpty()
        return side("bids") to side("asks")
    }

    /** Kraken `GET /0/public/Depth` → (bids, asks). */
    fun krakenBook(body: String): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val r = JSONObject(body).optJSONObject("result") ?: return emptyList<Pair<Double, Double>>() to emptyList()
        val pair = r.keys().asSequence().firstOrNull()?.let { r.getJSONObject(it) } ?: return emptyList<Pair<Double, Double>>() to emptyList()
        fun side(k: String) = pair.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0).toDouble() to it.getString(1).toDouble() } } }.orEmpty()
        return side("bids") to side("asks")
    }

    /** Gemini `GET /v1/book/btcusd` → (bids, asks). */
    fun geminiBook(body: String): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val o = JSONObject(body)
        fun side(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("price").toDouble() to it.getString("amount").toDouble() } } }.orEmpty()
        return side("bids") to side("asks")
    }

    /** Coinbase `GET /products/BTC-USD/trades` → (epoch ms, price, size). */
    fun coinbaseTrades(body: String): List<Triple<Long, Double, Double>> {
        val a = JSONArray(body)
        return (0 until a.length()).mapNotNull { i ->
            val t = a.getJSONObject(i)
            val ms = runCatching { Instant.parse(t.getString("time")).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Triple(ms, t.getString("price").toDouble(), t.getString("size").toDouble())
        }
    }

    /** Kraken `GET /0/public/Trades` → (epoch ms, price, size). */
    fun krakenTrades(body: String): List<Triple<Long, Double, Double>> {
        val r = JSONObject(body).optJSONObject("result") ?: return emptyList()
        val key = r.keys().asSequence().firstOrNull { it != "last" } ?: return emptyList()
        val a = r.getJSONArray(key)
        return (0 until a.length()).map { i ->
            val t = a.getJSONArray(i)
            Triple((t.getDouble(2) * 1000).toLong(), t.getString(0).toDouble(), t.getString(1).toDouble())
        }
    }

    /** Coinbase candles (newest first: [time, low, high, open, close, volume]) → (open time s, close), oldest first. */
    fun coinbaseCandles(body: String): List<Pair<Long, Double>> {
        val a = JSONArray(body)
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getLong(0) to it.getDouble(4) } }.sortedBy { it.first }
    }

    /** Volume-weighted price per second for [seconds] seconds ending at [nowSec] (NaN with no trade). */
    fun vwapPerSecond(trades: Collection<Triple<Long, Double, Double>>, nowSec: Long, seconds: Int): DoubleArray {
        val pv = DoubleArray(seconds)
        val v = DoubleArray(seconds)
        val from = nowSec - seconds + 1
        for ((ms, p, s) in trades) {
            val i = (ms / 1000 - from).toInt()
            if (i in 0 until seconds) { pv[i] += p * s; v[i] += s }
        }
        return DoubleArray(seconds) { if (v[it] > 0) pv[it] / v[it] else Double.NaN }
    }

    /** Kalshi's tier-0 perp taker fee (12 bps of notional). */
    const val PERP_TAKER_FEE = 0.0012
}
