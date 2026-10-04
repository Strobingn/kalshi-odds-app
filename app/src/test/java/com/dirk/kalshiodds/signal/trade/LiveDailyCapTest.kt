package com.dirk.kalshiodds.signal.trade

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDailyCapTest {

    private val day = "2026-10-04"
    private val next = "2026-10-05"

    @Test
    fun defaultIsFiftyDollarsAndOn() {
        val s = LiveDailyCap.State()
        assertEquals(50.0, s.capUsd, 1e-9)
        assertTrue(s.enabled)
        assertEquals(50.0, LiveDailyCap.remainingUsd(s, day)!!, 1e-9)
        assertTrue("daily_live_cap" in LiveOrderGates.catalog.map { it.id })
    }

    @Test
    fun buysFitUntilTheCapThenBlockWithAReason() {
        var s = LiveDailyCap.State(capUsd = 15.0)
        repeat(3) {
            assertNull(LiveDailyCap.blockReason(s, day, 5.0))
            s = LiveDailyCap.record(s, day, 5.0)
        }
        assertEquals(15.0, s.spentUsd, 1e-9)
        assertEquals(3, s.orders)
        val reason = LiveDailyCap.blockReason(s, day, 5.0)
        assertNotNull(reason)
        assertEquals(
            "Daily live cap: $15.00 of $15 already sent today, so this $5.00 order would pass it. " +
                "Nothing was sent. Change it in Settings → Live Approve tickets.",
            reason
        )
        assertEquals(0.0, LiveDailyCap.remainingUsd(s, day)!!, 1e-9)
    }

    @Test
    fun exactFitPassesAndOneCentOverBlocks() {
        val s = LiveDailyCap.record(LiveDailyCap.State(capUsd = 10.0), day, 5.02)
        assertNull(LiveDailyCap.blockReason(s, day, 4.98))
        assertNotNull(LiveDailyCap.blockReason(s, day, 4.99))
    }

    @Test
    fun newDayResetsTheCountAndKeepsTheCap() {
        val spent = LiveDailyCap.record(LiveDailyCap.State(capUsd = 10.0), day, 9.0)
        assertNotNull(LiveDailyCap.blockReason(spent, day, 5.0))
        assertNull(LiveDailyCap.blockReason(spent, next, 5.0))
        val rolled = LiveDailyCap.rolled(spent, next)
        assertEquals(0.0, rolled.spentUsd, 1e-9)
        assertEquals(0, rolled.orders)
        assertEquals(10.0, rolled.capUsd, 1e-9)
        assertEquals(next, rolled.day)
        val again = LiveDailyCap.record(spent, next, 5.0)
        assertEquals(5.0, again.spentUsd, 1e-9)
        assertEquals(1, again.orders)
    }

    @Test
    fun zeroCapTurnsItOff() {
        val off = LiveDailyCap.record(LiveDailyCap.State(capUsd = 0.0), day, 400.0)
        assertFalse(off.enabled)
        assertNull(LiveDailyCap.blockReason(off, day, 5.0))
        assertNull(LiveDailyCap.remainingUsd(off, day))
        assertEquals("Daily live cap  off", LiveDailyCap.settingsLabel(off, day))
    }

    @Test
    fun unknownCostIsTreatedAsTheFiveDollarLiveCap() {
        val s = LiveDailyCap.record(LiveDailyCap.State(capUsd = 10.0), day, 6.0)
        assertNotNull(LiveDailyCap.blockReason(s, day, null))
        assertNotNull(LiveDailyCap.blockReason(s, day, Double.NaN))
        assertNotNull(LiveDailyCap.blockReason(s, day, 0.0))
        assertNull(LiveDailyCap.blockReason(LiveDailyCap.State(capUsd = 10.0), day, null))
        // A bad cost is never counted.
        assertEquals(6.0, LiveDailyCap.record(s, day, Double.NaN).spentUsd, 1e-9)
        assertEquals(1, LiveDailyCap.record(s, day, -3.0).orders)
    }

    @Test
    fun releaseGivesBackButNeverBelowZero() {
        val s = LiveDailyCap.record(LiveDailyCap.State(), day, 5.0)
        assertEquals(2.0, LiveDailyCap.release(s, day, 3.0).spentUsd, 1e-9)
        assertEquals(0.0, LiveDailyCap.release(s, day, 9.0).spentUsd, 1e-9)
        assertEquals(5.0, LiveDailyCap.release(s, day, 0.0).spentUsd, 1e-9)
        // Yesterday's order cancelled today releases nothing from today.
        assertEquals(0.0, LiveDailyCap.release(s, next, 5.0).spentUsd, 1e-9)
    }

    @Test
    fun clampKeepsTheCapInRange() {
        assertEquals(0.0, LiveDailyCap.clampCap(-5.0), 1e-9)
        assertEquals(LiveDailyCap.MAX_CAP_USD, LiveDailyCap.clampCap(9_999.0), 1e-9)
        assertEquals(LiveDailyCap.DEFAULT_CAP_USD, LiveDailyCap.clampCap(Double.NaN), 1e-9)
        assertEquals(25.0, LiveDailyCap.clampCap(25.0), 1e-9)
    }

    @Test
    fun dayKeyIsTheLocalCalendarDay() {
        val ny = ZoneId.of("America/New_York")
        // 2026-10-05 03:30 UTC is still 4 Oct, 11:30 PM in New York.
        val ms = 1791171000000L
        assertEquals("2026-10-04", LiveDailyCap.dayKey(ms, ny))
        assertEquals("2026-10-05", LiveDailyCap.dayKey(ms, ZoneId.of("UTC")))
    }

    @Test
    fun costOfUsesTheAllInFigureFirst() {
        assertEquals(4.97, LiveDailyCap.costOf(ticket(allIn = 4.97)), 1e-9)
        assertEquals(4.75, LiveDailyCap.costOf(ticket(allIn = null, fill = 4.75)), 1e-9)
        assertEquals(5.0, LiveDailyCap.costOf(ticket(allIn = null, fill = 0.0, stake = 5.0)), 1e-9)
        assertEquals(LiveOrderGates.LIVE_ALL_IN, LiveDailyCap.costOf(ticket(allIn = null, fill = 0.0, stake = 0.0)), 1e-9)
    }

    @Test
    fun cancelReleasesOnlyWhatKalshiReportedCancelled() {
        val t = ticket(allIn = 5.0)
        fun order(error: String?) = PlacedOrder(
            ticket = t,
            clientOrderId = "c",
            orderId = "o",
            fillCount = 0.0,
            remainingCount = 10.0,
            averageFillPrice = null,
            placedAtMs = 0L,
            error = error
        )
        assertEquals(5.0, LiveDailyCap.cancelledCostOf(order("cancelled (−10)")), 1e-9)
        assertEquals(5.0, LiveDailyCap.cancelledCostOf(order("cancelled (−10.00)")), 1e-9)
        assertEquals(2.0, LiveDailyCap.cancelledCostOf(order("cancelled (−4)")), 1e-9)
        // More than the order size is clipped to the whole order.
        assertEquals(5.0, LiveDailyCap.cancelledCostOf(order("cancelled (−40)")), 1e-9)
        // No count from Kalshi: release nothing.
        assertEquals(0.0, LiveDailyCap.cancelledCostOf(order("cancelled")), 1e-9)
        assertEquals(0.0, LiveDailyCap.cancelledCostOf(order(null)), 1e-9)
    }

    @Test
    fun settingsLabelShowsCapAndTodaysCount() {
        var s = LiveDailyCap.State()
        assertEquals("Daily live cap  $50  ·  $0.00 sent today (0 orders)", LiveDailyCap.settingsLabel(s, day))
        s = LiveDailyCap.record(s, day, 4.97)
        assertEquals("Daily live cap  $50  ·  $4.97 sent today (1 order)", LiveDailyCap.settingsLabel(s, day))
        assertEquals("Daily live cap  $50  ·  $0.00 sent today (0 orders)", LiveDailyCap.settingsLabel(s, next))
    }

    @Test
    fun storePersistsAndRollsOverAtMidnight() {
        var saved: String? = null
        var now = 1791136800000L // 2026-10-04 18:00 UTC
        val utc = ZoneId.of("UTC")
        val store = LiveDailyCapStore(load = { saved }, save = { saved = it }, nowMs = { now }, zone = { utc })
        store.setCapUsd(10.0)
        assertNull(store.blockReason(5.0))
        store.record(5.0)
        store.record(5.0)
        assertNotNull(store.blockReason(5.0))
        assertEquals(10.0, store.snapshot().spentUsd, 1e-9)

        // A new process reads the same day back.
        val reopened = LiveDailyCapStore(load = { saved }, save = { saved = it }, nowMs = { now }, zone = { utc })
        assertEquals(10.0, reopened.snapshot().spentUsd, 1e-9)
        assertEquals(10.0, reopened.snapshot().capUsd, 1e-9)
        assertNotNull(reopened.blockReason(5.0))

        // Cancel gives it back.
        reopened.release(5.0)
        assertNull(reopened.blockReason(5.0))

        // Next day: count is zero, cap kept.
        now += 24L * 60L * 60L * 1000L
        assertNull(reopened.blockReason(5.0))
        assertEquals(0.0, reopened.snapshot().spentUsd, 1e-9)
        assertEquals(10.0, reopened.snapshot().capUsd, 1e-9)
        assertEquals("2026-10-05", reopened.snapshot().day)
    }

    @Test
    fun storeSurvivesCorruptJson() {
        val store = LiveDailyCapStore(load = { "{not json" }, save = { })
        assertEquals(LiveDailyCap.DEFAULT_CAP_USD, store.snapshot().capUsd, 1e-9)
        assertEquals(0.0, store.snapshot().spentUsd, 1e-9)
    }

    private fun ticket(allIn: Double?, fill: Double = 5.0, stake: Double = 5.0) = TradeTicket(
        id = "t1",
        ticker = "KXBTC15M-26OCT041430-30",
        side = "YES",
        bookSide = "bid",
        stakeUsd = stake,
        limitPrice = 0.50,
        yesLimitPrice = 0.50,
        contracts = 10,
        estimatedFillUsd = fill,
        maxPayoutUsd = 10.0,
        estimatedAvgFill = 0.50,
        sizingNote = "test",
        kind = TicketKind.MANUAL,
        allInUsd = allIn
    )
}
