package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproveRouterTest {

    @Test
    fun paperOnNoCredentialsIsPaperNotLive() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = false,
            canApprove = true
        )
        assertEquals(ApproveRouter.Decision.Paper, d)
    }

    @Test
    fun paperOffNoCredentialsBlocksWithVisibleReason() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = false,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = false,
            canApprove = true
        )
        val blocked = d as ApproveRouter.Decision.Blocked
        assertTrue(blocked.reason.contains("Paper trading"))
        assertTrue(blocked.reason.contains("API Key"))
    }

    @Test
    fun liveSellStillNeedsKeyEvenIfPaperToggleOn() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = true,
            liveCredentialsConfigured = false,
            canApprove = true
        )
        val blocked = d as ApproveRouter.Decision.Blocked
        assertTrue(blocked.reason.contains("live sell"))
    }

    @Test
    fun paperOnlySellIsPaperWithoutKey() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = false,
            paperOnly = true,
            isSell = true,
            liveCredentialsConfigured = false,
            canApprove = true
        )
        assertEquals(ApproveRouter.Decision.Paper, d)
    }

    @Test
    fun liveApproveWhenKeyedAndPaperOff() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = false,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true
        )
        assertEquals(ApproveRouter.Decision.Live, d)
    }
}
