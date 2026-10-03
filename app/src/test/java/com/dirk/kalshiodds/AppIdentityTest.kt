package com.dirk.kalshiodds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifier
import com.dirk.kalshiodds.signal.notify.OpportunityNotifier
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import com.dirk.kalshiodds.signal.service.LiveSignalsWatchdogWorker
import com.dirk.kalshiodds.ui.AppVersion
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.worker.BackfillWorker
import com.dirk.kalshiodds.worker.MarketRefreshWorker
import com.dirk.kalshiodds.worker.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppIdentityTest {

    @Test
    fun manifestApplicationIdAndLabelAreKashi() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AppIdentity.APPLICATION_ID, BuildConfig.APPLICATION_ID)
        assertEquals(AppIdentity.APPLICATION_ID, ctx.packageName)
        assertEquals(AppIdentity.LABEL, ctx.getString(R.string.app_name))
        assertEquals(AppIdentity.LABEL, ctx.applicationInfo.loadLabel(ctx.packageManager).toString())
        assertEquals("com.dirk.kalshiodds", AppIdentity.NAMESPACE)
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("${AppIdentity.NAMESPACE}."))
        assertEquals(38, BuildConfig.VERSION_CODE)
        assertEquals("0.3.23", BuildConfig.VERSION_NAME)
        assertEquals(AppIdentity.LABEL, HomeCopy.TITLE)
        assertEquals(AppIdentity.LABEL, LiveSignalsPolicy.NOTIFICATION_TITLE)
        assertTrue(AppVersion.label.startsWith("${AppIdentity.LABEL} v"))
    }

    @Test
    fun gradleKeepsNamespaceAndChangesOnlyApplicationId() {
        val gradle = listOf(
            File("app/build.gradle.kts"),
            File("build.gradle.kts")
        ).first { it.isFile && it.readText().contains("applicationId") }.readText()
        assertTrue(gradle.contains("namespace = \"com.dirk.kalshiodds\""))
        assertTrue(gradle.contains("applicationId = \"com.dirk.kalshiodds.kashi\""))
        assertFalse(gradle.contains("applicationId = \"com.dirk.kalshiodds\""))
        assertTrue(gradle.contains("versionCode = 38"))
        assertTrue(gradle.contains("versionName = \"0.3.23\""))
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertFalse(manifest.contains("android:authorities="))
        assertFalse(manifest.contains("FileProvider"))
        assertFalse(manifest.contains("android:scheme="))
    }
}

class AppIdentityStorageAuditTest {

    @Test
    fun storageNamesMatch0315SoAdbCopyWorks() {
        assertEquals(AppIdentity.DB_RESULTS, SqliteResultsStore.DB_NAME)
        assertEquals(AppIdentity.DB_VERSION, SqliteResultsStore.DB_VERSION)
        assertEquals(6, com.dirk.kalshiodds.data.local.paper.PaperFillSchema.VERSION)
        assertEquals(AppIdentity.DB_VERSION, com.dirk.kalshiodds.data.local.paper.PaperFillSchema.VERSION)
        assertEquals(AppIdentity.PREFS_KEEPALIVE, LiveSignalsPolicy.PREFS_NAME)
        assertEquals("diphunter_paper_book", AppIdentity.PREFS_PAPER)
        assertEquals("diphunter_last_minute", AppIdentity.PREFS_LAST_MINUTE)
        assertEquals("diphunter_d3", AppIdentity.PREFS_D3)
        assertEquals("diphunter_last_order_error", AppIdentity.PREFS_LAST_ORDER)
        assertEquals("diphunter_oom", AppIdentity.PREFS_OOM)
        assertEquals("kalshi_signal_secrets", AppIdentity.PREFS_SECRETS)
        assertEquals("diphunter_signal_prefs", AppIdentity.DS_SIGNAL)
        assertEquals("kalshi_market_cache", AppIdentity.DS_MARKET)
        val src = mainKtSource()
        listOf(
            AppIdentity.DB_RESULTS,
            AppIdentity.PREFS_KEEPALIVE,
            AppIdentity.PREFS_PAPER,
            AppIdentity.PREFS_LAST_MINUTE,
            AppIdentity.PREFS_D3,
            AppIdentity.PREFS_LAST_ORDER,
            AppIdentity.PREFS_OOM,
            AppIdentity.PREFS_SECRETS,
            AppIdentity.PREFS_SECRETS_FALLBACK,
            AppIdentity.PREFS_SECRETS_LEGACY,
            AppIdentity.PREFS_EXTRA,
            AppIdentity.PREFS_EXTRA_FALLBACK,
            AppIdentity.DS_SIGNAL,
            AppIdentity.DS_PREDICTION,
            AppIdentity.DS_GUARDRAILS,
            AppIdentity.DS_ADAPTER,
            AppIdentity.DS_HUB,
            AppIdentity.DS_HEAVY_ML,
            AppIdentity.DS_MARKET
        ).forEach { name ->
            assertTrue("$name must remain the 0.3.15 file name", src.contains("\"$name\""))
        }
    }

    @Test
    fun channelAndWorkNamesArePerApplicationId() {
        assertEquals(AppIdentity.CHANNEL_ALERTS, SignalNotifier.CHANNEL_ALERTS)
        assertEquals(AppIdentity.CHANNEL_FOREGROUND, LiveSignalsPolicy.CHANNEL_ONGOING)
        assertEquals(AppIdentity.CHANNEL_FOREGROUND_LEGACY, LiveSignalsPolicy.CHANNEL_LEGACY)
        assertEquals(AppIdentity.CHANNEL_LAST_MINUTE, LastMinuteNotifier.CHANNEL)
        assertEquals(AppIdentity.CHANNEL_D3, com.dirk.kalshiodds.signal.d3.D3Notifier.CHANNEL)
        assertEquals(AppIdentity.CHANNEL_OPPORTUNITY, OpportunityNotifier.CHANNEL_OPPORTUNITY)
        assertEquals(AppIdentity.WM_LIVE_PERIODIC, LiveSignalsWatchdogWorker.PERIODIC_NAME)
        assertEquals(AppIdentity.WM_LIVE_SOON, LiveSignalsWatchdogWorker.SOON_NAME)
        assertEquals(AppIdentity.WM_CLOUD_SYNC, SyncWorker.UNIQUE)
        assertEquals(AppIdentity.WM_CLOUD_ONCE, SyncWorker.ONCE)
        assertEquals(AppIdentity.WM_BACKFILL, BackfillWorker.UNIQUE)
        assertEquals(AppIdentity.WM_MARKET_REFRESH, MarketRefreshWorker.UNIQUE_NAME)
        assertEquals("com.dirk.kalshiodds.kashi.fileprovider", AppIdentity.FILE_PROVIDER_AUTHORITY)
        assertEquals(
            "package:com.dirk.kalshiodds.kashi",
            LiveSignalsPolicy.batteryPackageUri(AppIdentity.APPLICATION_ID)
        )
    }

    @Test
    fun noHardcodedOldApplicationIdOutsideKotlinPackages() {
        val files = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/res/values/strings.xml"),
            File("src/main/res/values/strings.xml")
        ).filter { it.isFile }
        files.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.name} must not hardcode the old applicationId",
                text.contains("\"com.dirk.kalshiodds\"") && !text.contains("namespace")
            )
        }
        val strings = files.first { it.name == "strings.xml" }.readText()
        assertTrue(strings.contains(">DipHunter (Kashi)<"))
    }

    @Test
    fun launcherIconIsGoldKOnNavy() {
        val fg = listOf(
            File("app/src/main/res/drawable/ic_launcher_foreground.xml"),
            File("src/main/res/drawable/ic_launcher_foreground.xml")
        ).first { it.isFile }.readText()
        val bg = listOf(
            File("app/src/main/res/drawable/ic_launcher_background.xml"),
            File("src/main/res/drawable/ic_launcher_background.xml")
        ).first { it.isFile }.readText()
        val mono = listOf(
            File("app/src/main/res/drawable/ic_launcher_monochrome.xml"),
            File("src/main/res/drawable/ic_launcher_monochrome.xml")
        ).first { it.isFile }.readText()
        val adaptive = listOf(
            File("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"),
            File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        ).first { it.isFile }.readText()
        val adaptiveRound = listOf(
            File("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"),
            File("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml")
        ).first { it.isFile }.readText()
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
        val colors = listOf(
            File("app/src/main/res/values/colors.xml"),
            File("src/main/res/values/colors.xml")
        ).first { it.isFile }.readText()

        assertTrue(fg.contains("#E8B84A"))
        assertTrue(fg.contains("M28,26 h14 v18"))
        assertTrue(fg.contains("M78,44 h9 v20"))
        assertFalse(fg.contains("#3FB950"))
        assertFalse(fg.contains("#E3B341"))
        assertFalse(fg.contains("Kashi badge"))
        assertFalse(fg.contains("M22,40 L38,40"))

        assertTrue(bg.contains("#070B16"))
        assertFalse(bg.contains("#3FB950"))
        assertTrue(colors.contains("ic_launcher_background\">#070B16"))

        assertTrue(mono.contains("#FFFFFF"))
        assertTrue(mono.contains("M28,26 h14 v18"))
        assertFalse(mono.contains("#3FB950"))

        assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_foreground"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_monochrome"))

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))

        val xx = listOf(
            File("app/src/main/res/mipmap-xxhdpi/ic_launcher.png"),
            File("src/main/res/mipmap-xxhdpi/ic_launcher.png")
        ).firstOrNull { it.isFile }
        assertTrue("legacy xxhdpi mipmap missing", xx != null && xx.length() > 0L)
    }

    @Test
    fun wiringAuditListsEveryApplicationIdSurface() {
        val items = AppIdentity.wiringAuditItems()
        listOf(
            "com.dirk.kalshiodds.kashi",
            "FileProvider",
            "ContentProvider",
            "deep links",
            "PendingIntent",
            "package:\$packageName",
            "BuildConfig.APPLICATION_ID",
            "diphunter_results.db",
            "diphunter_keepalive.xml",
            "diphunter_paper_book.xml",
            "diphunter_last_minute.xml",
            "diphunter_d3.xml",
            "diphunter_last_order_error.xml",
            "diphunter_oom.xml",
            "kalshi_signal_secrets.xml",
            "diphunter_extra_secrets.xml",
            "diphunter_signal_prefs.preferences_pb",
            "diphunter_prediction_log.preferences_pb",
            "diphunter_guardrails.preferences_pb",
            "diphunter_adapter.preferences_pb",
            "diphunter_data_hub.preferences_pb",
            "diphunter_heavy_ml.preferences_pb",
            "kalshi_market_cache.preferences_pb",
            "diphunter_signal_alerts",
            "diphunter_live_signals_ongoing",
            "diphunter_last_minute",
            "diphunter_d3",
            "diphunter_opportunities",
            "diphunter_live_signals_watchdog",
            "diphunter_cloud_sync",
            "diphunter-backfill",
            "kalshi_market_refresh_15m"
        ).forEach { needle ->
            assertTrue("wiring audit missing $needle", items.any { it.contains(needle) })
        }
    }

    private fun mainKtSource(): String {
        val root = listOf(File("app/src/main/java"), File("src/main/java")).first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AppIdentity.kt" }
            .joinToString("\n") { it.readText() }
    }
}
