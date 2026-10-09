package dev.podscompanion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.podscompanion.MainActivity
import dev.podscompanion.R
import dev.podscompanion.data.battery.BatteryPart
import dev.podscompanion.data.battery.LowBatteryEvent
import dev.podscompanion.data.displayText
import dev.podscompanion.protocol.advertising.BatteryLevel

/**
 * Предупреждения о низком заряде. Отдельный канал с обычной важностью: в отличие от постоянной
 * строки с зарядом, это уведомление со звуком, и пользователь может настроить его отдельно.
 */
internal class LowBatteryNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.low_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.low_channel_description) }
        manager.createNotificationChannel(channel)
    }

    fun handle(event: LowBatteryEvent) {
        when (event) {
            is LowBatteryEvent.Alert -> show(event)
            is LowBatteryEvent.Dismiss -> manager.cancel(event.device, NOTIFICATION_ID)
        }
    }

    private fun show(alert: LowBatteryEvent.Alert) {
        val name = alert.status.model?.displayName ?: "AirPods"
        val exact = alert.status.exactBattery
        val parts = alert.parts.joinToString(" · ") { low ->
            val value = BatteryLevel(low.percent).displayText(exact)
            when (low.part) {
                BatteryPart.LEFT -> context.getString(R.string.low_left, value)
                BatteryPart.RIGHT -> context.getString(R.string.low_right, value)
                BatteryPart.CASE -> context.getString(R.string.low_case, value)
                BatteryPart.SINGLE -> value
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(context.getString(R.string.low_title, name))
            .setContentText(parts)
            .setSubText(context.getString(R.string.low_charge_hint))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        // Тег — устройство: у каждой пары своё уведомление, новое предупреждение заменяет старое.
        runCatching { manager.notify(alert.device, NOTIFICATION_ID, notification) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "low_battery"
        const val NOTIFICATION_ID = 2
    }
}
