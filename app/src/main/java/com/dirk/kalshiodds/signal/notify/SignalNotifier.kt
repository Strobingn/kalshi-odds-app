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
import com.dirk.kalshiodds.signal.service.LiveSignalsPolicy
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import com.dirk.kalshiodds.ui.SignalCopy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HIGH-importance alerts plus the ongoing live-signals foreground notice.
 * Killed-app remote push would need FCM later; this release posts instantly
 * from the live-signals foreground WS service.
 */
class SignalNotifier(private val context: Context) {

    private val ids = AtomicInteger(2000)

    fun notify(alert: SignalAlert): Long {
        ensureChannels(context)
        val postedAt = SystemClock.elapsedRealtimeNanos()
        if (!SignalCopy.shouldNotify(alert)) return postedAt
        val card = SignalCopy.card(alert)
        val title = context.getString(R.string.signal_alert_title, card.title)
        val text = "${card.call} · ${card.modelLine}"
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
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(text)
                        card.details?.let { append("\n").append(it) }
                    }
                )
            )
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

    /**
     * Scalper event notification — same HIGH-importance alerts channel as
     * signal alerts, prefixed "Scalp:" by the caller. Guardrail-blocked
     * events are deliberately NOT routed here (AppContainer filters them) so
     * a throttled-out scalper cannot spam notifications.
     */
    fun notifyScalp(title: String, text: String, ticker: String? = null): Long {
        ensureChannels(context)
        val postedAt = SystemClock.elapsedRealtimeNanos()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ticker?.let { putExtra(EXTRA_TICKER, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ids.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — same fail-soft as alert notifications
        }
        return postedAt
    }

    fun foregroundNotification(text: String = context.getString(R.string.live_signals_fg_text)): Notification {        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(context, LiveSignalsService::class.java).apply {
            action = LiveSignalsPolicy.ACTION_STOP
        }
        val stopPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.live_signals_fg_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.live_signals_stop), stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun pausedNotification(): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = LiveSignalsPolicy.ACTION_RESUME
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.live_signals_paused_text)
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.live_signals_fg_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        const val CHANNEL_ALERTS = "diphunter_signal_alerts"
        const val CHANNEL_FOREGROUND = LiveSignalsPolicy.CHANNEL_ONGOING
        const val FG_NOTIFICATION_ID = 1001
        const val PAUSED_NOTIFICATION_ID = 1002
        const val EXTRA_TICKER = "signal_ticker"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching { nm.deleteNotificationChannel(LiveSignalsPolicy.CHANNEL_LEGACY) }
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.live_signals_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(alerts)
            nm.createNotificationChannel(fg)
        }
    }
}
