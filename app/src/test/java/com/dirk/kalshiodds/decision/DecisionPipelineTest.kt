package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.decision.DecisionTestData.samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class DecisionPipelineTest {
    private val now = 1_800_000_000_000L

    private fun input(
        secondsLeft: Double = 400.0,
        raw: Double = 0.80,
        market: Double = 0.70,
        yesAsk: Double = 0.71,
        noAsk: Double = 0.31,
        depth: Int = 500,
        bookAge: Long? = 1_000L,
        fresh: Boolean = true,
        cf: DecisionPipeline.CfSnapshot? = null
    ) = DecisionPipeline.Input(
        ticker = "KXBTC15M-26OCT061200-00",
        series = "KXBTC15M",
        nowMs = now,
        closeTimeMs = now + (secondsLeft * 1000).toLong(),
        rawModelYes = raw,
        marketYes = market,
        yesAsk = yesAsk,
        noAsk = noAsk,
        yesBid = yesAsk - 0.01,
        noBid = noAsk - 0.01,
        yesDepth = depth,
        noDepth = depth,
        bookAgeMs = bookAge,
        spot = 100_000.0,
        strike = 100_000.0,
        volPerSec = 0.0001,
        settlementSource = if (cf != null) "CF BRTI" else "Coinbase fallback",
        settlementFresh = fresh,
        cf = cf
    )

    private fun calibratedCtx(): DecisionPipeline.Context {
        val key = RegimeCalibration.keyOf("KXBTC15M", 400.0, 0.0)
        return DecisionPipeline.Context(RegimeCalibration.fit(samples(key, 2_000, seed = 11)))
    }

    @Test
    fun zDistanceMatchesSpec() {
        val z = DecisionPipeline.zDistance(101.0, 100.0, 0.001, 400.0)!!
        assertEquals(ln(101.0 / 100.0) / (0.001 * sqrt(400.0)), z, 1e-9)
    }

    @Test
    fun zSettleUsesRunningFinalMinuteAverage() {
        // Inside the last minute the fixed part of the average comes from CF's running average.
        val above = DecisionPipeline.zSettle(100.0, 100.0, 0.001, 20.0, runningFinalAvg = 101.0)!!
        val below = DecisionPipeline.zSettle(100.0, 100.0, 0.001, 20.0, runningFinalAvg = 99.0)!!
        assertTrue(above > 0.0)
        assertTrue(below < 0.0)
        // Before the minute: std σ√(T−40).
        val early = DecisionPipeline.zSettle(101.0, 100.0, 0.001, 400.0, null)!!
        assertEquals(ln(1.01) / (0.001 * sqrt(360.0)), early, 1e-9)
    }

    @Test
    fun coldStartIsNoBetWithReason() {
        val a = DecisionPipeline.assess(input())
        assertFalse(a.allow)
        assertEquals(TradeEligibility.UNCALIBRATED_REASON, a.reason)
        assertNull(a.calibratedYes)
        // Market-as-prior still bounded with the unapproved cap.
        assertEquals(0.72, a.finalYes!!, 1e-9)
    }

    @Test
    fun finalWindowColdStartShowsFinalWindowReason() {
        val a = DecisionPipeline.assess(input(secondsLeft = 45.0))
        assertEquals("NO BET — model uncertainty too high for this final-window regime", a.reason)
    }

    @Test
    fun calibratedRegimeCanBetAndUsesBoundedCorrection() {
        val a = DecisionPipeline.assess(input(), calibratedCtx())
        assertNotNull(a.calibratedYes)
        assertTrue(a.regimeApproved)
        assertTrue(kotlin.math.abs(a.correction) <= MarketPrior.CAP + 1e-12)
        assertTrue("reason=${a.reason}", a.allow)
        assertEquals("YES", a.chosen!!.side)
        assertTrue(a.chosen!!.expectedNetPerContract > 0.0)
    }

    @Test
    fun staleBookAndStaleSettlementBlock() {
        val ctx = calibratedCtx()
        assertEquals(TradeEligibility.BOOK_REASON, DecisionPipeline.assess(input(bookAge = 120_000L), ctx).reason)
        assertEquals(TradeEligibility.BOOK_REASON, DecisionPipeline.assess(input(bookAge = null), ctx).reason)
        assertEquals(TradeEligibility.SETTLEMENT_STALE_REASON, DecisionPipeline.assess(input(fresh = false), ctx).reason)
    }

    @Test
    fun featuresHashIsStableAndSensitive() {
        val a = DecisionPipeline.assess(input())
        val b = DecisionPipeline.assess(input())
        assertEquals(a.featuresHash, b.featuresHash)
        assertEquals(16, a.featuresHash.length)
        assertNotEquals(a.featuresHash, DecisionPipeline.assess(input(raw = 0.81)).featuresHash)
    }

    @Test
    fun ledgerRowRecordsEverythingTheSpecAsks() {
        val i = input(cf = DecisionPipeline.CfSnapshot("BRTI", 100_000.0, 99_990.0, null, null))
        val a = DecisionPipeline.assess(i)
        val row = DecisionPipeline.ledgerRow(i, a, "regime-pav-v2@0")
        assertEquals(now, row.timestampMs)
        assertEquals(i.ticker, row.ticker)
        assertNotNull(row.modelVersion)
        assertEquals(a.featuresHash, row.featuresHash)
        assertEquals(0.80, row.rawModelProb!!, 1e-12)
        assertNull(row.calibratedProb)
        assertEquals(0.70, row.marketMid!!, 1e-12)
        assertNotNull(row.yesAsk)
        assertNotNull(row.depthAtBest)
        assertEquals("NO BET", row.decision)
        assertTrue(row.reasonCodes!!.startsWith("NO BET"))
        assertEquals("CF BRTI", row.settlementSource)
        assertNull(row.settlementResult)
    }

    @Test
    fun lookAheadInvariance() {
        // A fit as of time T must not change when rows predicted or settled after T are added.
        val key = RegimeCalibration.keyOf("KXBTC15M", 400.0, 0.0)
        val past = samples(key, 400, seed = 21).mapIndexed { i, s -> row(s, i, settledAt = s.timestampMs + 900_000L) }
        val asOf = past.maxOf { it.settledAtMs!! }
        val futureSettled = samples(key, 300, seed = 22, startMs = 100L).mapIndexed { i, s ->
            row(s, 10_000 + i, settledAt = asOf + 1)
        }
        val futurePredicted = samples(key, 300, seed = 23, startMs = asOf + 5).mapIndexed { i, s ->
            row(s, 20_000 + i, settledAt = asOf + 10_000_000L)
        }
        val m1 = RegimeCalibration.fit(DecisionRuntime.samplesAsOf(past, asOf), asOf)
        val m2 = RegimeCalibration.fit(DecisionRuntime.samplesAsOf(past + futureSettled + futurePredicted, asOf), asOf)
        assertEquals(m1.maps, m2.maps)
        assertEquals(m1.reports, m2.reports)
        val i = input()
        assertEquals(
            DecisionPipeline.assess(i, DecisionPipeline.Context(m1)),
            DecisionPipeline.assess(i, DecisionPipeline.Context(m2))
        )
    }

    private fun row(s: RegimeCalibration.Sample, i: Int, settledAt: Long) = LedgerRow(
        timestampMs = s.timestampMs,
        modelVersion = "t",
        ticker = "KXBTC15M-T$i",
        series = "KXBTC15M",
        settlementRule = "cf-60s-average",
        settlementSource = "CF BRTI",
        secondsRemaining = 400.0,
        spot = 100.0,
        targetStrike = 100.0,
        zDistance = 0.0,
        rawModelProb = s.rawProbability,
        calibratedProb = null,
        marketMid = s.marketProbability,
        bid = null,
        ask = null,
        spread = null,
        depthAtBest = null,
        imbalance = null,
        bookAgeMs = null,
        decision = "NO BET",
        sizeContracts = 0.0,
        reasonCodes = "",
        settlementResult = if (s.outcomeYes) "yes" else "no",
        settledAtMs = settledAt
    )
}
