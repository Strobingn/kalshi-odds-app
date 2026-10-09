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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

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
        assertEquals(60, BuildConfig.VERSION_CODE)
        assertEquals("0.3.45", BuildConfig.VERSION_NAME)
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
        assertTrue(gradle.contains("versionCode = 60"))
        assertTrue(gradle.contains("versionName = \"0.3.45\""))
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains(".fileprovider"))
        assertFalse(manifest.contains("android:scheme="))
    }
}

class AppIdentityStorageAuditTest {

    @Test
    fun storageNamesMatch0315SoAdbCopyWorks() {
        assertEquals(AppIdentity.DB_RESULTS, SqliteResultsStore.DB_NAME)
        assertEquals(AppIdentity.DB_VERSION, SqliteResultsStore.DB_VERSION)
        assertEquals(8, com.dirk.kalshiodds.data.local.paper.PaperFillSchema.VERSION)
        assertEquals(AppIdentity.DB_VERSION, com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.VERSION) // 0.3.43 v9
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
        assertTrue(strings.contains(">Kalshi Trader<"))
    }

    @Test
    fun launcherIconIsTraderMark() {
        val bg = listOf(
            File("app/src/main/res/drawable/ic_launcher_background.xml"),
            File("src/main/res/drawable/ic_launcher_background.xml")
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

        assertTrue(bg.contains("#242A36"))
        assertFalse(bg.contains("#E8B84A"))
        assertFalse(bg.contains("#070B16"))
        assertTrue(colors.contains("ic_launcher_background\">#242A36"))

        assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_foreground"))
        assertTrue(adaptiveRound.contains("@drawable/ic_launcher_monochrome"))

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))

        val res = listOf(File("app/src/main/res"), File("src/main/res")).first { it.isDirectory }
        assertFalse(File(res, "drawable/ic_launcher_foreground.xml").isFile)
        assertFalse(File(res, "drawable/ic_launcher_monochrome.xml").isFile)

        val fg = readPng(File(res, "drawable-xxhdpi/ic_launcher_foreground.png"))
        assertEquals(324, fg.width)
        assertEquals(324, fg.height)
        assertTrue("foreground should keep the green candle", countHue(fg, green = true) > 40)
        assertTrue("foreground should keep the cyan check", countHue(fg, green = false) > 20)
        assertTrue("foreground stays transparent outside the mark", countTransparent(fg) > 1000)

        val mono = readPng(File(res, "drawable-xxhdpi/ic_launcher_monochrome.png"))
        assertEquals(324, mono.width)
        assertTrue(countWhite(mono) > 40)

        mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        ).forEach { (dir, px) ->
            val icon = readPng(File(res, "$dir/ic_launcher.png"))
            val round = readPng(File(res, "$dir/ic_launcher_round.png"))
            assertEquals("$dir width", px, icon.width)
            assertEquals("$dir height", px, icon.height)
            assertEquals("$dir round width", px, round.width)
            assertTrue(countHue(icon, green = true) > 5)
            assertTrue(countHue(icon, green = false) > 3)
        }
    }

    private class RgbaPng(val width: Int, val height: Int, val pixels: ByteArray)

    private fun readPng(file: File): RgbaPng {
        val bytes = file.readBytes()
        check(bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            "${file.name} is not a PNG"
        }
        var i = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (i + 12 <= bytes.size) {
            val len = be32(bytes, i)
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val start = i + 8
            when (type) {
                "IHDR" -> {
                    width = be32(bytes, start)
                    height = be32(bytes, start + 4)
                    val bitDepth = bytes[start + 8].toInt() and 0xff
                    val colorType = bytes[start + 9].toInt() and 0xff
                    check(bitDepth == 8 && colorType == 6) { "${file.name} must be 8-bit RGBA" }
                }
                "IDAT" -> idat.write(bytes, start, len)
                "IEND" -> break
            }
            i = start + len + 4
        }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray(height * (1 + width * 4))
        var filled = 0
        while (!inflater.finished() && filled < raw.size) {
            filled += inflater.inflate(raw, filled, raw.size - filled)
        }
        inflater.end()
        check(filled == raw.size) { "${file.name} inflated $filled of ${raw.size}" }
        val pixels = ByteArray(width * height * 4)
        var prev = ByteArray(width * 4)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src].toInt() and 0xff
            src++
            val row = ByteArray(width * 4)
            for (x in row.indices) {
                val v = raw[src].toInt() and 0xff
                src++
                val left = if (x >= 4) row[x - 4].toInt() and 0xff else 0
                val up = prev[x].toInt() and 0xff
                val upLeft = if (x >= 4) prev[x - 4].toInt() and 0xff else 0
                val recon = when (filter) {
                    0 -> v
                    1 -> v + left
                    2 -> v + up
                    3 -> v + ((left + up) / 2)
                    4 -> v + paeth(left, up, upLeft)
                    else -> error("png filter $filter")
                } and 0xff
                row[x] = recon.toByte()
            }
            System.arraycopy(row, 0, pixels, y * row.size, row.size)
            prev = row
        }
        return RgbaPng(width, height, pixels)
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val p = left + up - upLeft
        val pa = abs(p - left)
        val pb = abs(p - up)
        val pc = abs(p - upLeft)
        return when {
            pa <= pb && pa <= pc -> left
            pb <= pc -> up
            else -> upLeft
        }
    }

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun countHue(image: RgbaPng, green: Boolean): Int {
        var n = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val i = (y * image.width + x) * 4
                val r = image.pixels[i].toInt() and 0xff
                val g = image.pixels[i + 1].toInt() and 0xff
                val b = image.pixels[i + 2].toInt() and 0xff
                val a = image.pixels[i + 3].toInt() and 0xff
                if (a > 180) {
                    if (green && g > 140 && g > r + 40 && g > b) n++
                    if (!green && b > 140 && b > r + 30 && g > 80) n++
                }
                x += 2
            }
            y += 2
        }
        return n
    }

    private fun countTransparent(image: RgbaPng): Int {
        var n = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val a = image.pixels[(y * image.width + x) * 4 + 3].toInt() and 0xff
                if (a < 16) n++
                x += 3
            }
            y += 3
        }
        return n
    }

    private fun countWhite(image: RgbaPng): Int {
        var n = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val i = (y * image.width + x) * 4
                val r = image.pixels[i].toInt() and 0xff
                val g = image.pixels[i + 1].toInt() and 0xff
                val b = image.pixels[i + 2].toInt() and 0xff
                val a = image.pixels[i + 3].toInt() and 0xff
                if (a > 180 && r > 240 && g > 240 && b > 240) n++
                x += 2
            }
            y += 2
        }
        return n
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
