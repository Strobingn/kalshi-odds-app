package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.ui.HomeMarkets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastOrderErrorOnceTest {

    @Test
    fun acceptNeverRecordsLifecycleNotices() {
        assertNull(LastOrderErrorOnce.accept(null, TicketBuilder.WINDOW_CLOSED))
        assertNull(LastOrderErrorOnce.accept(null, TicketSession.WINDOW_CLOSED))
        assertNull(LastOrderErrorOnce.accept(null, TicketSession.WINDOW_CLOSED_NOTICE))
        assertNull(LastOrderErrorOnce.accept(null, TicketBuilder.MARKET_CLOSED))
        assertNull(LastOrderErrorOnce.accept(null, HomeMarkets.NEXT_WINDOW_LOADING))
        assertNull(LastOrderErrorOnce.accept(null, "  Window closed  "))
        assertNull(LastOrderErrorOnce.accept(null, "  That window closed. Nothing was sent.  "))
        assertNull(LastOrderErrorOnce.accept(null, "  Market closed  "))
        assertNull(LastOrderErrorOnce.accept(null, "  Next window loading  "))
        assertNull(LastOrderErrorOnce.accept("401 reject", TicketSession.WINDOW_CLOSED_NOTICE))
    }

    @Test
    fun acceptKeepsRealPlacementAndConnectionFailures() {
        assertEquals(
            "401 {\"error\":{\"code\":\"INCORRECT_API_KEY_SIGNATURE\"}}",
            LastOrderErrorOnce.accept(
                null,
                "401 {\"error\":{\"code\":\"INCORRECT_API_KEY_SIGNATURE\"}}"
            )
        )
        assertEquals(
            "connection refused",
            LastOrderErrorOnce.accept(null, "connection refused")
        )
        assertNull(
            LastOrderErrorOnce.accept(
                "connection refused",
                "connection refused"
            )
        )
        assertNull(LastOrderErrorOnce.accept("x", "PAPER filled"))
        assertNull(LastOrderErrorOnce.accept(null, "   "))
        assertNull(LastOrderErrorOnce.accept(null, null))
    }

    @Test
    fun shouldClearPersistedWindowClosedAndNoticeOnly() {
        assertTrue(LastOrderErrorOnce.shouldClearPersisted(TicketBuilder.WINDOW_CLOSED))
        assertTrue(LastOrderErrorOnce.shouldClearPersisted(TicketSession.WINDOW_CLOSED))
        assertTrue(LastOrderErrorOnce.shouldClearPersisted(TicketSession.WINDOW_CLOSED_NOTICE))
        assertTrue(LastOrderErrorOnce.shouldClearPersisted("  Window closed  "))
        assertTrue(LastOrderErrorOnce.shouldClearPersisted("  That window closed. Nothing was sent.  "))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted(TicketBuilder.MARKET_CLOSED))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted(HomeMarkets.NEXT_WINDOW_LOADING))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted("401 INCORRECT_API_KEY_SIGNATURE"))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted(null))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted(""))
        assertFalse(LastOrderErrorOnce.shouldClearPersisted("   "))
    }

    @Test
    fun isNotAnOrderErrorCoversLifecycleButNotPlacement() {
        assertTrue(LastOrderErrorOnce.isNotAnOrderError(TicketBuilder.WINDOW_CLOSED))
        assertTrue(LastOrderErrorOnce.isNotAnOrderError(TicketSession.WINDOW_CLOSED_NOTICE))
        assertTrue(LastOrderErrorOnce.isNotAnOrderError(TicketBuilder.MARKET_CLOSED))
        assertTrue(LastOrderErrorOnce.isNotAnOrderError(HomeMarkets.NEXT_WINDOW_LOADING))
        assertFalse(LastOrderErrorOnce.isNotAnOrderError("401 reject"))
        assertFalse(LastOrderErrorOnce.isNotAnOrderError(null))
    }
}
