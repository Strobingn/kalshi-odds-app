package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.FairGapTune
import com.dirk.kalshiodds.decision.Research
import com.dirk.kalshiodds.decision.ScalpStrategySettings
import com.dirk.kalshiodds.llm.LlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate103FairGapTest {
    @Test fun winnersKeptLosersTweaked() {
        listOf("fair", "fairh60", "v2-fair", "v2-fairh60", Research.HOLD, Research.KELLY, Research.MAKER, "v2-llm").forEach { assertFalse(it, FairGapTune.isTweaked(it)) }
        listOf("dip", "momo", "xrev", "cfr", "mkdip", "fcride", "fcfade", "sf01", "sf41", "sf51", "v2-sf51", "v2-dip", Research.LATE).forEach { assertTrue(it, FairGapTune.isTweaked(it)) }
    }
    @Test fun bandDefaultsAndSliderOverride() {
        assertEquals(0.24, FairGapTune.band(null).lo, 1e-9); assertEquals(0.42, FairGapTune.band(null).hi, 1e-9)
        val b = FairGapTune.band(ScalpStrategySettings(minEntryCents = 30, maxEntryCents = 50))
        assertEquals(0.30, b.lo, 1e-9); assertEquals(0.50, b.hi, 1e-9)
    }
    @Test fun holdExitNoStop() {
        assertNull(FairGapTune.exit(100.0, null))
        assertNotNull(FairGapTune.exit(270.0, null))
        assertNull(FairGapTune.exit(10_000.0, ScalpStrategySettings(maxHoldSeconds = 0))) // 0 = hold to settlement
    }
    @Test fun neverBothSides() {
        assertNotNull(FairGapTune.entryBlock(null, "YES", 0.30, 10, null, otherSideTaken = true, nowMs = 0))
    }
    @Test fun gapUsesKalshiTakerFee() {
        // 10 contracts @ 30¢: ceil(0.07·10·0.3·0.7·100) = 15¢ → 1.5¢/contract
        assertEquals(0.50 - 0.30 - 0.015, FairGapTune.gapAfterFee(0.50, 0.30, 10), 1e-9)
    }
    @Test fun llmNeverSilentlySwitchesModel() {
        assertTrue(LlmClient.sameModel("grok-4", "grok-4"))
        assertTrue(LlmClient.sameModel("grok-4", "grok-4-0709"))
        assertFalse(LlmClient.sameModel("grok-4", "grok-4-fast-reasoning"))
        assertFalse(LlmClient.sameModel("grok-4", "grok-3"))
        assertFalse(LlmClient.sameModel("grok-4", "grok-3-mini"))
    }
}
