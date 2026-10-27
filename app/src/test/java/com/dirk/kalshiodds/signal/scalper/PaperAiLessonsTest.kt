package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Paper AI writes a lesson after each window it bet on, and those lessons steer its next bets. Before 1.24.0
 * the reflection was handed no history at all, so it invented one ("historical data shows…") and stored the
 * invention; and the betting prompt took the newest ten lessons, so a losing run filled every slot with "stay out"
 * and the AI stopped betting — which stopped the only source of new lessons.
 */
class PaperAiLessonsTest {

    /** Records each prompt and answers whatever it is told to. */
    private class Spy(private val answers: MutableList<String?>) : LlmChat {
        val systems = ArrayList<String>()
        val users = ArrayList<String>()
        override fun ask(system: String, user: String, tools: JsonArray): String? {
            systems.add(system)
            users.add(user)
            return if (answers.isEmpty()) null else answers.removeAt(0)
        }
    }

    private fun bet(
        id: Long, ticker: String, side: String, conf: Int, result: String?, pnl: Double?
    ) = PaperBet(
        id = id, ticker = ticker, side = side, price = 0.40, contracts = 10, feeUsd = 0.1,
        openedMs = id * 1_000L, confidence = conf, reason = "r", result = result, pnlUsd = pnl, fairCents = 45.0
    )

    private fun feed(secIn: Int = 300) = buildJsonObject {
        put("ticker", "KXBTC15M-NOW")
        put("secondsIntoWindow", secIn)
        put("yesAskCents", 40.0)
        put("noAskCents", 59.0)
        put("fairYesCents", 45.0)
    }

    private fun ai(
        spy: Spy,
        memory: PaperAiMemory = PaperAiMemory(),
        saved: MutableList<String> = ArrayList()
    ) = PaperAi(
        chat = spy,
        feed = { feed() },
        nowMs = { 9_000_000L },
        persistMemory = { saved.add(it) },
        initialMemory = memory
    )

    private fun lastMemory(saved: List<String>) =
        Json { ignoreUnknownKeys = true }.decodeFromString(PaperAiMemory.serializer(), saved.last())

    // ---- the reflection gets real numbers ----

    @Test
    fun theLookBackIsGivenItsRealRecordOverallAndInSimilarSpots() {
        val settled = listOf(
            bet(1, "W-OLD", "NO", 62, "YES", -4.1),
            bet(2, "W-OLD", "NO", 65, "YES", -4.1),
            bet(3, "W-OLD2", "NO", 88, "NO", 6.0),
            bet(4, "W-OLD2", "YES", 65, "YES", 6.0),
            bet(5, "W-NOW", "NO", 62, "YES", -4.0)
        )
        val spy = Spy(mutableListOf("""{"lesson":"0 of 2 NO bets at 60-69 have won; too few to call it a rule."}"""))
        ai(spy, PaperAiMemory(bets = settled, toReflect = listOf("W-NOW"))).tick()

        val ctx = Json.parseToJsonElement(spy.users.first()).jsonObject
        val overall = ctx["yourRecordOverall"]!!.jsonObject
        assertEquals("the window looked back on is left out", 4, overall["settledBets"]!!.jsonPrimitive.int())
        assertEquals(2, overall["won"]!!.jsonPrimitive.int())

        val similar = ctx["yourRecordInSimilarSpots"]!!.jsonArray
        assertEquals("one row per (side, confidence band) in the window", 1, similar.size)
        val row = similar.first().jsonObject
        assertEquals("NO", row["side"]!!.jsonPrimitive.content)
        assertEquals("60-69", row["confidence"]!!.jsonPrimitive.content)
        assertEquals("the two earlier NO bets at 62 and 65", 2, row["settledBets"]!!.jsonPrimitive.int())
        assertEquals(0, row["won"]!!.jsonPrimitive.int())
    }

    @Test
    fun theLookBackIsToldNotToInventAHistoryAndWhenOneWindowIsOnlyOneSample() {
        val s = PaperAi.REFLECT_SYSTEM
        assertTrue(s.contains("NEVER say \"historical data shows\""))
        assertTrue("the threshold is interpolated, not left as a literal", s.contains("fewer than 8 settled bets"))
        assertTrue(s.contains("a losing row of at least 8 bets"))
        assertFalse("the raw template must not survive into the prompt", s.contains("\$MIN_FOR_RULE"))
    }

    // ---- the lesson keeps what the window made ----

    @Test
    fun aLessonIsStoredWithTheWindowsResult() {
        val saved = ArrayList<String>()
        val spy = Spy(mutableListOf("""{"lesson":"NO at 62% was priced right; the window went the other way."}"""))
        val settled = listOf(bet(1, "W-NOW", "NO", 62, "YES", -4.0))
        ai(spy, PaperAiMemory(bets = settled, toReflect = listOf("W-NOW")), saved).tick()

        val m = lastMemory(saved)
        assertEquals(1, m.written.size)
        assertEquals("W-NOW", m.written.first().ticker)
        assertEquals(-4.0, m.written.first().netUsd!!, 1e-9)
    }

    // ---- the betting prompt cannot fill up with "stay out" ----

    @Test
    fun theBettingPromptMixesLessonsFromWinningAndLosingWindows() {
        val lessons = (1..9).map { PaperLesson("avoid $it", "L$it", -2.0, it * 1_000L) } +
            (1..3).map { PaperLesson("keep doing $it", "W$it", 5.0, 100_000L + it) }
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        ai(spy, PaperAiMemory(written = lessons)).tick()

        val shown = Json.parseToJsonElement(spy.users.first()).jsonObject["lessonsFromResults"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, shown.size)
        assertEquals("every winning lesson is kept", 3, shown.count { it.startsWith("keep doing") })
        assertEquals(7, shown.count { it.startsWith("avoid") })
    }

    @Test
    fun withOnlyLosingLessonsItStillShowsTheNewestTen() {
        val lessons = (1..14).map { PaperLesson("avoid $it", "L$it", -2.0, it * 1_000L) }
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        ai(spy, PaperAiMemory(written = lessons)).tick()

        val shown = Json.parseToJsonElement(spy.users.first()).jsonObject["lessonsFromResults"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, shown.size)
        assertEquals("newest first lesson of the ten", "avoid 5", shown.first())
        assertEquals("avoid 14", shown.last())
    }

    // ---- it can see that it has stopped betting ----

    @Test
    fun aRunOfSkipsIsCountedShownAndClearedByABet() {
        val saved = ArrayList<String>()
        val spy = Spy(
            mutableListOf(
                """{"decision":"SKIP","reason":"no edge"}""",
                """{"decision":"SKIP","reason":"no edge"}""",
                """{"decision":"YES","reason":"edge","confidence":70,"contracts":5}"""
            )
        )
        val paper = ai(spy, PaperAiMemory(), saved)
        paper.tick()
        paper.tick()
        assertEquals(2, lastMemory(saved).skipsSinceBet)
        assertEquals(1, Json.parseToJsonElement(spy.users[1]).jsonObject["skipsSinceYourLastBet"]!!.jsonPrimitive.int())

        paper.tick()
        assertEquals("a placed bet clears the run", 0, lastMemory(saved).skipsSinceBet)
        assertEquals(1, paper.openBets().size)
    }

    @Test
    fun theBettingPromptSaysLessonsAreEvidenceNotRules() {
        assertTrue(PaperAi.SYSTEM.contains("treat them as evidence, not as rules"))
        assertTrue(PaperAi.SYSTEM.contains("skipsSinceYourLastBet"))
    }

    // ---- nothing already saved is lost ----

    @Test
    fun lessonsSavedBeforeTheOutcomeWasKeptAreStillRead() {
        val old = """{"bets":[],"lessons":["an old lesson","another"],"toReflect":[]}"""
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(PaperAiMemory.serializer(), old)
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        val paper = ai(spy, m)
        assertEquals(listOf("an old lesson", "another"), paper.lessons())
        paper.tick()
        val shown = Json.parseToJsonElement(spy.users.first()).jsonObject["lessonsFromResults"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("an old lesson", "another"), shown)
    }

    // ---- it learns from the windows it skipped, not only the ones it bet on ----

    @Test
    fun aSkippedWindowIsRememberedWithTheAsksItTurnedDown() {
        val saved = ArrayList<String>()
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        ai(spy, PaperAiMemory(), saved).tick()

        val sk = lastMemory(saved).skips.single()
        assertEquals("KXBTC15M-NOW", sk.ticker)
        assertEquals(40.0, sk.yesAskCents!!, 1e-9)
        assertEquals("YES was 40¢ against a 45¢ fair price", "YES", sk.sideItPassedUp)
        assertNull("not settled yet", sk.wouldHavePaidUsd)
    }

    @Test
    fun oneSkipPerWindowNotOnePerMinute() {
        val saved = ArrayList<String>()
        val spy = Spy(MutableList(4) { """{"decision":"SKIP","reason":"no edge"}""" })
        val paper = ai(spy, PaperAiMemory(), saved)
        repeat(4) { paper.tick() }
        assertEquals(1, lastMemory(saved).skips.size)
        assertEquals(4, lastMemory(saved).skipsSinceBet)
    }

    @Test
    fun onceTheWindowSettlesTheSkipIsScoredAndShownToIt() {
        val saved = ArrayList<String>()
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}""", """{"decision":"SKIP","reason":"no edge"}"""))
        val paper = ai(spy, PaperAiMemory(), saved)
        paper.tick()
        paper.onSettled("KXBTC15M-NOW", "yes")

        val sk = lastMemory(saved).skips.single()
        assertEquals("YES", sk.result)
        // YES at 40¢ wins: 60¢ a contract, less the taker fee.
        assertTrue("the bet it passed up would have paid", sk.wouldHavePaidUsd!! > 0.55)

        paper.tick()
        val cost = Json.parseToJsonElement(spy.users.last()).jsonObject["whatSkippingHasCost"]!!.jsonObject
        assertEquals(1, cost["settledSkipsWithABetYouPassedUp"]!!.jsonPrimitive.int())
        assertEquals(1, cost["thoseThatWouldHaveWon"]!!.jsonPrimitive.int())
        assertTrue(cost["netUsdOneContractEach"]!!.jsonPrimitive.content.toDouble() > 0.55)
    }

    @Test
    fun aSkipThatWasRightScoresAsALoss() {
        val saved = ArrayList<String>()
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        val paper = ai(spy, PaperAiMemory(), saved)
        paper.tick()
        paper.onSettled("KXBTC15M-NOW", "no")
        val sk = lastMemory(saved).skips.single()
        assertTrue("passing on YES at 40¢ saved money", sk.wouldHavePaidUsd!! < 0.0)
    }

    @Test
    fun aVoidedWindowScoresNothing() {
        val saved = ArrayList<String>()
        val spy = Spy(mutableListOf("""{"decision":"SKIP","reason":"no edge"}"""))
        val paper = ai(spy, PaperAiMemory(), saved)
        paper.tick()
        paper.onSettled("KXBTC15M-NOW", "void")
        assertNull(lastMemory(saved).skips.single().wouldHavePaidUsd)
    }

    @Test
    fun bothPromptsAreToldWhatSkippingHasCost() {
        assertTrue(PaperAi.SYSTEM.contains("whatSkippingHasCost"))
        assertTrue(PaperAi.REFLECT_SYSTEM.contains("whatSkippingHasCost"))
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toDouble().toInt()
}
