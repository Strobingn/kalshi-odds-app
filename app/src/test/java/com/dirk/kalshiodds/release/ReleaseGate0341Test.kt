package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.decision.DecisionPipeline
import com.dirk.kalshiodds.decision.DecisionTestData.samples
import com.dirk.kalshiodds.decision.RegimeCalibration
import com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.3.41 release gate:
 *  - LiveSignalsService never misses its startForeground window (ForegroundServiceDidNotStartInTimeException):
 *    started after the first frame, startForeground first with minimal work, heavy init deferred,
 *    background-start failures fall back to a retrying WorkManager job, correct foregroundServiceType.
 *  - TradeEligibility's $5 minimum is wired: the decision pipeline sizes the Kelly stake.
 */
class ReleaseGate0341Test {
    private fun src(rel: String): String =
        listOf(File("app/src/main/$rel"), File("src/main/$rel")).first { it.exists() }.readText()

    private fun kt(rel: String) = src("java/com/dirk/kalshiodds/$rel")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var i = source.indexOf('{', start)
        var depth = 0
        val from = i
        while (i < source.length) {
            when (source[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return source.substring(from, i + 1) } }
            i++
        }
        return source.substring(from)
    }

    // ---- FGS start timing ----

    @Test
    fun serviceStartIsGatedOnFirstFrame() {
        val keep = kt("signal/service/LiveSignalsKeepAlive.kt")
        val fromUi = body(keep, "fun ensureServiceFromUi(")
        assertTrue(fromUi.contains("if (!firstFrameDrawn.get()) return"))
        assertTrue(fromUi.indexOf("firstFrameDrawn") < fromUi.indexOf("startService"))
        assertTrue(keep.contains("addOnDrawListener"))
        assertTrue(LiveSignalsKeepAlive.FIRST_FRAME_START_DELAY_MS in 500L..5_000L)
        assertTrue(kt("MainActivity.kt").contains("LiveSignalsKeepAlive.startAfterFirstFrame(this)"))
    }

    @Test
    fun applicationOnCreateNeverStartsTheService() {
        assertFalse(LiveSignalsPolicy.shouldPromoteFromApplicationOnCreate())
        val app = body(kt("KalshiOddsApp.kt"), "override fun onCreate()")
        assertFalse(app.contains("startForegroundService"))
        assertFalse(app.contains("LiveSignalsKeepAlive.startService"))
        // WorkManager enqueues moved off the main thread.
        assertTrue(app.contains("appScope.launch(Dispatchers.IO) {\n            runCatching { MarketRefreshScheduler.enqueue"))
        assertTrue(app.contains("appScope.launch(Dispatchers.IO) { runCatching { com.dirk.kalshiodds.worker.SyncWorker.enqueuePeriodic"))
    }

    @Test
    fun startForegroundIsFirstAndMinimal() {
        val svc = kt("signal/service/LiveSignalsService.kt")
        val onCreate = body(svc, "override fun onCreate()")
        val lines = onCreate.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && it != "{" }
        assertEquals("super.onCreate()", lines[0])
        assertEquals("promoteToForeground()", lines[1])
        // Wakelock + rollover run on the service scope (Dispatchers.Default), after startForeground.
        assertTrue(onCreate.indexOf("scope.launch") > onCreate.indexOf("promoteToForeground()"))
        assertTrue(onCreate.indexOf("acquireWakeLock") > onCreate.indexOf("scope.launch"))
        val promote = body(svc, "private fun promoteToForeground()")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse("no container access before startForeground", promote.contains("container"))
        assertFalse(promote.contains("ensureAutopilotHost"))
        // Autopilot host (OddsViewModel) is posted, never inline in onStartCommand.
        assertTrue(svc.contains("Handler(mainLooper).post { runCatching { KalshiOddsApp.from(this).ensureAutopilotHost() } }"))
        val onStart = body(svc, "override fun onStartCommand(")
        assertTrue(onStart.indexOf("promoteToForeground()") < onStart.indexOf("ensureAutopilotHost"))
    }

    @Test
    fun backgroundStartFailureFallsBackToRetryingWork() {
        val keep = kt("signal/service/LiveSignalsKeepAlive.kt")
        val start = body(keep, "fun startService(context: Context, fromForegroundUi: Boolean = false): Boolean")
        assertTrue(start.contains("startForegroundService"))
        assertTrue(start.contains("catch (t: Throwable)"))
        assertTrue(start.contains("enqueueSoon(app)"))
        assertTrue(keep.contains("Result.retry()"))
        assertTrue(keep.contains("BackoffPolicy.EXPONENTIAL"))
        assertTrue(keep.contains("runAttemptCount >= MAX_RETRIES"))
    }

    @Test
    fun manifestDeclaresForegroundServiceTypes() {
        val manifest = src("AndroidManifest.xml")
        val svc = Regex("<service[^>]*LiveSignalsService[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertNotNull(svc)
        assertTrue(svc!!.contains("android:foregroundServiceType=\"specialUse|dataSync\""))
        listOf(
            "android.permission.FOREGROUND_SERVICE\"",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS"
        ).forEach { assertTrue(it, manifest.contains(it)) }
        assertTrue(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        // Runtime types tried are a subset of what the manifest declares.
        val specialUse = 0x40000000
        val dataSync = 1
        listOf(29, 33, 34, 35).forEach { sdk ->
            LiveSignalsPolicy.foregroundServiceTypesToTry(sdk).forEach { t ->
                assertTrue("sdk=$sdk type=$t", t == specialUse || t == dataSync)
            }
        }
    }

    // ---- $5 minimum stake in the decision pipeline ----

    private val now = 1_800_000_000_000L

    private fun input(bankroll: Double?) = DecisionPipeline.Input(
        ticker = "KXBTC15M-26OCT061200-00",
        series = "KXBTC15M",
        nowMs = now,
        closeTimeMs = now + 400_000L,
        rawModelYes = 0.80,
        marketYes = 0.70,
        yesAsk = 0.71,
        noAsk = 0.31,
        yesBid = 0.70,
        noBid = 0.30,
        yesDepth = 500,
        noDepth = 500,
        bookAgeMs = 1_000L,
        spot = 100_000.0,
        strike = 100_000.0,
        volPerSec = 0.0001,
        settlementSource = "Coinbase fallback",
        settlementFresh = true,
        stakeBankrollUsd = bankroll
    )

    private fun ctx(): DecisionPipeline.Context {
        val key = RegimeCalibration.keyOf("KXBTC15M", 400.0, 0.0)
        return DecisionPipeline.Context(RegimeCalibration.fit(samples(key, 2_000, seed = 11)))
    }

    @Test
    fun smallBankrollKellyStakeBelowFiveIsNoBet() {
        val a = DecisionPipeline.assess(input(bankroll = 20.0), ctx())
        val stake = DecisionPipeline.kellyStakeUsd(input(20.0), a.chosen!!)!!
        assertTrue("stake=$stake", AutopilotMinStake.below(stake))
        assertFalse(a.allow)
        assertEquals(AutopilotMinStake.REASON, a.reason)
    }

    @Test
    fun largeBankrollPassesMinimumStake() {
        val a = DecisionPipeline.assess(input(bankroll = 10_000.0), ctx())
        assertTrue("reason=${a.reason}", a.allow)
        val stake = DecisionPipeline.kellyStakeUsd(input(10_000.0), a.chosen!!)!!
        assertFalse("stake=$stake", AutopilotMinStake.below(stake))
    }

    @Test
    fun noBankrollKeepsLegacyBehaviour() {
        val a = DecisionPipeline.assess(input(bankroll = null), ctx())
        assertTrue("reason=${a.reason}", a.allow)
        assertNull(DecisionPipeline.kellyStakeUsd(input(null), a.chosen!!))
    }

    @Test
    fun autopilotPassesFreePaperBankrollIntoThePipeline() {
        val vm = kt("ui/OddsViewModel.kt")
        assertTrue(vm.contains("stakeBankrollUsd = PaperAutopilot.freeBankroll(paperBook.snapshot())"))
        val pipe = kt("decision/DecisionPipeline.kt")
        assertTrue(pipe.contains("stakeUsd = kellyStakeUsd(input, chosen)"))
        assertTrue(pipe.contains("enforceMinStake = input.stakeBankrollUsd != null"))
    }
}
