package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

/** 0.3.85 regression: 0.3.83's 1% stop + missing forecast cooldown made fcride/fcfade churn 170 round trips in 35 min. */
class ScalpChurnGuardTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!

    @Test fun stopDistanceIsFlooredBySpreadFeesAndThreeCents() {
        // 30¢ entry bid, 1¢ spread, 10 contracts: 4% = 1.2¢ < spread + fees (~2×1.5¢) + 1¢ → floor wins.
        val d = ScalpRisk.stopDistance(.30, 4.0, .01, 10)
        assertTrue("floor $d", d >= .03 - 1e-12)
        assertEquals(.01 + ScalpRule.feePerContract(10, .31) + ScalpRule.feePerContract(10, .30) + .01, d, 1e-12)
        assertEquals(.03, ScalpRisk.stopDistance(.05, 1.0, 0.0, 1000), 1e-12) // tiny fees, absolute 3¢ minimum
        assertEquals(.80 * .20, ScalpRisk.stopDistance(.80, 20.0, .01, 10), 1e-12) // a wide manual % still wins
        // Measured from the entry bid: the spread at entry, or a one-tick dip, can never trigger it.
        assertFalse(ScalpRisk.flooredHit(.30, .30, 4.0, .01, 10))
        assertFalse(ScalpRisk.flooredHit(.30, .29, 4.0, .01, 10))
        assertFalse(ScalpRisk.flooredHit(.30, .28, 4.0, .01, 10))
        assertTrue(ScalpRisk.flooredHit(.30, .30 - d, 4.0, .01, 10))
        assertEquals(4, ScalpControls().stopLossPercent)
        assertEquals(4, ScalpControls(stopLossPercent = 1, stopPolicyVersion = 1).migrateStopPolicy().stopLossPercent)
        assertEquals(7, ScalpControls(stopLossPercent = 7, stopPolicyVersion = 1).migrateStopPolicy().stopLossPercent)
    }

    @Test fun openPositionDoesNotStopOnOneTickNoiseAroundEntry() {
        val at = close - 600_000L
        val t = ScalpTrade("fc", ticker, "YES", ScalpState.OPEN, at - 2000, .31, .5, contracts = 10,
            entryPrice = .31, entryFeeUsd = .16, entryAtMs = at - 1000,
            note = "riskRefBid=.30 · riskSpread=.01", ruleVersion = "v|${ForecastScalp.variantId(true, 600)}|P")
        val b = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(t)) }, variants = emptyList(), liveExtras = true)
        val rnd = java.util.Random(7)
        for (i in 0 until 200) {
            val bid = .30 + (rnd.nextInt(3) - 1) * .01 // 29–31¢ noise: 1% of 31¢ is a third of a tick
            val ms = at + i * 1000L
            b.onForecastQuote(ScalpRule.Quote(ticker, ms, close, ms, bid, 50.0, bid + .01, 50.0, null, null, null), 10.0, 10.0, false)
        }
        val row = b.allTrades().single { it.id == "fc" }
        assertFalse("stopped on noise: ${row.exitReason}", (row.exitReason ?: "").contains("stop"))
        assertFalse(ScalpRisk.managedExit(row))
    }

    @Test fun cooldownAndWindowCapLimitRoundTripsUnderNoise() {
        val vid = ForecastScalp.variantId(true, 600)
        val start = close - 840_000L
        val trades = ArrayList<ScalpTrade>()
        var n = 0
        var ms = start
        // A strategy that wants to re-enter on every 1 s tick, each trade stopped out 3 s later (the 0.3.83 churn).
        while (ms < start + 35 * 60_000L) {
            if (!ScalpRisk.entryBlocked(trades, vid, ticker, ms)) {
                n++
                trades += ScalpTrade("t$n", ticker, "YES", ScalpState.CLOSED, ms, .31, .5, contracts = 10, entryPrice = .31,
                    entryAtMs = ms, closedAtMs = ms + 3000, exitReason = "stop loss 1% below fill", ruleVersion = "v|$vid|P")
                ms += 3000
            }
            ms += 1000
        }
        // 35 minutes of every-tick stop-outs: the 60 s stop cooldown alone limits this to ~33, versus 171 in the field report.
        assertTrue("round trips $n", n in 1..36)
        for (w in trades.indices) {
            val from = trades[w].entryAtMs!!
            assertTrue(trades.count { it.entryAtMs!! in from until from + ScalpRisk.ROUND_TRIP_WINDOW_MS } <= ScalpRisk.MAX_ROUND_TRIPS_PER_WINDOW)
        }
        // Runaway cap: 20 per strategy per market per 15 min (non-stop exits, 15 s cooldown), other markets unaffected.
        val loop = (0 until 20).map { i -> trades[0].copy(id = "l$i", entryAtMs = start + i * 20_000L, closedAtMs = start + i * 20_000L + 1000, exitReason = "horizon reached") }
        assertTrue(ScalpRisk.entryBlocked(loop, vid, ticker, start + 400_000L))
        assertFalse(ScalpRisk.entryBlocked(loop.dropLast(1), vid, ticker, start + 400_000L))
        assertFalse(ScalpRisk.entryBlocked(loop, vid, "KXBTC15M-26OCT122030-30", start + 400_000L))
        // Stop exit → 60 s cooldown on that market; other strategies and markets are unaffected.
        val one = listOf(trades.first())
        val exit = one[0].closedAtMs!!
        assertTrue(ScalpRisk.entryBlocked(one, vid, ticker, exit + 59_000))
        assertFalse(ScalpRisk.entryBlocked(one, vid, ticker, exit + 60_000))
        assertFalse(ScalpRisk.entryBlocked(one, ForecastScalp.variantId(false, 600), ticker, exit + 1000))
        assertFalse(ScalpRisk.entryBlocked(one, vid, "KXBTC15M-26OCT122030-30", exit + 1000))
        // Non-stop exit keeps the pre-0.3.83 15 s cooldown.
        val tp = listOf(one[0].copy(exitReason = "horizon reached"))
        assertTrue(ScalpRisk.entryBlocked(tp, vid, ticker, exit + 14_000))
        assertFalse(ScalpRisk.entryBlocked(tp, vid, ticker, exit + 15_000))
    }
}
