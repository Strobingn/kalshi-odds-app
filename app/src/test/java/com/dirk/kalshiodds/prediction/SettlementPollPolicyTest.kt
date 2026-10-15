package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementPollPolicyTest {

    private val close = 1_700_000_900_000L

    @Test
    fun filtersToTrackedKxbtc15mAfterClose() {
        assertTrue(SettlementPollPolicy.isPollableTicker("KXBTC15M-27SEP161500-00"))
        assertFalse(SettlementPollPolicy.isPollableTicker("KXBTCD-27SEP16"))
        assertFalse(SettlementPollPolicy.isPollableTicker("KXBTCY-27SEP16"))
        assertFalse(SettlementPollPolicy.isPollableTicker("KXGRAMMY-BESTSONG"))
        assertFalse(SettlementPollPolicy.isPollableTicker("KXCRYPTOLEAD15M-27SEP16"))
        assertFalse(SettlementPollPolicy.isPollableTicker("KXETH15M-27SEP16"))
        assertFalse(SettlementPollPolicy.afterClose(close, close - 1))
        assertTrue(SettlementPollPolicy.afterClose(close, close))
        assertFalse(
            SettlementPollPolicy.shouldPoll(
                ticker = "KXBTC15M-OPEN",
                closeTimeMs = close + 60_000L,
                nowMs = close,
                nextAttemptMs = 0L,
                inFlight = false
            )
        )
        assertTrue(
            SettlementPollPolicy.shouldPoll(
                ticker = "KXBTC15M-CLOSED",
                closeTimeMs = close,
                nowMs = close + 1,
                nextAttemptMs = 0L,
                inFlight = false
            )
        )
        assertFalse(
            SettlementPollPolicy.shouldPoll(
                ticker = "KXBTC15M-CLOSED",
                closeTimeMs = close,
                nowMs = close + 1,
                nextAttemptMs = 0L,
                inFlight = true
            )
        )
    }

    @Test
    fun backoffStepsThenCaps() {
        assertEquals(5_000L, SettlementPollPolicy.delayMs(0))
        assertEquals(15_000L, SettlementPollPolicy.delayMs(1))
        assertEquals(30_000L, SettlementPollPolicy.delayMs(2))
        assertEquals(60_000L, SettlementPollPolicy.delayMs(3))
        assertEquals(60_000L, SettlementPollPolicy.delayMs(8))
        val first = SettlementPollPolicy.afterMiss(SettlementPollPolicy.Schedule(), close)
        assertEquals(close + 5_000L, first.nextAttemptMs)
        val second = SettlementPollPolicy.afterMiss(first, first.nextAttemptMs)
        assertEquals(first.nextAttemptMs + 15_000L, second.nextAttemptMs)
        val third = SettlementPollPolicy.afterMiss(second, second.nextAttemptMs)
        assertEquals(second.nextAttemptMs + 30_000L, third.nextAttemptMs)
        val fourth = SettlementPollPolicy.afterMiss(third, third.nextAttemptMs)
        assertEquals(third.nextAttemptMs + 60_000L, fourth.nextAttemptMs)
        val fifth = SettlementPollPolicy.afterMiss(fourth, fourth.nextAttemptMs)
        assertEquals(fourth.nextAttemptMs + 60_000L, fifth.nextAttemptMs)
    }

    @Test
    fun honors429RetryAfterAndDedupe() {
        assertEquals(12_000L, SettlementPollPolicy.retryAfterHeader("12"))
        assertEquals(null, SettlementPollPolicy.retryAfterHeader("  "))
        val sched = SettlementPollPolicy.after429(
            SettlementPollPolicy.Schedule(),
            nowMs = close,
            retryAfterMs = 20_000L
        )
        assertEquals(close + 20_000L, sched.nextAttemptMs)
        val tracked = listOf(
            SettlementPollPolicy.Tracked("KXBTC15M-A", close),
            SettlementPollPolicy.Tracked("KXGRAMMY-X", close),
            SettlementPollPolicy.Tracked("KXBTC15M-B", close + 60_000L),
            SettlementPollPolicy.Tracked("KXBTC15M-A", close)
        )
        val due = SettlementPollPolicy.candidates(
            tracked = tracked,
            nowMs = close + 1,
            schedules = emptyMap(),
            inFlight = setOf("KXBTC15M-A")
        )
        assertEquals(emptyList<String>(), due)
        val due2 = SettlementPollPolicy.candidates(
            tracked = tracked,
            nowMs = close + 1,
            schedules = emptyMap(),
            inFlight = emptySet()
        )
        assertEquals(listOf("KXBTC15M-A"), due2)
    }

    @Test
    fun mergeDropsForeignSeriesAndKeepsCloseTimes() {
        val open = listOf(
            PredictionLogEntry(
                ticker = "KXBTC15M-KEEP",
                series = "KXBTC15M",
                predictedYes = 0.6,
                predictedNo = 0.4,
                marketMid = 0.5,
                timestampMs = close - 10,
                closeTimeMs = close
            ),
            PredictionLogEntry(
                ticker = "KXGRAMMY-DROP",
                series = "KXGRAMMY",
                predictedYes = 0.6,
                predictedNo = 0.4,
                marketMid = 0.5,
                timestampMs = close - 10,
                closeTimeMs = close
            )
        )
        val merged = SettlementPollPolicy.mergeTracked(
            open,
            listOf("KXBTCD-NO", "KXBTC15M-PAPER"),
            closeTimeOf = { if (it == "KXBTC15M-PAPER") close else null }
        )
        assertEquals(setOf("KXBTC15M-KEEP", "KXBTC15M-PAPER"), merged.map { it.ticker }.toSet())
        assertEquals(close, merged.first { it.ticker == "KXBTC15M-KEEP" }.closeTimeMs)
    }
}
