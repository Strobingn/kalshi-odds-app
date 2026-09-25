package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproveRouterTest {

    /**
     * Broke in 0.3.6 (`e48258e` / v0.3.6-debug / versionCode 21).
     * Still reproduced on 0.3.7 (`a558f6c`): `approveTicket` ran
     * `if (!credentialsConfigured) failSoft(...); return` **before**
     * looking at paper mode, and the Approve dialog confirm was
     * `enabled = credentialsConfigured && canApprove`. Hero Buy UP/DOWN
     * opens that dialog, so Dirk could not paper-buy without a Kalshi key.
     * 0.3.8 routes paper-on + no key to [ApproveRouter.Decision.Paper].
     */
    @Test
    fun regression036HeroApproveWithNoKeyIsPaper_037DidNotFix() {
        val paperOnNoKey = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = false,
            canApprove = true
        )
        assertEquals(
            "0.3.6/0.3.7 required a Kalshi key for hero Approve even in paper mode",
            ApproveRouter.Decision.Paper,
            paperOnNoKey
        )
    }

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

    @Test
    fun paperOnWithKeyIsLiveNotPaper() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true
        )
        assertEquals(
            "0.3.9 swallowed Live Approve when paper was on (default) after the user pasted a key",
            ApproveRouter.Decision.Live,
            d
        )
    }

    @Test
    fun liveIntentNeverRoutesToPaperEvenWhenPaperToggleOn() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true,
            intent = ApproveRouter.Intent.Live
        )
        assertEquals(ApproveRouter.Decision.Live, d)
    }

    @Test
    fun paperIntentIsPaperEvenWhenKeyed() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true,
            intent = ApproveRouter.Intent.Paper
        )
        assertEquals(ApproveRouter.Decision.Paper, d)
    }

    @Test
    fun blockedTicketSurfacesReason() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = false,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = false,
            blockedReason = "No sellers on YES right now"
        )
        val blocked = d as ApproveRouter.Decision.Blocked
        assertEquals("No sellers on YES right now", blocked.reason)
    }
}
