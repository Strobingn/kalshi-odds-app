package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Paper AI: every bet inside the limits is placed, settled when the window settles, then a lesson. */
class PaperAiTest {
    private val ticker = "KXBTC15M-26OCT221200-00"

    private fun feed(sec: Int = 60, yesAsk: Double = 55.0, noAsk: Double = 47.0, fairYes: Double? = null): () -> JsonObject = {
        buildJsonObject {
            put("ticker", ticker); put("secondsIntoWindow", sec)
            put("yesAskCents", yesAsk); put("noAskCents", noAsk)
            fairYes?.let { put("fairYesCents", it) }
        }
    }

    private fun ai(answer: String?, f: () -> JsonObject = feed(), calls: IntArray = IntArray(1)) =
        PaperAi(chat = LlmChat { _, _, _ -> calls[0]++; answer }, feed = f)

    @Test
    fun betsAtTheAskWithTheFeeAndSettlesOnTheResult() {
        val p = ai("""{"decision":"YES","contracts":30,"confidence":70,"reason":"momentum"}""")
        assertTrue(p.tick().startsWith("bet YES"))
        val b = p.openBets().single()
        assertEquals(0.55, b.price, 1e-9)
        assertEquals(30, b.contracts)
        assertTrue(b.feeUsd > 0.0)
        p.onSettled(ticker, "yes")
        val s = p.settledBets().single()
        assertEquals(30 * 0.45 - b.feeUsd, s.pnlUsd!!, 1e-3)
        val q = ai("""{"decision":"NO","contracts":1,"confidence":60,"reason":"x"}""")
        q.tick(); q.onSettled(ticker, "yes")
        assertEquals(-(0.47) - q.settledBets().single().feeUsd, q.settledBets().single().pnlUsd!!, 1e-3)
    }

    @Test
    fun betsAgainEveryMinuteWithNoCapAndNeverTooEarlyOrLate() {
        val calls = IntArray(1)
        val p = ai("""{"decision":"YES","contracts":2,"confidence":70,"reason":"again"}""", calls = calls)
        repeat(5) { p.tick() }
        assertEquals(5, calls[0])
        assertEquals(5, p.openBets().size)
        val early = ai("""{"decision":"YES","reason":"x"}""", feed(sec = 5), calls = IntArray(1))
        assertTrue(early.tick().contains("watching"))
        val late = ai("""{"decision":"YES","reason":"x"}""", feed(sec = 890))
        assertTrue(late.tick().contains("too late"))
        assertTrue(late.openBets().isEmpty())
    }

    @Test
    fun anAskOutsideTheLimitsIsRejectedAndContractsAreNotCapped() {
        val cheap = ai("""{"decision":"YES","reason":"long shot"}""", feed(yesAsk = 4.0))
        assertTrue(cheap.tick().startsWith("rejected"))
        assertTrue(cheap.openBets().isEmpty())
        val big = ai("""{"decision":"YES","contracts":25000,"confidence":70,"reason":"all in"}""")
        big.tick()
        assertEquals(25000, big.openBets().single().contracts)
    }

    @Test
    fun noAnswerOrGarbageChangesNothing() {
        assertTrue(ai(null).tick().contains("no answer"))
        assertTrue(ai("not json").tick().contains("not JSON"))
        assertTrue(ai(null).openBets().isEmpty())
    }

    @Test
    fun everyBetInsideTheLimitsIsPlacedAndKeepsTheFairPriceAndBothCases() {
        // A thin edge and a bet against the math still go through: only the limits decide.
        val thin = ai("""{"decision":"YES","contracts":10,"confidence":56,"reason":"maybe"}""")
        assertTrue(thin.tick().startsWith("bet YES"))
        val against = ai(
            """{"decision":"YES","contracts":5,"confidence":90,"bull_case":"above strike","bear_case":"could fade","reason":"hunch"}""",
            feed(fairYes = 40.0)
        )
        assertTrue(against.tick().startsWith("bet YES"))
        val b = against.openBets().single()
        assertEquals(40.0, b.fairCents!!, 1e-9)
        assertEquals("above strike", b.bullCase)
        assertEquals("could fade", b.bearCase)
    }

    @Test
    fun calibrationTrustsOnlyTheEdgeThatShowedUp() {
        fun bet(won: Boolean, i: Int) = PaperBet(
            id = i.toLong(), ticker = "T$i", side = "YES", price = 0.50, contracts = 1, feeUsd = 0.0,
            openedMs = 0L, confidence = 70, reason = "", result = if (won) "YES" else "NO"
        )
        assertEquals(PaperAiCalibration.PRIOR_TRUST, PaperAiCalibration.read(emptyList()).trust, 1e-9)
        // Claimed +20c a bet, won half at 50c: no realised edge, so trust falls under the prior.
        val coinFlip = PaperAiCalibration.read((0 until 40).map { bet(it % 2 == 0, it) })
        assertEquals(20.0, coinFlip.claimedEdgeCents, 1e-9)
        assertEquals(0.0, coinFlip.realisedEdgeCents, 1e-9)
        assertTrue(coinFlip.trust < PaperAiCalibration.PRIOR_TRUST)
        // Won 7 of 10 at 50c claiming 70%: the edge showed up, trust rises above the prior.
        val good = PaperAiCalibration.read((0 until 10).map { bet(it < 7, it) })
        assertTrue(good.trust > PaperAiCalibration.PRIOR_TRUST)
        assertEquals(62.5, PaperAiCalibration.adjust(70.0, 55.0, 0.5), 1e-9)
    }

    @Test
    fun aLessonIsWrittenOnlyAfterTheWindowSettles() {
        val prompts = ArrayList<String>()
        val chat = LlmChat { system, _, _ ->
            prompts += system
            if (system == PaperAi.REFLECT_SYSTEM) """{"lesson":"late 55c YES above the strike paid"}"""
            else """{"decision":"YES","contracts":2,"confidence":70,"reason":"up","lesson":"ignored"}"""
        }
        val p = PaperAi(chat = chat, feed = feed())
        p.tick()
        assertTrue(p.lessons().isEmpty())
        p.onSettled(ticker, "yes")
        p.tick()
        assertEquals(listOf("late 55c YES above the strike paid"), p.lessons())
        assertEquals(PaperAi.REFLECT_SYSTEM, prompts[1])
        // Each settled window is looked back on once.
        p.tick()
        assertEquals(1, prompts.count { it == PaperAi.REFLECT_SYSTEM })
    }

    @Test
    fun theHighestPriceItBuysIs85Cents() {
        val at85 = ai("""{"decision":"YES","contracts":1,"confidence":95,"reason":"favourite"}""", feed(yesAsk = 85.0))
        assertTrue(at85.tick().startsWith("bet YES"))
        val at86 = ai("""{"decision":"YES","contracts":1,"confidence":95,"reason":"favourite"}""", feed(yesAsk = 86.0))
        assertTrue(at86.tick().startsWith("rejected"))
        assertTrue(at86.openBets().isEmpty())
    }

    @Test
    fun aLookBackWithNoAnswerIsTriedAgainNextTick() {
        var reflectAnswer: String? = null
        val reflectCalls = IntArray(1)
        val chat = LlmChat { system, _, _ ->
            if (system == PaperAi.REFLECT_SYSTEM) { reflectCalls[0]++; reflectAnswer }
            else """{"decision":"YES","contracts":1,"confidence":70,"reason":"up"}"""
        }
        val p = PaperAi(chat = chat, feed = feed())
        p.tick(); p.onSettled(ticker, "no")
        p.tick()
        assertTrue(p.lessons().isEmpty())
        reflectAnswer = """{"lesson":"DOWN won; the UP lean was wrong"}"""
        p.tick()
        assertEquals(2, reflectCalls[0])
        assertEquals(listOf("DOWN won; the UP lean was wrong"), p.lessons())
    }
}
