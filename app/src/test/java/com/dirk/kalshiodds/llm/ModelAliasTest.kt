package com.dirk.kalshiodds.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.7: xAI's grok-4 alias answers as grok-4.3 / grok-4-0709; other families are still rejected. */
class ModelAliasTest {
    @Test fun aliasResolvesToVersionsOfSameModel() {
        for (u in listOf("grok-4", "GROK-4", "grok-4.3", "grok-4-0709", "grok-4.3-20260101", "grok-4-2026-01-01"))
            assertTrue(u, LlmClient.sameModel("grok-4", u))
    }

    @Test fun differentFamilyIsRejected() {
        for (u in listOf("grok-3", "grok-3-mini", "grok-4-mini", "grok-4-fast", "grok-4-fast-reasoning", "grok-40", "grok-4.3-mini", "grok", ""))
            assertFalse(u, LlmClient.sameModel("grok-4", u))
    }
}
