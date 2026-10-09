package dev.podscompanion.data.media

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Пустой «слушатель уведомлений». Android отдаёт список плееров (MediaSessionManager.getActiveSessions)
 * только приложениям, которым пользователь дал доступ к уведомлениям, и просит назвать такой сервис.
 * Сами уведомления не читаем: методы onNotificationPosted не переопределены.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
    }

    companion object {
        private val _connected = MutableStateFlow(false)

        /** Система подключила слушателя: доступ только что дали или телефон перезагрузился. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
