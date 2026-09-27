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
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.WindowLabel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local notifications when a Long-shot or hunter card appears.
 * Tapping opens the ticket. Placing still requires an in-app Approve.
 */
class OpportunityNotifier(private val context: Context) {

    private val ids = AtomicInteger(4000)
    private val lastPosted = ConcurrentHashMap<OpportunityDedupe.Key, Long>()

    fun consider(
        tickets: List<TradeTicket>,
        enabled: Boolean,
        quiet: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!enabled) return 0
        ensureChannel(context)
        var posted = 0
        for (ticket in tickets) {
            if (!OpportunityDedupe.shouldNotify(ticket, nowMs, lastPosted)) continue
            if (notify(ticket, quiet)) {
                lastPosted[OpportunityDedupe.keyOf(ticket)] = nowMs
                posted++
            }
        }
        return posted
    }

    fun notify(ticket: TradeTicket, quiet: Boolean): Boolean {
        val title = when (ticket.kind) {
            TicketKind.HUNTER_VALUE -> context.getString(R.string.opportunity_longshot_title, WindowLabel.of(ticket.ticker))
            TicketKind.HUNTER -> context.getString(R.string.opportunity_hunter_title, WindowLabel.of(ticket.ticker))
            else -> context.getString(R.string.opportunity_wintarget_title, WindowLabel.of(ticket.ticker))
        }
        val text = context.getString(
            R.string.opportunity_body,
            com.dirk.kalshiodds.ui.SignalCopy.callLabel(ticket.side),
            ticket.contracts,
            String.format(java.util.Locale.US, "%.2f", ticket.stakeUsd)
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SignalNotifier.EXTRA_TICKER, ticket.ticker)
            putExtra(EXTRA_TICKET_ID, ticket.id)
            putExtra(EXTRA_OPEN_TICKET, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            ticket.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_OPPORTUNITY)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nApprove still required — never auto-placed."))
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setSilent(quiet)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(ids.getAndIncrement(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_OPPORTUNITY = "diphunter_opportunities"
        const val EXTRA_TICKET_ID = "opportunity_ticket_id"
        const val EXTRA_OPEN_TICKET = "opportunity_open_ticket"
        val DEDUPE_MS: Long = SignalConstants.OPPORTUNITY_DEDUPE_MS

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_OPPORTUNITY,
                context.getString(R.string.opportunity_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.opportunity_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
