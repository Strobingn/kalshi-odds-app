package com.dirk.kalshiodds.signal.d3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Heads-up when D3 qualifies. Never places an order.
 */
class D3Notifier(private val context: Context) {
    private val ids = AtomicInteger(8300)

    fun notifyFired(signal: D3Signal): Int {
        if (!com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifyPolicy.shouldNotify(
                com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()
            )
        ) {
            return -1
        }
        SignalNotifier.ensureChannels(context)
        ensureChannel(context)
        val title = D3Copy.notificationTitle(signal)
        val text = D3Copy.notificationBody(signal)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SignalNotifier.EXTRA_TICKER, signal.ticker)
            putExtra(EXTRA_OPEN_TICKET, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            signal.ticker.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setSound(sound)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setOnlyAlertOnce(false)
            .build()
        val id = ids.getAndIncrement()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
        return id
    }

    companion object {
        const val CHANNEL = "diphunter_d3"
        const val EXTRA_OPEN_TICKET = "d3_open_ticket"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL,
                context.getString(R.string.d3_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.d3_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
