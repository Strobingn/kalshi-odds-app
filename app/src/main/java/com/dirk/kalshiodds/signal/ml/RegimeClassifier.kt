package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.engine.RegimeTag
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * On-device regime classifier. Keeps the 0.2.x vol/trend/chop tags and
 * adds session / weekend / news-shock labels that reweight the ensemble.
 */
enum class SessionTag {
    ASIA, EUROPE, US, WEEKEND;

    val label: String
        get() = when (this) {
            ASIA -> "Asia session"
            EUROPE -> "EU session"
            US -> "US session"
            WEEKEND -> "Weekend"
        }
}

data class ExtendedRegime(
    val micro: RegimeTag,
    val session: SessionTag,
    val newsShock: Boolean,
    val scores: Map<String, Double>,
    val label: String
)

object RegimeClassifier {

    fun sessionOf(nowMs: Long, zone: TimeZone = TimeZone.getTimeZone("UTC")): SessionTag {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return SessionTag.WEEKEND
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 0..6 -> SessionTag.ASIA
            in 7..13 -> SessionTag.EUROPE
            else -> SessionTag.US
        }
    }

    /**
     * Softmax over [quiet, trend, vol, chop, news] from a compact feature
     * vector — a tiny linear net, not a remote model.
     */
    fun classify(
        micro: RegimeTag,
        vol: Double,
        velocityPerSec: Double?,
        netPp: Double,
        spotAbs: Double?,
        nowMs: Long
    ): ExtendedRegime {
        val session = sessionOf(nowMs)
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
            .get(Calendar.HOUR_OF_DAY)
        val hs = sin(hour * Math.PI / 12.0)
        val hc = cos(hour * Math.PI / 12.0)
        val vel = abs(velocityPerSec ?: 0.0) * 100.0
        val x = doubleArrayOf(
            vol.coerceIn(0.0, 8.0),
            vel.coerceIn(0.0, 8.0),
            abs(netPp).coerceIn(0.0, 20.0),
            hs,
            hc,
            if (session == SessionTag.WEEKEND) 1.0 else 0.0,
            (spotAbs ?: 0.0).coerceIn(0.0, 0.05) * 40.0
        )
        // Rows: quiet, trend, vol, chop, news
        val w = arrayOf(
            doubleArrayOf(-0.8, -0.6, -0.4, 0.1, 0.1, 0.3, -0.8),
            doubleArrayOf(-0.2, 0.7, 0.6, 0.05, 0.0, -0.2, 0.1),
            doubleArrayOf(0.9, 0.8, 0.2, 0.0, 0.0, -0.1, 0.4),
            doubleArrayOf(0.4, -0.1, -0.3, 0.0, 0.0, 0.1, 0.0),
            doubleArrayOf(0.3, 0.4, 0.2, 0.0, 0.0, -0.2, 1.4)
        )
        val b = doubleArrayOf(0.4, 0.1, -0.2, 0.2, -0.8)
        val logits = DoubleArray(5) { i -> b[i] + (x.indices).sumOf { j -> w[i][j] * x[j] } }
        val scores = softmax(logits)
        val names = listOf("quiet", "trend", "vol", "chop", "news")
        val newsShock = scores[4] >= 0.28 && (spotAbs ?: 0.0) >= 0.004
        val label = buildString {
            append(micro.shortLabel)
            append('/').append(session.name)
            if (newsShock) append("/NEWS")
        }
        return ExtendedRegime(
            micro = micro,
            session = session,
            newsShock = newsShock,
            scores = names.zip(scores.toList()).toMap(),
            label = label
        )
    }

    /**
     * Reweight ensemble members. News/vol → GBM; trend → sequence;
     * weekend → more MLP (thinner microstructure).
     */
    fun scaleStack(base: EnsembleStack.Weights, regime: ExtendedRegime): EnsembleStack.Weights {
        var mlp = base.mlp
        var cnn = base.cnn
        var lstm = base.lstm
        var gbm = base.gbm
        when (regime.micro) {
            RegimeTag.TREND -> { cnn *= 1.25; lstm *= 1.15; mlp *= 0.90 }
            RegimeTag.VOL_SPIKE -> { gbm *= 1.30; cnn *= 0.80; mlp *= 0.85 }
            RegimeTag.CHOP -> { mlp *= 1.15; cnn *= 0.75 }
            RegimeTag.QUIET -> { mlp *= 1.05; gbm *= 0.90 }
        }
        if (regime.newsShock) {
            gbm *= 1.20
            cnn *= 0.85
        }
        if (regime.session == SessionTag.WEEKEND) {
            mlp *= 1.15
            cnn *= 0.80
            lstm *= 0.80
        }
        val sum = (mlp + cnn + lstm + gbm).coerceAtLeast(1e-9)
        return EnsembleStack.Weights(
            mlp = mlp / sum,
            cnn = cnn / sum,
            lstm = lstm / sum,
            gbm = gbm / sum,
            sampleCount = base.sampleCount
        )
    }

    internal fun softmax(z: DoubleArray): DoubleArray {
        val m = z.maxOrNull() ?: 0.0
        val e = z.map { exp(it - m) }
        val s = e.sum().coerceAtLeast(1e-12)
        return e.map { it / s }.toDoubleArray()
    }
}
