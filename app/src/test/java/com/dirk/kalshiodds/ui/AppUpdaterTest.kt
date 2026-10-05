package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun parsesBranchApkRunNumberIntoMonotonicVersion() {
        val raw = """{"assets":[
            {"name":"edge_model.json","browser_download_url":"https://github.com/other"},
            {"name":"BitcoinKimi-v1.0-KIMI-Bitcoin-17.apk",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/kimi-v1.0-KIMI-Bitcoin/BitcoinKimi-v1.0-KIMI-Bitcoin-17.apk"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(1_000_017, asset.versionCode)
        assertEquals(true, asset.url.endsWith("KIMI-Bitcoin-17.apk"))
    }

    @Test
    fun ignoresAssetsFromAnotherRepository() {
        val raw = """{"assets":[{"name":"BitcoinKimi-v1.0-KIMI-Bitcoin-99.apk",
            "browser_download_url":"https://github.com/other/repo/releases/download/tag/app.apk"}]}"""
        assertNull(AppUpdater.parseAsset(raw))
    }
}
