package com.dirk.kalshiodds.data.backfill

import com.dirk.kalshiodds.data.local.archive.SpotCandleRow
import org.json.JSONArray

/**
 * Public Coinbase Exchange candles. No auth.
 * GET /products/{product}/candles?granularity=60&start=&end=
 * Response rows: [time, low, high, open, close, volume]
 */
class CoinbaseSpotBackfill(
    private val transport: HistoryTransport,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val pauseMs: Long = 120L
) {
    fun productForSeries(series: String): String = when {
        series.contains("ETH") && !series.contains("BTC") -> "ETH-USD"
        series.contains("SOL") -> "SOL-USD"
        else -> "BTC-USD"
    }

    fun candles(product: String, startMs: Long, endMs: Long, granularitySec: Int = 60): List<SpotCandleRow> {
        // Coinbase caps ~300 bars per call.
        val barMs = granularitySec * 1000L
        val maxSpan = barMs * 280
        val out = ArrayList<SpotCandleRow>()
        var cursor = startMs
        while (cursor < endMs) {
            val chunkEnd = minOf(endMs, cursor + maxSpan)
            val q = mapOf(
                "granularity" to granularitySec.toString(),
                "start" to iso(cursor),
                "end" to iso(chunkEnd)
            )
            val body = transport.get("/products/$product/candles", q) ?: break
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                val t = row.optLong(0) * 1000L
                out.add(
                    SpotCandleRow(
                        product = product,
                        tMs = t,
                        low = row.optDouble(1),
                        high = row.optDouble(2),
                        open = row.optDouble(3),
                        close = row.optDouble(4),
                        volume = row.optDouble(5)
                    )
                )
            }
            cursor = chunkEnd
            sleep(pauseMs)
        }
        return out.distinctBy { it.tMs }.sortedBy { it.tMs }
    }

    companion object {
        fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
    }
}
