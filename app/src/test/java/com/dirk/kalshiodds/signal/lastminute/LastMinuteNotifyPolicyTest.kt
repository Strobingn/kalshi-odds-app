package com.dirk.kalshiodds.signal.lastminute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LastMinuteNotifyPolicyTest {

    @Test
    fun headsUpPostsWhenAppIsInForeground() {
        assertTrue(LastMinuteNotifyPolicy.shouldNotify(uiInForeground = true))
        assertTrue(LastMinuteNotifyPolicy.shouldNotify(uiInForeground = false))
    }

    @Test
    fun viewModelAndNotifierDoNotGateOnUiForeground() {
        val notifier = File("app/src/main/java/com/dirk/kalshiodds/signal/lastminute/LastMinuteNotifier.kt")
            .takeIf { it.isFile }
            ?: File("src/main/java/com/dirk/kalshiodds/signal/lastminute/LastMinuteNotifier.kt")
        val vm = File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
            .takeIf { it.isFile }
            ?: File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        val notifierSrc = notifier.readText()
        val vmSrc = vm.readText()
        assertTrue(notifierSrc.contains("LastMinuteNotifyPolicy.shouldNotify"))
        assertTrue(notifierSrc.contains("notifyFired"))
        val attach = vmSrc.substring(vmSrc.indexOf("private fun attachLastMinute"))
        val body = attach.substring(0, attach.indexOf("private fun ticketContext"))
        assertTrue(body.contains("lastMinuteNotifier.notifyFired"))
        assertFalse(body.contains("isUiInForeground"))
        assertFalse(body.contains("if (!LiveSignalsKeepAlive"))
    }
}
