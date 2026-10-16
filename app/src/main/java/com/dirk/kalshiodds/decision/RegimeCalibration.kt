package com.dirk.kalshiodds.decision

import kotlin.math.abs

/**
 * Regime isotonic (PAV) calibration with a three-level hierarchical fallback:
 *
 *   regime (coin | horizon | time-left | z-distance)  →  coin  →  global
 *
 * A level is usable once it has [minSamples] settled predictions. The final
 * 60 seconds never fall back: that regime must be calibrated on its own
 * rows (and not be worse than the market there) before the gate lets a bet
 * through. Brier, log loss, and the reliability buckets are out-of-fold
 * (k-fold, time-ordered folds), so the report is not in-sample.
 *
 * Deterministic: the same rows always produce the same maps. No LLM.
 */
object RegimeCalibration {
    const val VERSION = "regime-pav-v2"
    const val MIN_REGIME = 150
    const val MIN_COIN = 200
    const val MIN_GLOBAL = 300
    const val NEAR_Z = 0.5
    const val FAR_Z = 1.5
    const val FOLDS = 5
    const val RELIABILITY_BINS = 10

    enum class TimeLeft(val label: String) {
        S0_30("0–30s"), S30_60("30–60s"), M1_3("1–3m"), M3_15("3–15m"), H_PLUS(">15m");

        val finalWindow: Boolean get() = this == S0_30 || this == S30_60
    }

    enum class Distance(val label: String) { DEEP_BELOW("z<-1.5"), BELOW("-1.5..-0.5"), NEAR("|z|≤0.5"), ABOVE("0.5..1.5"), DEEP_ABOVE("z>1.5"), UNKNOWN("z?") }

    enum class Level { REGIME, COIN, GLOBAL }

    data class Key(
        val coin: String,
        val horizon: String,
        val time: TimeLeft,
        val distance: Distance
    ) {
        val regimeId: String get() = "${coin.uppercase()}|$horizon|${time.name}|${distance.name}"
        val coinId: String get() = "${coin.uppercase()}|*"
        val finalWindow: Boolean get() = time.finalWindow
        fun idAt(level: Level): String = when (level) {
            Level.REGIME -> regimeId
            Level.COIN -> coinId
            Level.GLOBAL -> GLOBAL_ID
        }
    }

    const val GLOBAL_ID = "*"

    data class Sample(
        val rawProbability: Double,
        val marketProbability: Double,
        val outcomeYes: Boolean,
        val key: Key,
        val timestampMs: Long = 0L,
        /** Market (ticker) id for clustered statistics. */
        val cluster: String = ""
    )

    data class Bucket(val lo: Double, val hi: Double, val n: Int, val meanPredicted: Double, val observedRate: Double)

    data class BucketReport(
        val id: String,
        val level: Level,
        val n: Int,
        val modelBrier: Double?,
        val marketBrier: Double?,
        val rawBrier: Double?,
        val modelLogLoss: Double?,
        val marketLogLoss: Double?,
        val reliability: List<Bucket>,
        val ready: Boolean,
        val notWorseThanMarket: Boolean
    ) {
        val approved: Boolean get() = ready && notWorseThanMarket
    }

    data class Applied(val probability: Double, val level: Level, val n: Int, val approved: Boolean)

    data class Model(
        val maps: Map<String, List<PavIsotonic.Knot>> = emptyMap(),
        val reports: Map<String, BucketReport> = emptyMap(),
        val fittedAtMs: Long = 0L,
        val rows: Int = 0
    ) {
        /** regime → coin → global. Final window: regime only. Null when nothing qualifies. */
        fun apply(rawProbability: Double, key: Key): Applied? {
            if (!rawProbability.isFinite()) return null
            val levels = if (key.finalWindow) listOf(Level.REGIME) else Level.values().toList()
            for (level in levels) {
                val id = key.idAt(level)
                val report = reports[id] ?: continue
                val knots = maps[id]
                if (!report.ready || knots.isNullOrEmpty()) continue
                return Applied(PavIsotonic.apply(rawProbability, knots), level, report.n, report.approved)
            }
            return null
        }

        /** The final 60 seconds are NO BET until that exact regime is calibrated and not worse than the market. */
        fun finalWindowReady(key: Key): Boolean {
            if (!key.finalWindow) return true
            return reports[key.regimeId]?.approved == true
        }

        fun report(id: String): BucketReport? = reports[id]

        val global: BucketReport? get() = reports[GLOBAL_ID]

        fun sortedReports(): List<BucketReport> =
            reports.values.sortedWith(compareBy<BucketReport>({ it.level.ordinal }, { -it.n }, { it.id }))
    }

    fun timeOf(secondsRemaining: Double?): TimeLeft = when {
        secondsRemaining == null -> TimeLeft.H_PLUS
        secondsRemaining <= 30.0 -> TimeLeft.S0_30
        secondsRemaining <= 60.0 -> TimeLeft.S30_60
        secondsRemaining <= 180.0 -> TimeLeft.M1_3
        secondsRemaining <= 900.0 -> TimeLeft.M3_15
        else -> TimeLeft.H_PLUS
    }

    fun distanceOf(z: Double?): Distance = when {
        z == null || !z.isFinite() -> Distance.UNKNOWN
        z < -FAR_Z -> Distance.DEEP_BELOW
        z < -NEAR_Z -> Distance.BELOW
        abs(z) <= NEAR_Z -> Distance.NEAR
        z <= FAR_Z -> Distance.ABOVE
        else -> Distance.DEEP_ABOVE
    }

    fun keyOf(series: String?, secondsRemaining: Double?, z: Double?): Key {
        val s = series?.uppercase().orEmpty()
        val coin = when {
            s.startsWith("KXBTC") -> "BTC"
            s.startsWith("KXETH") -> "ETH"
            s.startsWith("KXSOL") -> "SOL"
            else -> "OTHER"
        }
        val horizon = if (s.endsWith("15M")) "15m" else if (s.endsWith("D")) "daily" else "other"
        return Key(coin, horizon, timeOf(secondsRemaining), distanceOf(z))
    }

    fun minFor(level: Level): Int = when (level) {
        Level.REGIME -> MIN_REGIME
        Level.COIN -> MIN_COIN
        Level.GLOBAL -> MIN_GLOBAL
    }

    fun fit(samples: List<Sample>, nowMs: Long = 0L): Model {
        val clean = samples.filter {
            it.rawProbability.isFinite() && it.marketProbability.isFinite()
        }.sortedWith(compareBy({ it.timestampMs }, { it.cluster }, { it.rawProbability }))
        val maps = LinkedHashMap<String, List<PavIsotonic.Knot>>()
        val reports = LinkedHashMap<String, BucketReport>()
        for (level in Level.values()) {
            val grouped = clean.groupBy { it.key.idAt(level) }
            for ((id, rows) in grouped) {
                maps[id] = PavIsotonic.fit(rows.map { it.rawProbability to if (it.outcomeYes) 1.0 else 0.0 })
                reports[id] = report(id, level, rows)
            }
        }
        return Model(maps, reports, nowMs, clean.size)
    }

    private fun report(id: String, level: Level, rows: List<Sample>): BucketReport {
        val oof = outOfFold(rows)
        val modelPairs = oof
        val marketPairs = rows.map { it.marketProbability to it.outcomeYes }
        val rawPairs = rows.map { it.rawProbability to it.outcomeYes }
        val mb = DecisionMath.brier(modelPairs)
        val kb = DecisionMath.brier(marketPairs)
        val ready = rows.size >= minFor(level)
        return BucketReport(
            id = id,
            level = level,
            n = rows.size,
            modelBrier = mb,
            marketBrier = kb,
            rawBrier = DecisionMath.brier(rawPairs),
            modelLogLoss = DecisionMath.logLoss(modelPairs),
            marketLogLoss = DecisionMath.logLoss(marketPairs),
            reliability = reliability(oof),
            ready = ready,
            notWorseThanMarket = mb != null && kb != null && mb <= kb + 1e-12
        )
    }

    /** Time-ordered contiguous folds: each fold's map is fitted on the other folds only. */
    fun outOfFold(rows: List<Sample>): List<Pair<Double, Boolean>> {
        if (rows.size < FOLDS * 2) return rows.map { it.rawProbability to it.outcomeYes }
        val n = rows.size
        val pred = DoubleArray(n)
        for (fold in 0 until FOLDS) {
            val lo = fold * n / FOLDS
            val hi = (fold + 1) * n / FOLDS
            val train = rows.filterIndexed { i, _ -> i < lo || i >= hi }
            val knots = PavIsotonic.fit(train.map { it.rawProbability to if (it.outcomeYes) 1.0 else 0.0 })
            for (i in lo until hi) pred[i] = PavIsotonic.apply(rows[i].rawProbability, knots)
        }
        return rows.indices.map { pred[it] to rows[it].outcomeYes }
    }

    fun reliability(pairs: List<Pair<Double, Boolean>>, bins: Int = RELIABILITY_BINS): List<Bucket> {
        if (pairs.isEmpty()) return emptyList()
        return (0 until bins).mapNotNull { i ->
            val lo = i / bins.toDouble()
            val hi = (i + 1) / bins.toDouble()
            val inBin = pairs.filter { (p, _) -> if (i == bins - 1) p >= lo else p >= lo && p < hi }
            if (inBin.isEmpty()) null
            else Bucket(
                lo = lo,
                hi = hi,
                n = inBin.size,
                meanPredicted = inBin.map { it.first }.average(),
                observedRate = inBin.count { it.second }.toDouble() / inBin.size
            )
        }
    }
}
