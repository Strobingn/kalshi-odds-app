package com.dirk.kalshiodds.decision

/**
 * Out-of-fold isotonic calibration by regime.
 *
 * A bucket is usable at [MIN_SAMPLES] settled rows. Smaller buckets fall
 * back by dropping vol, then liquidity, then distance, then price, hours,
 * coin, and series. The final 60 seconds may not fall back to a wider
 * time bucket. If nothing qualifies, calibrated probability is unavailable.
 *
 * The map applied to a new quote is the full-bucket PAV. Brier, log loss,
 * and the reliability chart are 5-fold out of fold so they are not
 * in-sample.
 */
object RegimeCalibration {
    const val MIN_SAMPLES = 200
    const val NEAR_Z = 0.5
    const val LIQUID_SPREAD = 0.04
    const val LIQUID_DEPTH = 20.0
    const val HIGH_VOL = 0.80
    const val FOLDS = 5

    enum class TimeLeft { S0_30, S30_60, M1_3, M3_PLUS }
    enum class Distance { FAR_BELOW, NEAR, FAR_ABOVE }
    enum class Liquidity { THIN, LIQUID }
    enum class Vol { CALM, HIGH }

    data class Key(
        val time: TimeLeft? = null,
        val distance: Distance? = null,
        val liquidity: Liquidity? = null,
        val vol: Vol? = null,
        val priceLevel: String? = null,
        val hoursToClose: String? = null,
        val coin: String? = null,
        val series: String? = null
    ) {
        fun lockTime(): Boolean = time == TimeLeft.S0_30 || time == TimeLeft.S30_60

        fun broader(lockTime: Boolean = false): Key? = when {
            vol != null -> copy(vol = null)
            liquidity != null -> copy(liquidity = null)
            distance != null -> copy(distance = null)
            priceLevel != null -> copy(priceLevel = null)
            hoursToClose != null -> copy(hoursToClose = null)
            coin != null -> copy(coin = null)
            series != null -> copy(series = null)
            time != null && !lockTime -> copy(time = null)
            else -> null
        }
    }

    data class Sample(
        val probability: Double,
        val marketProbability: Double,
        val outcomeYes: Boolean,
        val key: Key
    )

    data class BucketReport(
        val key: String,
        val n: Int,
        val modelBrier: Double?,
        val marketBrier: Double?,
        val modelLogLoss: Double?,
        val marketLogLoss: Double?,
        val brierCi95: Pair<Double, Double>?,
        val reliability: List<PavIsotonic.Knot>,
        val ready: Boolean,
        val notWorseThanMarket: Boolean
    )

    data class Model(
        val maps: Map<String, List<PavIsotonic.Knot>> = emptyMap(),
        val counts: Map<String, Int> = emptyMap(),
        val reports: List<BucketReport> = emptyList()
    ) {
        fun apply(probability: Double, key: Key): Double? {
            var cursor: Key? = key
            val lock = key.lockTime()
            while (cursor != null) {
                val id = idOf(cursor)
                val n = counts[id] ?: 0
                val knots = maps[id]
                if (n >= MIN_SAMPLES && !knots.isNullOrEmpty()) {
                    return PavIsotonic.apply(probability, knots)
                }
                cursor = cursor.broader(lock)
            }
            return null
        }

        fun regimeApproved(key: Key): Boolean {
            val report = reportFor(key) ?: return false
            return report.ready && report.notWorseThanMarket
        }

        fun finalWindowReady(key: Key): Boolean {
            if (!key.lockTime()) return true
            return regimeApproved(key)
        }

        private fun reportFor(key: Key): BucketReport? {
            var cursor: Key? = key
            val lock = key.lockTime()
            while (cursor != null) {
                val id = idOf(cursor)
                reports.firstOrNull { it.key == id && it.ready }?.let { return it }
                cursor = cursor.broader(lock)
            }
            return null
        }
    }

    fun timeOf(seconds: Double?): TimeLeft = when {
        seconds == null || seconds <= 30.0 -> TimeLeft.S0_30
        seconds <= 60.0 -> TimeLeft.S30_60
        seconds <= 180.0 -> TimeLeft.M1_3
        else -> TimeLeft.M3_PLUS
    }

    fun distanceOf(z: Double?): Distance = when {
        z == null || kotlin.math.abs(z) <= NEAR_Z -> Distance.NEAR
        z > 0.0 -> Distance.FAR_ABOVE
        else -> Distance.FAR_BELOW
    }

    fun liquidityOf(spread: Double?, depth: Double?): Liquidity {
        if (spread == null || depth == null) return Liquidity.THIN
        return if (spread <= LIQUID_SPREAD && depth >= LIQUID_DEPTH) Liquidity.LIQUID else Liquidity.THIN
    }

    fun volOf(realizedVolAnnual: Double?): Vol =
        if (realizedVolAnnual != null && realizedVolAnnual >= HIGH_VOL) Vol.HIGH else Vol.CALM

    fun priceLevel(prob: Double): String = when {
        prob < 0.05 -> "lt5c"
        prob < 0.25 -> "5-25c"
        prob < 0.75 -> "25-75c"
        prob < 0.95 -> "75-95c"
        else -> "gt95c"
    }

    fun hoursToClose(seconds: Double?): String = when {
        seconds == null -> "unknown"
        seconds < 3600.0 -> "lt1h"
        seconds < 3.0 * 3600.0 -> "1-3h"
        seconds < 8.0 * 3600.0 -> "3-8h"
        else -> "gt8h"
    }

    fun idOf(key: Key): String = listOf(
        key.time?.name ?: "*",
        key.distance?.name ?: "*",
        key.liquidity?.name ?: "*",
        key.vol?.name ?: "*",
        key.priceLevel ?: "*",
        key.hoursToClose ?: "*",
        key.coin ?: "*",
        key.series ?: "*"
    ).joinToString("|")

    fun fit(samples: List<Sample>): Model {
        val grouped = samples.groupBy { idOf(it.key) }
        val maps = HashMap<String, List<PavIsotonic.Knot>>()
        val counts = HashMap<String, Int>()
        val reports = ArrayList<BucketReport>()
        for ((id, rows) in grouped) {
            counts[id] = rows.size
            val pairs = rows.map { it.probability to if (it.outcomeYes) 1.0 else 0.0 }
            maps[id] = PavIsotonic.fit(pairs)
            val oof = outOfFold(rows)
            val modelPairs = oof.map { it.first to it.second }
            val marketPairs = rows.map { it.marketProbability to it.outcomeYes }
            val mb = DecisionMath.brier(modelPairs)
            val kb = DecisionMath.brier(marketPairs)
            val squared = oof.map { (p, y) ->
                val t = if (y) 1.0 else 0.0
                val d = p - t
                d * d
            }
            val ready = rows.size >= MIN_SAMPLES
            reports += BucketReport(
                key = id,
                n = rows.size,
                modelBrier = mb,
                marketBrier = kb,
                modelLogLoss = DecisionMath.logLoss(modelPairs),
                marketLogLoss = DecisionMath.logLoss(marketPairs),
                brierCi95 = DecisionMath.meanCi95(squared),
                reliability = reliability(oof),
                ready = ready,
                notWorseThanMarket = mb != null && kb != null && mb <= kb + 1e-12
            )
        }
        return Model(maps, counts, reports.sortedBy { it.key })
    }

    fun outOfFold(rows: List<Sample>): List<Pair<Double, Boolean>> {
        if (rows.size < 2) return rows.map { it.probability to it.outcomeYes }
        val k = FOLDS.coerceAtMost(rows.size)
        val order = rows.indices.sortedBy { rows[it].probability }
        val pred = DoubleArray(rows.size)
        for (fold in 0 until k) {
            val test = order.filterIndexed { i, _ -> i % k == fold }
            val train = order.filterIndexed { i, _ -> i % k != fold }
            val knots = PavIsotonic.fit(train.map { rows[it].probability to if (rows[it].outcomeYes) 1.0 else 0.0 })
            for (i in test) pred[i] = PavIsotonic.apply(rows[i].probability, knots)
        }
        return rows.indices.map { pred[it] to rows[it].outcomeYes }
    }

    private fun reliability(oof: List<Pair<Double, Boolean>>): List<PavIsotonic.Knot> {
        if (oof.isEmpty()) return emptyList()
        val bins = 8
        return (0 until bins).mapNotNull { i ->
            val lo = i / bins.toDouble()
            val hi = (i + 1) / bins.toDouble()
            val inBin = oof.filter { (p, _) ->
                if (i == bins - 1) p >= lo else p >= lo && p < hi
            }
            if (inBin.isEmpty()) null
            else PavIsotonic.Knot(
                x = inBin.map { it.first }.average(),
                y = inBin.count { it.second }.toDouble() / inBin.size
            )
        }
    }
}
