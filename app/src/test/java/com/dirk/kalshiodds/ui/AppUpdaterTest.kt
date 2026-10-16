package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun parsesBranchApkRunNumberIntoMonotonicVersion() {
        val raw = """{"assets":[
            {"name":"edge_model.json","browser_download_url":"https://github.com/other"},
            {"name":"DipHunter-v1.0-dot-adaptive-stops-fix-20261016-17.apk",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/apk-v1.0-dot-adaptive-stops-fix-20261016/DipHunter-v1.0-dot-adaptive-stops-fix-20261016-17.apk"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(1_000_017, asset.versionCode)
        assertEquals(true, asset.url.endsWith("dot-adaptive-stops-fix-20261016-17.apk"))
    }

    @Test
    fun ignoresAssetsFromAnotherRepository() {
        val raw = """{"assets":[{"name":"DipHunter-v1.0-dot-adaptive-stops-fix-20261016-99.apk",
            "browser_download_url":"https://github.com/other/repo/releases/download/tag/app.apk"}]}"""
        assertNull(AppUpdater.parseAsset(raw))
    }
}
