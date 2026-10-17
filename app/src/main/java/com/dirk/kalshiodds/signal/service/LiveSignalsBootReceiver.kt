package com.dirk.kalshiodds.signal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-attach the live-signals foreground service after reboot or an app update.
 * BOOT_COMPLETED is an Android 12+ exemption for starting a foreground service.
 */
class LiveSignalsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return
        // 0.3.81: onReceive runs on main — read prefs / start the service on a background thread.
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { runCatching { LiveSignalsKeepAlive.ensureService(app) } } finally { pending.finish() }
        }.start()
    }
}
