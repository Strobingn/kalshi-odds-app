package com.dirk.kalshiodds.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.StrategyLadder
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired
import com.dirk.kalshiodds.signal.notify.TradeEventPolicy
import com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot
import com.dirk.kalshiodds.signal.paper.AutopilotBackoff
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.3.39 release gates (live-arming gates retired in 0.3.40 — Autopilot is paper-only): always-on, backoff, last-minute retired. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseGate0339Test {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun paperAutopilotAndScalpDefaultOnAndKeepServiceAlive() {
        val s = SignalSettings()
        assertTrue(s.paperTradingEnabled)
        assertTrue(s.aiPaperAutopilotEnabled)
        assertFalse(s.liveSignalsEnabled)
        assertTrue(
            AlwaysOnAutopilot.serviceWanted(
                s.liveSignalsEnabled, s.paperTradingEnabled, s.aiPaperAutopilotEnabled
            )
        )
        // Scalp paper follows paper trading even with the AI toggle off.
        assertTrue(AlwaysOnAutopilot.autopilotWanted(true, false))
        // Manual off (paper trading off) is still respected.
        assertFalse(AlwaysOnAutopilot.serviceWanted(false, false, false))
    }

    @Test
    fun autopilotStatePersistsAcrossRestart() {
        // Service mirror is on disk, so boot / START_STICKY / watchdog restarts it.
        LiveSignalsKeepAlive.setEnabled(ctx, true)
        assertTrue(LiveSignalsKeepAlive.isEnabled(ctx))
        assertTrue(LiveSignalsPolicy.shouldStartFromBackground(LiveSignalsKeepAlive.isEnabled(ctx), timeoutPaused = false))
        assertTrue(LiveSignalsPolicy.shouldRestartAfterKill(LiveSignalsKeepAlive.isEnabled(ctx), explicitStop = false))
    }

    @Test
    fun errorBacksOffAndResumesInsteadOfDisabling() {
        assertEquals(30_000L, AutopilotBackoff.delayFor(1))
        assertEquals(60_000L, AutopilotBackoff.delayFor(2))
        assertEquals(480_000L, AutopilotBackoff.delayFor(5))
        assertEquals(600_000L, AutopilotBackoff.delayFor(6))
        assertEquals(600_000L, AutopilotBackoff.delayFor(40))

        val b = AutopilotBackoff()
        val wait = b.onError(1_000L, "HTTP 500")
        assertEquals(30_000L, wait)
        assertTrue(b.blocked(1_000L + 29_999L))
        // After the wait it resumes on its own.
        assertFalse(b.blocked(31_000L))
        // A second error in the same episode doubles; success resets.
        assertEquals(60_000L, b.onError(31_000L, "HTTP 500"))
        assertEquals(1_000L, b.episodeStartMs)
        b.onSuccess()
        assertFalse(b.blocked(31_001L))
        assertEquals(0, b.failures)
        // Alert once per episode.
        val policy = TradeEventPolicy()
        assertTrue(policy.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "backoff:paper:1000"))
        assertFalse(policy.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "backoff:paper:1000"))
    }

    @Test
    fun lastMinutePlayRetiredAndScalpStaysPaperOnly() {
        assertTrue(LastMinuteRetired.retired)
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.Id.SCALP.maxStage)
    }
}
