package com.dirk.kalshiodds.signal.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R

/**
 * 1.0.9 scalper alerts (hot streak, big loss, strong AI call). Paper-only insights; real money untouched.
 * Dedupe lives in [com.dirk.kalshiodds.decision.insights.ScalpAlertPolicy]; this only posts. Never includes key material.
 */
class ScalpInsightNotifier(private val context: Context) {

    fun post(kind: String, key: String, title: String, body: String) {
        runCatching {
            ensureChannels(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                7000 + (key.hashCode() and 0xfff),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_INSIGHTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            NotificationManagerCompat.from(context)
                .notify(7000 + (key.hashCode() and 0xfff), notification)
        }
    }

    companion object {
        const val CHANNEL_INSIGHTS = "scalp_insights"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val insights = NotificationChannel(
                CHANNEL_INSIGHTS,
                "Scalper alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Scalper alert notifications"
            }
            nm.createNotificationChannel(insights)
        }
    }
}
