package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun parsesBranchApkRunNumberIntoMonotonicVersion() {
        val raw = """{"assets":[
            {"name":"edge_model.json","browser_download_url":"https://github.com/other"},
            {"name":"DipHunter-GTP-v1.0-chat-GTP-17.apk",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/gtp-v1.0-chat-GTP/DipHunter-GTP-v1.0-chat-GTP-17.apk"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(1_000_017, asset.versionCode)
        assertEquals(true, asset.url.endsWith("chat-GTP-17.apk"))
    }

    @Test
    fun ignoresAssetsFromAnotherRepository() {
        val raw = """{"assets":[{"name":"DipHunter-GTP-v1.0-chat-GTP-99.apk",
            "browser_download_url":"https://github.com/other/repo/releases/download/tag/app.apk"}]}"""
        assertNull(AppUpdater.parseAsset(raw))
    }

    @Test
    fun releaseUrlUsesTheBuildTag() {
        assertTrue(AppUpdater.releaseApiUrl().endsWith("/" + BuildConfig.UPDATE_RELEASE_TAG))
        assertEquals(
            "https://api.github.com/repos/Strobingn/kalshi-odds-app/releases/tags/gtp-v1.0-main",
            AppUpdater.releaseApiUrl("gtp-v1.0-main")
        )
        assertFalse(AppUpdater.releaseApiUrl("gtp-v1.0-main").contains("chat-GTP"))
    }

    @Test
    fun signerCheckMatchesTheInstalledCertificateBytes() {
        val cert = "installed-cert".toByteArray()
        assertTrue(AppUpdater.sameSigningCertificates(listOf(cert), listOf(cert.copyOf())))
        assertFalse(AppUpdater.sameSigningCertificates(listOf(cert), listOf("other".toByteArray())))
        assertFalse(AppUpdater.sameSigningCertificates(emptyList(), listOf(cert)))
    }
}
