package com.dirk.kalshiodds.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.decision.LiveBalancePolicy
import com.dirk.kalshiodds.decision.StrategyLadder
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired
import com.dirk.kalshiodds.signal.notify.TradeEventPolicy
import com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot
import com.dirk.kalshiodds.signal.paper.AutopilotBackoff
import com.dirk.kalshiodds.signal.paper.AutopilotDispatch
import com.dirk.kalshiodds.signal.paper.AutopilotMode
import com.dirk.kalshiodds.signal.paper.LiveAutopilotPreflight
import com.dirk.kalshiodds.signal.paper.LiveAutopilotSession
import com.dirk.kalshiodds.signal.paper.SharedPrefsLiveArmStore
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.3.39 release gates: always-on Autopilot, persistent live arming, backoff, last-minute retired. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseGate0339Test {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(SharedPrefsLiveArmStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun liveRequest(armed: Boolean, failClosed: Boolean = false) = AutopilotDispatch.Request(
        mode = AutopilotMode.LIVE,
        masterOn = true,
        decisionOk = true,
        paperFilled = true,
        armed = armed,
        credentialsOk = true,
        failClosed = failClosed,
        paperSide = "YES",
        paperPrice = 0.55,
        shadowSide = "YES",
        shadowPrice = 0.55,
        shadowDepthFill = true,
        shadowAllInUsd = 6.0,
        alreadyAttempted = false
    )

    @Test
    fun paperAutopilotAndScalpDefaultOnAndKeepServiceAlive() {
        val s = SignalSettings()
        assertTrue(s.paperTradingEnabled)
        assertTrue(s.aiPaperAutopilotEnabled)
        assertFalse(s.liveSignalsEnabled)
        assertTrue(
            AlwaysOnAutopilot.serviceWanted(
                s.liveSignalsEnabled, s.paperTradingEnabled, s.aiPaperAutopilotEnabled,
                liveMode = false, liveArmed = false
            )
        )
        // Scalp paper follows paper trading even with the AI toggle off.
        assertTrue(AlwaysOnAutopilot.autopilotWanted(true, false, liveMode = false, liveArmed = false))
        // Manual off (paper trading off, live not armed) is still respected.
        assertFalse(AlwaysOnAutopilot.serviceWanted(false, false, false, liveMode = false, liveArmed = false))
    }

    @Test
    fun autopilotStatePersistsAcrossRestart() {
        // Service mirror is on disk, so boot / START_STICKY / watchdog restarts it.
        LiveSignalsKeepAlive.setEnabled(ctx, true)
        assertTrue(LiveSignalsKeepAlive.isEnabled(ctx))
        assertTrue(LiveSignalsPolicy.shouldStartFromBackground(LiveSignalsKeepAlive.isEnabled(ctx), timeoutPaused = false))
        assertTrue(LiveSignalsPolicy.shouldRestartAfterKill(LiveSignalsKeepAlive.isEnabled(ctx), explicitStop = false))
        // Limited-live arming survives a "restart" (new session over a new store instance).
        val first = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        first.tapApprove()
        assertTrue(first.confirmRealMoney("REAL MONEY"))
        val afterRestart = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        assertTrue(afterRestart.armed)
        assertTrue(AutopilotDispatch.decide(liveRequest(armed = afterRestart.armed)).shouldPlace)
        // Manual disarm also persists.
        afterRestart.disarm()
        assertFalse(LiveAutopilotSession(SharedPrefsLiveArmStore(ctx)).armed)
    }

    @Test
    fun errorBacksOffAndResumesInsteadOfDisabling() {
        assertEquals(30_000L, AutopilotBackoff.delayFor(1))
        assertEquals(60_000L, AutopilotBackoff.delayFor(2))
        assertEquals(480_000L, AutopilotBackoff.delayFor(5))
        assertEquals(600_000L, AutopilotBackoff.delayFor(6))
        assertEquals(600_000L, AutopilotBackoff.delayFor(40))

        val session = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        session.tapApprove()
        session.confirmRealMoney("REAL MONEY")
        val b = AutopilotBackoff()
        val wait = b.onError(1_000L, "HTTP 500")
        assertEquals(30_000L, wait)
        assertTrue(b.blocked(1_000L + 29_999L))
        // While backing off: no send, but still armed and Autopilot still on.
        val during = AutopilotDispatch.decide(liveRequest(armed = session.armed, failClosed = b.blocked(20_000L)))
        assertFalse(during.shouldPlace)
        assertTrue(during.reason.contains("backing off"))
        assertTrue(session.armed)
        assertTrue(LiveAutopilotSession(SharedPrefsLiveArmStore(ctx)).armed)
        // After the wait it resumes on its own.
        assertFalse(b.blocked(31_000L))
        assertTrue(AutopilotDispatch.decide(liveRequest(armed = session.armed, failClosed = b.blocked(31_000L))).shouldPlace)
        // A second error in the same episode doubles; success resets.
        assertEquals(60_000L, b.onError(31_000L, "HTTP 500"))
        assertEquals(1_000L, b.episodeStartMs)
        b.onSuccess()
        assertFalse(b.blocked(31_001L))
        assertEquals(0, b.failures)
        // Alert once per episode.
        val policy = TradeEventPolicy()
        assertTrue(policy.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "backoff:live:1000"))
        assertFalse(policy.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "backoff:live:1000"))
    }

    @Test
    fun liveArmedPersistsButNoBalanceMeansNoOrder() {
        val armedSession = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        armedSession.tapApprove()
        armedSession.confirmRealMoney("REAL MONEY")
        val restarted = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        assertTrue(restarted.armed)
        val now = 10_000_000L
        val missing = LiveAutopilotPreflight.check(
            armed = restarted.armed, decisionOk = true,
            balanceFresh = LiveBalancePolicy.fresh(null, null, now),
            backoffBlocked = false, kellyOk = true, allInUsd = 8.0
        )
        assertFalse(missing.ok)
        assertTrue(missing.paused)
        val stale = LiveAutopilotPreflight.check(
            armed = restarted.armed, decisionOk = true,
            balanceFresh = LiveBalancePolicy.fresh(100.0, now - LiveBalancePolicy.FRESH_MS - 1, now),
            backoffBlocked = false, kellyOk = true, allInUsd = 8.0
        )
        assertFalse(stale.ok)
        // NO BET gate and $5 Kelly skip still apply while armed.
        assertFalse(LiveAutopilotPreflight.check(true, decisionOk = false, balanceFresh = true, backoffBlocked = false, kellyOk = true, allInUsd = 8.0).ok)
        val small = LiveAutopilotPreflight.check(true, decisionOk = true, balanceFresh = true, backoffBlocked = false, kellyOk = true, allInUsd = 4.99)
        assertFalse(small.ok)
        assertEquals(AutopilotMinStake.REASON, small.reason)
        assertTrue(
            LiveAutopilotPreflight.check(true, true, LiveBalancePolicy.fresh(100.0, now - 1_000, now), false, true, 8.0).ok
        )
    }

    @Test
    fun missingRealMoneyArmingMeansNoLiveOrders() {
        val s = LiveAutopilotSession(SharedPrefsLiveArmStore(ctx))
        assertFalse(s.confirmRealMoney("REAL MONEY")) // no Approve yet
        s.tapApprove()
        assertFalse(s.confirmRealMoney(""))
        assertFalse(s.confirmRealMoney("real money"))
        assertFalse(s.confirmRealMoney(null))
        assertFalse(s.armed)
        assertFalse(LiveAutopilotSession(SharedPrefsLiveArmStore(ctx)).armed)
        assertFalse(AutopilotDispatch.decide(liveRequest(armed = s.armed)).shouldPlace)
        assertFalse(LiveAutopilotPreflight.check(s.armed, true, true, false, true, 8.0).ok)
    }

    @Test
    fun lastMinutePlayRetiredAndScalpStaysPaperOnly() {
        assertTrue(LastMinuteRetired.retired)
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.Id.SCALP.maxStage)
    }
}
