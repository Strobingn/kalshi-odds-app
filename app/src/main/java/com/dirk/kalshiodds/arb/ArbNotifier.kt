package com.dirk.kalshiodds.arb

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dirk.kalshiodds.MainActivity
import com.dirk.kalshiodds.R
import com.dirk.kalshiodds.arb.ui.Format
import com.dirk.kalshiodds.arb.scan.Opportunity

/** "New arb" notifications. Informational only; tapping opens the app. */
object ArbNotifier {

    const val MIN_PROFIT_CENTS = 50L
    private const val CHANNEL = "diphunter_arb_opportunities"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyNew(context: Context, opps: List<Opportunity>) {
        val worth = opps.filter { it.profitCents >= MIN_PROFIT_CENTS }
        if (worth.isEmpty() || !canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "New arbitrage opportunities", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val top = worth.maxBy { it.profitCents }
        val title = if (worth.size == 1) {
            "Arb: ${Format.cents(top.profitCents)} locked"
        } else {
            "${worth.size} new arbs · best ${Format.cents(top.profitCents)}"
        }
        val text = "${top.structure.eventTitle} · ${top.structure.type.label} · ${top.sets} sets"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nPrices can move before you act."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(top.key.hashCode(), n)
        } catch (_: SecurityException) {
            // Permission revoked between check and post.
        }
    }
}
