package com.dirk.kalshiodds.signal.model

/**
 * The live model is clipped to 2–98%. A probability sitting on either
 * bound (or outside it) is clamp-forced: the number is not a real
 * belief, so paper autopilot skips the window and the home card does
 * not score it as an AI pick.
 */
object ProbabilityClamp {
    const val LO = 0.02
    const val HI = 0.98

    fun unit(raw: Double): Double? {
        if (!raw.isFinite()) return null
        return if (raw > 1.0 + 1e-6) raw / 100.0 else raw
    }

    fun binding(raw: Double): Boolean {
        val u = unit(raw) ?: return false
        return u <= LO + 1e-9 || u >= HI - 1e-9
    }
}
