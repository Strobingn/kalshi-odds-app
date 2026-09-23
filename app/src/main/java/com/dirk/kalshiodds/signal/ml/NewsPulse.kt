package com.dirk.kalshiodds.signal.ml

import kotlin.math.ln

/**
 * Lightweight headline prior for BTC / ETH / SOL.
 *
 * Token hashing → 16-d embedding + a small bull/bear lexicon. Network
 * fetch is fail-soft and cached; unit tests use [scoreHeadlines] only.
 */
data class NewsPulseSnapshot(
    val btc: Double = 0.0,
    val eth: Double = 0.0,
    val sol: Double = 0.0,
    val embedding: FloatArray = FloatArray(DIM),
    val headlineCount: Int = 0,
    val source: String = "none",
    val fetchedAtMs: Long = 0L
) {
    fun forSeries(series: String): Double {
        val u = series.uppercase()
        return when {
            u.contains("BTC") -> btc
            u.contains("ETH") && !u.contains("BTC") -> eth
            u.contains("SOL") -> sol
            else -> 0.0
        }
    }

    fun label(series: String): String? {
        if (headlineCount <= 0) return null
        val p = forSeries(series)
        if (kotlin.math.abs(p) < 0.04) return "news flat ($headlineCount)"
        return String.format(java.util.Locale.US, "news %+.0f ($headlineCount)", p * 100.0)
    }
}

object NewsPulse {
    const val DIM = 16

    val BULL = setOf(
        "etf", "approval", "rally", "surge", "ath", "bull", "adopt", "inflow",
        "breakout", "halving", "spot", "buy"
    )
    val BEAR = setOf(
        "hack", "crash", "sec", "lawsuit", "dump", "bear", "liquidation",
        "outflow", "ban", "exploit", "fraud", "selloff"
    )

    fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

    fun embed(tokens: List<String>): FloatArray {
        val v = FloatArray(DIM)
        for (t in tokens) {
            val h = t.hashCode()
            val idx = (h ushr 1) % DIM
            val sign = if (h < 0) -1f else 1f
            v[idx] += sign * (1f / (1f + ln(1.0 + t.length.toDouble()).toFloat()))
        }
        return v
    }

    fun lexiconScore(tokens: List<String>): Double {
        var s = 0.0
        for (t in tokens) {
            if (t in BULL) s += 1.0
            if (t in BEAR) s -= 1.0
        }
        return (s / (3.0 + tokens.size * 0.05)).coerceIn(-1.0, 1.0)
    }

    fun mentions(tokens: List<String>, asset: String): Boolean {
        val keys = when (asset) {
            "BTC" -> listOf("btc", "bitcoin")
            "ETH" -> listOf("eth", "ethereum")
            "SOL" -> listOf("sol", "solana")
            else -> emptyList()
        }
        return tokens.any { it in keys }
    }

    fun scoreHeadlines(headlines: List<String>, nowMs: Long = 0L, source: String = "lexicon"): NewsPulseSnapshot {
        if (headlines.isEmpty()) return NewsPulseSnapshot(fetchedAtMs = nowMs, source = source)
        val emb = FloatArray(DIM)
        var btc = 0.0
        var eth = 0.0
        var sol = 0.0
        var nB = 0
        var nE = 0
        var nS = 0
        for (h in headlines) {
            val tok = tokenize(h)
            val e = embed(tok)
            for (i in e.indices) emb[i] += e[i]
            val lex = lexiconScore(tok)
            if (mentions(tok, "BTC")) { btc += lex; nB++ }
            if (mentions(tok, "ETH")) { eth += lex; nE++ }
            if (mentions(tok, "SOL")) { sol += lex; nS++ }
        }
        val n = headlines.size.toFloat()
        for (i in emb.indices) emb[i] /= n
        return NewsPulseSnapshot(
            btc = if (nB == 0) 0.0 else (btc / nB).coerceIn(-1.0, 1.0),
            eth = if (nE == 0) 0.0 else (eth / nE).coerceIn(-1.0, 1.0),
            sol = if (nS == 0) 0.0 else (sol / nS).coerceIn(-1.0, 1.0),
            embedding = emb,
            headlineCount = headlines.size,
            source = source,
            fetchedAtMs = nowMs
        )
    }

    fun tiltPp(midPp: Double, pulse: Double): Double =
        (midPp + 4.0 * pulse.coerceIn(-1.0, 1.0)).coerceIn(2.0, 98.0)
}
