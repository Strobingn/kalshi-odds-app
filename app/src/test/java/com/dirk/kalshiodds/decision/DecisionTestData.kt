package com.dirk.kalshiodds.decision

/** Deterministic synthetic samples for decision tests (fixed LCG; no Random()). */
object DecisionTestData {
    class Lcg(seed: Long) {
        private var s = seed
        fun next(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }

    /** Perfectly calibrated raw model; market is uninformative 0.5 → model beats market. */
    fun samples(
        key: RegimeCalibration.Key,
        n: Int,
        seed: Long = 7L,
        startMs: Long = 1_000L,
        marketP: Double? = 0.5
    ): List<RegimeCalibration.Sample> {
        val r = Lcg(seed)
        return (0 until n).map { i ->
            val p = 0.05 + 0.9 * r.next()
            val y = r.next() < p
            RegimeCalibration.Sample(
                rawProbability = p,
                marketProbability = marketP ?: p,
                outcomeYes = y,
                key = key,
                timestampMs = startMs + i * 1_000L,
                cluster = "${key.regimeId}-$i"
            )
        }
    }

    val BTC_MID = RegimeCalibration.Key("BTC", "15m", RegimeCalibration.TimeLeft.M3_15, RegimeCalibration.Distance.NEAR)
    val BTC_MID2 = RegimeCalibration.Key("BTC", "15m", RegimeCalibration.TimeLeft.M1_3, RegimeCalibration.Distance.NEAR)
    val BTC_FINAL = RegimeCalibration.Key("BTC", "15m", RegimeCalibration.TimeLeft.S0_30, RegimeCalibration.Distance.NEAR)
    val ETH_MID = RegimeCalibration.Key("ETH", "15m", RegimeCalibration.TimeLeft.M3_15, RegimeCalibration.Distance.NEAR)
}
