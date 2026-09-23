package com.dirk.kalshiodds.signal.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R
import com.dirk.kalshiodds.signal.model.SignalAlert
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HIGH-importance alerts. Killed-app remote push would need FCM later;
 * this release posts instantly from the live-signals foreground WS service.
 */
class SignalNotifier(private val context: Context) {

    private val ids = AtomicInteger(2000)

    fun notify(alert: SignalAlert): Long {
        ensureChannels(context)
        val postedAt = SystemClock.elapsedRealtimeNanos()
        val title = context.getString(R.string.signal_alert_title, alert.ticker)
        val text = String.format(
            Locale.US,
            "Δ %+.1fpp · %s",
            alert.deltaPp,
            alert.reason
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TICKER, alert.ticker)
        }
        val pending = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${alert.stance}\n$text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(false)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ids.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — UI still keeps the in-app recent-signals list
        }
        return postedAt
    }

    fun foregroundNotification(): Notification {
        ensureChannels(context)
        val intent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.live_signals_fg_title))
            .setContentText(context.getString(R.string.live_signals_fg_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ALERTS = "diphunter_signal_alerts"
        const val CHANNEL_FOREGROUND = "diphunter_live_signals"
        const val FG_NOTIFICATION_ID = 1001
        const val EXTRA_TICKER = "signal_ticker"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.signal_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.signal_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            val fg = NotificationChannel(
                CHANNEL_FOREGROUND,
                context.getString(R.string.live_signals_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.live_signals_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(alerts)
            nm.createNotificationChannel(fg)
        }
    }
}
