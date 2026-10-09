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
import dev.podscompanion.data.displayText
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

    fun build(status: PodsStatus?) = build(title(status), text(status))

    private fun title(status: PodsStatus?) = status?.model?.displayName ?: context.getString(R.string.notif_title_idle)

    private fun text(status: PodsStatus?) = status?.let(::batteryLine) ?: context.getString(R.string.notif_text_idle)

    private fun build(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_pods)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    /** Что сейчас в шторке: новое уведомление шлём, только если текст изменился. */
    private var shown: Pair<String, String>? = null

    /**
     * Статус приходит с каждым рекламным пакетом (несколько раз в секунду), а меняется в нём обычно
     * только сила сигнала. Android ограничивает частоту обновлений уведомления и пропускает лишние,
     * поэтому без проверки шторка обновлялась впустую и могла не показать настоящее изменение.
     */
    fun update(status: PodsStatus?) {
        val content = title(status) to text(status)
        if (content == shown) return
        shown = content
        manager.notify(NOTIFICATION_ID, build(content.first, content.second))
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
            val case = format(status.caseBattery, status.caseCharging)
            parts += context.getString(R.string.notif_case, if (status.caseBatteryRemembered) "~$case" else case)
        }
        return parts.joinToString(" · ")
    }

    private fun format(battery: BatteryLevel?, charging: Boolean): String {
        val value = battery?.displayText() ?: "—"
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
