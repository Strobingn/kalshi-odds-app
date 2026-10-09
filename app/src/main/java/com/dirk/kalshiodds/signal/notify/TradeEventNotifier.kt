package com.dirk.kalshiodds.signal.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R

/**
 * Once-per-event rules for the three trade notifications (0.3.38). Pure; no Android.
 *  - REAL_BET: once per client order id.
 *  - ERROR_STOP: once per stop event id (the failing order's client id, or the error text).
 *  - BALANCE_UNAVAILABLE: once per outage. Re-arms only after a fresh balance is seen again.
 */
class TradeEventPolicy(private val maxKeys: Int = 500) {
    enum class Kind { REAL_BET, ERROR_STOP, BALANCE_UNAVAILABLE }

    private val seen = LinkedHashSet<String>()
    private var balanceOutage = false

    @Synchronized
    fun firstTime(kind: Kind, eventId: String): Boolean {
        val key = "${kind.name}:$eventId"
        if (!seen.add(key)) return false
        while (seen.size > maxKeys) seen.remove(seen.first())
        return true
    }

    /** True exactly once when live trading needs a balance and it has become unavailable. */
    @Synchronized
    fun balanceChanged(needsBalance: Boolean, fresh: Boolean): Boolean {
        if (!needsBalance || fresh) {
            balanceOutage = false
            return false
        }
        if (balanceOutage) return false
        balanceOutage = true
        return true
    }
}

/** Posts the once-per-event notifications. Never includes key material. */
class TradeEventNotifier(private val context: Context) {
    val policy = TradeEventPolicy()

    fun realBetPlaced(clientOrderId: String, line: String) {
        if (policy.firstTime(TradeEventPolicy.Kind.REAL_BET, clientOrderId)) post(1, "Real bet placed", line)
    }

    fun errorStop(eventId: String, message: String) {
        if (policy.firstTime(TradeEventPolicy.Kind.ERROR_STOP, eventId)) post(2, "Live Autopilot stopped on an error", message)
    }

    fun balance(needsBalance: Boolean, fresh: Boolean) {
        if (policy.balanceChanged(needsBalance, fresh)) {
            post(3, "Kalshi balance unavailable", "Live Autopilot will not send until a fresh balance loads.")
        }
    }

    private fun post(slot: Int, title: String, text: String) {
        runCatching {
            SignalNotifier.ensureChannels(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 7300 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, SignalNotifier.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            NotificationManagerCompat.from(context).notify(ID_BASE + (System.nanoTime() % 1000).toInt(), n)
        }
    }

    companion object {
        const val ID_BASE = 7400
    }
}
