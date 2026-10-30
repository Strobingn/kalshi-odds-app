package com.dirk.kalshiodds.decision

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
/**
 * Paper Hybrid Quant gates. Defaults stay off / log-only, and the features
 * ignore any bar that is not closed before the decision.
 */
class HybridQuantGatesTest {
    @Before fun reset() = ModelFilter.resetForTest()
    @After fun clear() = ModelFilter.resetForTest()

    @Test fun eachGateIsOffUntilItsOwnSwitchIsOn() {
        val prefs = HybridQuantGates.Prefs()
        assertFalse(prefs.mtfAgree)
        assertFalse(prefs.strongMove)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, prefs.fibMode)
        val disagree = ticks(up = false)
        val off = eval(prefs, "YES", 0.40, 100_000.0, 100_000.0, disagree, priorEnter = true, priorReason = "enter")
        assertTrue(off.enter)
        assertEquals("enter", off.reason)
        val mtf = eval(prefs.copy(mtfAgree = true), "YES", 0.40, 100_000.0, 100_000.0, disagree, priorEnter = true, priorReason = "enter")
        assertFalse(mtf.enter)
        assertEquals(HybridQuantGates.MTF_DISAGREE, mtf.reason)
        val agree = ticks(up = true)
        val mtfPass = eval(prefs.copy(mtfAgree = true), "YES", 0.40, 101_000.0, 100_000.0, agree, priorEnter = true, priorReason = "enter")
        assertTrue(mtfPass.reason, mtfPass.enter)
        val down = eval(prefs.copy(mtfAgree = true), "NO", 0.40, 99_000.0, 100_000.0, disagree, priorEnter = true, priorReason = "enter")
        assertTrue(down.reason, down.enter)
        val downBlocked = eval(prefs.copy(mtfAgree = true), "NO", 0.40, 99_000.0, 100_000.0, agree, priorEnter = true, priorReason = "enter")
        assertEquals(HybridQuantGates.MTF_DISAGREE, downBlocked.reason)

        val weak = eval(prefs.copy(strongMove = true), "YES", 0.50, 100_000.0, 100_000.0, agree, priorEnter = true, priorReason = "enter", sigma = 3e-5, tauS = 600.0)
        assertFalse(weak.reason, weak.enter)
        assertEquals(HybridQuantGates.STRONG_MOVE_LOW, weak.reason)
        val strong = eval(
            prefs.copy(strongMove = true), "YES", 0.20, 101_500.0, 100_000.0, agree,
            priorEnter = true, priorReason = "enter", sigma = 1e-5, tauS = 30.0
        )
        assertTrue(strong.reason, strong.enter)
        assertTrue(strong.pStrongCents!! > HybridQuantGates.hurdleCents(0.20))

        val fibTicks = fibPath()
        val logged = eval(prefs, "YES", 0.20, 102.0, 108.0, fibTicks, priorEnter = true, priorReason = "enter", sigma = 1e-4, tauS = 400.0)
        assertTrue(logged.enter)
        assertTrue(logged.reason.contains(HybridQuantGates.FIB_BARRIER))
        assertTrue(logged.fibBarrier)
        val blocked = eval(prefs.copy(fibMode = HybridQuantGates.FibMode.BLOCK), "YES", 0.20, 102.0, 108.0, fibTicks, priorEnter = true, priorReason = "enter", sigma = 1e-4, tauS = 400.0)
        assertFalse(blocked.enter)
        assertEquals(HybridQuantGates.FIB_BARRIER, blocked.reason)
        val fibOff = eval(prefs.copy(fibMode = HybridQuantGates.FibMode.OFF), "YES", 0.20, 102.0, 108.0, fibTicks, priorEnter = true, priorReason = "enter", sigma = 1e-4, tauS = 400.0)
        assertTrue(fibOff.enter)
        assertFalse(fibOff.reason.contains(HybridQuantGates.FIB_BARRIER))
        assertFalse(fibOff.fibBarrier)
    }

    @Test fun anEarlierGateReasonIsLeftAlone() {
        val v = eval(
            HybridQuantGates.Prefs(mtfAgree = true, strongMove = true, fibMode = HybridQuantGates.FibMode.BLOCK),
            "YES", 0.40, 100.0, 110.0, fibPath(), priorEnter = false, priorReason = "ask above 55¢ ceiling"
        )
        assertFalse(v.enter)
        assertEquals("ask above 55¢ ceiling", v.reason)
    }

    @Test fun featuresIgnoreBarsThatAreNotClosedBeforeTheDecision() {
        val base = 1_700_000_280_000L
        val decision = base + 90_000L
        val closed = listOf(base + 10_000L to 100.0, base + 20_000L to 101.0)
        val leaked = closed + (base + 70_000L to 999.0) + (decision to 5.0) + (decision + 1_000L to 1.0)
        val a = HybridQuantGates.completedMinuteBars(closed, decision)
        val b = HybridQuantGates.completedMinuteBars(leaked, decision)
        assertEquals(a, b)
        assertEquals(1, a.size)
        assertEquals(101.0, a[0].high, 0.0)
        assertTrue(a[0].endMs <= decision)
        assertTrue(leaked.any { it.first >= decision || it.first >= a[0].endMs })
        val fibClean = HybridQuantGates.fib(a, 100.5, 101.0, "YES", 0.0, 1e-4)
        val fibLeak = HybridQuantGates.fib(b, 100.5, 101.0, "YES", 0.0, 1e-4)
        assertEquals(fibClean, fibLeak)
        val withFutureSlope = HybridQuantGates.trend1m(b)
        val without = HybridQuantGates.trend1m(a)
        assertEquals(without, withFutureSlope)
    }

    @Test fun fibDegenerateRangesDoNotDivideByZero() {
        val flat = listOf(bar(0, 100.0), bar(60_000L, 100.0))
        val same = HybridQuantGates.fib(flat, 100.0, 100.0, "YES", -1.0, 1e-5)
        assertNull(same.fibPos)
        assertNull(same.signedDist)
        assertFalse(same.barrier)
        val one = HybridQuantGates.fib(listOf(bar(0, 100.0, 110.0)), 105.0, 120.0, "YES", 0.0, 1e-5)
        assertFalse(one.barrier)
        assertNull(one.fibPos)
        val none = HybridQuantGates.fib(emptyList(), 100.0, 110.0, "YES", 0.0, 0.0)
        assertFalse(none.barrier)
        val wide = listOf(bar(0, 100.0, 110.0), bar(60_000L, 102.0, 104.0))
        val read = HybridQuantGates.fib(wide, 102.0, 108.0, "YES", 0.0, 1e-6)
        assertNotNull(read.fibPos)
        assertNotNull(read.signedDist)
        assertTrue(read.barrier)
        val supported = HybridQuantGates.fib(wide, 102.0, 108.0, "YES", 0.80, 1e-6)
        assertFalse(supported.barrier)
        val alreadyThrough = HybridQuantGates.fib(wide, 112.0, 108.0, "YES", 0.0, 1e-6)
        assertFalse(alreadyThrough.barrier)
    }

    @Test fun strongMoveShrinksAsSigmaAndTimeGrowAndTheBoundaryMovesOut() {
        val k = HybridQuantGates.strongK()
        assertTrue(k > 0.2 && k < 2.0)
        val near = HybridQuantGates.pStrong(100_200.0, 100_000.0, 2e-5, 120.0, "YES", k)!!
        val wider = HybridQuantGates.pStrong(100_200.0, 100_000.0, 8e-5, 120.0, "YES", k)!!
        val later = HybridQuantGates.pStrong(100_200.0, 100_000.0, 2e-5, 600.0, "YES", k)!!
        assertTrue("near $near wider $wider", near > wider)
        assertTrue("near $near later $later", near > later)
        val b1 = HybridQuantGates.strongBoundaryDistance(100_000.0, 2e-5, 120.0, k)
        val b2 = HybridQuantGates.strongBoundaryDistance(100_000.0, 8e-5, 120.0, k)
        val b3 = HybridQuantGates.strongBoundaryDistance(100_000.0, 2e-5, 600.0, k)
        assertTrue(b2 > b1)
        assertTrue(b3 > b1)
        val classes = HybridQuantGates.moveClasses(100_200.0, 100_000.0, 2e-5, 120.0, "YES", k)!!
        assertEquals(1.0, classes.strong + classes.weak + classes.other, 1e-6)
        assertTrue(classes.strong > 0.0 && classes.other >= 0.0 && classes.weak >= 0.0)
        val noNear = HybridQuantGates.pStrong(99_800.0, 100_000.0, 2e-5, 120.0, "NO", k)!!
        val noWider = HybridQuantGates.pStrong(99_800.0, 100_000.0, 8e-5, 120.0, "NO", k)!!
        assertTrue(noNear > noWider)
    }

    @Test fun decideHonorsTheTogglesAndRecordsReasonCodes() {
        val twin = ModelFilter.twin("sf41-logistic")!!
        val q = quote(tauS = 600.0, bid = 0.39, ask = 0.40, spot = 100_000.0, strike = 100_000.0)
        ModelFilter.observe(q)
        val open = ModelFilter.decide(twin, q, "YES", 0.40, 0.48)
        assertTrue(open.reason, open.enter)
        assertFalse(open.reason.contains(HybridQuantGates.MTF_DISAGREE))
        ModelFilter.setMtfAgree(true)
        val blocked = ModelFilter.decide(twin, q.copy(nowMs = q.nowMs + 1_000L), "YES", 0.40, 0.48)
        assertFalse(blocked.enter)
        assertEquals(HybridQuantGates.MTF_DISAGREE, blocked.reason)
        ModelFilter.setMtfAgree(false)
        ModelFilter.setStrongMove(false)
        val priced = ModelFilter.decide(twin, q.copy(nowMs = q.nowMs + 2_000L, yesBid = 0.48, yesAsk = 0.50), "YES", 0.50, 0.70)
        assertTrue(priced.reason, priced.enter)
        ModelFilter.setStrongMove(true)
        val low = ModelFilter.decide(twin, q.copy(nowMs = q.nowMs + 3_000L, yesBid = 0.48, yesAsk = 0.50), "YES", 0.50, 0.70)
        assertFalse(low.reason, low.enter)
        assertEquals(HybridQuantGates.STRONG_MOVE_LOW, low.reason)
        assertNotNull(low.pStrongCents)
        val line = ModelFilter.gateReasonLine()
        assertTrue(line, line.contains(HybridQuantGates.MTF_DISAGREE) && line.contains("blocked"))
        assertTrue(line, line.contains(HybridQuantGates.STRONG_MOVE_LOW))
        assertTrue(line, line.contains(HybridQuantGates.FIB_BARRIER))
        val page = ResearchCompanion.journal()
        assertTrue(page.regimes.any { it.group == "Hybrid gate" && it.name == HybridQuantGates.MTF_DISAGREE })
    }

    @Test fun fibLogOnlyStaysOnTheJournalRowAndBlockRejects() {
        val twin = ModelFilter.twin("sf41-logistic")!!
        val base = 1_700_000_280_000L
        val decision = base + 180_000L
        val spots = listOf(base + 10_000L to 100_000.0, base + 70_000L to 100_040.0, base + 130_000L to 100_010.0)
        spots.forEach { (at, px) -> ModelFilter.observe(quote(now = at, tauS = 600.0, spot = px, strike = 100_030.0, bid = 0.19, ask = 0.20)) }
        val q = quote(now = decision, tauS = 600.0, spot = 100_010.0, strike = 100_030.0, bid = 0.19, ask = 0.20)
        val logged = ModelFilter.decide(twin, q, "YES", 0.20, 0.55)
        assertTrue(logged.reason, logged.enter)
        assertTrue(logged.reason, logged.reason.contains(HybridQuantGates.FIB_BARRIER))
        assertNotNull(logged.pStrongCents)
        val gates = ScalpGateLog()
        gates.flag(twin.id, "win", HybridQuantGates.FIB_BARRIER)
        gates.entered(twin.id, "win")
        val snap = gates.snapshot(listOf(twin.id))
        assertEquals(HybridQuantGates.FIB_BARRIER, snap.single().reason)
        assertNull(gates.line(twin.id, "win"))
        ModelFilter.setFibMode(HybridQuantGates.FibMode.BLOCK)
        val blocked = ModelFilter.decide(twin, q.copy(nowMs = decision + 1_000L), "YES", 0.20, 0.55)
        assertFalse(blocked.enter)
        assertEquals(HybridQuantGates.FIB_BARRIER, blocked.reason)
    }

    @Test fun prefsPersistAndAdviceDoesNotFlipThem() {
        val dir = File("build/hybrid-gate-prefs").apply { mkdirs() }
        val file = File(dir, "model-filter-runtime.json")
        file.writeText("""{"mode":"LOGISTIC","path":"CPU_FAST","manual":false}""")
        ModelFilter.bindGateFile(file)
        ModelFilter.setMtfAgree(true)
        ModelFilter.setStrongMove(true)
        ModelFilter.setFibMode(HybridQuantGates.FibMode.BLOCK)
        ModelFilter.applySessionAdvice()
        val kept = ModelFilter.hybridPrefs()
        assertTrue(kept.mtfAgree)
        assertTrue(kept.strongMove)
        assertEquals(HybridQuantGates.FibMode.BLOCK, kept.fibMode)
        val saved = JSONObject(file.readText())
        assertEquals("LOGISTIC", saved.getString("mode"))
        assertTrue(saved.getBoolean("mtfAgree"))
        assertEquals("BLOCK", saved.getString("fibMode"))
        ModelFilter.resetForTest()
        assertFalse(ModelFilter.hybridPrefs().mtfAgree)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        ModelFilter.bindGateFile(file)
        assertTrue(ModelFilter.hybridPrefs().mtfAgree)
        assertTrue(ModelFilter.hybridPrefs().strongMove)
        assertEquals(HybridQuantGates.FibMode.BLOCK, ModelFilter.hybridPrefs().fibMode)
        assertEquals(15, PaperFleetDefaults.BUY_MIN)
        assertEquals(55, PaperFleetDefaults.BUY_MAX)
        assertEquals(25, PaperFleetDefaults.PROFIT_CENTS)
        assertEquals(0, PaperFleetDefaults.OPEN_AT_ONCE)
    }

    @Test fun aiLearnAndTheTunerDoNotOwnTheNewToggles() {
        for (code in listOf("sf41", "sf42", "sf43")) {
            val fields = PaperSettingTuner.fieldsFor(code)
            assertFalse(fields.contains("mtfAgree"))
            assertFalse(fields.contains("strongMove"))
            assertFalse(fields.contains("fibMode"))
        }
        val tape = PaperTape("sf42", 12, 10, 4.0, 0.8, List(12) { 0.3 }, emptyList())
        val changes = PaperSettingTuner.propose(ScalpControls(), listOf(tape))
        assertTrue(changes.none { it.setting == "mtfAgree" || it.setting == "strongMove" || it.setting == "fibMode" })
        assertFalse(ModelFilter.hybridPrefs().mtfAgree)
        assertFalse(ModelFilter.hybridPrefs().strongMove)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        val bounds = source("AiLearnBounds.kt")
        val tuner = source("PaperSettingTuner.kt")
        assertFalse(bounds.contains("mtfAgree") || bounds.contains("strongMove") || bounds.contains("fibMode"))
        assertFalse(tuner.contains("mtfAgree") || tuner.contains("setFibMode") || tuner.contains("setStrongMove"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/java/com/dirk/kalshiodds/decision/$name"),
        File("app/src/main/java/com/dirk/kalshiodds/decision/$name")
    ).first { it.isFile }.readText()

    private fun bar(start: Long, low: Double, high: Double = low) = HybridQuantGates.MinuteBar(
        start, start + HybridQuantGates.MINUTE_MS, low, high, low, (low + high) / 2.0
    )

    private fun ticks(up: Boolean): List<Pair<Long, Double>> {
        val base = 1_700_000_200_000L
        val decision = base + 600_000L
        val out = ArrayList<Pair<Long, Double>>()
        for (i in 0 until 10) {
            val px = if (up) 100.0 + i else 110.0 - i
            out += (base + i * 60_000L + 1_000L) to px
        }
        assertTrue(out.all { it.first < decision })
        return out
    }

    private fun fibPath(): List<Pair<Long, Double>> {
        val base = 1_700_000_280_000L
        return listOf(base + 10_000L to 100.0, base + 70_000L to 110.0, base + 130_000L to 102.0)
    }

    private fun eval(
        prefs: HybridQuantGates.Prefs,
        side: String,
        ask: Double,
        spot: Double,
        strike: Double,
        ticks: List<Pair<Long, Double>>,
        priorEnter: Boolean,
        priorReason: String,
        sigma: Double = 3e-5,
        tauS: Double = 400.0
    ): HybridQuantGates.Verdict {
        val decision = ticks.maxOf { it.first } + 120_000L
        return HybridQuantGates.evaluate(
            prefs, side, ask, spot, strike, sigma, tauS, decision, decision - 900_000L,
            ticks, 0.0, priorEnter, priorReason
        )
    }

    private fun quote(
        now: Long = 1_700_000_000_000L,
        tauS: Double = 600.0,
        bid: Double = 0.39,
        ask: Double = 0.40,
        spot: Double = 100_000.0,
        strike: Double = 100_000.0
    ) = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT221200-00",
        nowMs = now,
        closeMs = now + (tauS * 1000.0).toLong(),
        bookAtMs = now,
        yesBid = bid,
        yesBidSize = 20.0,
        yesAsk = ask,
        yesAskSize = 20.0,
        spot = spot,
        strike = strike,
        sigmaPerSec = 3e-5
    )
}
