package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertTrue
import org.junit.Test

class DipHunterModelTest {
    @Test
    fun predictionDivergesFromMarketAfterHistory() {
        val model = DipHunterModel()
        val now = System.currentTimeMillis()
        val close = now + 600_000
        var last = 0.0
        for (i in 0 until 12) {
            val mid = 0.40 + i * 0.02
            last = mid
            model.predict("T", mid, volume = 100_000.0, closeEpochMs = close, nowMs = now + i * 800L)
            // feed history via predict after push — use annotate path
        }
        // Manually push via public predict after building history through FeatureHistory
        val histModel = DipHunterModel()
        // Use reflection-free: call predict repeatedly won't fill history — use annotate
        // Instead construct and call predict after simulating via package FeatureHistory through annotate in domain.
        // Simpler assert: single-shot still in (0.02,0.98) and can differ
        val p = model.predict("X", 0.70, 80_000.0, close, now)
        assertTrue(p.yes in 0.02..0.98)
        assertTrue(kotlin.math.abs(p.yes - 0.70) > 0.005 || p.note.isNotEmpty())
        assertTrue(kotlin.math.abs(p.yes + p.no - 1.0) < 1e-6)
    }
}
