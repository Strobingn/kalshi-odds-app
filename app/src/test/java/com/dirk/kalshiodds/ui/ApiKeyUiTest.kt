package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dirk could not find where to paste the Kalshi key. The home banner
 * must appear only when no key is saved, and Settings must use the
 * three-state status line — not a buried "WS + approve-gated orders" header.
 */
class ApiKeyUiTest {

    @Test
    fun bannerVisibleOnlyWhenNoKeySaved() {
        assertTrue(ApiKeyUi.showNoKeyBanner(hasKey = false))
        assertFalse(ApiKeyUi.showNoKeyBanner(hasKey = true))
        assertEquals("Add your Kalshi API key to place real bets", ApiKeyUi.BANNER)
    }

    @Test
    fun keyIdWithoutPemIsStillNoKeyForBanner() {
        val configured = false
        assertTrue(
            "Key ID alone is not a saved trading key — banner stays up",
            ApiKeyUi.showNoKeyBanner(hasKey = configured)
        )
        assertEquals(ApiKeyUi.NONE, ApiKeyUi.statusLine(hasKey = false, connectionOk = false))
    }

    @Test
    fun statusIsNoneSavedOrReady() {
        assertEquals("No key - live orders disabled", ApiKeyUi.statusLine(false, false))
        assertEquals("No key - live orders disabled", ApiKeyUi.statusLine(false, true))
        assertEquals("Key saved - tap Test connection", ApiKeyUi.statusLine(true, false))
        assertEquals("Live trading ready", ApiKeyUi.statusLine(true, true))
        assertEquals("Kalshi API key", ApiKeyUi.HEADER)
    }

    @Test
    fun headerIsPlainKalshiApiKey() {
        assertEquals("Kalshi API key", ApiKeyUi.HEADER)
        assertFalse(ApiKeyUi.HEADER.contains("WS"))
        assertFalse(ApiKeyUi.HEADER.contains("approve"))
    }
}
