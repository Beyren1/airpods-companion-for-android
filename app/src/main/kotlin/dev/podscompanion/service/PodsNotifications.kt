package dev.podscompanion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.podscompanion.MainActivity
import dev.podscompanion.R
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability

/** Постоянное уведомление сервиса: заряд в шторке. */
internal class PodsNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        // Важность LOW: без звука и всплывания, просто строка в шторке.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(status: PodsStatus?) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_pods)
        .setContentTitle(status?.model?.displayName ?: context.getString(R.string.notif_title_idle))
        .setContentText(status?.let(::batteryLine) ?: context.getString(R.string.notif_text_idle))
        .setContentIntent(openApp())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun update(status: PodsStatus?) {
        manager.notify(NOTIFICATION_ID, build(status))
    }

    private fun batteryLine(status: PodsStatus): String {
        val model = status.model
        if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
            return format(status.primary.battery, status.primary.charging)
        }
        val parts = mutableListOf(
            context.getString(R.string.notif_left, format(status.left.battery, status.left.charging)),
            context.getString(R.string.notif_right, format(status.right.battery, status.right.charging)),
        )
        if (status.caseBattery != null) {
            parts += context.getString(R.string.notif_case, format(status.caseBattery, status.caseCharging))
        }
        return parts.joinToString(" · ")
    }

    private fun format(battery: BatteryLevel?, charging: Boolean): String {
        val value = battery?.let { "${it.percent}%" } ?: "—"
        return if (charging) "⚡$value" else value
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "pods_status"
        const val NOTIFICATION_ID = 1
    }
}
