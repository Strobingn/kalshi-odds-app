package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdaterTest {
    @Test
    fun versionCodeBaseAgreesWithGradleAndTheWorkflow() {
        val gradle = File("build.gradle.kts").readText()
        val base = Regex("""val versionCodeBase = ([0-9_]+)""")
            .find(gradle)!!.groupValues[1].replace("_", "").toInt()
        assertEquals(base, BuildConfig.VERSION_CODE_BASE)
        assertTrue(BuildConfig.VERSION_CODE >= BuildConfig.VERSION_CODE_BASE)
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("versionCodeBase"))
        assertFalse(workflow.contains("1100000"))
        assertFalse(workflow.contains("1_000_000"))
        assertTrue(workflow.contains("refs/heads/grokbot"))
        assertTrue(workflow.contains("publish=false"))
        assertTrue(workflow.contains("pull_request"))
    }

    @Test
    fun parsesStableAssetWithTheSharedVersionBase() {
        val code = BuildConfig.VERSION_CODE_BASE + 17
        val raw = """{"body":"applicationId: ${BuildConfig.APPLICATION_ID}\nversionCode: $code\n",
            "assets":[
            {"name":"edge_model.json","browser_download_url":"https://github.com/other"},
            {"name":"${BuildConfig.UPDATE_ASSET_NAME}",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/grokbot-latest/${BuildConfig.UPDATE_ASSET_NAME}"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(BuildConfig.VERSION_CODE_BASE + 17, asset.versionCode)
        assertEquals(BuildConfig.APPLICATION_ID, asset.applicationId)
        assertTrue(asset.url.endsWith(BuildConfig.UPDATE_ASSET_NAME))
    }

    @Test
    fun filenameRunNumberUsesTheSameBase() {
        val raw = """{"assets":[
            {"name":"DipHunter-GTP-v1.3-grokbot-17.apk",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/gtp-v1.3-grokbot/DipHunter-GTP-v1.3-grokbot-17.apk"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(BuildConfig.VERSION_CODE_BASE + 17, asset.versionCode)
    }

    @Test
    fun ignoresAssetsFromAnotherRepository() {
        val raw = """{"assets":[{"name":"${BuildConfig.UPDATE_ASSET_NAME}",
            "browser_download_url":"https://github.com/other/repo/releases/download/tag/app.apk"}]}"""
        assertNull(AppUpdater.parseAsset(raw))
        assertFalse(AppUpdater.acceptsDownloadUrl("https://github.com/other/repo/releases/download/tag/app.apk"))
    }

    @Test
    fun anotherAppsHigherVersionIsSkipped() {
        val raw = """{"body":"applicationId: com.other.app\nversionCode: 99999999\n",
            "assets":[{"name":"${BuildConfig.UPDATE_ASSET_NAME}",
            "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/grokbot-latest/${BuildConfig.UPDATE_ASSET_NAME}"}]}"""
        assertTrue(AppUpdater.pickRelease(raw) is AppUpdater.Pick.ForeignApp)
        assertNull(AppUpdater.parseAsset(raw))
    }

    @Test
    fun releaseUrlIsTheRollingTag() {
        assertTrue(AppUpdater.releaseApiUrl().endsWith("/" + BuildConfig.UPDATE_RELEASE_TAG))
        assertTrue(BuildConfig.UPDATE_RELEASE_TAG.endsWith("-latest"))
        assertEquals(
            "https://api.github.com/repos/Strobingn/kalshi-odds-app/releases/tags/grokbot-latest",
            AppUpdater.releaseApiUrl("grokbot-latest")
        )
        assertFalse(AppUpdater.releaseApiUrl().contains("gtp-v1.2-grokbot"))
    }

    @Test
    fun signerCheckMatchesCertificateSha256() {
        val cert = "installed-cert".toByteArray()
        assertTrue(AppUpdater.sameSigningCertificates(listOf(cert), listOf(cert.copyOf())))
        assertFalse(AppUpdater.sameSigningCertificates(listOf(cert), listOf("other".toByteArray())))
        assertFalse(AppUpdater.sameSigningCertificates(emptyList(), listOf(cert)))
        assertEquals(64, AppUpdater.sha256(cert).length)
        assertEquals(AppUpdater.certificateSha256(listOf(cert)), AppUpdater.certificateSha256(listOf(cert.copyOf())))
    }
}
