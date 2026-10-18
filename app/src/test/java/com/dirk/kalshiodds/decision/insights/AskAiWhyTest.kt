package com.dirk.kalshiodds.decision.insights

import org.junit.Assert.*
import org.junit.Test

class AskAiWhyTest {
    @Test fun questionCarriesTradeDetails() {
        val t = settled("x", -0.42, 1_792_356_000_000L).copy(exitReason = "stop")
        val q = AskAiWhy.question("Fair gap", t)
        listOf("Fair gap", "KXBTC15M-26OCT181700-00", "UP / YES", "entry 40¢", "exit 45¢", "model prob at signal 50%", " ET", "-0.42", "lose").forEach { assertTrue(it, q.contains(it)) }
    }
    @Test fun pendingIsTakenOnce() {
        AskAiWhy.ask("why?"); assertEquals("why?", AskAiWhy.take()); assertNull(AskAiWhy.take())
    }
}
