package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.llm.LlmPaperStrategy
import com.dirk.kalshiodds.update.KashiReleasePolicy
import org.junit.Assert.*
import org.junit.Test

/** Kalshi Scalper 1.0.0 hard rules. */
class ScalperRulesTest {
    @Test fun ceilingIs55AndTrailIs3() {
        assertEquals(0.55, ScalpRule.ENTRY_HI, 1e-9)
        assertEquals(0.55, ScalpV2.MAX_ASK, 1e-9)
        assertEquals(0.03, ScalpV2.TRAIL, 1e-9)
        assertTrue(ScalpV2.entryOk(0.55)); assertFalse(ScalpV2.entryOk(0.56))
        assertEquals("V2 trail 3¢ below peak bid", ScalpV2.peakExit(0.40, 20.0, 0.50, 0.47, ScalpV2.TRAIL))
        assertNull(ScalpV2.peakExit(0.40, 20.0, 0.50, 0.48, ScalpV2.TRAIL))
    }

    @Test fun everyV2SpecIsDistinctAndUnderCeiling() {
        val specs = ScalpV2.EXTRA_SPECS + (1..20).map { ScalpV2.spec(String.format("v2-sf%02d", it)) }
        specs.forEach { assertTrue(it.maxAsk <= 0.55 + 1e-9); assertTrue(it.openMin in 1.0..6.0) }
        assertEquals(specs.size, specs.map { Triple(it.maxAsk, it.openMin, it.base to it.clip) }.distinct().size)
    }

    @Test fun holdGateNeedsOver95() {
        assertEquals(0.94, ScalperBrain.holdConfidence("untrained-x", 0.93, 0.94), 1e-9)
        assertTrue(ScalperBrain.holdConfidence("untrained-y", 0.93, 0.94) <= ScalperBrain.HOLD_CONF)
    }

    @Test fun brainLearnsAndStaysBounded() {
        val x = ScalperBrain.features(0.45, 0.60, 1.0)
        val p0 = ScalperBrain.p("t-brain", x)
        repeat(500) { ScalperBrain.learn("t-brain", x, true) }
        assertTrue(ScalperBrain.p("t-brain", x) > p0)
        assertEquals(ScalperBrain.CONF_MIN, ScalperBrain.minConf("t-brain"), 1e-9)
        repeat(500) { ScalperBrain.learn("t-brain", x, false) }
        assertEquals(ScalperBrain.CONF_MAX, ScalperBrain.minConf("t-brain"), 1e-9)
        assertNotNull(ScalperBrain.parse(ScalperBrain.tag(x)))
    }

    @Test fun llmReplyParsing() {
        assertEquals("YES" to 0.7, LlmPaperStrategy.parse("YES 0.7"))
        assertEquals("NO", LlmPaperStrategy.parse("no 0.62 because")?.first)
        assertNull(LlmPaperStrategy.parse("SKIP"))
        assertEquals(ScalpModels.Model.V2_LLM, ScalpModels.modelByCode("v2-llm"))
    }

    @Test fun identity() {
        assertEquals("com.dirk.kalshiodds.scalp", KashiReleasePolicy.PACKAGE_ID)
        assertEquals("app-id: com.dirk.kalshiodds.scalp", KashiReleasePolicy.APP_ID_MARKER)
        assertTrue(KashiReleasePolicy.TAG.matches("scalp-v1.0.0")); assertFalse(KashiReleasePolicy.TAG.matches("v0.3.77-debug"))
        assertEquals("scalp:", com.dirk.kalshiodds.data.supabase.SupabaseSync.KEY_PREFIX)
    }
}
