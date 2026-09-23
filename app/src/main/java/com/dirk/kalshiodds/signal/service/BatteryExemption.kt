package com.dirk.kalshiodds.signal.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Optional OEM battery-unrestricted prompt. Never applied automatically —
 * Settings shows a one-tap deep link the user can decline.
 */
object BatteryExemption {
    fun isUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun promptIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse(LiveSignalsPolicy.batteryPackageUri(context.packageName))
        }

    fun settingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse(LiveSignalsPolicy.batteryPackageUri(context.packageName))
        }

    fun openPrompt(context: Context) {
        val attempts = listOf(
            promptIntent(context),
            settingsListIntent(),
            appDetailsIntent(context)
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // try the next deep link
            }
        }
    }
}
