package com.dirk.kalshiodds.decision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Grounded evidence cards, number checks, the template fallback, volatility tags,
 * and Dirk's paper defaults.
 */
class EvidenceCardTest {
    @Before fun reset() = clear()
    @After fun done() = clear()

    private fun clear() {
        ModelFilter.resetForTest()
        EvidenceCard.reset()
        VolRegime.reset()
        VolRegimeLedger.reset()
        EvidenceDisagreements.reset()
    }

    @Test fun cardNumbersMatchTheGateInputs() {
        val decision = 1_700_000_400_000L
        val ticks = listOf(
            decision - 180_000L to 100.0,
            decision - 120_000L to 101.0,
            decision - 60_000L to 102.0
        )
        val ask = 0.40
        val ofi = 0.35
        val verdict = HybridQuantGates.evaluate(
            HybridQuantGates.Prefs(), "YES", ask, 102.0, 100.0, 3e-5, 400.0,
            decision, decision - 900_000L, ticks, ofi, true, "enter"
        )
        val bars = HybridQuantGates.completedMinuteBars(ticks, decision)
        val fib = HybridQuantGates.fib(bars, 102.0, 100.0, "YES", ofi, 3e-5)
        val slope1 = HybridQuantGates.trend1m(bars)
        val slope5 = HybridQuantGates.trend5m(bars, decision)
        val student = HftSignals.studentTFair(102.0, 100.0, 3e-5, 400.0, "YES")
        val card = EvidenceCard.build(
            EvidenceCard.Input(
                scalperId = "sf41-logistic",
                side = "YES",
                gateEnter = verdict.enter,
                gateReason = verdict.reason,
                regime = VolRegime.CALM,
                logisticScore = 0.62,
                lgbmScore = 0.01,
                ofi = ofi,
                ofi20 = 0.40,
                ofi5 = 0.10,
                studentT = student,
                entryFair = 0.48,
                ask = ask,
                slope1m = slope1,
                slope5m = slope5,
                pStrongCents = verdict.pStrongCents,
                fibPos = fib.fibPos,
                fibSigned = fib.signedDist,
                fibBarrier = fib.barrier,
                fibRatio = fib.nearestRatio,
                fibLevels = fib.levels,
                bestBid = 0.39,
                bestAsk = ask,
                bidDepth = 100.0,
                askDepth = 80.0,
                spread = 0.01,
                vamp = HftMathEngines.vamp(0.39, ask, 100.0, 80.0),
                timeLeftS = 400.0,
                histN = 4,
                histWins = 3,
                histWinRate = 0.75,
                histAvgPnl = 0.12,
                scoreBucket = "mid"
            )
        )
        assertEquals(verdict.enter, card.gateEnter)
        assertEquals(verdict.reason, card.gateReason)
        assertEquals(ofi, card.ofi, 0.0)
        assertEquals(0.40, card.ofi20, 0.0)
        assertEquals(0.10, card.ofi5, 0.0)
        assertEquals(student!!, card.studentT!!, 1e-12)
        assertEquals(ask, card.ask, 0.0)
        assertEquals(ScalpRule.orderFee(1, ask), card.takerFee, 1e-12)
        assertEquals(student - ask - card.takerFee, card.edgeAfterFee, 1e-9)
        assertEquals(HybridQuantGates.hurdleCents(ask), card.hurdleCents, 1e-9)
        assertEquals(verdict.pStrongCents!!, card.pStrongCents!!, 1e-9)
        assertEquals(slope1!!, card.slope1m!!, 0.0)
        assertEquals(fib.fibPos, card.fibPos)
        assertEquals(fib.barrier, card.fibBarrier)
        assertEquals(fib.levels, card.fibLevels)
        assertEquals(0.39, card.bestBid, 0.0)
        assertEquals(400.0, card.timeLeftS, 0.0)
        assertEquals(4, card.histN)
        assertEquals(3, card.drivers.size)
        val prompt = EvidenceCard.prompt(card)
        assertTrue(EvidenceCard.validates(prompt, EvidenceCard.templateOf(prompt)))
        assertTrue(prompt.contains("gates_enter: ${verdict.enter}"))
        assertTrue(prompt.contains("ofi: 0.3500"))
    }

    @Test fun validatorRejectsInventedNumbersAndFallsBackToTheTemplate() {
        val card = sample(enter = true, logistic = 0.70, ofi = 0.40)
        val prompt = EvidenceCard.prompt(card)
        val invented = "The model likes this because the hidden score is 99.9 and the fee is 12.5.\nAI verdict: take"
        assertFalse(EvidenceCard.validates(prompt, invented))
        val fallback = EvidenceCard.phrase(prompt, invented, card.scalperId)
        assertTrue(fallback.fallback)
        assertFalse(fallback.disagrees)
        assertEquals(EvidenceCard.templateOf(prompt), fallback.text)
        assertTrue(fallback.text.contains("Gates entered."))
        val missing = EvidenceCard.phrase(prompt, null, card.scalperId)
        assertTrue(missing.fallback)
        assertEquals(EvidenceCard.templateOf(prompt), missing.text)
        val blank = EvidenceCard.phrase(prompt, "   ", card.scalperId)
        assertEquals(EvidenceCard.templateOf(prompt), blank.text)
    }

    @Test fun aDisagreeingVerdictIsFlaggedAndDoesNotChangeTheGate() {
        val card = sample(enter = true, logistic = 0.70, ofi = 0.40)
        val prompt = EvidenceCard.prompt(card)
        val template = EvidenceCard.templateOf(prompt)
        val rewritten = template.replace("AI verdict: take", "AI verdict: skip")
        assertTrue(EvidenceCard.validates(prompt, rewritten))
        val phrase = EvidenceCard.phrase(prompt, rewritten, card.scalperId)
        assertFalse(phrase.fallback)
        assertTrue(phrase.disagrees)
        assertTrue(phrase.text.contains("AI disagrees with gates"))
        assertEquals(1, EvidenceDisagreements.snapshot().size)
        assertTrue(EvidenceDisagreements.snapshot().single().gatesEnter)
        assertFalse(EvidenceDisagreements.snapshot().single().llmTake)
        assertTrue(card.gateEnter)
    }

    @Test fun abstainWhenScoreAndOfiPointOppositeWays() {
        val against = sample(enter = true, logistic = 0.80, ofi = -0.50)
        assertTrue(against.abstain)
        assertTrue(against.abstainWhy.contains("OFI"))
        val prompt = EvidenceCard.prompt(against)
        assertTrue(EvidenceCard.templateOf(prompt).contains("Abstain: yes"))
        val aligned = sample(enter = true, logistic = 0.80, ofi = 0.50)
        assertFalse(aligned.abstainWhy, aligned.abstain)
    }

    @Test fun decideAttachesTheSameGateResultTheFilterUsed() {
        val twin = ModelFilter.twin("sf41-logistic")!!
        val now = 1_700_000_500_000L
        val q = ScalpRule.Quote(
            ticker = "KXBTC15M-26OCT221200-00",
            nowMs = now,
            closeMs = now + 400_000L,
            bookAtMs = now,
            yesBid = 0.39,
            yesBidSize = 40.0,
            yesAsk = 0.40,
            yesAskSize = 30.0,
            spot = 100_200.0,
            strike = 100_000.0,
            sigmaPerSec = 2e-5
        )
        ModelFilter.observe(q)
        val d = ModelFilter.decide(twin, q, "YES", 0.40, 0.55)
        assertTrue(d.evidenceText.contains(EvidenceCard.MARK))
        assertTrue(d.evidenceText.contains("gates_enter: ${d.enter}"))
        assertTrue(d.evidenceText.contains("gate_reason: ${d.reason}"))
        assertTrue(d.evidenceText.contains("ofi:"))
        assertTrue(EvidenceCard.validates(d.evidenceText, EvidenceCard.templateOf(d.evidenceText)))
        val explained = ResearchCompanion.explain(ModelFilter.researchLog().last { it.twinId == twin.id })
        assertEquals(EvidenceCard.templateOf(d.evidenceText), explained.cardText)
        assertTrue(explained.cardText.contains(if (d.enter) "Gates entered." else "Gates skipped."))
    }

    @Test fun regimeTagsCompareRealizedVolWithTheBaseline() {
        assertEquals(VolRegime.CALM, VolRegime.tag(0.0002, 0.001))
        assertEquals(VolRegime.NORMAL, VolRegime.tag(0.001, 0.001))
        assertEquals(VolRegime.CHOPPY, VolRegime.tag(0.002, 0.001))
        val calm = bars(0.01)
        val hot = bars(2.5)
        assertTrue(VolRegime.realizedVol(calm)!! < VolRegime.realizedVol(hot)!!)
        val window = "26OCT221200"
        val tagged = VolRegime.observeBars(window, calm)
        assertTrue(tagged == VolRegime.CALM || tagged == VolRegime.NORMAL || tagged == VolRegime.CHOPPY)
        assertEquals(tagged, VolRegime.current(window))
        val note = VolRegime.stamp("model filter", tagged)
        assertEquals(tagged, VolRegime.regimeOf(note))
        assertEquals(note, VolRegime.stamp(note, VolRegime.CHOPPY))
    }

    @Test fun regimeLedgerSplitsPnlAndSimilarSetups() {
        VolRegimeLedger.record("sf41", VolRegime.CALM, 1.25, "a")
        VolRegimeLedger.record("sf41", VolRegime.CALM, -0.25, "b")
        VolRegimeLedger.record("sf41", VolRegime.CHOPPY, -0.50, "c")
        VolRegimeLedger.record("sf41", VolRegime.CALM, 9.0, "a")
        val calm = VolRegimeLedger.breakdown("sf41").single { it.regime == VolRegime.CALM }
        assertEquals(2, calm.n)
        assertEquals(1, calm.wins)
        assertEquals(1.0, calm.netUsd, 1e-9)
        assertEquals(0.5, calm.winRate, 1e-9)
        VolRegimeLedger.addSetup("a", "sf41-logistic", "YES", "mid", VolRegime.CALM, 1.25)
        VolRegimeLedger.addSetup("b", "sf41-logistic", "YES", "mid", VolRegime.CALM, -0.25)
        VolRegimeLedger.addSetup("c", "sf41-logistic", "NO", "mid", VolRegime.CALM, 4.0)
        val sim = VolRegimeLedger.similar("sf41-logistic", "YES", "mid", VolRegime.CALM)
        assertEquals(2, sim.n)
        assertEquals(1, sim.wins)
        assertEquals(0.5, sim.avgPnl, 1e-9)
        val line = VolRegimeLedger.lineFor("sf41")
        assertTrue(line.contains("calm"))
        assertTrue(line.contains("choppy"))
        val trade = ScalpTrade(
            id = "hist-1",
            ticker = "KXBTC15M-26OCT221200-00",
            side = "YES",
            state = ScalpState.CLOSED,
            signalAtMs = 1L,
            signalAsk = 0.4,
            fairAtSignal = 0.5,
            contracts = 50,
            netUsd = 0.8,
            note = "paper · volRegime=normal · scoreBucket=mid",
            ruleVersion = "${ScalpRule.VERSION}|sf41|P"
        )
        VolRegimeLedger.ingest(listOf(trade, trade))
        assertEquals(1, VolRegimeLedger.breakdown("sf41").single { it.regime == VolRegime.NORMAL }.n)
    }

    @Test fun dirkDefaultsStayPut() {
        assertEquals(15, PaperFleetDefaults.BUY_MIN)
        assertEquals(55, PaperFleetDefaults.BUY_MAX)
        assertEquals(25, PaperFleetDefaults.PROFIT_CENTS)
        assertEquals(0, PaperFleetDefaults.OPEN_AT_ONCE)
        assertEquals(50, PaperClip.MIN)
        val factory = PaperFleetDefaults.factory()
        assertEquals(15, factory.minEntryCents)
        assertEquals(55, factory.maxEntryCents)
        assertEquals(25, factory.takeProfitCents)
        assertEquals(0, factory.maxOpenPerSide)
        val prefs = HybridQuantGates.Prefs()
        assertFalse(prefs.mtfAgree)
        assertFalse(prefs.strongMove)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, prefs.fibMode)
        assertFalse(ModelFilter.hybridPrefs().mtfAgree)
        assertFalse(ModelFilter.hybridPrefs().strongMove)
        assertEquals(HybridQuantGates.FibMode.LOG_ONLY, ModelFilter.hybridPrefs().fibMode)
        val healed = PaperFleetDefaults.heal(factory)
        assertEquals(15, healed.minEntryCents)
        assertEquals(55, healed.maxEntryCents)
        assertEquals(25, healed.takeProfitCents)
    }

    private fun sample(enter: Boolean, logistic: Double, ofi: Double): EvidenceCard.Card = EvidenceCard.build(
        EvidenceCard.Input(
            scalperId = "sf41-logistic",
            side = "YES",
            gateEnter = enter,
            gateReason = if (enter) "enter" else HybridQuantGates.MTF_DISAGREE,
            regime = VolRegime.NORMAL,
            logisticScore = logistic,
            lgbmScore = 0.0,
            ofi = ofi,
            ofi20 = ofi,
            ofi5 = ofi,
            studentT = 0.55,
            entryFair = 0.55,
            ask = 0.40,
            slope1m = 1.0,
            slope5m = 1.0,
            pStrongCents = 30.0,
            fibPos = 0.4,
            fibSigned = 0.2,
            fibBarrier = false,
            fibRatio = 0.5,
            fibLevels = listOf(101.0, 102.0, 103.0),
            bestBid = 0.39,
            bestAsk = 0.40,
            bidDepth = 50.0,
            askDepth = 40.0,
            spread = 0.01,
            vamp = 0.395,
            timeLeftS = 400.0
        )
    )

    private fun bars(step: Double): List<HybridQuantGates.MinuteBar> {
        val out = ArrayList<HybridQuantGates.MinuteBar>()
        var px = 100.0
        for (i in 0 until 6) {
            val next = px + if (i % 2 == 0) step else -step / 2.0
            out += HybridQuantGates.MinuteBar(i * 60_000L, (i + 1) * 60_000L, px, maxOf(px, next), minOf(px, next), next)
            px = next
        }
        return out
    }
}
