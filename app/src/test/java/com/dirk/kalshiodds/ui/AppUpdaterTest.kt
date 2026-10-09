package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun parsesBranchApkRunNumberIntoMonotonicVersion() {
        val raw = """{"assets":[
            {"name":"edge_model.json","browser_download_url":"https://github.com/other"},
            {"name":"BitcoinEdge-Codex-v1.0-chat-GTP-17.apk",
             "browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/bitcoin-edge-codex/BitcoinEdge-Codex-v1.0-chat-GTP-17.apk"}
        ]}"""
        val asset = AppUpdater.parseAsset(raw)!!
        assertEquals(2_000_017, asset.versionCode)
        assertEquals(true, asset.url.endsWith("chat-GTP-17.apk"))
    }

    @Test
    fun ignoresAssetsFromAnotherRepository() {
        val raw = """{"assets":[{"name":"BitcoinEdge-Codex-v1.0-chat-GTP-99.apk",
            "browser_download_url":"https://github.com/other/repo/releases/download/tag/app.apk"}]}"""
        assertNull(AppUpdater.parseAsset(raw))
    }
}
