package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Paper AI: one bounded paper bet per window, settled when the window settles. */
class PaperAiTest {
    private val ticker = "KXBTC15M-26OCT221200-00"

    private fun feed(sec: Int = 60, yesAsk: Double = 55.0, noAsk: Double = 47.0): () -> JsonObject = {
        buildJsonObject {
            put("ticker", ticker); put("secondsIntoWindow", sec)
            put("yesAskCents", yesAsk); put("noAskCents", noAsk)
        }
    }

    private fun ai(answer: String?, f: () -> JsonObject = feed(), calls: IntArray = IntArray(1)) =
        PaperAi(chat = LlmChat { _, _, _ -> calls[0]++; answer }, feed = f)

    @Test
    fun betsAtTheAskWithTheFeeAndSettlesOnTheResult() {
        val p = ai("""{"decision":"YES","contracts":30,"confidence":62,"reason":"momentum"}""")
        assertTrue(p.tick().startsWith("bet YES"))
        val b = p.openBets().single()
        assertEquals(0.55, b.price, 1e-9)
        assertEquals(30, b.contracts)
        assertTrue(b.feeUsd > 0.0)
        p.onSettled(ticker, "yes")
        val s = p.settledBets().single()
        assertEquals(30 * 0.45 - b.feeUsd, s.pnlUsd!!, 1e-3)
        val q = ai("""{"decision":"NO","contracts":1,"reason":"x"}""")
        q.tick(); q.onSettled(ticker, "yes")
        assertEquals(-(0.47) - q.settledBets().single().feeUsd, q.settledBets().single().pnlUsd!!, 1e-3)
    }

    @Test
    fun oneCallPerWindowAndNeverTooEarlyOrLate() {
        val calls = IntArray(1)
        val p = ai("""{"decision":"SKIP","reason":"no edge"}""", calls = calls)
        p.tick(); p.tick(); p.tick()
        assertEquals(1, calls[0])
        val early = ai("""{"decision":"YES","reason":"x"}""", feed(sec = 5), calls = IntArray(1))
        assertTrue(early.tick().contains("watching"))
        val late = ai("""{"decision":"YES","reason":"x"}""", feed(sec = 800))
        assertTrue(late.tick().contains("too late"))
        assertTrue(late.openBets().isEmpty())
    }

    @Test
    fun anAskOutsideTheLimitsIsRejectedAndContractsAreCapped() {
        val cheap = ai("""{"decision":"YES","reason":"long shot"}""", feed(yesAsk = 4.0))
        assertTrue(cheap.tick().startsWith("rejected"))
        assertTrue(cheap.openBets().isEmpty())
        val big = ai("""{"decision":"YES","contracts":500,"reason":"all in"}""")
        big.tick()
        assertEquals(PaperAi.MAX_CONTRACTS, big.openBets().single().contracts)
    }

    @Test
    fun noAnswerOrGarbageChangesNothing() {
        assertTrue(ai(null).tick().contains("no answer"))
        assertTrue(ai("not json").tick().contains("not JSON"))
        assertTrue(ai(null).openBets().isEmpty())
    }
}
